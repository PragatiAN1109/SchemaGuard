package com.schemaguard.controller;

import com.schemaguard.config.TestSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class PlanCrudIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String VALID_PLAN = """
        {
          "planCostShares": {
            "deductible": 2000,
            "_org": "example.com",
            "copay": 23,
            "objectId": "1234vxc2324sdf-501",
            "objectType": "membercostshare"
          },
          "linkedPlanServices": [
            {
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
            }
          ],
          "_org": "example.com",
          "objectId": "12xvxc345ssdsds-508",
          "objectType": "plan",
          "planType": "inNetwork",
          "creationDate": "12-12-2017"
        }
        """;

    @Test
    void createPlan_returns201() throws Exception {
        mockMvc.perform(post("/api/v1/plan")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN))
                .andExpect(status().isCreated())
                .andExpect(header().exists("ETag"))
                .andExpect(header().exists("Location"));
    }

    @Test
    void getPlan_afterCreate_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/plan")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN));

        mockMvc.perform(get("/api/v1/plan/12xvxc345ssdsds-508")
                .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"));
    }

    @Test
    void getPlan_withMatchingEtag_returns304() throws Exception {
        var result = mockMvc.perform(post("/api/v1/plan")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN))
                .andReturn();

        String etag = result.getResponse().getHeader("ETag");

        mockMvc.perform(get("/api/v1/plan/12xvxc345ssdsds-508")
                .with(jwt())
                .header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void deletePlan_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/plan")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN));

        mockMvc.perform(delete("/api/v1/plan/12xvxc345ssdsds-508")
                .with(jwt()))
                .andExpect(status().isNoContent());
    }

    @Test
    void getPlan_nonExistent_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/plan/does-not-exist")
                .with(jwt()))
                .andExpect(status().isNotFound());
    }

    @Test
    void createPlan_duplicate_returns409() throws Exception {
        mockMvc.perform(post("/api/v1/plan")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN));

        mockMvc.perform(post("/api/v1/plan")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN))
                .andExpect(status().isConflict());
    }

    @Test
    void putPlan_withStaleIfMatch_returns412() throws Exception {
        mockMvc.perform(post("/api/v1/plan")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN));

        mockMvc.perform(put("/api/v1/plan/12xvxc345ssdsds-508")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .header("If-Match", "\"wrong-etag-value\"")
                .content(VALID_PLAN))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void patchPlan_withStaleIfMatch_returns412() throws Exception {
        mockMvc.perform(post("/api/v1/plan")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN));

        mockMvc.perform(patch("/api/v1/plan/12xvxc345ssdsds-508")
                .with(jwt())
                .contentType("application/merge-patch+json")
                .header("If-Match", "\"wrong-etag-value\"")
                .content("{\"planType\": \"outOfNetwork\"}"))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void deletePlan_withStaleIfMatch_returns412() throws Exception {
        mockMvc.perform(post("/api/v1/plan")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN));

        mockMvc.perform(delete("/api/v1/plan/12xvxc345ssdsds-508")
                .with(jwt())
                .header("If-Match", "\"wrong-etag-value\""))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void rapidConsecutivePatches_lastWriteWins() throws Exception {
        // Uses its own objectId (distinct from VALID_PLAN's) so this test's etag
        // chain can't be disturbed by other tests sharing the same KV store instance.
        String objectId = "rapid-patch-plan-999";
        String plan = VALID_PLAN.replaceFirst(
                "\"objectId\": \"12xvxc345ssdsds-508\"",
                "\"objectId\": \"" + objectId + "\"");

        var createResult = mockMvc.perform(post("/api/v1/plan")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(plan))
                .andExpect(status().isCreated())
                .andReturn();

        String etagV0 = createResult.getResponse().getHeader("ETag");

        var patchV1Result = mockMvc.perform(patch("/api/v1/plan/" + objectId)
                .with(jwt())
                .contentType("application/merge-patch+json")
                .header("If-Match", etagV0)
                .content("{\"planType\": \"outOfNetwork\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String etagV1 = patchV1Result.getResponse().getHeader("ETag");

        mockMvc.perform(patch("/api/v1/plan/" + objectId)
                .with(jwt())
                .contentType("application/merge-patch+json")
                .header("If-Match", etagV1)
                .content("{\"planType\": \"inNetwork\"}"))
                .andExpect(status().isOk());

        // An attempt to write against the now-superseded v1 etag must be rejected —
        // this is the precondition that ultimately makes the async stale-event guard possible.
        mockMvc.perform(patch("/api/v1/plan/" + objectId)
                .with(jwt())
                .contentType("application/merge-patch+json")
                .header("If-Match", etagV1)
                .content("{\"planType\": \"outOfNetwork\"}"))
                .andExpect(status().isPreconditionFailed());

        mockMvc.perform(get("/api/v1/plan/" + objectId)
                .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"planType\":\"inNetwork\"")));
    }
}
