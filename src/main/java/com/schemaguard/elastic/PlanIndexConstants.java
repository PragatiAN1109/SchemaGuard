package com.schemaguard.elastic;

/**
 * Central constants for the Elasticsearch plans index.
 *
 * Having a single source of truth for the index name, join field name,
 * and type names avoids magic strings scattered across indexing,
 * search, and routing logic.
 */
public final class PlanIndexConstants {

    private PlanIndexConstants() {}

    /** Name of the Elasticsearch index. */
    public static final String INDEX_NAME = "plans-index";

    /**
     * The join field added to every document.
     * Value is either the string "plan" (parent)
     * or {"name": "child", "parent": "<parentId>"} (child).
     */
    public static final String JOIN_FIELD  = "my_join_field";

    /** Join relation name for parent documents. */
    public static final String TYPE_PLAN   = "plan";

    /** Join relation name for child documents. */
    public static final String TYPE_CHILD  = "child";
}
