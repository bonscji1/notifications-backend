package com.redhat.cloud.notifications.processors.email.aggregators;

import com.redhat.cloud.notifications.Severity;
import com.redhat.cloud.notifications.models.EmailAggregation;
import com.redhat.cloud.notifications.models.EventAggregationCriterion;
import com.redhat.cloud.notifications.processors.email.finalizers.AggregatedDataFinalizerFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Oracle test: the legacy per-recipient {@link ResourceOptimizationPayloadAggregator} (still in engine until
 * the final cleanup part) decides what is correct. For a stream of state changes and every severity subset a
 * recipient might be eligible for, the output of the legacy aggregator over just that subset must equal what
 * the finalizer from {@link AggregatedDataFinalizerFactory} derives from the folded entries of that same subset, both for the
 * uncompressed fold (one entry per event) and for aggregator's compressed fold (latest entry per system and
 * severity only).
 */
class ResourceOptimizationFinalizerParityTest {

    private record StateChange(String inventoryId, String state, int systemsWithSuggestions, Severity severity) {
    }

    @Test
    void finalizeMatchesTheLegacyAggregatorForEverySeveritySubset() {
        List<StateChange> stream = randomStream(300, 12345L);

        for (Set<Severity> wanted : severitySubsets()) {
            Predicate<StateChange> eligible = change -> wanted.contains(change.severity());
            if (stream.stream().noneMatch(eligible)) {
                continue; // A recipient with nothing left is dropped by the trimmer and never reaches the finalizer.
            }
            JsonObject expected = legacyOutput(stream.stream().filter(eligible).toList());

            assertEquals(expected, finalizedOutput(foldedEntries(stream, false, eligible)), "uncompressed fold, severities " + wanted);
            assertEquals(expected, finalizedOutput(foldedEntries(stream, true, eligible)), "compressed fold, severities " + wanted);
        }
    }

    @Test
    void finalizeMatchesTheLegacyAggregatorWhenAStateChangesBackAndForthAcrossSeverities() {
        // The case that motivated recording the order: the newest event of a system is filtered out, so the
        // older one (different severity) is the recipient's real latest state.
        List<StateChange> stream = List.of(
            new StateChange("system-1", "IDLING", 10, Severity.LOW),
            new StateChange("system-1", "UNDER_PRESSURE", 11, Severity.CRITICAL),
            new StateChange("system-2", "UNKNOWN", 12, Severity.LOW),
            new StateChange("system-1", "UNKNOWN", 13, Severity.CRITICAL));

        for (Set<Severity> wanted : severitySubsets()) {
            Predicate<StateChange> eligible = change -> wanted.contains(change.severity());
            if (stream.stream().noneMatch(eligible)) {
                continue;
            }
            assertEquals(legacyOutput(stream.stream().filter(eligible).toList()),
                finalizedOutput(foldedEntries(stream, true, eligible)), "severities " + wanted);
        }
    }

    private static List<Set<Severity>> severitySubsets() {
        return List.of(
            Set.of(Severity.LOW), Set.of(Severity.IMPORTANT), Set.of(Severity.CRITICAL),
            Set.of(Severity.LOW, Severity.IMPORTANT), Set.of(Severity.LOW, Severity.CRITICAL),
            Set.of(Severity.IMPORTANT, Severity.CRITICAL), Set.of(Severity.LOW, Severity.IMPORTANT, Severity.CRITICAL));
    }

    private static List<StateChange> randomStream(int size, long seed) {
        Random random = new Random(seed);
        String[] states = {"IDLING", "UNDER_PRESSURE", "UNKNOWN", "OPTIMIZED"};
        Severity[] severities = {Severity.LOW, Severity.IMPORTANT, Severity.CRITICAL};
        List<StateChange> stream = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            stream.add(new StateChange("system-" + random.nextInt(15), states[random.nextInt(states.length)], random.nextInt(500), severities[random.nextInt(severities.length)]));
        }
        return stream;
    }

    private JsonObject legacyOutput(List<StateChange> changes) {
        ResourceOptimizationPayloadAggregator legacy = new ResourceOptimizationPayloadAggregator();
        for (StateChange change : changes) {
            legacy.aggregate(rawAggregation(change));
        }
        return normalize(JsonObject.mapFrom(legacy.getContext()));
    }

    /**
     * Folded entries of the eligible changes, exactly as aggregator's fold shapes them (the fold itself
     * lives in the aggregator module, which engine can't use). The order is the position in the whole stream.
     */
    private List<JsonObject> foldedEntries(List<StateChange> stream, boolean compressed, Predicate<StateChange> eligible) {
        Map<String, JsonObject> latestPerSystemAndSeverity = new HashMap<>();
        List<JsonObject> all = new ArrayList<>();
        for (int order = 0; order < stream.size(); order++) {
            StateChange change = stream.get(order);
            JsonObject entry = new JsonObject()
                .put("inventory_id", change.inventoryId())
                .put("current_state", change.state())
                .put("systems_with_suggestions", change.systemsWithSuggestions())
                .put("_order", order);
            all.add(entry);
            latestPerSystemAndSeverity.put(change.inventoryId() + "|" + change.severity(), entry);
        }
        List<JsonObject> source = compressed ? new ArrayList<>(latestPerSystemAndSeverity.values()) : all;
        // Eligibility is decided on the entry's own severity, which the fold keeps as a tag.
        List<JsonObject> kept = new ArrayList<>();
        for (JsonObject entry : source) {
            int order = entry.getInteger("_order");
            if (eligible.test(stream.get(order))) {
                kept.add(entry);
            }
        }
        return kept;
    }

    private JsonObject finalizedOutput(List<JsonObject> entries) {
        Map<String, Object> data = new JsonObject()
            .put("aggregated_data", new JsonObject().put("system_states", new JsonArray(entries)))
            .mapTo(Map.class);
        AggregatedDataFinalizerFactory.by(new EventAggregationCriterion("org", UUID.randomUUID(), UUID.randomUUID(), "rhel", "resource-optimization")).finalizeData(data);
        return normalize(JsonObject.mapFrom(data));
    }

    private static JsonObject normalize(JsonObject json) {
        return new JsonObject(json.encode());
    }

    private static EmailAggregation rawAggregation(StateChange change) {
        JsonObject action = new JsonObject()
            .put("event_type", "new-suggestion")
            .put("context", new JsonObject().put("systems_with_suggestions", change.systemsWithSuggestions()))
            .put("events", new JsonArray().add(new JsonObject().put("payload", new JsonObject()
                .put("inventory_id", change.inventoryId())
                .put("current_state", change.state()))));
        EmailAggregation aggregation = new EmailAggregation();
        aggregation.setBundleName("rhel");
        aggregation.setApplicationName("resource-optimization");
        aggregation.setOrgId("org");
        aggregation.setPayload(action);
        return aggregation;
    }
}
