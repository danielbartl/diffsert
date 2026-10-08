package dev.jbaby.diffsert.boot;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import dev.jbaby.diffsert.BatchWriteResult;
import dev.jbaby.diffsert.DiffsertListener;

/**
 * Counts written documents per collection and outcome as {@code diffsert.documents}, tagged with
 * {@code collection} and {@code outcome} ({@code inserted}, {@code updated}, {@code unchanged}, {@code not_found}).
 */
public class DiffsertMetrics implements DiffsertListener {

    public static final String METER_NAME = "diffsert.documents";

    private final MeterRegistry registry;

    public DiffsertMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onWrite(String collectionName, BatchWriteResult result) {
        increment(collectionName, "inserted", result.inserted());
        increment(collectionName, "updated", result.modified());
        increment(collectionName, "unchanged", result.unchanged());
        increment(collectionName, "not_found", result.notFound());
    }

    private void increment(String collectionName, String outcome, int amount) {
        Counter.builder(METER_NAME)
                .description("Documents written by Diffsert, by outcome")
                .tag("collection", collectionName)
                .tag("outcome", outcome)
                .register(registry)
                .increment(amount);
    }
}
