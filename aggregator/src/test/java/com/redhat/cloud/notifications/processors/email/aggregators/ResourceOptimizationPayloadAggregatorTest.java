package com.redhat.cloud.notifications.processors.email.aggregators;

import com.redhat.cloud.notifications.Severity;
import com.redhat.cloud.notifications.models.EmailAggregation;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class ResourceOptimizationPayloadAggregatorTest {

    private static final String ORG_ID = "test-org-id";
    private static final UUID EVENT_TYPE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String IDLING = "IDLING";
    private static final String UNDER_PRESSURE = "UNDER_PRESSURE";
    private static final String UNKNOWN = "UNKNOWN";

    private ResourceOptimizationPayloadAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new ResourceOptimizationPayloadAggregator();
    }

    @Test
    void emptyAggregatorHasNoOrgIdAndIsEmpty() {
        Assertions.assertNull(aggregator.getOrgId());
        Assertions.assertTrue(aggregator.isEmpty());
    }

    @Test
    void shouldKeepOnlyTheLatestEntryPerSystemWhenTagsAreIdentical() {
        aggregator.aggregate(aggregation(101, "system-1", IDLING, Severity.IMPORTANT));
        aggregator.aggregate(aggregation(103, "system-1", UNDER_PRESSURE, Severity.IMPORTANT));
        aggregator.aggregate(aggregation(78, "system-2", IDLING, Severity.IMPORTANT));
        aggregator.aggregate(aggregation(48, "system-2", UNKNOWN, Severity.IMPORTANT));
        aggregator.aggregate(aggregation(121, "system-2", IDLING, Severity.IMPORTANT));

        JsonArray entries = entries();
        Assertions.assertEquals(2, entries.size());
        JsonObject system1 = entryOf(entries, "system-1");
        Assertions.assertEquals(UNDER_PRESSURE, system1.getString("current_state"));
        Assertions.assertEquals(103, system1.getInteger("systems_with_suggestions"));
        Assertions.assertEquals(1L, system1.getLong("_order"));
        JsonObject system2 = entryOf(entries, "system-2");
        Assertions.assertEquals(IDLING, system2.getString("current_state"));
        Assertions.assertEquals(121, system2.getInteger("systems_with_suggestions"));
        Assertions.assertEquals(4L, system2.getLong("_order"));
    }

    @Test
    void shouldKeepOneEntryPerSeverityForTheSameSystem() {
        // A recipient filtering out CRITICAL must still see the older LOW state, so both entries are needed.
        aggregator.aggregate(aggregation(10, "system-1", IDLING, Severity.LOW));
        aggregator.aggregate(aggregation(11, "system-1", UNDER_PRESSURE, Severity.CRITICAL));

        JsonArray entries = entries();
        Assertions.assertEquals(2, entries.size());
        Assertions.assertEquals("LOW", entries.getJsonObject(0).getString("_severity"));
        Assertions.assertEquals(IDLING, entries.getJsonObject(0).getString("current_state"));
        Assertions.assertEquals("CRITICAL", entries.getJsonObject(1).getString("_severity"));
        Assertions.assertEquals(UNDER_PRESSURE, entries.getJsonObject(1).getString("current_state"));
        Assertions.assertTrue(entries.getJsonObject(0).getLong("_order") < entries.getJsonObject(1).getLong("_order"));
    }

    @Test
    void shouldKeepTheLatestEntryOfEveryGroupInArbitraryOrder() {
        // Interleaved severities: for every (system, severity) group exactly the last event must survive.
        Severity[] severities = {Severity.LOW, Severity.CRITICAL, Severity.LOW, Severity.LOW, Severity.CRITICAL, Severity.CRITICAL};
        String[] systems = {"a", "a", "b", "a", "b", "a"};
        for (int i = 0; i < severities.length; i++) {
            aggregator.aggregate(aggregation(i, systems[i], "STATE_" + i, severities[i]));
        }

        JsonArray entries = entries();
        Assertions.assertEquals(4, entries.size());
        Assertions.assertEquals("STATE_3", entryOf(entries, "a", "LOW").getString("current_state"));
        Assertions.assertEquals("STATE_5", entryOf(entries, "a", "CRITICAL").getString("current_state"));
        Assertions.assertEquals("STATE_2", entryOf(entries, "b", "LOW").getString("current_state"));
        Assertions.assertEquals("STATE_4", entryOf(entries, "b", "CRITICAL").getString("current_state"));
    }

    @Test
    void shouldTagEveryEntryAndKeepSystemsWithBlankInventoryIdAsOneSystem() {
        EmailAggregation first = aggregation(5, null, IDLING, Severity.CRITICAL);
        first.setSubscribedByDefault(true);
        EmailAggregation second = aggregation(6, null, UNKNOWN, Severity.CRITICAL);
        second.setSubscribedByDefault(true);
        aggregator.aggregate(first);
        aggregator.aggregate(second);

        JsonArray entries = entries();
        Assertions.assertEquals(1, entries.size(), "legacy treated a missing inventory id as a single system");
        JsonObject entry = entries.getJsonObject(0);
        Assertions.assertEquals(UNKNOWN, entry.getString("current_state"));
        Assertions.assertEquals("CRITICAL", entry.getString("_severity"));
        Assertions.assertTrue(entry.getBoolean("_subscribedByDefault"));
        Assertions.assertEquals("new-suggestion", entry.getString("_eventType"));
        Assertions.assertEquals(EVENT_TYPE_ID.toString(), entry.getString("_eventTypeId"));
    }

    @Test
    void shouldSkipMalformedNotifications() {
        EmailAggregation noContext = aggregation(5, "system-1", IDLING, Severity.LOW);
        noContext.getPayload().remove("context");
        EmailAggregation noEvents = aggregation(5, "system-2", IDLING, Severity.LOW);
        noEvents.getPayload().remove("events");
        EmailAggregation noState = aggregation(5, "system-3", null, Severity.LOW);
        EmailAggregation noPayload = aggregation(5, "system-4", IDLING, Severity.LOW);
        noPayload.getPayload().put("events", new JsonArray().add(new JsonObject()));

        aggregator.aggregate(noContext);
        aggregator.aggregate(noEvents);
        aggregator.aggregate(noState);
        aggregator.aggregate(noPayload);

        Assertions.assertTrue(aggregator.isEmpty(), "malformed notifications must be skipped, not crash the fold");
    }

    /**
     * Pins the exact folded shape, which is the contract with engine's finalizer: engine's
     * AggregatedDataFinalizerTest feeds the same structure through the trim and finalize steps.
     */
    @Test
    void shouldProduceTheFoldedShapeEngineFinalizes() {
        aggregator.aggregate(aggregation(7, "system-1", IDLING, Severity.LOW));

        JsonObject expected = new JsonObject()
            .put("aggregated_data", new JsonObject()
                .put("system_states", new JsonArray().add(new JsonObject()
                    .put("_severity", "LOW")
                    .put("_eventType", "new-suggestion")
                    .put("_eventTypeId", EVENT_TYPE_ID.toString())
                    .put("_subscribedByDefault", false)
                    .put("inventory_id", "system-1")
                    .put("current_state", IDLING)
                    .put("systems_with_suggestions", 7)
                    .put("_order", 0))));

        Assertions.assertEquals(expected, JsonObject.mapFrom(aggregator.getContext()));
    }

    private JsonArray entries() {
        return JsonObject.mapFrom(aggregator.getContext()).getJsonObject("aggregated_data").getJsonArray("system_states");
    }

    private static JsonObject entryOf(JsonArray entries, String inventoryId) {
        return entries.stream().map(JsonObject.class::cast)
            .filter(entry -> inventoryId.equals(entry.getString("inventory_id")))
            .findFirst().orElseThrow();
    }

    private static JsonObject entryOf(JsonArray entries, String inventoryId, String severity) {
        return entries.stream().map(JsonObject.class::cast)
            .filter(entry -> inventoryId.equals(entry.getString("inventory_id")) && severity.equals(entry.getString("_severity")))
            .findFirst().orElseThrow();
    }

    private static EmailAggregation aggregation(int systemsWithSuggestions, String inventoryId, String currentState, Severity severity) {
        JsonObject payload = new JsonObject()
            .put("display_name", "ros-stage-sytem")
            .put("inventory_id", inventoryId)
            .put("previous_state", "IDLING")
            .put("current_state", currentState);
        JsonObject action = new JsonObject()
            .put("event_type", "new-suggestion")
            .put("context", new JsonObject().put("systems_with_suggestions", systemsWithSuggestions))
            .put("events", new JsonArray().add(new JsonObject().put("payload", payload)));
        EmailAggregation aggregation = new EmailAggregation(ORG_ID, "rhel", "resource-optimization", action, severity, EVENT_TYPE_ID);
        aggregation.setEventTypeName("new-suggestion");
        return aggregation;
    }
}
