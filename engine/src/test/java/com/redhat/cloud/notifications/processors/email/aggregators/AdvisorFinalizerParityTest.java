package com.redhat.cloud.notifications.processors.email.aggregators;

import com.redhat.cloud.notifications.Severity;
import com.redhat.cloud.notifications.models.EmailAggregation;
import com.redhat.cloud.notifications.models.EventAggregationCriterion;
import com.redhat.cloud.notifications.processors.email.finalizers.AggregatedDataFinalizerFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Oracle test: the legacy per-recipient {@link AdvisorEmailAggregator} (still in engine until the final
 * cleanup part) decides what is correct. For streams of recommendation events and every severity subset a
 * recipient might be eligible for, the legacy aggregator over just that subset must produce the same digest
 * content as the finalizer from {@link AggregatedDataFinalizerFactory} replaying the folded entries of that same subset, including the
 * legacy quirks (truncation after every event, rules that were cut counted again, totals that exceed what is
 * displayed) and the display order of the rules.
 */
class AdvisorFinalizerParityTest {

    private static final String[] EVENT_TYPES = {"new-recommendation", "resolved-recommendation", "deactivated-recommendation"};
    private static final Severity[] SEVERITIES = {Severity.LOW, Severity.IMPORTANT, Severity.CRITICAL};

    private record Recommendation(String eventType, String ruleId, String risk, String incident, Severity severity) {
    }

    @Test
    void finalizeMatchesTheLegacyAggregatorForEverySeveritySubset() {
        boolean totalsExceededWhatIsDisplayed = false;
        for (long seed : new long[]{1L, 2L, 3L, 42L, 2024L}) {
            // Many more rules than the 5 shown per category, so truncation and the re-counting quirk are exercised.
            List<Recommendation> stream = randomStream(400, 20, seed, false);
            totalsExceededWhatIsDisplayed |= assertParityForEverySubset(stream, "constant risk per rule, seed " + seed);
        }
        assertTrue(totalsExceededWhatIsDisplayed, "the streams must exercise the legacy overcounting quirk, otherwise this test proves little");
    }

    @Test
    void finalizeMatchesTheLegacyAggregatorWhenTheRiskOfARuleVariesBetweenEvents() {
        // The risk used for a rule is the one of the event that (re)creates its entry, so varying risks
        // exercise the path where a rule that was cut comes back at a different rank.
        for (long seed : new long[]{7L, 8L, 9L}) {
            assertParityForEverySubset(randomStream(400, 12, seed, true), "varying risk per rule, seed " + seed);
        }
    }

    @Test
    void finalizeMatchesTheLegacyAggregatorWithFewEvents() {
        assertParityForEverySubset(List.of(
            new Recommendation("new-recommendation", "rule-1", "3", "true", Severity.LOW),
            new Recommendation("deactivated-recommendation", "rule-2", "1", "false", Severity.CRITICAL)), "two events");
    }

    /** @return whether, in any subset, the legacy totals counted more recommendations than it displays. */
    private boolean assertParityForEverySubset(List<Recommendation> stream, String description) {
        boolean totalsExceededWhatIsDisplayed = false;
        for (Set<Severity> wanted : severitySubsets()) {
            Predicate<Recommendation> eligible = recommendation -> wanted.contains(recommendation.severity());
            if (stream.stream().noneMatch(eligible)) {
                continue; // A recipient with nothing left is dropped by the trimmer and never reaches the finalizer.
            }
            JsonObject expected = legacyOutput(stream.stream().filter(eligible).toList());
            JsonObject actual = finalizedOutput(foldedEntries(stream, eligible));

            assertEquals(expected, actual, description + ", severities " + wanted);
            for (String category : List.of("new_recommendations", "resolved_recommendations", "deactivated_recommendations")) {
                assertEquals(ruleOrder(expected, category), ruleOrder(actual, category), description + ", display order of " + category + ", severities " + wanted);
            }
            totalsExceededWhatIsDisplayed |= displayedRules(expected) < expected.getJsonObject("advisor").getInteger("total_recommendation");
        }
        return totalsExceededWhatIsDisplayed;
    }

    private static int displayedRules(JsonObject output) {
        int displayed = 0;
        JsonObject advisor = output.getJsonObject("advisor");
        for (String category : List.of("new_recommendations", "resolved_recommendations", "deactivated_recommendations")) {
            displayed += advisor.containsKey(category) ? advisor.getJsonObject(category).size() : 0;
        }
        return displayed;
    }

