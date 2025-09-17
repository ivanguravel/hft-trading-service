package com.trading.buffer;

import net.openhft.chronicle.bytes.Bytes;

public class ChronicleRingBuffer implements RingBuffer {
    private final Bytes<?> bytes;
    private final long capacity;

    public ChronicleRingBuffer(long capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        long totalBytes = capacity * Double.BYTES;
        this.bytes = Bytes.allocateDirect(totalBytes);
    }

    @Override
    public void set(long absoluteIndex, double value) {
        long offset = (absoluteIndex % capacity) * Double.BYTES;
        bytes.writeDouble(offset, value);
    }

    @Override
    public double get(long absoluteIndex) {
        long offset = (absoluteIndex % capacity) * Double.BYTES;
        return bytes.readDouble(offset);
    }

    @Override
    public long capacity() {
        return capacity;
    }

    @Override
    public void release() {
        bytes.releaseLast();
    }
}
