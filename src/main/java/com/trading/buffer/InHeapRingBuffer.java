package com.trading.buffer;

public class InHeapRingBuffer implements RingBuffer {
    private final double[] buffer;

    public InHeapRingBuffer(int capacity) {
        this.buffer = new double[capacity];
    }

    @Override
    public void set(long absoluteIndex, double value) {
        buffer[(int) (absoluteIndex % buffer.length)] = value;
    }

    @Override
    public double get(long absoluteIndex) {
        return buffer[(int) (absoluteIndex % buffer.length)];
    }

    @Override
    public long capacity() {
        return buffer.length;
    }
}
