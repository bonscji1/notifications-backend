package com.redhat.cloud.notifications.processors.email.finalizers;

import java.util.Map;

/**
 * Rebuilds, for one recipient, the values the legacy per-recipient aggregators used to derive while folding
 * (running totals, de-duplication, top-N, latest-per-key). Aggregator folds such apps into un-derived tagged
 * contributions, {@code AggregatedDataTrimmer} keeps only what the recipient is eligible for, and a finalizer
 * derives the final legacy-shaped content from what is left, so templates and connector-email stay untouched.
 *
 * <p>One subclass per app, obtained from {@link AggregatedDataFinalizerFactory}. Each owns one top-level
 * section of the app's data ({@link #sectionKey}); this class takes care of leaving the data untouched when
 * that section is missing. Finalizers are stateless and shared, and they mutate the recipient's data in
 * place: it is a fresh copy per recipient, built by the trimmer.
 */
public abstract class AggregatedDataFinalizer {

    private final String sectionKey;

    protected AggregatedDataFinalizer(String sectionKey) {
        this.sectionKey = sectionKey;
    }

    @SuppressWarnings("unchecked")
    public void finalizeData(Map<String, Object> data) {
        if (data.get(sectionKey) instanceof Map<?, ?> section) {
            finalizeSection(data, (Map<String, Object>) section);
        }
    }

    /**
     * @param data    the recipient's whole data for the app, for finalizers that also write outside their section.
     * @param section the value under {@link #sectionKey}, guaranteed to be a map.
     */
    protected abstract void finalizeSection(Map<String, Object> data, Map<String, Object> section);
}