    private static List<String> ruleOrder(JsonObject output, String category) {
        JsonObject advisor = output.getJsonObject("advisor");
        return advisor.containsKey(category) ? new ArrayList<>(advisor.getJsonObject(category).fieldNames()) : List.of();
    }

    private static List<Set<Severity>> severitySubsets() {
        return List.of(
            Set.of(Severity.LOW), Set.of(Severity.IMPORTANT), Set.of(Severity.CRITICAL),
            Set.of(Severity.LOW, Severity.IMPORTANT), Set.of(Severity.LOW, Severity.CRITICAL),
            Set.of(Severity.IMPORTANT, Severity.CRITICAL), Set.of(Severity.LOW, Severity.IMPORTANT, Severity.CRITICAL));
    }

    private static List<Recommendation> randomStream(int size, int rules, long seed, boolean varyRisk) {
        Random random = new Random(seed);
        List<Recommendation> stream = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            int rule = random.nextInt(rules);
            String risk = String.valueOf(varyRisk ? 1 + random.nextInt(4) : 1 + rule % 4);
            stream.add(new Recommendation(EVENT_TYPES[random.nextInt(EVENT_TYPES.length)], "rule-" + rule, risk,
                String.valueOf(rule % 3 == 0), SEVERITIES[random.nextInt(SEVERITIES.length)]));
        }
        return stream;
    }

    private JsonObject legacyOutput(List<Recommendation> recommendations) {
        AdvisorEmailAggregator legacy = new AdvisorEmailAggregator();
        for (Recommendation recommendation : recommendations) {
            legacy.aggregate(rawAggregation(recommendation));
        }
        return normalize(JsonObject.mapFrom(legacy.getContext()));
    }

    /** Folded entries of the eligible recommendations, shaped like aggregator's fold; order = position in the whole stream. */
    private static List<JsonObject> foldedEntries(List<Recommendation> stream, Predicate<Recommendation> eligible) {
        List<JsonObject> entries = new ArrayList<>();
        for (int order = 0; order < stream.size(); order++) {
            Recommendation recommendation = stream.get(order);
            if (eligible.test(recommendation)) {
                entries.add(new JsonObject()
                    .put("event_type", recommendation.eventType())
                    .put("rule_id", recommendation.ruleId())
                    .put("rule_description", "description of " + recommendation.ruleId())
                    .put("total_risk", recommendation.risk())
                    .put("has_incident", recommendation.incident())
                    .put("rule_url", "https://example.com/" + recommendation.ruleId())
                    .put("_order", order));
            }
        }
        return entries;
    }

    private JsonObject finalizedOutput(List<JsonObject> entries) {
        // Shuffle the position, not the order values: the replay must sort by _order itself.
        List<JsonObject> shuffled = new ArrayList<>(entries);
        java.util.Collections.shuffle(shuffled, new Random(99));
        Map<String, Object> data = new JsonObject()
            .put("advisor", new JsonObject().put("recommendation_events", new JsonArray(shuffled)))
            .mapTo(Map.class);
        AggregatedDataFinalizerFactory.by(new EventAggregationCriterion("org", UUID.randomUUID(), UUID.randomUUID(), "rhel", "advisor")).finalizeData(data);
        return normalize(JsonObject.mapFrom(data));
    }

    private static JsonObject normalize(JsonObject json) {
        return new JsonObject(json.encode());
    }

    private static EmailAggregation rawAggregation(Recommendation recommendation) {
        JsonObject action = new JsonObject()
            .put("event_type", recommendation.eventType())
            .put("events", new JsonArray().add(new JsonObject().put("payload", new JsonObject()
                .put("rule_id", recommendation.ruleId())
                .put("rule_description", "description of " + recommendation.ruleId())
                .put("total_risk", recommendation.risk())
                .put("has_incident", recommendation.incident())
                .put("rule_url", "https://example.com/" + recommendation.ruleId()))));
        EmailAggregation aggregation = new EmailAggregation();
        aggregation.setBundleName("rhel");
        aggregation.setApplicationName("advisor");
        aggregation.setOrgId("org");
        aggregation.setPayload(action);
        return aggregation;
    }
}
