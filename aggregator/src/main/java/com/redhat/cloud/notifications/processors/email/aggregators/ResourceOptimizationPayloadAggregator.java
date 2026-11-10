package com.redhat.cloud.notifications.processors.email.aggregators;

import com.redhat.cloud.notifications.models.EmailAggregation;
import io.quarkus.logging.Log;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collects the state changes of the day, without computing the digest numbers.
 *
 * <p>The digest needs three numbers: how many systems changed state, how many systems are in each state, and
 * the latest {@code systems_with_suggestions}. They depend on which events a recipient is allowed to see, and
 * that is only known later, in engine. So this class does not compute them. It keeps one entry per state
 * change instead, and engine's {@code AggregatedDataFinalizer} computes the numbers per recipient.
 *
 * <p>Each entry holds the system, its new state, {@code systems_with_suggestions}, the usual tags and
 * {@value #ORDER_KEY}, the position of the event in the fold. Engine uses {@value #ORDER_KEY} to find the
 * latest state of a system, the same "last event wins" rule the old code used.
 *
 * <p>To keep the payload small, only the newest entry is kept for each system and tag combination. Example:
 * system A goes IDLING, then UNDER_PRESSURE, both with the same tags: only UNDER_PRESSURE is kept, since it
 * replaces IDLING for any recipient who sees both. If the two events had different severities they are kept
 * separately, because a recipient may see only the older one.
 */
public class ResourceOptimizationPayloadAggregator extends AbstractEmailPayloadAggregator {

    static final String AGGREGATED_DATA = "aggregated_data";
    static final String SYSTEM_STATES = "system_states";
    static final String INVENTORY_ID = "inventory_id";
    static final String CURRENT_STATE = "current_state";
    static final String SYSTEMS_WITH_SUGGESTIONS = "systems_with_suggestions";
    static final String ORDER_KEY = "_order";

    // Latest entry per system + tag combination, keyed by the inventory id and the encoded tags.
    private final Map<String, JsonObject> latestEntries = new LinkedHashMap<>();
    private long order;

    public ResourceOptimizationPayloadAggregator() {
        context.put(AGGREGATED_DATA, new JsonObject());
    }

    @Override
    public boolean isEmpty() {
        return latestEntries.isEmpty();
    }

    @Override
    public Map<String, Object> getContext() {
        context.getJsonObject(AGGREGATED_DATA).put(SYSTEM_STATES, new JsonArray(new ArrayList<>(latestEntries.values())));
        return super.getContext();
    }

    @Override
    void processEmailAggregation(EmailAggregation aggregation) {
        JsonObject notification = aggregation.getPayload();
        JsonObject notificationContext = notification.getJsonObject("context");
        Integer systemsWithSuggestions = notificationContext == null ? null : notificationContext.getInteger(SYSTEMS_WITH_SUGGESTIONS);
        JsonArray events = notification.getJsonArray("events");
        // Malformed notifications would NPE below (legacy threw); skip them instead of crashing the whole fold.
        if (systemsWithSuggestions == null || events == null) {
            Log.warnf("Skipping resource-optimization aggregation with no systems_with_suggestions or no events array, orgId=%s", getOrgId());
            return;
        }

        /*
         * The ROS payload only contain one event, but it doesn't hurt to iterate over
         * the events array.
         */
        for (int i = 0; i < events.size(); i++) {
            JsonObject payload = events.getJsonObject(i).getJsonObject("payload");
            String currentState = payload == null ? null : payload.getString(CURRENT_STATE);
            if (currentState == null) {
                Log.warnf("Skipping resource-optimization event with no payload or current_state, orgId=%s", getOrgId());
                continue;
            }

            String inventoryId = payload.getString(INVENTORY_ID);
            if (inventoryId == null || inventoryId.isBlank()) {
                Log.warn("Missing or blank inventory_id field found in resource-optimization aggregation payload");
            }

            JsonObject entry = new JsonObject();
            tagMetadata(entry, aggregation);
            String key = inventoryId + "|" + entry.encode();
            entry.put(INVENTORY_ID, inventoryId)
                .put(CURRENT_STATE, currentState)
                .put(SYSTEMS_WITH_SUGGESTIONS, systemsWithSuggestions)
                .put(ORDER_KEY, order++);
            latestEntries.put(key, entry);
        }
    }
}
