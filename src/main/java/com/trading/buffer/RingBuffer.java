package com.trading.buffer;

public interface RingBuffer {
    void set(long absoluteIndex, double value);
    double get(long absoluteIndex);
    long capacity();

    default void release() {}
}
