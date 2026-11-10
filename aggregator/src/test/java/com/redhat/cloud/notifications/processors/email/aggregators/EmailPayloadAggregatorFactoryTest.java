package com.redhat.cloud.notifications.processors.email.aggregators;

import com.redhat.cloud.notifications.models.EventAggregationCriterion;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmailPayloadAggregatorFactoryTest {

    @Test
    void shouldReturnComplianceAggregatorForRhelCompliance() {
        assertInstanceOf(ComplianceEmailAggregator.class, EmailPayloadAggregatorFactory.by(criterion("rhel", "compliance")));
    }

    @Test
    void shouldReturnInventoryAggregatorForRhelInventory() {
        assertInstanceOf(InventoryEmailAggregator.class, EmailPayloadAggregatorFactory.by(criterion("rhel", "inventory")));
    }

    @Test
    void shouldReturnPatchAggregatorForRhelPatch() {
        assertInstanceOf(PatchEmailPayloadAggregator.class, EmailPayloadAggregatorFactory.by(criterion("rhel", "patch")));
    }

    @Test
    void shouldReturnVulnerabilityAggregatorForRhelVulnerability() {
        assertInstanceOf(VulnerabilityEmailPayloadAggregator.class, EmailPayloadAggregatorFactory.by(criterion("rhel", "vulnerability")));
    }

    @Test
    void shouldReturnResourceOptimizationAggregatorForRhelResourceOptimization() {
        assertInstanceOf(ResourceOptimizationPayloadAggregator.class, EmailPayloadAggregatorFactory.by(criterion("rhel", "resource-optimization")));
    }

    @Test
    void shouldReturnAdvisorAggregatorForRhelAdvisor() {
        assertInstanceOf(AdvisorEmailAggregator.class, EmailPayloadAggregatorFactory.by(criterion("rhel", "advisor")));
    }

    @Test
    void shouldReturnErrataAggregatorForSubscriptionServicesErrata() {
        assertInstanceOf(ErrataEmailPayloadAggregator.class, EmailPayloadAggregatorFactory.by(criterion("subscription-services", "errata-notifications")));
    }

    @Test
    void shouldReturnApplicationServicesAggregatorForSubscriptionServicesApplicationServices() {
        assertInstanceOf(ApplicationServicesEmailPayloadAggregator.class, EmailPayloadAggregatorFactory.by(criterion("subscription-services", "application-services")));
    }

    @Test
    void shouldReturnNullForAppsWithoutAnAggregator() {
        assertNull(EmailPayloadAggregatorFactory.by(criterion("application-services", "ansible")));
    }

    @Test
    void shouldReturnNullForUnknownBundle() {
        assertNull(EmailPayloadAggregatorFactory.by(criterion("unknown-bundle", "compliance")));
    }

    private static EventAggregationCriterion criterion(String bundle, String application) {
        return new EventAggregationCriterion("org-id", UUID.randomUUID(), UUID.randomUUID(), bundle, application);
    }
}
