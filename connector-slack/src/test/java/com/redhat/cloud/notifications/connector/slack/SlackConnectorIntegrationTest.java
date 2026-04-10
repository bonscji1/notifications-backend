package com.redhat.cloud.notifications.connector.slack;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.redhat.cloud.notifications.TestConstants;
import com.redhat.cloud.notifications.connector.v2.TestLifecycleManager;
import com.redhat.cloud.notifications.connector.v2.http.BaseHttpConnectorIntegrationTest;
import com.redhat.cloud.notifications.qute.templates.mapping.SubscriptionServices;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
@QuarkusTestResource(TestLifecycleManager.class)
class SlackConnectorIntegrationTest extends BaseHttpConnectorIntegrationTest {

    @Override
    protected String getRemoteServerPath() {
        return "/test-webhook";
    }

    @Override
    protected JsonObject buildIncomingPayload(String targetUrl) {
        JsonObject payload = new JsonObject();
        payload.put("org_id", "12345");
        payload.put("webhookUrl", targetUrl);
        payload.put("channel", "#test-channel");

        Map<String, Object> source = new HashMap<>();
        source.put("event_type", Map.of("display_name", SubscriptionServices.ERRATA_NEW_SUBSCRIPTION_BUGFIX_ERRATA));
        source.put("application", Map.of("display_name", SubscriptionServices.ERRATA_APP_NAME));
        source.put("bundle", Map.of("display_name", SubscriptionServices.BUNDLE_NAME));

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("bundle", SubscriptionServices.BUNDLE_NAME);
        eventData.put("application", SubscriptionServices.ERRATA_APP_NAME);
        eventData.put("event_type", SubscriptionServices.ERRATA_NEW_SUBSCRIPTION_BUGFIX_ERRATA);
        eventData.put("events", new ArrayList<>());
        eventData.put("environment", Map.of("url", new ArrayList<>()));
        eventData.put("orgId", TestConstants.DEFAULT_ORG_ID);
        eventData.put("source", source);

        payload.put("eventData", eventData);

        return payload;
    }

    @Override
    protected void afterSuccessfulNotification(List<LoggedRequest> loggedRequests) {
        assertEquals(1, loggedRequests.size());
        LoggedRequest request = loggedRequests.get(0);
        String body = request.getBodyAsString();
        assertNotNull(body);
        assertFalse(body.isEmpty(), "Outgoing payload should not be empty");

        JsonObject outgoingPayload = new JsonObject(body);
        assertEquals("#test-channel", outgoingPayload.getString("channel"));
    }
}
