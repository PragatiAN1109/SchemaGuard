package com.schemaguard.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for PUT and PATCH endpoints.
 * Uses the in-memory store (test profile) — no Redis required.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanCrudIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // A complete, schema-valid plan fixture
    private static final String VALID_PLAN = """
            {
              "planCostShares": {
                "deductible": 2000,
                "_org": "example.com",
                "copay": 23,
                "objectId": "1234vxc2324sdf-501",
                "objectType": "membercostshare"
              },
              "linkedPlanServices": [{
                "linkedService": {
                  "_org": "example.com",
                  "objectId": "1234520xvc30asdf-502",
                  "objectType": "service",
                  "name": "Yearly physical"
                },
                "planserviceCostShares": {
                  "deductible": 10,
                  "_org": "example.com",
                  "copay": 0,
                  "objectId": "1234512xvc1314asdfs-503",
                  "objectType": "membercostshare"
                },
                "_org": "example.com",
                "objectId": "27283xvx9asdff-504",
                "objectType": "planservice"
              }],
              "_org": "example.com",
              "objectId": "crud-test-plan-001",
              "objectType": "plan",
              "planType": "inNetwork",
              "creationDate": "12-12-2017"
            }
            """;

    @BeforeEach
    void setUp() throws Exception {
        // Ensure plan exists before each test by attempting creation
        // (ignore 409 Conflict if already present from a previous test)
        mockMvc.perform(post("/api/v1/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN));
    }

    // -------------------------------------------------------
    // PUT - success: full replace
    // -------------------------------------------------------
    @Test
    void put_success_replacesDocumentAndUpdatesEtag() throws Exception {
        // Get original ETag
        MvcResult getResult = mockMvc.perform(get("/api/v1/plan/crud-test-plan-001"))
                .andExpect(status().isOk())
                .andReturn();
        String originalEtag = getResult.getResponse().getHeader("ETag");

        // Replace with updated planType
        String replacedPlan = VALID_PLAN.replace("\"inNetwork\"", "\"outOfNetwork\"");

        MvcResult putResult = mockMvc.perform(put("/api/v1/plan/crud-test-plan-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacedPlan))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.objectId").value("crud-test-plan-001"))
                .andExpect(jsonPath("$.etag").exists())
                .andReturn();

        String newEtag = putResult.getResponse().getHeader("ETag");
        // ETag must change after update
        org.junit.jupiter.api.Assertions.assertNotEquals(originalEtag, newEtag);

        // Verify the stored document was actually replaced
        mockMvc.perform(get("/api/v1/plan/crud-test-plan-001"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("outOfNetwork")));
    }

    // -------------------------------------------------------
    // PUT - 404 when plan does not exist
    // -------------------------------------------------------
    @Test
    void put_notFound_returns404() throws Exception {
        mockMvc.perform(put("/api/v1/plan/nonexistent-xyz")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_PLAN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // -------------------------------------------------------
    // PUT - 400 when body fails schema validation
    // -------------------------------------------------------
    @Test
    void put_invalidSchema_returns400() throws Exception {
        // Missing required "objectType" at top level
        String invalidBody = """
                {
                  "_org": "example.com",
                  "objectId": "crud-test-plan-001",
                  "planType": "inNetwork",
                  "creationDate": "12-12-2017",
                  "planCostShares": {
                    "deductible": 2000, "_org": "example.com", "copay": 23,
                    "objectId": "cs-001", "objectType": "membercostshare"
                  },
                  "linkedPlanServices": [{
                    "_org": "example.com", "objectId": "ps-001", "objectType": "planservice",
                    "linkedService": {
                      "_org": "example.com", "objectId": "ls-001",
                      "objectType": "service", "name": "Test"
                    },
                    "planserviceCostShares": {
                      "deductible": 0, "_org": "example.com", "copay": 0,
                      "objectId": "pcs-001", "objectType": "membercostshare"
                    }
                  }]
                }
                """;
        mockMvc.perform(put("/api/v1/plan/crud-test-plan-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    // -------------------------------------------------------
    // PATCH - success: partial update merges fields
    // -------------------------------------------------------
    @Test
    void patch_success_mergesFieldAndUpdatesEtag() throws Exception {
        // Get original ETag
        MvcResult getResult = mockMvc.perform(get("/api/v1/plan/crud-test-plan-001"))
                .andExpect(status().isOk())
                .andReturn();
        String originalEtag = getResult.getResponse().getHeader("ETag");

        // Patch only planType
        String patchBody = "{\"planType\":\"outOfNetwork\"}";

        MvcResult patchResult = mockMvc.perform(patch("/api/v1/plan/crud-test-plan-001")
                        .contentType(PlanController.MERGE_PATCH_CONTENT_TYPE)
                        .content(patchBody))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(content().string(containsString("outOfNetwork")))
                // Other required fields must still be present
                .andExpect(content().string(containsString("planCostShares")))
                .andReturn();

        String newEtag = patchResult.getResponse().getHeader("ETag");
        org.junit.jupiter.api.Assertions.assertNotEquals(originalEtag, newEtag,
                "ETag must change after a successful PATCH");
    }

    // -------------------------------------------------------
    // PATCH - 404 when plan does not exist
    // -------------------------------------------------------
    @Test
    void patch_notFound_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/plan/does-not-exist-999")
                        .contentType(PlanController.MERGE_PATCH_CONTENT_TYPE)
                        .content("{\"planType\":\"outOfNetwork\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // -------------------------------------------------------
    // PATCH - 400 when patch removes a required field (null value)
    // -------------------------------------------------------
    @Test
    void patch_nullRequiredField_failsSchemaValidation() throws Exception {
        // Setting "objectType" to null removes it → schema violation
        String patchBody = "{\"objectType\":null}";

        mockMvc.perform(patch("/api/v1/plan/crud-test-plan-001")
                        .contentType(PlanController.MERGE_PATCH_CONTENT_TYPE)
                        .content(patchBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details").isArray());
    }

    // -------------------------------------------------------
    // PATCH - 400 when patch results in a schema-invalid value
    // -------------------------------------------------------
    @Test
    void patch_invalidSchemaValue_returns400() throws Exception {
        // objectType must be "plan" per schema const
        String patchBody = "{\"objectType\":\"invalid-type\"}";

        mockMvc.perform(patch("/api/v1/plan/crud-test-plan-001")
                        .contentType(PlanController.MERGE_PATCH_CONTENT_TYPE)
                        .content(patchBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    // -------------------------------------------------------
    // Existing POST + GET still work (regression guard)
    // -------------------------------------------------------
    @Test
    void post_andGet_existingBehaviourUnchanged() throws Exception {
        String newPlan = VALID_PLAN.replace("crud-test-plan-001", "regression-plan-002");

        mockMvc.perform(post("/api/v1/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(newPlan))
                .andExpect(status().isCreated())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.objectId").value("regression-plan-002"));

        mockMvc.perform(get("/api/v1/plan/regression-plan-002"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(content().string(containsString("regression-plan-002")));
    }
}
