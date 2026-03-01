package com.schemaguard.elastic;

import org.springframework.stereotype.Component;

import java.util.Map;

import static com.schemaguard.elastic.PlanIndexConstants.TYPE_CHILD;
import static com.schemaguard.elastic.PlanIndexConstants.TYPE_PLAN;

/**
 * Encapsulates all Elasticsearch routing and join-field logic for the plans index.
 *
 * Routing rules:
 * - parent document: routing = its own objectId (Elasticsearch default, no override needed)
 * - child document:  routing = parentId (MUST be set explicitly — enforces co-location
 *   with the parent on the same shard, required for join queries)
 *
 * Join field shapes:
 * - parent: my_join_field = "plan"  (plain string)
 * - child:  my_join_field = { "name": "child", "parent": "<parentId>" }  (object)
 *
 * This class is intentionally stateless — all methods are pure functions that
 * take IDs and return the correct field values. The indexing layer (next task)
 * calls these methods when building index requests.
 */
@Component
public class PlanRoutingStrategy {

    /**
     * Returns the join field value for a parent (plan) document.
     * Elasticsearch accepts a plain string when there are no child relations to specify.
     *
     * @return  the string "plan"
     */
    public String parentJoinField() {
        return TYPE_PLAN;
    }

    /**
     * Returns the join field value for a child document.
     * Must be an object with "name" and "parent" keys.
     *
     * @param parentId  objectId of the parent plan document
     * @return  map representing { "name": "child", "parent": "<parentId>" }
     */
    public Map<String, String> childJoinField(String parentId) {
        return Map.of(
                "name",   TYPE_CHILD,
                "parent", parentId
        );
    }

    /**
     * Returns the routing value for a child document.
     * Must equal the parent document's ID so both land on the same shard.
     *
     * @param parentId  objectId of the parent plan document
     * @return  parentId (used as the ?routing= query parameter on index requests)
     */
    public String childRouting(String parentId) {
        return parentId;
    }
}
