package com.schemaguard.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a plan document into parent and child nodes for Elasticsearch indexing.
 *
 * Parent-child split strategy:
 * - Parent: the top-level plan document (objectType = "plan")
 * - Children: each entry in "linkedPlanServices" array
 *   Each linkedPlanService has its own objectId (used as the ES document id)
 *   and is routed by the parent plan's objectId.
 *
 * This mirrors the actual data model in plan.json:
 *   plan (parent)
 *   └── linkedPlanServices[0]  (child, objectType = planservice)
 *   └── linkedPlanServices[1]  (child, objectType = planservice)
 *
 * The split is intentionally minimal: only linkedPlanServices are indexed
 * as children. planCostShares and nested cost shares are stored within
 * the parent document and are searchable via dynamic field mapping.
 */
@Component
public class PlanDocumentSplitter {

    private static final Logger log = LoggerFactory.getLogger(PlanDocumentSplitter.class);

    private final ObjectMapper objectMapper;

    public PlanDocumentSplitter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record ChildEntry(String childId, JsonNode childDoc) {}

    /**
     * Extracts child entries from a plan document.
     * Returns an empty list if the plan has no linkedPlanServices or if parsing fails.
     *
     * @param planJson  raw JSON string of the plan document
     * @return list of child entries, each with its objectId and JsonNode
     */
    public List<ChildEntry> extractChildren(String planJson) {
        List<ChildEntry> children = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(planJson);
            JsonNode services = root.get("linkedPlanServices");
            if (services == null || !services.isArray()) {
                return children;
            }
            for (JsonNode service : services) {
                JsonNode idNode = service.get("objectId");
                if (idNode != null && !idNode.isNull()) {
                    children.add(new ChildEntry(idNode.asText(), service));
                }
            }
        } catch (Exception ex) {
            log.warn("failed to extract children from plan document — {}", ex.getMessage());
        }
        return children;
    }
}
