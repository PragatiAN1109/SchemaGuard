package com.schemaguard.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
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
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN))
                .andExpect(status().isCreated())
                .andExpect(header().exists("ETag"))
                .andExpect(header().exists("Location"));
    }

    @Test
    void getPlan_afterCreate_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN));

        mockMvc.perform(get("/api/v1/plan/12xvxc345ssdsds-508"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"));
    }

    @Test
    void getPlan_withMatchingEtag_returns304() throws Exception {
        var result = mockMvc.perform(post("/api/v1/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN))
                .andReturn();

        String etag = result.getResponse().getHeader("ETag");

        mockMvc.perform(get("/api/v1/plan/12xvxc345ssdsds-508")
                .header("If-None-Match", etag))
                .andExpect(status().isNotModified());
    }

    @Test
    void deletePlan_returns204() throws Exception {
        mockMvc.perform(post("/api/v1/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN));

        mockMvc.perform(delete("/api/v1/plan/12xvxc345ssdsds-508"))
                .andExpect(status().isNoContent());
    }

    @Test
    void getPlan_nonExistent_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/plan/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createPlan_duplicate_returns409() throws Exception {
        mockMvc.perform(post("/api/v1/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN));

        mockMvc.perform(post("/api/v1/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_PLAN))
                .andExpect(status().isConflict());
    }
}
