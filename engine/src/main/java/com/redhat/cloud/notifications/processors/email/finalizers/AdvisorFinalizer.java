package com.redhat.cloud.notifications.processors.email.finalizers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces the recipient's folded recommendation events with the legacy {@code advisor} section, by replaying
 * the exact algorithm of the legacy per-recipient {@code AdvisorEmailAggregator} over them. That algorithm is
 * order dependent: after every single event it sorts the category map by risk and cuts it to the top
 * {@value #MAXIMUM_NUMBER_RETURNED_EVENTS}, and a rule that was cut is treated as new again on its next event
 * (its system count restarts at 1 and the recommendation/incident totals are incremented again). That behavior
 * is reproduced on purpose and must not be "fixed" here: the digest content has to stay identical to the
 * legacy one.
 *
 * <p>Major assumption, inherited from the legacy aggregator: there is at most one event per day for each
 * (rule, system) pair and event type. That is generally true, because a rule would have to be new twice or
 * resolved twice on the same system (new, then resolved, then new again) for it to be wrong. That is why
 * counting one system per event gives the number of affected systems, without knowing which systems they are.
 *
 * <p>Deliberately a line by line copy of the legacy logic, not a re-implementation. The legacy class stays in
 * place until the final cleanup part of the aggregation work.
 */
final class AdvisorFinalizer extends AggregatedDataFinalizer {

    static final int MAXIMUM_NUMBER_RETURNED_EVENTS = 5;

    private static final String ADVISOR_KEY = "advisor";
    private static final String RECOMMENDATION_EVENTS_KEY = "recommendation_events";

    private static final String NEW_RECOMMENDATION = "new-recommendation";
    private static final String RESOLVED_RECOMMENDATION = "resolved-recommendation";
    private static final String DEACTIVATED_RECOMMENDATION = "deactivated-recommendation";

    private static final String NEW_RECOMMENDATIONS = "new_recommendations";
    private static final String RESOLVED_RECOMMENDATIONS = "resolved_recommendations";
    private static final String DEACTIVATED_RECOMMENDATIONS = "deactivated_recommendations";
    private static final String TOTAL_INCIDENT = "total_incident";
    private static final String TOTAL_RECOMMENDATION = "total_recommendation";

    private static final String EVENT_TYPE = "event_type";
    private static final String RULE_ID = "rule_id";
    private static final String RULE_DESCRIPTION = "rule_description";
    private static final String TOTAL_RISK = "total_risk";
    private static final String HAS_INCIDENT = "has_incident";
    private static final String RULE_URL = "rule_url";
    private static final String CONTENT_SYSTEM_COUNT = "systems";
    private static final String ORDER_KEY = "_order";

    AdvisorFinalizer() {
        super(ADVISOR_KEY);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void finalizeSection(Map<String, Object> data, Map<String, Object> advisor) {
        if (!(advisor.get(RECOMMENDATION_EVENTS_KEY) instanceof List<?> entries) || entries.isEmpty()) {
            return;
        }
        data.put(ADVISOR_KEY, new Replay().run((List<Map<?, ?>>) entries));
    }

    /**
     * The state of one replay. The finalizer itself is shared and stateless, so every call gets its own.
     */
    private static final class Replay {

        private Map<String, Map<String, Object>> newRecommendations = new LinkedHashMap<>();
        private Map<String, Map<String, Object>> resolvedRecommendations = new LinkedHashMap<>();
        private Map<String, Map<String, Object>> deactivatedRecommendations = new LinkedHashMap<>();

        private int incidentCounter;
        private int recommendationCounter;

        private Map<String, Object> run(List<Map<?, ?>> entries) {
            List<Map<?, ?>> ordered = new ArrayList<>(entries);
            ordered.sort(Comparator.comparingLong(entry -> ((Number) entry.get(ORDER_KEY)).longValue()));

            Map<String, Object> advisor = new LinkedHashMap<>();
            for (Map<?, ?> entry : ordered) {
                String eventType = (String) entry.get(EVENT_TYPE);
                String ruleId = (String) entry.get(RULE_ID);
                String ruleDescription = (String) entry.get(RULE_DESCRIPTION);
                String ruleRisk = (String) entry.get(TOTAL_RISK);
                String ruleIncident = (String) entry.get(HAS_INCIDENT);
                String ruleURL = (String) entry.get(RULE_URL);
                Map<String, Object> ruleData;

                switch (eventType) {
                    case NEW_RECOMMENDATION:
                        ruleData = newRecommendations.computeIfAbsent(
                            ruleId, key -> {
                                countRecommendationsAndIncidents(ruleIncident);
                                return new HashMap<>(Map.of(
                                    RULE_DESCRIPTION, ruleDescription,
                                    HAS_INCIDENT, ruleIncident,
                                    TOTAL_RISK, ruleRisk,
                                    RULE_URL, ruleURL,
                                    CONTENT_SYSTEM_COUNT, 0
                                ));
                            }
                        );
                        ruleData.put(CONTENT_SYSTEM_COUNT, (Integer) ruleData.get(CONTENT_SYSTEM_COUNT) + 1);

                        this.newRecommendations = sortAndLimitMapByRisk(this.newRecommendations);
                        advisor.put(NEW_RECOMMENDATIONS, newRecommendations);
                        break;
                    case RESOLVED_RECOMMENDATION:
                        ruleData = resolvedRecommendations.computeIfAbsent(
                            ruleId, key -> {
                                countRecommendationsAndIncidents(ruleIncident);
                                return new HashMap<>(Map.of(
                                    RULE_DESCRIPTION, ruleDescription,
                                    HAS_INCIDENT, ruleIncident,
                                    TOTAL_RISK, ruleRisk,
                                    RULE_URL, ruleURL,
                                    CONTENT_SYSTEM_COUNT, 0
                                ));
                            }
                        );
                        ruleData.put(CONTENT_SYSTEM_COUNT, (Integer) ruleData.get(CONTENT_SYSTEM_COUNT) + 1);

                        this.resolvedRecommendations = sortAndLimitMapByRisk(this.resolvedRecommendations);
                        advisor.put(RESOLVED_RECOMMENDATIONS, resolvedRecommendations);
                        break;
                    case DEACTIVATED_RECOMMENDATION:
                        deactivatedRecommendations.computeIfAbsent(
                            ruleId, key -> {
                                countRecommendationsAndIncidents(ruleIncident);
                                return new HashMap<>(Map.of(
                                    RULE_DESCRIPTION, ruleDescription,
                                    HAS_INCIDENT, ruleIncident,
                                    TOTAL_RISK, ruleRisk,
                                    RULE_URL, ruleURL
                                ));
                            }
                        );

                        this.deactivatedRecommendations = sortAndLimitMapByRisk(this.deactivatedRecommendations);
                        advisor.put(DEACTIVATED_RECOMMENDATIONS, deactivatedRecommendations);
                        break;
                    default:
                        break;
                }
            }

            advisor.put(TOTAL_INCIDENT, incidentCounter);
            advisor.put(TOTAL_RECOMMENDATION, recommendationCounter);
            return advisor;
        }

        private void countRecommendationsAndIncidents(String ruleIncident) {
            recommendationCounter++;
            if (Boolean.valueOf(ruleIncident)) {
                incidentCounter++;
            }
        }
    }

    /**
     * Sorts the provided map by the "total_risk" element and limits it to {@link #MAXIMUM_NUMBER_RETURNED_EVENTS}
     * entries, highest risk first. Same stable sort and tie behavior as the legacy implementation.
     */
    static LinkedHashMap<String, Map<String, Object>> sortAndLimitMapByRisk(final Map<String, Map<String, Object>> unsortedMap) {
        final List<Map.Entry<String, Map<String, Object>>> sortedEntries = new ArrayList<>(unsortedMap.entrySet());
        sortedEntries.sort((o1, o2) -> {
            final Integer o1TotalRisk = Integer.parseInt((String) o1.getValue().get(TOTAL_RISK));
            final Integer o2TotalRisk = Integer.parseInt((String) o2.getValue().get(TOTAL_RISK));

            // The order is reversed because we want the elements sorted from the highest risk to the lowest one.
            return o2TotalRisk.compareTo(o1TotalRisk);
        });

        final LinkedHashMap<String, Map<String, Object>> resultMap = new LinkedHashMap<>(
            MAXIMUM_NUMBER_RETURNED_EVENTS + 1,
            1.0F
        );

        for (final Map.Entry<String, Map<String, Object>> entry : sortedEntries) {
            if (resultMap.size() > MAXIMUM_NUMBER_RETURNED_EVENTS - 1) {
                break;
            }
            resultMap.put(entry.getKey(), entry.getValue());
        }

        return resultMap;
    }
}
