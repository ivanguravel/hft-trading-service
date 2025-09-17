package com.trading.model;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class Batch {
    private final List<Double> values;

    public Batch(List<Double> values) {
        this.values = values;
    }

    public List<Double> getValues() {
        return values;
    }

}
