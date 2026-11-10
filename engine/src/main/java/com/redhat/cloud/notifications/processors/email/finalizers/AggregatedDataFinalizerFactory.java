package com.redhat.cloud.notifications.processors.email.finalizers;

import com.redhat.cloud.notifications.models.EventAggregationCriterion;

/**
 * Routes a bundle+application pair to its {@link AggregatedDataFinalizer}, mirroring the aggregator module's
 * {@code EmailPayloadAggregatorFactory}: every app that aggregator folds into un-derived form needs an entry
 * here, otherwise its digest would be dispatched with the raw intermediate data. Apps that need nothing
 * (and apps without a fold-only aggregator) get the {@link DefaultFinalizer}, never {@code null}.
 */
public final class AggregatedDataFinalizerFactory {

    private static final String RHEL = "rhel";
    private static final String SUBSCRIPTION_SERVICES = "subscription-services";

    private static final String ADVISOR = "advisor";
    private static final String PATCH = "patch";
    private static final String VULNERABILITY = "vulnerability";
    private static final String RESOURCE_OPTIMIZATION = "resource-optimization";
    private static final String APPLICATION_SERVICES = "application-services";

    // Finalizers are stateless, so one shared instance each is enough.
    private static final AggregatedDataFinalizer DEFAULT = new DefaultFinalizer();
    private static final AggregatedDataFinalizer ADVISOR_FINALIZER = new AdvisorFinalizer();
    private static final AggregatedDataFinalizer PATCH_FINALIZER = new PatchFinalizer();
    private static final AggregatedDataFinalizer VULNERABILITY_FINALIZER = new VulnerabilityFinalizer();
    private static final AggregatedDataFinalizer RESOURCE_OPTIMIZATION_FINALIZER = new ResourceOptimizationFinalizer();
    private static final AggregatedDataFinalizer APPLICATION_SERVICES_FINALIZER = new ApplicationServicesFinalizer();

    private AggregatedDataFinalizerFactory() {

    }

    public static AggregatedDataFinalizer by(EventAggregationCriterion aggregationKey) {
        switch (aggregationKey.getBundle()) {
            case RHEL:
                switch (aggregationKey.getApplication()) {
                    case ADVISOR:
                        return ADVISOR_FINALIZER;
                    case PATCH:
                        return PATCH_FINALIZER;
                    case VULNERABILITY:
                        return VULNERABILITY_FINALIZER;
                    case RESOURCE_OPTIMIZATION:
                        return RESOURCE_OPTIMIZATION_FINALIZER;
                    default:
                        break;
                }
                break;
            case SUBSCRIPTION_SERVICES:
                if (APPLICATION_SERVICES.equals(aggregationKey.getApplication())) {
                    return APPLICATION_SERVICES_FINALIZER;
                }
                break;
            default:
                break;
        }
        return DEFAULT;
    }
}
