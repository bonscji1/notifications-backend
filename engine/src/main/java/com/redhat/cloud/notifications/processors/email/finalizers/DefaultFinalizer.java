package com.redhat.cloud.notifications.processors.email.finalizers;

import java.util.Map;

/**
 * The finalizer for every app without a specific one: apps whose folded data needs no derivation after
 * trimming (their output is just the items a recipient keeps), and apps without a fold-only aggregator.
 * Does nothing, so the factory never has to return {@code null}.
 */
final class DefaultFinalizer extends AggregatedDataFinalizer {

    DefaultFinalizer() {
        super(null);
    }

    @Override
    public void finalizeData(Map<String, Object> data) {
        // Nothing to derive.
    }

    @Override
    protected void finalizeSection(Map<String, Object> data, Map<String, Object> section) {
        // Never called, see finalizeData.
    }
}
