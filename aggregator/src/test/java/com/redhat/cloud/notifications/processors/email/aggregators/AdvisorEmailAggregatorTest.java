package com.redhat.cloud.notifications.processors.email.aggregators;

import com.redhat.cloud.notifications.Severity;
import com.redhat.cloud.notifications.models.EmailAggregation;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class AdvisorEmailAggregatorTest {

    private static final String ORG_ID = "test-org-id";
    private static final UUID EVENT_TYPE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private AdvisorEmailAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new AdvisorEmailAggregator();
    }

    @Test
    void emptyAggregatorHasNoOrgIdAndIsEmpty() {
        Assertions.assertNull(aggregator.getOrgId());
        Assertions.assertTrue(aggregator.isEmpty());
    }

    @Test
    void shouldKeepOneEntryPerEventInProcessingOrderWithoutDerivingAnything() {
        // Identical events are NOT merged and nothing is counted or truncated: engine replays the legacy
        // algorithm over these entries, which needs every event.
        for (int i = 0; i < 8; i++) {
            aggregator.aggregate(aggregation("new-recommendation", Severity.IMPORTANT, rule("rule-" + (i % 7), "2", "false")));
        }

        JsonArray entries = entries();
        Assertions.assertEquals(8, entries.size());
        for (int i = 0; i < 8; i++) {
            Assertions.assertEquals(i, entries.getJsonObject(i).getLong("_order"));
            Assertions.assertEquals("rule-" + (i % 7), entries.getJsonObject(i).getString("rule_id"));
        }
        JsonObject advisor = JsonObject.mapFrom(aggregator.getContext()).getJsonObject("advisor");
        Assertions.assertFalse(advisor.containsKey("total_recommendation"));
        Assertions.assertFalse(advisor.containsKey("new_recommendations"));
    }

    @Test
    void shouldKeepEventsOfAllThreeEventTypesAndIgnoreOthers() {
        aggregator.aggregate(aggregation("new-recommendation", Severity.IMPORTANT, rule("r1", "1", "false")));
        aggregator.aggregate(aggregation("resolved-recommendation", Severity.IMPORTANT, rule("r2", "2", "true")));
        aggregator.aggregate(aggregation("deactivated-recommendation", Severity.IMPORTANT, rule("r3", "3", "false")));
        aggregator.aggregate(aggregation("something-else", Severity.IMPORTANT, rule("r4", "4", "false")));

        JsonArray entries = entries();
        Assertions.assertEquals(3, entries.size());
        Assertions.assertEquals("new-recommendation", entries.getJsonObject(0).getString("event_type"));
        Assertions.assertEquals("resolved-recommendation", entries.getJsonObject(1).getString("event_type"));
        Assertions.assertEquals("deactivated-recommendation", entries.getJsonObject(2).getString("event_type"));
    }

    @Test
    void shouldNumberTheInnerEventsOfOneNotificationInOrder() {
        aggregator.aggregate(aggregationOf("new-recommendation", Severity.IMPORTANT,
            rule("r1", "1", "false"), rule("r2", "2", "false"), rule("r3", "3", "false")));
        aggregator.aggregate(aggregation("new-recommendation", Severity.IMPORTANT, rule("r4", "4", "false")));

        JsonArray entries = entries();
        Assertions.assertEquals(4, entries.size());
        for (int i = 0; i < 4; i++) {
            Assertions.assertEquals("r" + (i + 1), entries.getJsonObject(i).getString("rule_id"));
            Assertions.assertEquals(i, entries.getJsonObject(i).getLong("_order"));
        }
    }

    @Test
    void shouldNormalizeFieldsTheWayTheLegacyCodeReadThem() {
        // has_incident arrives as a JSON boolean and was read with getString(); missing fields were "".
        JsonObject rule = new JsonObject().put("rule_id", "r1").put("has_incident", true);

        aggregator.aggregate(aggregation("new-recommendation", Severity.IMPORTANT, rule));

        JsonObject entry = entries().getJsonObject(0);
        Assertions.assertEquals("true", entry.getString("has_incident"));
        Assertions.assertEquals("", entry.getString("rule_description"));
        Assertions.assertEquals("", entry.getString("total_risk"));
        Assertions.assertEquals("", entry.getString("rule_url"));
    }

    @Test
    void shouldTagEveryEntryWithItsEventMetadata() {
        EmailAggregation aggregation = aggregation("new-recommendation", Severity.CRITICAL, rule("r1", "4", "true"));
        aggregation.setSubscribedByDefault(true);

        aggregator.aggregate(aggregation);

        JsonObject entry = entries().getJsonObject(0);
        Assertions.assertEquals("CRITICAL", entry.getString("_severity"));
        Assertions.assertEquals("new-recommendation", entry.getString("_eventType"));
        Assertions.assertEquals(EVENT_TYPE_ID.toString(), entry.getString("_eventTypeId"));
        Assertions.assertTrue(entry.getBoolean("_subscribedByDefault"));
    }

    @Test
    void shouldSkipMalformedEvents() {
        EmailAggregation noEvents = aggregation("new-recommendation", Severity.IMPORTANT, rule("r1", "1", "false"));
        noEvents.getPayload().remove("events");
        EmailAggregation noPayload = aggregation("new-recommendation", Severity.IMPORTANT, rule("r2", "1", "false"));
        noPayload.getPayload().put("events", new JsonArray().add(new JsonObject()));

        aggregator.aggregate(noEvents);
        aggregator.aggregate(noPayload);

        Assertions.assertTrue(aggregator.isEmpty(), "malformed events must be skipped, not crash the fold");
    }

    /**
     * Pins the exact folded shape, which is the contract with engine's finalizer: engine's
     * AggregatedDataFinalizerTest feeds the same structure through the trim and finalize steps.
     */
    @Test
    void shouldProduceTheFoldedShapeEngineFinalizes() {
        aggregator.aggregate(aggregation("new-recommendation", Severity.LOW, rule("rule-1", "2", "false")));

        JsonObject expected = new JsonObject()
            .put("advisor", new JsonObject()
                .put("recommendation_events", new JsonArray().add(new JsonObject()
                    .put("event_type", "new-recommendation")
                    .put("rule_id", "rule-1")
                    .put("rule_description", "description of rule-1")
                    .put("total_risk", "2")
                    .put("has_incident", "false")
                    .put("rule_url", "https://example.com/rule-1")
                    .put("_order", 0)
                    .put("_severity", "LOW")
                    .put("_eventType", "new-recommendation")
                    .put("_eventTypeId", EVENT_TYPE_ID.toString())
                    .put("_subscribedByDefault", false))));

        Assertions.assertEquals(expected, JsonObject.mapFrom(aggregator.getContext()));
    }

    private JsonArray entries() {
        return JsonObject.mapFrom(aggregator.getContext()).getJsonObject("advisor").getJsonArray("recommendation_events");
    }

    private static JsonObject rule(String ruleId, String risk, String hasIncident) {
        return new JsonObject()
            .put("rule_id", ruleId)
            .put("rule_description", "description of " + ruleId)
            .put("total_risk", risk)
            .put("has_incident", hasIncident)
            .put("rule_url", "https://example.com/" + ruleId);
    }

    private static EmailAggregation aggregation(String eventType, Severity severity, JsonObject rule) {
        return aggregationOf(eventType, severity, rule);
    }

    private static EmailAggregation aggregationOf(String eventType, Severity severity, JsonObject... rules) {
        JsonArray events = new JsonArray();
        for (JsonObject rule : rules) {
            events.add(new JsonObject().put("payload", rule));
        }
        JsonObject action = new JsonObject().put("event_type", eventType).put("events", events);
        EmailAggregation aggregation = new EmailAggregation(ORG_ID, "rhel", "advisor", action, severity, EVENT_TYPE_ID);
        aggregation.setEventTypeName(eventType);
        return aggregation;
    }
}
