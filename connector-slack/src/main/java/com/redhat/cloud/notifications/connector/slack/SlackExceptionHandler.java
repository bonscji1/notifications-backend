package com.redhat.cloud.notifications.connector.slack;

import com.redhat.cloud.notifications.connector.v2.http.HttpExceptionHandler;
import com.redhat.cloud.notifications.connector.v2.http.models.HandledHttpExceptionDetails;
import com.redhat.cloud.notifications.connector.v2.models.HandledExceptionDetails;
import io.quarkus.logging.Log;
import io.smallrye.reactive.messaging.ce.IncomingCloudEventMetadata;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

@ApplicationScoped
@Alternative
@Priority(0)
public class SlackExceptionHandler extends HttpExceptionHandler {

    @Override
    protected HandledExceptionDetails process(Throwable t, IncomingCloudEventMetadata<JsonObject> incomingCloudEvent) {
        HandledExceptionDetails details = super.process(t, incomingCloudEvent);
        if (details instanceof HandledHttpExceptionDetails httpDetails) {
            try {
                httpDetails.targetUrl = incomingCloudEvent.getData().getString("webhookUrl");
            } catch (Exception e) {
                Log.debugf(e, "Failed to extract target URL from notification during exception handling [historyId=%s]", incomingCloudEvent.getId());
            }
        }
        return details;
    }
}
