package com.schemaguard.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SchemaValidatorTest {

    @Test
    void validPayload_passes() {
        SchemaValidator validator = new SchemaValidator();

        String validJson = """
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
          "objectId": "12xvxc345ssdsds-508",
          "objectType": "plan",
          "planType": "inNetwork",
          "creationDate": "12-12-2017"
        }
        """;

        assertDoesNotThrow(() -> validator.validatePlanJson(validJson));
    }

    @Test
    void missingRequiredField_fails() {
        SchemaValidator validator = new SchemaValidator();

        // removed top-level objectType
        String invalidJson = """
        {
          "_org": "example.com",
          "objectId": "12xvxc345ssdsds-508",
          "planType": "inNetwork",
          "creationDate": "12-12-2017",
          "planCostShares": {
            "deductible": 2000,
            "_org": "example.com",
            "copay": 23,
            "objectId": "1234vxc2324sdf-501",
            "objectType": "membercostshare"
          },
          "linkedPlanServices": [{
            "_org": "example.com",
            "objectId": "27283xvx9asdff-504",
            "objectType": "planservice",
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
            }
          }]
        }
        """;

        SchemaValidationException ex =
                assertThrows(SchemaValidationException.class, () -> validator.validatePlanJson(invalidJson));

        assertTrue(ex.getErrors().size() > 0);
    }
}