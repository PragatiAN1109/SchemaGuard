package com.schemaguard.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;
import java.time.Instant;

public class StoredDocument implements Serializable {
    private final String objectId;
    private final String json;
    private final String etag;
    private final Instant lastModified;

    @JsonCreator
    public StoredDocument(
            @JsonProperty("objectId") String objectId,
            @JsonProperty("json") String json,
            @JsonProperty("etag") String etag,
            @JsonProperty("lastModified") Instant lastModified) {
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
