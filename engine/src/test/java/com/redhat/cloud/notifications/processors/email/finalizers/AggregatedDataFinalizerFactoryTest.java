package com.redhat.cloud.notifications.processors.email.finalizers;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.redhat.cloud.notifications.processors.email.finalizers.FinalizerTestSupport.criterion;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AggregatedDataFinalizerFactoryTest {

    @Test
    void shouldReturnTheFinalizerOfEachAppThatDerivesValuesAfterTrimming() {
        assertInstanceOf(AdvisorFinalizer.class, AggregatedDataFinalizerFactory.by(criterion("rhel", "advisor")));
        assertInstanceOf(PatchFinalizer.class, AggregatedDataFinalizerFactory.by(criterion("rhel", "patch")));
        assertInstanceOf(VulnerabilityFinalizer.class, AggregatedDataFinalizerFactory.by(criterion("rhel", "vulnerability")));
        assertInstanceOf(ResourceOptimizationFinalizer.class, AggregatedDataFinalizerFactory.by(criterion("rhel", "resource-optimization")));
        assertInstanceOf(ApplicationServicesFinalizer.class, AggregatedDataFinalizerFactory.by(criterion("subscription-services", "application-services")));
    }

    @Test
    void shouldReturnTheDefaultFinalizerForAppsThatNeedNoDerivation() {
        assertInstanceOf(DefaultFinalizer.class, AggregatedDataFinalizerFactory.by(criterion("rhel", "compliance")));
        assertInstanceOf(DefaultFinalizer.class, AggregatedDataFinalizerFactory.by(criterion("rhel", "inventory")));
        assertInstanceOf(DefaultFinalizer.class, AggregatedDataFinalizerFactory.by(criterion("subscription-services", "errata-notifications")));
    }

    @Test
    void shouldReturnTheDefaultFinalizerForUnknownApps() {
        assertInstanceOf(DefaultFinalizer.class, AggregatedDataFinalizerFactory.by(criterion("rhel", "unknown-app")));
        assertInstanceOf(DefaultFinalizer.class, AggregatedDataFinalizerFactory.by(criterion("unknown-bundle", "advisor")));
    }

    @Test
    void theDefaultFinalizerLeavesTheDataUntouched() {
        Map<String, Object> data = new JsonObject().put("compliance", new JsonObject().put("anything", 1)).mapTo(Map.class);
        Map<String, Object> copy = new JsonObject(data).copy().mapTo(Map.class);

        AggregatedDataFinalizerFactory.by(criterion("rhel", "compliance")).finalizeData(data);

        assertEquals(copy, data);
    }
}
