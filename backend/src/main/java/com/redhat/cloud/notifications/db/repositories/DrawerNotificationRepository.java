package com.redhat.cloud.notifications.db.repositories;

import com.redhat.cloud.notifications.config.BackendConfig;
import com.redhat.cloud.notifications.db.Query;
import com.redhat.cloud.notifications.db.Sort;
import com.redhat.cloud.notifications.models.DrawerEntryPayload;
import com.redhat.cloud.notifications.models.DrawerNotification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class DrawerNotificationRepository {

    @Inject
    EntityManager entityManager;

    @Inject
    BackendConfig backendConfig;

    @Transactional
    public Integer updateReadStatus(String orgId, String username, Set<UUID> notificationIds, Boolean readStatus) {

        String hql = "UPDATE DrawerNotification SET read = :readStatus "
            + "WHERE id.orgId = :orgId and id.userId = :userId and id.eventId in (:notificationIds)";

        return entityManager.createQuery(hql)
            .setParameter("orgId", orgId)
            .setParameter("userId", username)
            .setParameter("readStatus", readStatus)
            .setParameter("notificationIds", notificationIds)
            .executeUpdate();
    }

    public List<DrawerEntryPayload> getNotifications(String orgId, String username, Set<UUID> bundleIds, Set<UUID> appIds, Set<UUID> eventTypeIds,
                                              LocalDateTime startDate, LocalDateTime endDate, Boolean readStatus, Query query) {

        boolean useNormalized = backendConfig.isNormalizedQueriesEnabled(orgId);
        Optional<Sort> sort = Sort.getSort(query, "created:DESC", DrawerNotification.getSortFields(useNormalized));

        // Calculate once for reuse
        boolean bundlesNotEmpty = bundleIds != null && !bundleIds.isEmpty();
        boolean applicationsNotEmpty = appIds != null && !appIds.isEmpty();
        boolean eventTypesNotEmpty = eventTypeIds != null && !eventTypeIds.isEmpty();

        String hql;
        if (useNormalized) {
            hql = "SELECT dn.id.eventId, dn.read, " +
                "bundle.displayName, app.displayName, et.displayName, dn.created, dn.event.renderedDrawerNotification, bundle.name, dn.event.severity " +
                "FROM DrawerNotification dn " +
                "JOIN dn.event e " +
                "JOIN e.eventType et " +
                "JOIN et.application app " +
                "JOIN app.bundle bundle " +
                "WHERE dn.id.orgId = :orgId AND dn.id.userId = :userid";
        } else {
            hql = "SELECT dn.id.eventId, dn.read, " +
                "dn.event.bundleDisplayName, dn.event.applicationDisplayName, dn.event.eventTypeDisplayName, dn.created, dn.event.renderedDrawerNotification, bundle.name, dn.event.severity " +
                "FROM DrawerNotification dn JOIN Bundle bundle ON dn.event.bundleId = bundle.id " +
                "WHERE dn.id.orgId = :orgId AND dn.id.userId = :userid";
        }

        hql = addHqlConditions(hql, useNormalized, bundlesNotEmpty, applicationsNotEmpty, eventTypesNotEmpty, startDate, endDate, readStatus);
        if (sort.isPresent()) {
            hql += getOrderBy(sort.get());
        }

        TypedQuery<Object[]> typedQuery = entityManager.createQuery(hql, Object[].class);
        setQueryParams(typedQuery, orgId, username, bundleIds, appIds, eventTypeIds, startDate, endDate, readStatus);

        Query.Limit limit = query.getLimit();

        typedQuery.setMaxResults(limit.getLimit());
        typedQuery.setFirstResult(limit.getOffset());

        List<Object[]> results = typedQuery.getResultList();
        return  results.stream().map(e -> new DrawerEntryPayload(e)).collect(Collectors.toList());
    }

    public Long count(String orgId, String username, Set<UUID> bundleIds, Set<UUID> appIds, Set<UUID> eventTypeIds,
                      LocalDateTime startDate, LocalDateTime endDate, Boolean readStatus) {
        boolean useNormalized = backendConfig.isNormalizedQueriesEnabled(orgId);

        // Calculate once for reuse
        boolean bundlesNotEmpty = bundleIds != null && !bundleIds.isEmpty();
        boolean applicationsNotEmpty = appIds != null && !appIds.isEmpty();
        boolean eventTypesNotEmpty = eventTypeIds != null && !eventTypeIds.isEmpty();

        String hql = "SELECT count(dn.id.userId) FROM DrawerNotification dn ";

        // Add selective JOINs for normalized approach - only join what we need
        if (useNormalized && (bundlesNotEmpty || applicationsNotEmpty || eventTypesNotEmpty)) {
            hql += "JOIN dn.event e ";
            hql += "JOIN e.eventType et ";

            if (bundlesNotEmpty || applicationsNotEmpty) {
                hql += "JOIN et.application app ";
            }

            if (bundlesNotEmpty) {
                hql += "JOIN app.bundle bundle ";
            }
        }

        hql += "WHERE dn.id.orgId = :orgId AND dn.id.userId = :userid";

        hql = addHqlConditions(hql, useNormalized, bundlesNotEmpty, applicationsNotEmpty, eventTypesNotEmpty, startDate, endDate, readStatus);

        TypedQuery<Long> typedQuery = entityManager.createQuery(hql, Long.class);
        setQueryParams(typedQuery, orgId, username, bundleIds, appIds, eventTypeIds, startDate, endDate, readStatus);

        return typedQuery.getSingleResult();
    }

    private static String addHqlConditions(String hql, boolean useNormalized,
                                           boolean bundlesNotEmpty, boolean applicationsNotEmpty, boolean eventTypesNotEmpty,
                                           LocalDateTime startDate, LocalDateTime endDate, Boolean readStatus) {

        if (startDate != null && endDate != null) {
            hql += " AND dn.created BETWEEN :startDate AND :endDate";
        } else if (startDate != null) {
            hql += " AND dn.created >= :startDate";
        } else if (endDate != null) {
            hql += " AND dn.created <= :endDate";
        }

        if (readStatus != null) {
            hql += " AND dn.read = :readStatus";
        }

        if (!useNormalized) {
            // add org id as criteria on event table to allow usage of
            // index ix_event_org_id_bundle_id_application_id_event_type_display_nam
            hql += " AND dn.event.orgId = :orgId";
        }

        if (eventTypesNotEmpty) {
            if (useNormalized) {
                hql += " AND et.id IN (:eventTypeIds)";
            } else {
                hql += " AND dn.event.eventType.id IN (:eventTypeIds)";
            }
        } else if (applicationsNotEmpty) {
            if (useNormalized) {
                hql += " AND app.id IN (:appIds)";
            } else {
                hql += " AND dn.event.applicationId IN (:appIds)";
            }
        } else if (bundlesNotEmpty) {
            if (useNormalized) {
                hql += " AND bundle.id IN (:bundleIds)";
            } else {
                hql += " AND dn.event.bundleId IN (:bundleIds)";
            }
        }

        return hql;
    }

    private void setQueryParams(TypedQuery<?> query, String orgId, String username, Set<UUID> bundleIds, Set<UUID> appIds, Set<UUID> eventTypeIds,
                                LocalDateTime startDate, LocalDateTime endDate, Boolean status) {
        query.setParameter("orgId", orgId);
        query.setParameter("userid", username);

        if (eventTypeIds != null && !eventTypeIds.isEmpty()) {
            query.setParameter("eventTypeIds", eventTypeIds);
        } else if (appIds != null && !appIds.isEmpty()) {
            query.setParameter("appIds", appIds);
        } else if (bundleIds != null && !bundleIds.isEmpty()) {
            query.setParameter("bundleIds", bundleIds);
        }

        if (startDate != null) {
            query.setParameter("startDate", startDate);
        }
        if (endDate != null) {
            query.setParameter("endDate", endDate);
        }

        if  (status != null) {
            query.setParameter("readStatus", status);
        }
    }

    private String getOrderBy(Sort sort) {
        if (!sort.getSortColumn().equals("dn.created")) {
            return " " + sort.getSortQuery() + ", dn.created DESC";
        } else {
            return " " + sort.getSortQuery();
        }
    }

    @Transactional
    public void cleanupIntegrations(int limit) {
        String deleteQuery = "delete from endpoints where id in (select id from endpoints where endpoint_type_v2 = 'DRAWER' " +
            "and org_id is not null and not exists (select 1 from endpoint_event_type where endpoint_id = id) limit :limit)";
        entityManager.createNativeQuery(deleteQuery)
            .setParameter("limit", limit)
            .executeUpdate();
    }
}
