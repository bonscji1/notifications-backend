package com.redhat.cloud.notifications.connector.slack;

import com.redhat.cloud.notifications.connector.slack.config.SlackConnectorConfig;
import com.redhat.cloud.notifications.connector.v2.TestLifecycleManager;
import com.redhat.cloud.notifications.connector.v2.http.models.HandledHttpMessageDetails;
import com.redhat.cloud.notifications.connector.v2.models.HandledMessageDetails;
import com.redhat.cloud.notifications.qute.templates.TemplateService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.redhat.cloud.notifications.connector.v2.BaseConnectorIntegrationTest.buildIncomingCloudEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
@QuarkusTestResource(TestLifecycleManager.class)
class SlackMessageHandlerTest {

    @Inject
    SlackMessageHandler handler;

    @InjectMock
    TemplateService templateService;

    @InjectMock
    SlackConnectorConfig connectorConfig;

    @InjectMock
    @RestClient
    SlackRestClient webhookRestClient;

    @Test
    void testMissingEventDataKeys() {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("events", java.util.List.of());

        when(connectorConfig.isUseBetaTemplatesEnabled(any(), any(), any(), any())).thenReturn(false);
        when(templateService.renderTemplate(
            argThat(td -> td.bundle() == null && td.application() == null && td.eventType() == null),
            anyMap()
        )).thenReturn("{}");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(webhookRestClient.post(eq("http://localhost/webhook"), eq("{}"))).thenReturn(mockResponse);

        JsonObject payload = buildPayload("http://localhost/webhook", eventData, null);
        HandledMessageDetails result = handler.handle(
            buildIncomingCloudEvent("test-id", "test-type", payload)
        );

        HandledHttpMessageDetails httpDetails = (HandledHttpMessageDetails) result;
        assertEquals(200, httpDetails.httpStatus);
        verify(webhookRestClient).post(eq("http://localhost/webhook"), eq("{}"));
    }

    @Test
    void testNullEventDataValues() {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("bundle", null);
        eventData.put("application", null);
        eventData.put("event_type", null);

        when(connectorConfig.isUseBetaTemplatesEnabled(any(), any(), any(), any())).thenReturn(false);
        when(templateService.renderTemplate(
            argThat(td -> td.bundle() == null && td.application() == null && td.eventType() == null),
            anyMap()
        )).thenReturn("{}");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(webhookRestClient.post(eq("http://localhost/webhook"), eq("{}"))).thenReturn(mockResponse);

        JsonObject payload = buildPayload("http://localhost/webhook", eventData, null);
        HandledMessageDetails result = handler.handle(
            buildIncomingCloudEvent("test-id", "test-type", payload)
        );

        HandledHttpMessageDetails httpDetails = (HandledHttpMessageDetails) result;
        assertEquals(200, httpDetails.httpStatus);
        verify(webhookRestClient).post(eq("http://localhost/webhook"), eq("{}"));
    }

    @Test
    void testPresentEventDataKeys() {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("bundle", "rhel");
        eventData.put("application", "advisor");
        eventData.put("event_type", "new-recommendation");

        when(connectorConfig.isUseBetaTemplatesEnabled(any(), any(), any(), any())).thenReturn(false);
        when(templateService.renderTemplate(
            argThat(td -> "rhel".equals(td.bundle())
                && "advisor".equals(td.application())
                && "new-recommendation".equals(td.eventType())),
            anyMap()
        )).thenReturn("{\"text\": \"hello\"}");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(webhookRestClient.post(eq("http://localhost/webhook"), eq("{\"text\":\"hello\"}"))).thenReturn(mockResponse);

        JsonObject payload = buildPayload("http://localhost/webhook", eventData, null);
        HandledMessageDetails result = handler.handle(
            buildIncomingCloudEvent("test-id", "test-type", payload)
        );

        HandledHttpMessageDetails httpDetails = (HandledHttpMessageDetails) result;
        assertEquals(200, httpDetails.httpStatus);
        assertEquals("http://localhost/webhook", httpDetails.targetUrl);
    }

    @Test
    void testChannelIsIncludedInPayload() {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("bundle", "rhel");
        eventData.put("application", "advisor");
        eventData.put("event_type", "new-recommendation");

        when(connectorConfig.isUseBetaTemplatesEnabled(any(), any(), any(), any())).thenReturn(false);
        when(templateService.renderTemplate(any(), anyMap())).thenReturn("{\"text\": \"hello\"}");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(webhookRestClient.post(eq("http://localhost/webhook"), argThat(body -> {
            JsonObject json = new JsonObject(body);
            return "#my-channel".equals(json.getString("channel"));
        }))).thenReturn(mockResponse);

        JsonObject payload = buildPayload("http://localhost/webhook", eventData, "#my-channel");
        HandledMessageDetails result = handler.handle(
            buildIncomingCloudEvent("test-id", "test-type", payload)
        );

        HandledHttpMessageDetails httpDetails = (HandledHttpMessageDetails) result;
        assertEquals(200, httpDetails.httpStatus);
    }

