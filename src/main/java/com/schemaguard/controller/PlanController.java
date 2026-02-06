package com.schemaguard.controller;

import com.schemaguard.model.StoredDocument;
import com.schemaguard.store.InMemoryKeyValueStore;
import com.schemaguard.store.KeyValueStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {
    private final KeyValueStore store;

    public PlanController(KeyValueStore store) {
        this.store = store;
    }

    @GetMapping(value = "/{objectId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getPlan(
            @PathVariable String objectId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        Optional<StoredDocument> docOpt = store.get(objectId);

        if (docOpt.isEmpty()) {
            return ResponseEntity.status(404).body("{\"error\":\"NOT_FOUND\",\"message\":\"Plan not found\"}");
        }

        StoredDocument doc = docOpt.get();

        // Conditional GET: If-None-Match matches current ETag -> 304
        if (ifNoneMatch != null && ifNoneMatch.equals(doc.getEtag())) {
            return ResponseEntity.status(304)
                    .eTag(doc.getEtag())
                    .build();
        }

        // Normal GET: return body + ETag
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .eTag(doc.getEtag())
                .body(doc.getJson());
    }
}