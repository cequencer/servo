/**
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.servo.monitor;

import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.servo.stats.StatsBuffer;
import com.netflix.servo.stats.StatsConfig;
import com.netflix.servo.tag.Tag;
import com.netflix.servo.tag.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Timer} that provides statistics.
 * <p>
 * The statistics are collected periodically and are published according to the configuration
 * specified by the user using a {@link com.netflix.servo.stats.StatsConfig} object.
 */
public class StatsTimer extends AbstractMonitor<Long> implements Timer, CompositeMonitor<Long> {
    private static final ThreadFactory threadFactory = new ThreadFactoryBuilder()
            .setDaemon(true)
            .setNameFormat("StatsTimer-%d")
            .build();
    private static final ScheduledExecutorService defaultExecutor = Executors.newScheduledThreadPool(1, threadFactory);
    private static final Logger LOGGER = LoggerFactory.getLogger(StatsTimer.class);

    private final TimeUnit timeUnit;
    private final MonitorConfig baseConfig;
    private final Counter count;
    private final Counter totalTime;
    private final List<Monitor<?>> monitors;

    private final List<GaugeWrapper> gaugeWrappers;

    private final Object updateLock = new Object();
    private StatsBuffer cur;
    private StatsBuffer prev;

    private static final String STATISTIC = "statistic";
    private static final String UNIT = "unit";
    private static final String PERCENTILE_FMT = "percentile_%.2f";
    private static final Tag STAT_TOTAL = Tags.newTag(STATISTIC, "totalTime");
    private static final Tag STAT_COUNT = Tags.newTag(STATISTIC, "count");
    private static final Tag STAT_MIN = Tags.newTag(STATISTIC, "min");
    private static final Tag STAT_MAX = Tags.newTag(STATISTIC, "max");
    private static final Tag STAT_MEAN = Tags.newTag(STATISTIC, "avg");
    private static final Tag STAT_VARIANCE = Tags.newTag(STATISTIC, "variance");
    private static final Tag STAT_STDDEV = Tags.newTag(STATISTIC, "stdDev");

    private interface GaugeWrapper {
        void update(StatsBuffer buffer);
        Monitor<?> getMonitor();
    }

    private static abstract class LongGaugeWrapper implements GaugeWrapper {
        protected final LongGauge gauge;

        protected LongGaugeWrapper(MonitorConfig config) {
            gauge = new LongGauge(config);
        }

        @Override
        public Monitor<?> getMonitor() {
            return gauge;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LongGaugeWrapper)) return false;

