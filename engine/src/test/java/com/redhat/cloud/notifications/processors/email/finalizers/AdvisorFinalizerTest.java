package com.redhat.cloud.notifications.processors.email.finalizers;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.redhat.cloud.notifications.processors.email.finalizers.FinalizerTestSupport.finalizeData;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvisorFinalizerTest {

    @Test
    void buildsTheLegacySectionFromTheRecommendationEvents() {
        Map<String, Object> data = data(
            recommendation("new-recommendation", "rule-1", "2", "true", 0),
            recommendation("new-recommendation", "rule-1", "2", "true", 1),
            recommendation("resolved-recommendation", "rule-2", "3", "false", 2),
            recommendation("deactivated-recommendation", "rule-3", "1", "false", 3));

        finalizeData("rhel", "advisor", data);

        Map<?, ?> advisor = (Map<?, ?>) data.get("advisor");
        assertFalse(advisor.containsKey("recommendation_events"), "the intermediate entries must not leak into the dispatched content");
        assertEquals(3, advisor.get("total_recommendation"));
        assertEquals(1, advisor.get("total_incident"));
        Map<?, ?> newRule = (Map<?, ?>) ((Map<?, ?>) advisor.get("new_recommendations")).get("rule-1");
        assertEquals(2, newRule.get("systems"));
        assertEquals("description of rule-1", newRule.get("rule_description"));
        assertEquals("true", newRule.get("has_incident"));
        Map<?, ?> deactivatedRule = (Map<?, ?>) ((Map<?, ?>) advisor.get("deactivated_recommendations")).get("rule-3");
        assertFalse(deactivatedRule.containsKey("systems"), "deactivated recommendations never carried a system count");
    }

    @Test
    void omitsCategoriesThatHadNoEvents() {
        Map<String, Object> data = data(recommendation("new-recommendation", "rule-1", "2", "false", 0));

        finalizeData("rhel", "advisor", data);

        Map<?, ?> advisor = (Map<?, ?>) data.get("advisor");
        assertTrue(advisor.containsKey("new_recommendations"));
        assertFalse(advisor.containsKey("resolved_recommendations"));
        assertFalse(advisor.containsKey("deactivated_recommendations"));
    }

    /**
     * Pins the legacy quirk on purpose (see AdvisorFinalizer): a rule cut from the top 5 is created again by
     * each of its next events, so the recommendation total keeps growing while the displayed rules don't.
     * If this test fails because someone "fixed" the counting, that is a deliberate behavior change.
     */
    @Test
    void keepsTheLegacyOvercountingOfRulesCutFromTheTopFive() {
        Map<String, Object> data = data(
            recommendation("new-recommendation", "rule-a", "4", "false", 0),
            recommendation("new-recommendation", "rule-b", "4", "false", 1),
            recommendation("new-recommendation", "rule-c", "4", "false", 2),
            recommendation("new-recommendation", "rule-d", "4", "false", 3),
            recommendation("new-recommendation", "rule-e", "4", "false", 4),
            recommendation("new-recommendation", "low-risk", "1", "false", 5),
            recommendation("new-recommendation", "low-risk", "1", "false", 6),
            recommendation("new-recommendation", "low-risk", "1", "false", 7));

        finalizeData("rhel", "advisor", data);

        Map<?, ?> advisor = (Map<?, ?>) data.get("advisor");
        Map<?, ?> displayed = (Map<?, ?>) advisor.get("new_recommendations");
        assertEquals(5, displayed.size());
        assertFalse(displayed.containsKey("low-risk"));
        assertEquals(8, advisor.get("total_recommendation"), "5 displayed rules plus the cut rule counted once per event");
    }

    @Test
    void replaysByFoldOrderNotByPositionInTheList() {
        // Same rule first reported with risk 1, later with risk 4: the entry is created by the first event
        // in fold order, whatever the position in the list.
        Map<String, Object> data = data(
            recommendation("new-recommendation", "rule-1", "4", "false", 9),
            recommendation("new-recommendation", "rule-1", "1", "false", 2));

        finalizeData("rhel", "advisor", data);

        Map<?, ?> rule = (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) data.get("advisor")).get("new_recommendations")).get("rule-1");
        assertEquals("1", rule.get("total_risk"));
        assertEquals(2, rule.get("systems"));
    }

    @Test
    void sortAndLimitKeepsTheFiveHighestRisksInDescendingOrder() {
        Map<String, Map<String, Object>> mapToSort = Map.of(
            "a", Map.of("total_risk", "52"),
            "b", Map.of("total_risk", "1"),
            "c", Map.of("total_risk", "11"),
            "h", Map.of("total_risk", "35"),
            "j", Map.of("total_risk", "41"),
            "g", Map.of("total_risk", "100"),
            "z", Map.of("total_risk", "100"));

        Map<String, Map<String, Object>> result = AdvisorFinalizer.sortAndLimitMapByRisk(mapToSort);

        assertEquals(AdvisorFinalizer.MAXIMUM_NUMBER_RETURNED_EVENTS, result.size());
        List<String> risks = result.values().stream().map(rule -> (String) rule.get("total_risk")).toList();
        assertEquals(List.of("100", "100", "52", "41", "35"), risks);
    }

    @Test
    void withoutRecommendationEventsTheDataIsLeftUntouched() {
        Map<String, Object> data = new JsonObject().put("advisor", new JsonObject().put("recommendation_events", new JsonArray())).mapTo(Map.class);

        finalizeData("rhel", "advisor", data);

        assertTrue(((Map<?, ?>) data.get("advisor")).containsKey("recommendation_events"));
    }

    private static Map<String, Object> data(JsonObject... entries) {
        JsonArray array = new JsonArray();
        for (JsonObject entry : entries) {
            array.add(entry);
        }
        return new JsonObject().put("advisor", new JsonObject().put("recommendation_events", array)).mapTo(Map.class);
    }

    private static JsonObject recommendation(String eventType, String ruleId, String risk, String incident, int order) {
        return new JsonObject()
            .put("event_type", eventType)
            .put("rule_id", ruleId)
            .put("rule_description", "description of " + ruleId)
            .put("total_risk", risk)
            .put("has_incident", incident)
            .put("rule_url", "https://example.com/" + ruleId)
            .put("_order", order);
    }
}
