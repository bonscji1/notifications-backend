package com.redhat.cloud.notifications.processors.email.finalizers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.redhat.cloud.notifications.processors.email.finalizers.FinalizerTestSupport.finalizeData;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ResourceOptimizationFinalizerTest {

    @Test
    void keepsTheLatestStatePerSystem() {
        Map<String, Object> data = data(
            entry("system-1", "IDLING", 101, 0),
            entry("system-2", "IDLING", 78, 1),
            entry("system-1", "UNDER_PRESSURE", 103, 2));

        finalizeData("rhel", "resource-optimization", data);

        Map<?, ?> aggregatedData = (Map<?, ?>) data.get("aggregated_data");
        assertEquals(2, aggregatedData.get("systems_triggered"));
        assertEquals(103, aggregatedData.get("systems_with_suggestions"));
        assertFalse(aggregatedData.containsKey("system_states"), "the intermediate entries must not leak into the dispatched content");
        Map<String, Long> countByState = new HashMap<>();
        for (Object state : (List<?>) aggregatedData.get("states")) {
            countByState.put((String) ((Map<?, ?>) state).get("state"), ((Number) ((Map<?, ?>) state).get("system_count")).longValue());
        }
        assertEquals(Map.of("IDLING", 1L, "UNDER_PRESSURE", 1L), countByState);
    }

    @Test
    void usesTheOrderNotThePositionInTheList() {
        // Entries can come in any order after trimming; the highest _order must still win.
        Map<String, Object> data = data(
            entry("system-1", "UNDER_PRESSURE", 103, 7),
            entry("system-1", "IDLING", 101, 3));

        finalizeData("rhel", "resource-optimization", data);

        Map<?, ?> aggregatedData = (Map<?, ?>) data.get("aggregated_data");
        assertEquals(103, aggregatedData.get("systems_with_suggestions"));
        Map<?, ?> onlyState = (Map<?, ?>) ((List<?>) aggregatedData.get("states")).get(0);
        assertEquals("UNDER_PRESSURE", onlyState.get("state"));
    }

    @Test
    void treatsMissingInventoryIdsAsOneSystem() {
        Map<String, Object> data = data(
            entry(null, "IDLING", 5, 0),
            entry(null, "UNKNOWN", 6, 1));

        finalizeData("rhel", "resource-optimization", data);

        assertEquals(1, ((Map<?, ?>) data.get("aggregated_data")).get("systems_triggered"));
    }

    private static Map<String, Object> data(JsonObject... entries) {
        JsonArray array = new JsonArray();
        for (JsonObject entry : entries) {
            array.add(entry);
        }
        return new JsonObject().put("aggregated_data", new JsonObject().put("system_states", array)).mapTo(Map.class);
    }

    private static JsonObject entry(String inventoryId, String state, int systemsWithSuggestions, int order) {
        return new JsonObject()
            .put("inventory_id", inventoryId)
            .put("current_state", state)
            .put("systems_with_suggestions", systemsWithSuggestions)
            .put("_order", order);
    }
}
