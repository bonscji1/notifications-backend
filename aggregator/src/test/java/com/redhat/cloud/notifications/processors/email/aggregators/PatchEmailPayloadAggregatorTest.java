package com.redhat.cloud.notifications.processors.email.aggregators;

import com.redhat.cloud.notifications.Severity;
import com.redhat.cloud.notifications.models.EmailAggregation;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class PatchEmailPayloadAggregatorTest {

    private static final String ORG_ID = "test-org-id";
    private static final UUID EVENT_TYPE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private PatchEmailPayloadAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new PatchEmailPayloadAggregator();
    }

    @Test
    void emptyAggregatorHasNoOrgIdAndIsEmpty() {
        Assertions.assertNull(aggregator.getOrgId(), "Empty aggregator has no orgId");
        Assertions.assertTrue(aggregator.isEmpty());
    }

    @Test
    void shouldSetOrgId() {
        aggregator.aggregate(createEmailAggregation(Severity.IMPORTANT, advisory("advisory", "synopsis", "bugfix")));
        Assertions.assertEquals(ORG_ID, aggregator.getOrgId());
        Assertions.assertFalse(aggregator.isEmpty());
    }

    @Test
    void shouldGroupAdvisoriesByType() {
        aggregator.aggregate(createEmailAggregation(Severity.IMPORTANT, advisory("advisory_1", "test synopsis", "security")));
        aggregator.aggregate(createEmailAggregation(Severity.IMPORTANT, advisory("advisory_2", "test synopsis", "enhancement")));
        aggregator.aggregate(createEmailAggregation(Severity.IMPORTANT, advisory("advisory_3", "test synopsis", "enhancement")));
        aggregator.aggregate(createEmailAggregation(Severity.IMPORTANT, advisory("advisory_4", "test synopsis", "bugfix")));
        aggregator.aggregate(createEmailAggregation(Severity.IMPORTANT, advisory("advisory_5", "test synopsis", "unspecified")));

        JsonObject patch = patch();
        Assertions.assertEquals(1, patch.getJsonArray("security").size());
        Assertions.assertEquals("advisory_1", patch.getJsonArray("security").getJsonObject(0).getString("name"));
        Assertions.assertEquals("test synopsis", patch.getJsonArray("security").getJsonObject(0).getString("synopsis"));
        Assertions.assertEquals(2, patch.getJsonArray("enhancement").size());
        Assertions.assertEquals("advisory_2", patch.getJsonArray("enhancement").getJsonObject(0).getString("name"));
        Assertions.assertEquals("advisory_3", patch.getJsonArray("enhancement").getJsonObject(1).getString("name"));
        Assertions.assertEquals(1, patch.getJsonArray("bugfix").size());
        Assertions.assertEquals("advisory_4", patch.getJsonArray("bugfix").getJsonObject(0).getString("name"));
        Assertions.assertEquals(1, patch.getJsonArray("other").size(), "unspecified goes under other");
        Assertions.assertEquals("advisory_5", patch.getJsonArray("other").getJsonObject(0).getString("name"));
        Assertions.assertEquals(5, JsonObject.mapFrom(aggregator.getContext()).getInteger("total_advisories"));
    }

    @Test
    void shouldHandleMultipleEventsAndDropUnknownTypes() {
        aggregator.aggregate(createEmailAggregation(Severity.IMPORTANT,
            advisory("RH-1", "synopsis", "ENHANCEMENT"),
            advisory("RH-2", "synopsis", "BUGFIX"),
            advisory("RH-3", "synopsis", "UNKNOWN")));

        JsonObject patch = patch();
        Assertions.assertEquals("RH-1", patch.getJsonArray("enhancement").getJsonObject(0).getString("name"));
        Assertions.assertEquals("RH-2", patch.getJsonArray("bugfix").getJsonObject(0).getString("name"));
        Assertions.assertTrue(patch.getJsonArray("other").isEmpty(), "an unknown advisory type must be dropped");
        Assertions.assertEquals(2, JsonObject.mapFrom(aggregator.getContext()).getInteger("total_advisories"));
    }

    @Test
    void shouldIgnoreOtherEventTypes() {
        EmailAggregation aggregation = createEmailAggregation(Severity.IMPORTANT, advisory("advisory", "synopsis", "bugfix"));
        aggregation.getPayload().put("event_type", "something-else");

        aggregator.aggregate(aggregation);

        Assertions.assertTrue(aggregator.isEmpty());
    }

    @Test
    void shouldTagEveryAdvisoryWithItsEventMetadata() {
        EmailAggregation aggregation = createEmailAggregation(Severity.CRITICAL, advisory("advisory_1", "synopsis", "security"));
        aggregation.setSubscribedByDefault(true);
        aggregation.setRecipientsOverride(new JsonArray().add(new JsonObject().put("only_admins", true)));

        aggregator.aggregate(aggregation);

        JsonObject item = patch().getJsonArray("security").getJsonObject(0);
        Assertions.assertEquals("CRITICAL", item.getString("_severity"));
        Assertions.assertEquals("new-advisory", item.getString("_eventType"));
        Assertions.assertEquals(EVENT_TYPE_ID.toString(), item.getString("_eventTypeId"));
        Assertions.assertTrue(item.getBoolean("_subscribedByDefault"));
        Assertions.assertEquals(1, item.getJsonArray("_recipientsOverride").size());
    }

    @Test
    void shouldSkipEventWithNoPayloadOrNoAdvisoryType() {
        JsonObject withoutPayload = new JsonObject();
        JsonObject withoutType = new JsonObject().put("payload", new JsonObject().put("advisory_name", "x"));

        aggregator.aggregate(createEmailAggregationFromEvents(Severity.IMPORTANT, new JsonArray().add(withoutPayload).add(withoutType)));

        Assertions.assertTrue(aggregator.isEmpty(), "malformed advisories must be skipped, not crash the fold");
    }

    @Test
    void shouldSkipNotificationWithNoEventsArray() {
        EmailAggregation aggregation = createEmailAggregationFromEvents(Severity.IMPORTANT, new JsonArray());
        aggregation.getPayload().remove("events");

        aggregator.aggregate(aggregation);

        Assertions.assertTrue(aggregator.isEmpty());
    }

    /**
     * Pins the exact folded shape, which is the contract with engine's finalizer: engine's
     * AggregatedDataFinalizerTest feeds the same structure through the trim and finalize steps.
     */
    @Test
    void shouldProduceTheFoldedShapeEngineFinalizes() {
        aggregator.aggregate(createEmailAggregation(Severity.LOW, advisory("RH-1", "synopsis 1", "security")));

        JsonObject expectedItem = new JsonObject()
            .put("name", "RH-1")
            .put("synopsis", "synopsis 1")
            .put("_severity", "LOW")
            .put("_eventType", "new-advisory")
            .put("_eventTypeId", EVENT_TYPE_ID.toString())
            .put("_subscribedByDefault", false);
        JsonObject expected = new JsonObject()
            .put("patch", new JsonObject()
                .put("security", new JsonArray().add(expectedItem))
                .put("bugfix", new JsonArray())
                .put("enhancement", new JsonArray())
                .put("other", new JsonArray()))
            .put("total_advisories", 1);

        Assertions.assertEquals(expected, JsonObject.mapFrom(aggregator.getContext()));
    }

    private JsonObject patch() {
        return JsonObject.mapFrom(aggregator.getContext()).getJsonObject("patch");
    }

    private static JsonObject advisory(String name, String synopsis, String type) {
        return new JsonObject()
            .put("payload", new JsonObject()
                .put("advisory_name", name)
                .put("synopsis", synopsis)
                .put("advisory_type", type));
    }

    private static EmailAggregation createEmailAggregation(Severity severity, JsonObject... events) {
        JsonArray array = new JsonArray();
        for (JsonObject event : events) {
            array.add(event);
        }
        return createEmailAggregationFromEvents(severity, array);
    }

    private static EmailAggregation createEmailAggregationFromEvents(Severity severity, JsonArray events) {
        JsonObject action = new JsonObject()
            .put("event_type", "new-advisory")
            .put("events", events);
        EmailAggregation aggregation = new EmailAggregation(ORG_ID, "rhel", "patch", action, severity, EVENT_TYPE_ID);
        aggregation.setEventTypeName("new-advisory");
        return aggregation;
    }
}
