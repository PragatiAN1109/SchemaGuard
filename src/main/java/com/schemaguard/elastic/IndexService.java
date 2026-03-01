package com.schemaguard.elastic;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Abstraction for all Elasticsearch indexing operations in SchemaGuard.
 *
 * Separates index management from KV store (Redis) logic so each layer
 * can evolve independently. The queue consumer (next task) will call
 * these methods after receiving a message — controllers never call them directly.
 *
 * All operations are idempotent:
 * - index* methods use upsert semantics (index with same id replaces the document)
 * - delete* methods succeed silently if the document or children do not exist
 *
 * Routing contract:
 * - parent documents: routing is the document’s own id (Elasticsearch default)
 * - child documents: routing MUST be the parentId so parent and child
 *   land on the same shard (required for has_child / parent_id queries)
 */
public interface IndexService {

    /**
     * Upsert a parent (plan) document into the index.
     *
     * @param parentId   objectId of the plan — used as the Elasticsearch document id
     * @param parentDoc  full plan JSON (validated, stored in Redis)
     * @param etag       current SHA-256 ETag stored alongside the document
     * @param metadata   optional extra fields to store (may be null or empty)
     */
    void indexParent(String parentId, JsonNode parentDoc, String etag, Map<String, Object> metadata);

    /**
     * Upsert a child document into the index with routing = parentId.
     *
     * @param parentId   objectId of the parent plan (used for routing)
     * @param childId    objectId of the child object
     * @param childDoc   child JSON node
     * @param etag       current SHA-256 ETag of the child
     * @param metadata   optional extra fields (may be null or empty)
     */
    void indexChild(String parentId, String childId, JsonNode childDoc, String etag, Map<String, Object> metadata);

    /**
     * Replace a parent document with its patched version.
     * Semantically identical to indexParent but named separately for clarity
     * in queue handler code — signals intent of a PATCH operation.
     *
     * @param parentId         objectId of the plan
     * @param patchedParentDoc fully merged (post-patch) plan JSON
     * @param etag             new ETag after the patch
     */
    void patchParent(String parentId, JsonNode patchedParentDoc, String etag);

    /**
     * Delete a parent document from the index by id.
     * Succeeds silently if the document does not exist.
     *
     * @param parentId  objectId of the plan to delete
     */
    void deleteParent(String parentId);

    /**
     * Delete all child documents for a given parent using delete_by_query.
     * Succeeds silently if no children exist.
     * Must include routing = parentId so the query targets the correct shard.
     *
     * @param parentId  objectId of the parent whose children should be removed
     */
    void deleteChildren(String parentId);
}
