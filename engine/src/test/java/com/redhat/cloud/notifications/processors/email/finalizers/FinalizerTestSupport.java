package com.redhat.cloud.notifications.processors.email.finalizers;

import com.redhat.cloud.notifications.models.EventAggregationCriterion;

import java.util.Map;
import java.util.UUID;

/**
 * The folded inputs used by the finalizer tests mirror the shapes pinned by the aggregator module's fold
 * tests; the finalizers' output must match what the legacy per-recipient aggregators produced.
 */
final class FinalizerTestSupport {

    private FinalizerTestSupport() {

    }

    static EventAggregationCriterion criterion(String bundle, String application) {
        return new EventAggregationCriterion("org-id", UUID.randomUUID(), UUID.randomUUID(), bundle, application);
    }

    static void finalizeData(String bundle, String application, Map<String, Object> data) {
        AggregatedDataFinalizerFactory.by(criterion(bundle, application)).finalizeData(data);
    }
}
