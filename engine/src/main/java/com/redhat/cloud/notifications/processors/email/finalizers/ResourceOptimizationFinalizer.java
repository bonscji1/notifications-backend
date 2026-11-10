package com.redhat.cloud.notifications.processors.email.finalizers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

/**
 * Rebuilds the legacy {@code aggregated_data} from the state changes a recipient kept: per system the entry
 * with the highest fold sequence number wins (the legacy "last write wins" in processing order), the
 * per-state counts and {@code systems_triggered} come from those winners, and {@code systems_with_suggestions}
 * from the latest entry overall. Same map types as the legacy code so the order of the {@code states} array
 * matches too.
 */
final class ResourceOptimizationFinalizer extends AggregatedDataFinalizer {

    private static final String AGGREGATED_DATA_KEY = "aggregated_data";
    private static final String SYSTEM_STATES_KEY = "system_states";
    private static final String INVENTORY_ID_KEY = "inventory_id";
    private static final String CURRENT_STATE_KEY = "current_state";
    private static final String SYSTEMS_WITH_SUGGESTIONS_KEY = "systems_with_suggestions";
    private static final String SYSTEMS_TRIGGERED_KEY = "systems_triggered";
    private static final String STATES_KEY = "states";
    private static final String STATE_KEY = "state";
    private static final String SYSTEM_COUNT_KEY = "system_count";
    private static final String ORDER_KEY = "_order";

    ResourceOptimizationFinalizer() {
        super(AGGREGATED_DATA_KEY);
    }

    @Override
    protected void finalizeSection(Map<String, Object> data, Map<String, Object> aggregatedData) {
        if (!(aggregatedData.get(SYSTEM_STATES_KEY) instanceof List<?> entries) || entries.isEmpty()) {
            return;
        }

        Map<String, Map<?, ?>> latestBySystem = new HashMap<>();
        Map<?, ?> latestOverall = null;
        for (Object rawEntry : entries) {
            Map<?, ?> entry = (Map<?, ?>) rawEntry;
            String inventoryId = (String) entry.get(INVENTORY_ID_KEY);
            Map<?, ?> current = latestBySystem.get(inventoryId);
            if (current == null || orderOf(entry) > orderOf(current)) {
                latestBySystem.put(inventoryId, entry);
            }
            if (latestOverall == null || orderOf(entry) > orderOf(latestOverall)) {
                latestOverall = entry;
            }
        }

        Map<String, String> currentStates = new HashMap<>();
        latestBySystem.forEach((inventoryId, entry) -> currentStates.put(inventoryId, (String) entry.get(CURRENT_STATE_KEY)));
        Map<String, Long> stateSystemCounts = currentStates.entrySet().stream()
            .collect(groupingBy(Map.Entry::getValue, counting()));

        List<Map<String, Object>> states = new ArrayList<>();
        for (Map.Entry<String, Long> stateSystemCount : stateSystemCounts.entrySet()) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put(STATE_KEY, stateSystemCount.getKey());
            state.put(SYSTEM_COUNT_KEY, stateSystemCount.getValue());
            states.add(state);
        }

        aggregatedData.remove(SYSTEM_STATES_KEY);
        aggregatedData.put(SYSTEMS_WITH_SUGGESTIONS_KEY, latestOverall.get(SYSTEMS_WITH_SUGGESTIONS_KEY));
        aggregatedData.put(SYSTEMS_TRIGGERED_KEY, latestBySystem.size());
        aggregatedData.put(STATES_KEY, states);
    }

    private static long orderOf(Map<?, ?> entry) {
        return ((Number) entry.get(ORDER_KEY)).longValue();
    }
}
