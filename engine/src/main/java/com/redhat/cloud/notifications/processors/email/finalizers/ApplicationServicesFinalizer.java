package com.redhat.cloud.notifications.processors.email.finalizers;

import java.util.List;
import java.util.Map;

/**
 * Aggregator's fold-only pipeline folds every recipient's events into one superset, so any running
 * {@code global_releases_number} it kept would already be wrong for every recipient whose trimmed view
 * differs from the full digest. The correct per-recipient count can only be known after trimming, so it is
 * recomputed from the already-trimmed products map: exactly what the legacy per-recipient aggregator's own
 * running counter amounted to, computed once at the end instead of incrementally during the fold.
 */
final class ApplicationServicesFinalizer extends AggregatedDataFinalizer {

    private static final String APPLICATION_SERVICES_KEY = "application-services";
    private static final String PRODUCTS_KEY = "products";
    private static final String PAYLOADS_KEY = "payloads";
    private static final String GLOBAL_RELEASES_NUMBER_KEY = "global_releases_number";

    ApplicationServicesFinalizer() {
        super(APPLICATION_SERVICES_KEY);
    }

    @Override
    protected void finalizeSection(Map<String, Object> data, Map<String, Object> applicationServices) {
        if (!(applicationServices.get(PRODUCTS_KEY) instanceof Map<?, ?> products)) {
            return;
        }
        int releasesCount = 0;
        for (Object product : products.values()) {
            if (product instanceof Map<?, ?> productData && productData.get(PAYLOADS_KEY) instanceof List<?> payloads) {
                releasesCount += payloads.size();
            }
        }
        applicationServices.put(GLOBAL_RELEASES_NUMBER_KEY, releasesCount);
    }
}
