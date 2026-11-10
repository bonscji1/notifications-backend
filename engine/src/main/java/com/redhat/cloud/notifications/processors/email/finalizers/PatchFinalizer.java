package com.redhat.cloud.notifications.processors.email.finalizers;

import java.util.List;
import java.util.Map;

/**
 * {@code total_advisories} is the sum of the advisories left in each type array, which is what the legacy
 * aggregator's running counter amounted to for a recipient who saw exactly these advisories.
 */
final class PatchFinalizer extends AggregatedDataFinalizer {

    private static final String PATCH_KEY = "patch";
    private static final String TOTAL_ADVISORIES_KEY = "total_advisories";

    PatchFinalizer() {
        super(PATCH_KEY);
    }

    @Override
    protected void finalizeSection(Map<String, Object> data, Map<String, Object> patch) {
        int total = 0;
        for (Object advisories : patch.values()) {
            if (advisories instanceof List<?> list) {
                total += list.size();
            }
        }
        // total_advisories sits next to the patch section, at the root of the app's data.
        data.put(TOTAL_ADVISORIES_KEY, total);
    }
}
