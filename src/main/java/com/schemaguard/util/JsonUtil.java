package com.schemaguard.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class JsonUtil {

    private JsonUtil() {}

    public static String extractTopLevelObjectId(ObjectMapper mapper, String rawJson) {
        try {
            JsonNode node = mapper.readTree(rawJson);
            JsonNode objectIdNode = node.get("objectId");
            if (objectIdNode == null || objectIdNode.asText().isBlank()) {
                return null;
            }
            return objectIdNode.asText();
        } catch (Exception e) {
            return null;
        }
    }
}