    @Test
    void testChannelIsOmittedWhenNull() {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("bundle", "rhel");

        when(connectorConfig.isUseBetaTemplatesEnabled(any(), any(), any(), any())).thenReturn(false);
        when(templateService.renderTemplate(any(), anyMap())).thenReturn("{\"text\": \"hello\"}");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(webhookRestClient.post(eq("http://localhost/webhook"), argThat(body -> {
            JsonObject json = new JsonObject(body);
            return !json.containsKey("channel");
        }))).thenReturn(mockResponse);

        JsonObject payload = buildPayload("http://localhost/webhook", eventData, null);
        HandledMessageDetails result = handler.handle(
            buildIncomingCloudEvent("test-id", "test-type", payload)
        );

        HandledHttpMessageDetails httpDetails = (HandledHttpMessageDetails) result;
        assertEquals(200, httpDetails.httpStatus);
    }

    @Test
    void testNonJsonTemplateOutputIsWrappedInTextField() {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("bundle", "rhel");

        when(connectorConfig.isUseBetaTemplatesEnabled(any(), any(), any(), any())).thenReturn(false);
        when(templateService.renderTemplate(any(), anyMap())).thenReturn("plain text message");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(webhookRestClient.post(eq("http://localhost/webhook"), argThat(body -> {
            JsonObject json = new JsonObject(body);
            return "plain text message".equals(json.getString("text"));
        }))).thenReturn(mockResponse);

        JsonObject payload = buildPayload("http://localhost/webhook", eventData, null);
        HandledMessageDetails result = handler.handle(
            buildIncomingCloudEvent("test-id", "test-type", payload)
        );

        HandledHttpMessageDetails httpDetails = (HandledHttpMessageDetails) result;
        assertEquals(200, httpDetails.httpStatus);
    }

    @Test
    void testNullWebhookUrl() {
        JsonObject payload = new JsonObject();
        payload.put("org_id", "12345");
        payload.putNull("webhookUrl");
        payload.put("eventData", new HashMap<>());

        assertThrows(ConstraintViolationException.class, () ->
            handler.handle(buildIncomingCloudEvent("test-id", "test-type", payload))
        );
    }

    @Test
    void testBlankWebhookUrl() {
        JsonObject payload = buildPayload("", new HashMap<>(), null);

        assertThrows(ConstraintViolationException.class, () ->
            handler.handle(buildIncomingCloudEvent("test-id", "test-type", payload))
        );
    }

    @Test
    void testNullEventData() {
        JsonObject payload = new JsonObject();
        payload.put("org_id", "12345");
        payload.put("webhookUrl", "http://localhost/webhook");
        payload.putNull("eventData");

        assertThrows(ConstraintViolationException.class, () ->
            handler.handle(buildIncomingCloudEvent("test-id", "test-type", payload))
        );
    }

    @Test
    void testHttpErrorStatus() {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("bundle", "rhel");

        when(connectorConfig.isUseBetaTemplatesEnabled(any(), any(), any(), any())).thenReturn(false);
        when(templateService.renderTemplate(any(), anyMap())).thenReturn("{}");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(500);
        when(webhookRestClient.post(anyString(), anyString())).thenReturn(mockResponse);

        JsonObject payload = buildPayload("http://localhost/webhook", eventData, null);
        HandledMessageDetails result = handler.handle(
            buildIncomingCloudEvent("test-id", "test-type", payload)
        );

        HandledHttpMessageDetails httpDetails = (HandledHttpMessageDetails) result;
        assertEquals(500, httpDetails.httpStatus);
        assertEquals("http://localhost/webhook", httpDetails.targetUrl);
    }

    @Test
    void testBetaTemplateEnabled() {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("bundle", "rhel");
        eventData.put("application", "advisor");
        eventData.put("event_type", "new-recommendation");

        when(connectorConfig.isUseBetaTemplatesEnabled(eq("12345"), eq("rhel"), eq("advisor"), eq("new-recommendation"))).thenReturn(true);
        when(templateService.renderTemplate(
            argThat(td -> "rhel".equals(td.bundle())
                && "advisor".equals(td.application())
                && "new-recommendation".equals(td.eventType())
                && td.isBetaVersion()),
            anyMap()
        )).thenReturn("{\"text\": \"beta\"}");

        Response mockResponse = mock(Response.class);
        when(mockResponse.getStatus()).thenReturn(200);
        when(webhookRestClient.post(eq("http://localhost/webhook"), eq("{\"text\":\"beta\"}"))).thenReturn(mockResponse);

        JsonObject payload = buildPayload("http://localhost/webhook", eventData, null);
        HandledMessageDetails result = handler.handle(
            buildIncomingCloudEvent("test-id", "test-type", payload)
        );

        HandledHttpMessageDetails httpDetails = (HandledHttpMessageDetails) result;
        assertEquals(200, httpDetails.httpStatus);
        verify(connectorConfig).isUseBetaTemplatesEnabled(eq("12345"), eq("rhel"), eq("advisor"), eq("new-recommendation"));
    }

    private static JsonObject buildPayload(String webhookUrl, Map<String, Object> eventData, String channel) {
        JsonObject payload = new JsonObject();
        payload.put("org_id", "12345");
        payload.put("webhookUrl", webhookUrl);
        payload.put("eventData", eventData);
        if (channel != null) {
            payload.put("channel", channel);
        }
        return payload;
    }
}