            final LongGaugeWrapper that = (LongGaugeWrapper) o;
            return gauge.equals(that.gauge);
        }

        @Override
        public int hashCode() {
            return gauge.hashCode();
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("gauge", gauge).toString();
        }
    }

    private static abstract class DoubleGaugeWrapper implements GaugeWrapper {
        protected final DoubleGauge gauge;

        protected DoubleGaugeWrapper(MonitorConfig config) {
            gauge = new DoubleGauge(config);
        }

        @Override
        public Monitor<?> getMonitor() {
            return gauge;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DoubleGaugeWrapper)) return false;

            final DoubleGaugeWrapper that = (DoubleGaugeWrapper) o;
            return gauge.equals(that.gauge);
        }

        @Override
        public int hashCode() {
            return gauge.hashCode();
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("gauge", gauge).toString();
        }
    }

    private static class MinStatGaugeWrapper extends LongGaugeWrapper {
        MinStatGaugeWrapper(MonitorConfig baseConfig) {
            super(baseConfig.withAdditionalTag(STAT_MIN));
        }

        @Override
        public void update(StatsBuffer buffer) {
            gauge.set(buffer.getMin());
        }
    }

    private static class MaxGaugeWrapper extends LongGaugeWrapper {
        MaxGaugeWrapper(MonitorConfig baseConfig) {
            super(baseConfig.withAdditionalTag(STAT_MAX));
        }

        @Override
        public void update(StatsBuffer buffer) {
            gauge.set(buffer.getMax());
        }
    }

    private static class MeanGaugeWrapper extends DoubleGaugeWrapper {
        MeanGaugeWrapper(MonitorConfig baseConfig) {
            super(baseConfig.withAdditionalTag(STAT_MEAN));
        }

        @Override
        public void update(StatsBuffer buffer) {
            gauge.set(buffer.getMean());
        }
    }

    private static class VarianceGaugeWrapper extends DoubleGaugeWrapper {
        VarianceGaugeWrapper(MonitorConfig baseConfig) {
            super(baseConfig.withAdditionalTag(STAT_VARIANCE));
        }

        @Override
        public void update(StatsBuffer buffer) {
            gauge.set(buffer.getVariance());
        }
    }

    private static class StdDevGaugeWrapper extends DoubleGaugeWrapper {
        StdDevGaugeWrapper(MonitorConfig baseConfig) {
            super(baseConfig.withAdditionalTag(STAT_STDDEV));
        }

        @Override
        public void update(StatsBuffer buffer) {
            gauge.set(buffer.getStdDev());
        }
    }

    private static class PercentileGaugeWrapper extends DoubleGaugeWrapper {
        private final double percentile;
        private final int index;

        private static Tag percentileTag(double percentile) {
            String percentileStr = String.format(PERCENTILE_FMT, percentile);
            if (percentileStr.endsWith(".00")) {
                percentileStr = percentileStr.substring(0, percentileStr.length() - 3);
            }

            return Tags.newTag(STATISTIC, percentileStr);
        }

        PercentileGaugeWrapper(MonitorConfig baseConfig, double percentile, int index) {
            super(baseConfig.withAdditionalTag(percentileTag(percentile)));
            this.percentile = percentile;
            this.index = index;
        }

        @Override
        public void update(StatsBuffer buffer) {
            gauge.set(buffer.getPercentileValues()[index]);
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("gauge", gauge).add("percentile", percentile).toString();
        }
    }

    private List<Counter> getCounters(StatsConfig config) {
        ImmutableList.Builder<Counter> monitors = ImmutableList.builder();
        if (config.getPublishCount()) {
            monitors.add(count);
        }
        if (config.getPublishTotal()) {
            monitors.add(totalTime);
        }
        return monitors.build();
    }

    private List<GaugeWrapper> getGaugeWrappers(StatsConfig config) {
        final ImmutableList.Builder<GaugeWrapper> builder = ImmutableList.builder();

        if (config.getPublishMax()) {
            builder.add(new MaxGaugeWrapper(baseConfig));
        }
        if (config.getPublishMin()) {
            builder.add(new MinStatGaugeWrapper(baseConfig));
        }
        if (config.getPublishVariance()) {
            builder.add(new VarianceGaugeWrapper(baseConfig));
        }
        if (config.getPublishStdDev()) {
            builder.add(new StdDevGaugeWrapper(baseConfig));
        }
        if (config.getPublishMean()) {
            builder.add(new MeanGaugeWrapper(baseConfig));
        }

        final double[] percentiles = config.getPercentiles();
        for (int i = 0; i < percentiles.length; ++i) {
            builder.add(new PercentileGaugeWrapper(baseConfig, percentiles[i], i));
        }

        final List<GaugeWrapper> wrappers = builder.build();

        // do a sanity check to prevent duplicated monitor configurations
        final Set<MonitorConfig> seen = Sets.newHashSet();
        for (final GaugeWrapper wrapper: wrappers) {
            final MonitorConfig cfg = wrapper.getMonitor().getConfig();
            if (seen.contains(cfg)) {
                throw new IllegalArgumentException("Duplicated monitor configuration found: " + cfg);
            }
            seen.add(cfg);
        }

        return wrappers;
    }

    /**
     * Creates a new instance of the timer with a unit of milliseconds, using the default executor.
     */
    public StatsTimer(MonitorConfig baseConfig, StatsConfig statsConfig) {
        this(baseConfig, statsConfig, TimeUnit.MILLISECONDS, defaultExecutor);
    }

    /**
     * Creates a new instance of the timer with a given unit, using the default executor.
     */
    public StatsTimer(MonitorConfig baseConfig, StatsConfig statsConfig, TimeUnit unit) {
        this(baseConfig, statsConfig, unit, defaultExecutor);
    }


    /**
     * Creates a new instance of the timer with a unit of milliseconds, using the {@link ScheduledExecutorService} provided by
     * the user.
     */
    public StatsTimer(MonitorConfig config, StatsConfig statsConfig, TimeUnit unit, ScheduledExecutorService executor) {
        super(config);
        this.timeUnit = unit;
        final Tag unitTag = Tags.newTag(UNIT, unit.name());
        this.baseConfig = config.withAdditionalTag(unitTag);
        this.cur = new StatsBuffer(statsConfig.getSampleSize(), statsConfig.getPercentiles());
        this.prev = new StatsBuffer(statsConfig.getSampleSize(), statsConfig.getPercentiles());
        this.count = new BasicCounter(baseConfig.withAdditionalTag(STAT_COUNT));
        this.totalTime = new BasicCounter(baseConfig.withAdditionalTag(STAT_TOTAL));
        this.gaugeWrappers = getGaugeWrappers(statsConfig);
        final Collection<Monitor<?>> gaugeMonitors = Collections2.transform(gaugeWrappers, new Function<GaugeWrapper, Monitor<?>>() {
            public Monitor<?> apply(GaugeWrapper perfStatGauge) {
                return perfStatGauge.getMonitor();
            }
        });
        this.monitors = new ImmutableList.Builder<Monitor<?>>()
                .addAll(getCounters(statsConfig))
                .addAll(gaugeMonitors)
                .build();

        startComputingStats(executor, statsConfig.getFrequencyMillis());
    }


    private void startComputingStats(ScheduledExecutorService executor, long frequencyMillis) {
        Runnable command = new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (updateLock) {
                        final StatsBuffer tmp = prev;
                        prev = cur;
                        cur = tmp;
                    }
                    prev.computeStats();
                    updateGauges();
                    prev.reset();
                } catch (Exception e) {
                    handleException(e);
                }
            }
        };

        executor.scheduleWithFixedDelay(command, frequencyMillis, frequencyMillis, TimeUnit.MILLISECONDS);
    }

    private void updateGauges() {
        for (GaugeWrapper gauge: gaugeWrappers) {
            gauge.update(prev);
        }
    }

    /** {@inheritDoc} */
    @Override
    public List<Monitor<?>> getMonitors() {
        return monitors;
    }

    /** {@inheritDoc} */
    @Override
    public Stopwatch start() {
        Stopwatch s = new TimedStopwatch(this);
        s.start();
        return s;
    }

    /** {@inheritDoc} */
    @Override
    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    /** {@inheritDoc} */
    @Override
    public void record(long duration) {
        synchronized(updateLock) {
            cur.record(duration);
        }
        count.increment();
        totalTime.increment(duration);
    }

    /** {@inheritDoc} */
    @Override
    public void record(long duration, TimeUnit timeUnit) {
        record(this.timeUnit.convert(duration, timeUnit));
    }

    /** {@inheritDoc} */
    @Override
    public Long getValue() {
        final long n = getCount();
        return n > 0 ? totalTime.getValue() / n : 0L;
    }

    /**
     * This is called when we encounter an exception while processing the values recorded to compute
     * the stats.
     * @param e  Exception encountered.
     */
    protected void handleException(Exception e) {
        LOGGER.warn("Unable to compute stats: ", e);
    }

    /** {@inheritDoc} */
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("baseConfig", baseConfig)
                .add("monitors", monitors)
                .toString();
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof StatsTimer)) {
            return false;
        }

        final StatsTimer m = (StatsTimer) obj;
        return baseConfig.equals(m.baseConfig) && monitors.equals(m.monitors);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return Objects.hashCode(baseConfig, monitors);
    }

    /**
     * Get the number of times this timer has been updated
     */
    public long getCount() {
        return count.getValue();
    }


    /**
     * Get the total time recorded for this timer
     */
    public long getTotalTime() {
        return totalTime.getValue();
    }
}
