package com.trading.model;

public final class Stats {

    private static final Stats EMPTY =
            new Stats(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0L);

    private final double min;
    private final double max;
    private final double last;
    private final double avg;
    private final double variance;
    private final long count;

    public Stats(double min, double max, double last, double avg, double variance, long count) {
        this.min = min;
        this.max = max;
        this.last = last;
        this.avg = avg;
        this.variance = variance;
        this.count = count;
    }

    public double getMin() { return min; }
    public double getMax() { return max; }
    public double getLast() { return last; }
    public double getAvg() { return avg; }
    public double getVariance() { return variance; }
    public long getCount() { return count; }

    public static Stats empty() {
        return EMPTY;
    }

    @Override
    public String toString() {
        return String.format("count=%d last=%.4f min=%.4f max=%.4f avg=%.4f var=%.6f",
                count, last, min, max, avg, variance);
    }
}
