package com.schemaguard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PlanController.applyMergePatch() — pure RFC 7396 logic,
 * no Spring context required.
 */
class MergePatchUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();
    // Instantiate just enough of the controller to call applyMergePatch
    private final PlanController controller = new PlanController(null, null, mapper);

    @Test
    void patch_overwritesScalarField() throws Exception {
        JsonNode target = mapper.readTree("{\"planType\":\"inNetwork\",\"_org\":\"example.com\"}");
        JsonNode patch  = mapper.readTree("{\"planType\":\"outOfNetwork\"}");

        JsonNode result = controller.applyMergePatch(target, patch);

        assertEquals("outOfNetwork", result.get("planType").asText());
        assertEquals("example.com",  result.get("_org").asText());
    }

    @Test
    void patch_nullValueRemovesField() throws Exception {
        JsonNode target = mapper.readTree("{\"planType\":\"inNetwork\",\"extra\":\"remove-me\"}");
        JsonNode patch  = mapper.readTree("{\"extra\":null}");

        JsonNode result = controller.applyMergePatch(target, patch);

        assertFalse(result.has("extra"), "Field with null patch value should be removed");
        assertEquals("inNetwork", result.get("planType").asText());
    }

    @Test
    void patch_addsNewField() throws Exception {
        JsonNode target = mapper.readTree("{\"_org\":\"example.com\"}");
        JsonNode patch  = mapper.readTree("{\"planType\":\"inNetwork\"}");

        JsonNode result = controller.applyMergePatch(target, patch);

        assertEquals("inNetwork",   result.get("planType").asText());
        assertEquals("example.com", result.get("_org").asText());
    }

    @Test
    void patch_mergesNestedObject() throws Exception {
        JsonNode target = mapper.readTree("""
                {
                  "planCostShares": {
                    "deductible": 2000,
                    "copay": 23
                  }
                }
                """);
        JsonNode patch = mapper.readTree("""
                {
                  "planCostShares": {
                    "copay": 50
                  }
                }
                """);

        JsonNode result = controller.applyMergePatch(target, patch);

        assertEquals(2000, result.get("planCostShares").get("deductible").asInt());
        assertEquals(50,   result.get("planCostShares").get("copay").asInt());
    }

    @Test
    void patch_nonObjectPatchReplacesTarget() throws Exception {
        JsonNode target = mapper.readTree("{\"a\":1}");
        JsonNode patch  = mapper.readTree("\"just a string\"");

        JsonNode result = controller.applyMergePatch(target, patch);

        assertTrue(result.isTextual());
        assertEquals("just a string", result.asText());
    }

    @Test
    void patch_emptyPatchLeavesTargetUnchanged() throws Exception {
        JsonNode target = mapper.readTree("{\"planType\":\"inNetwork\"}");
        JsonNode patch  = mapper.readTree("{}");

        JsonNode result = controller.applyMergePatch(target, patch);

        assertEquals("inNetwork", result.get("planType").asText());
    }
}
