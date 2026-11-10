package com.redhat.cloud.notifications.processors.email.aggregators;

import com.redhat.cloud.notifications.models.EmailAggregation;
import io.quarkus.logging.Log;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Folds Advisor recommendation events into one superset, without deriving anything. The legacy aggregator
 * sorted and truncated its per-category maps to the top 5 after every single event, so its output (system
 * counts, and the incident and recommendation totals) depends on the exact sequence of events a recipient
 * is eligible for. To reproduce it for each recipient, this fold keeps one tagged entry per event, in
 * processing order ({@value #ORDER_KEY}), and engine's finalizer replays the legacy algorithm over the
 * entries a recipient kept.
 *
 * <p>Major assumption, inherited from the legacy aggregator: there is at most one event per day for each
 * (rule, system) pair and event type. That is generally true, because a rule would have to be new twice or
 * resolved twice on the same system (new, then resolved, then new again) for it to be wrong. So one event
 * means one affected system, and the inventory id of the system doesn't need to be kept in the entries.
 *
 * <p>Field values are normalized exactly as the legacy code read them ({@code getString}, missing values as
 * the empty string) so that the replay sees the same inputs the legacy aggregator saw.
 */
public class AdvisorEmailAggregator extends AbstractEmailPayloadAggregator {

    // Advisor event types
    static final String NEW_RECOMMENDATION = "new-recommendation";
    static final String RESOLVED_RECOMMENDATION = "resolved-recommendation";
    static final String DEACTIVATED_RECOMMENDATION = "deactivated-recommendation";

    // Folded output
    static final String ADVISOR_KEY = "advisor";
    static final String RECOMMENDATION_EVENTS = "recommendation_events";
    static final String ORDER_KEY = "_order";

    // Advisor event payload, also the names of the folded entry fields
    static final String EVENT_TYPE = "event_type";
    static final String RULE_ID = "rule_id";
    static final String RULE_DESCRIPTION = "rule_description";
    static final String TOTAL_RISK = "total_risk";
    static final String HAS_INCIDENT = "has_incident";
    static final String RULE_URL = "rule_url";

    // Notification common
    private static final String EVENTS_KEY = "events";
    private static final String PAYLOAD_KEY = "payload";

    private static final Set<String> EVENT_TYPES = new HashSet<>(Arrays.asList(
            NEW_RECOMMENDATION, RESOLVED_RECOMMENDATION, DEACTIVATED_RECOMMENDATION
    ));

    private final JsonArray recommendationEvents = new JsonArray();
    private long order;

    public AdvisorEmailAggregator() {
        JsonObject advisor = new JsonObject();
        advisor.put(RECOMMENDATION_EVENTS, recommendationEvents);
        context.put(ADVISOR_KEY, advisor);
    }

    @Override
    public boolean isEmpty() {
        return recommendationEvents.isEmpty();
    }

    @Override
    void processEmailAggregation(EmailAggregation notification) {
        JsonObject notifPayload = notification.getPayload();
        String eventType = notifPayload.getString(EVENT_TYPE);

        if (!EVENT_TYPES.contains(eventType)) {
            return;
        }

        JsonArray events = notifPayload.getJsonArray(EVENTS_KEY);
        // A malformed event with no events array would NPE below; skip it instead of crashing the
        // whole fold (same guard as the other ported aggregators).
        if (events == null) {
            Log.warnf("Skipping Advisor aggregation event with no events array, orgId=%s", getOrgId());
            return;
        }

        events.stream().forEach(eventObject -> {
            JsonObject payload = ((JsonObject) eventObject).getJsonObject(PAYLOAD_KEY);
            if (payload == null) {
                Log.warnf("Skipping Advisor aggregation event with no payload, orgId=%s", getOrgId());
                return;
            }

            JsonObject entry = new JsonObject()
                .put(EVENT_TYPE, eventType)
                .put(RULE_ID, Optional.ofNullable(payload.getString(RULE_ID)).orElse(""))
                .put(RULE_DESCRIPTION, Optional.ofNullable(payload.getString(RULE_DESCRIPTION)).orElse(""))
                .put(TOTAL_RISK, Optional.ofNullable(payload.getString(TOTAL_RISK)).orElse(""))
                .put(HAS_INCIDENT, Optional.ofNullable(payload.getString(HAS_INCIDENT)).orElse(""))
                .put(RULE_URL, Optional.ofNullable(payload.getString(RULE_URL)).orElse(""))
                .put(ORDER_KEY, order++);
            tagMetadata(entry, notification);
            recommendationEvents.add(entry);
        });
    }
}
