package com.redhat.cloud.notifications.processors.email.finalizers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.redhat.cloud.notifications.processors.email.finalizers.FinalizerTestSupport.finalizeData;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApplicationServicesFinalizerTest {

    @Test
    void globalReleasesNumberIsRecomputedFromTheProductsLeftAfterTrimming() {
        // The folded superset said 99, but this recipient's trimmed view only kept 3 releases.
        Map<String, Object> data = new JsonObject()
            .put("application-services", new JsonObject()
                .put("products", new JsonObject()
                    .put("keycloak", new JsonObject().put("payloads", new JsonArray().add(new JsonObject()).add(new JsonObject())))
                    .put("quarkus", new JsonObject().put("payloads", new JsonArray().add(new JsonObject()))))
                .put("global_releases_number", 99))
            .mapTo(Map.class);

        finalizeData("subscription-services", "application-services", data);

        assertEquals(3, ((Map<?, ?>) data.get("application-services")).get("global_releases_number"));
    }

    @Test
    void withoutProductsTheDataIsLeftUntouched() {
        Map<String, Object> data = new JsonObject().put("application-services", new JsonObject()).mapTo(Map.class);

        finalizeData("subscription-services", "application-services", data);

        assertFalse(((Map<?, ?>) data.get("application-services")).containsKey("global_releases_number"));
    }
}
