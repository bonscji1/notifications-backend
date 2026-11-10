package com.redhat.cloud.notifications.processors.email.finalizers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.redhat.cloud.notifications.processors.email.finalizers.FinalizerTestSupport.finalizeData;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PatchFinalizerTest {

    @Test
    void totalAdvisoriesIsRecomputedFromTheAdvisoriesLeftAfterTrimming() {
        // The folded superset said 5, but this recipient's trimmed view only kept 3.
        Map<String, Object> data = new JsonObject()
            .put("patch", new JsonObject()
                .put("security", new JsonArray().add(advisory("RH-1")))
                .put("bugfix", new JsonArray().add(advisory("RH-2")))
                .put("enhancement", new JsonArray())
                .put("other", new JsonArray().add(advisory("RH-3"))))
            .put("total_advisories", 5)
            .mapTo(Map.class);

        finalizeData("rhel", "patch", data);

        assertEquals(3, data.get("total_advisories"));
    }

    @Test
    void withNothingLeftTheTotalIsZero() {
        Map<String, Object> data = new JsonObject()
            .put("patch", new JsonObject()
                .put("security", new JsonArray())
                .put("bugfix", new JsonArray())
                .put("enhancement", new JsonArray())
                .put("other", new JsonArray()))
            .put("total_advisories", 2)
            .mapTo(Map.class);

        finalizeData("rhel", "patch", data);

        assertEquals(0, data.get("total_advisories"));
    }

    @Test
    void withoutPatchSectionTheDataIsLeftUntouched() {
        Map<String, Object> data = new JsonObject().put("unrelated", 1).mapTo(Map.class);

        finalizeData("rhel", "patch", data);

        assertFalse(data.containsKey("total_advisories"));
    }

    private static JsonObject advisory(String name) {
        return new JsonObject().put("name", name).put("synopsis", "synopsis");
    }
}
