package com.redhat.cloud.notifications.processors.email.aggregators;

import com.redhat.cloud.notifications.models.EventAggregationCriterion;

/**
 * Routes a bundle+application pair to its fold-only aggregator. Returns {@code null} for every app
 * without one, in which case engine folds that app's raw events itself.
 */
public class EmailPayloadAggregatorFactory {

    private static final String RHEL = "rhel";
    private static final String SUBSCRIPTION_SERVICES = "subscription-services";

    private static final String COMPLIANCE = "compliance";
    private static final String INVENTORY = "inventory";
    private static final String PATCH = "patch";
    private static final String VULNERABILITY = "vulnerability";
    private static final String RESOURCE_OPTIMIZATION = "resource-optimization";
    private static final String ADVISOR = "advisor";
    private static final String ERRATA = "errata-notifications";
    private static final String APPLICATION_SERVICES = "application-services";

    private EmailPayloadAggregatorFactory() {

    }

    public static AbstractEmailPayloadAggregator by(EventAggregationCriterion aggregationKey) {
        return getAggregator(aggregationKey.getBundle(), aggregationKey.getApplication());
    }

    private static AbstractEmailPayloadAggregator getAggregator(String bundle, String application) {
        switch (bundle) {
            case RHEL:
                switch (application) {
                    case ADVISOR:
                        return new AdvisorEmailAggregator();
                    case COMPLIANCE:
                        return new ComplianceEmailAggregator();
                    case INVENTORY:
                        return new InventoryEmailAggregator();
                    case PATCH:
                        return new PatchEmailPayloadAggregator();
                    case VULNERABILITY:
                        return new VulnerabilityEmailPayloadAggregator();
                    case RESOURCE_OPTIMIZATION:
                        return new ResourceOptimizationPayloadAggregator();
                    default:
                        // No fold-only aggregator for this application.
                        break;
                }
                break;
            case SUBSCRIPTION_SERVICES:
                switch (application) {
                    case ERRATA:
                        return new ErrataEmailPayloadAggregator();
                    case APPLICATION_SERVICES:
                        return new ApplicationServicesEmailPayloadAggregator();
                    default:
                        // No fold-only aggregator for this application.
                        break;
                }
                break;
            default:
                // No fold-only aggregator for this bundle.
                break;
        }

        return null;
    }
}
