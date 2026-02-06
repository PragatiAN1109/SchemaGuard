package com.schemaguard.model;

import java.time.Instant;

public class StoredDocument {
    private final String objectId;
    private final String json;
    private final String etag;
    private final Instant lastModified;

    public StoredDocument(String objectId, String json, String etag, Instant lastModified) {
        this.objectId = objectId;
        this.json = json;
        this.etag = etag;
        this.lastModified = lastModified;
    }

    public String getObjectId() {
        return objectId;
    }

    public String getJson() {
        return json;
    }

    public String getEtag() {
        return etag;
    }

    public Instant getLastModified() {
        return lastModified;
    }
}