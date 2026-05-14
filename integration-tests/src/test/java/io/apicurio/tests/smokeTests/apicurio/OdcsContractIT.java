package io.apicurio.tests.smokeTests.apicurio;

import io.apicurio.registry.rest.client.models.CreateArtifactResponse;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.types.ContentTypes;
import io.apicurio.registry.utils.tests.TestUtils;
import io.apicurio.tests.ApicurioRegistryBaseIT;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

import static io.apicurio.deployment.Constants.SMOKE;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@Tag(SMOKE)
@QuarkusIntegrationTest
class OdcsContractIT extends ApicurioRegistryBaseIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(OdcsContractIT.class);

    private static final String AVRO_SCHEMA = """
            {
              "type": "record",
              "name": "OrderEvent",
              "fields": [
                {"name": "orderId", "type": "string"},
                {"name": "customerEmail", "type": "string", "tags": ["PII", "EMAIL"]},
                {"name": "totalAmount", "type": "double"}
              ]
            }
            """;

    private String createOdcsContract(String schemaGroupId, String schemaArtifactId, String contractId) {
        return "apiVersion: v3.1.0\n"
                + "kind: DataContract\n"
                + "id: " + contractId + "\n"
                + "info:\n"
                + "  title: Test Contract\n"
                + "  version: 1.0.0\n"
                + "  status: active\n"
                + "  dataClassification: confidential\n"
                + "team:\n"
                + "  name: test-team\n"
                + "  domain: testing\n"
                + "  contact: test@example.com\n"
                + "schemas:\n"
                + "  - name: OrderEvent\n"
                + "    type: avro\n"
                + "    location: " + schemaGroupId + "/" + schemaArtifactId + ":latest\n"
                + "    fields:\n"
                + "      customerEmail:\n"
                + "        pii: true\n"
                + "        tags:\n"
                + "          - PII\n"
                + "          - EMAIL\n"
                + "quality:\n"
                + "  accuracy:\n"
                + "    - name: positive-amount\n"
                + "      expression: totalAmount > 0\n"
                + "      threshold: 1.0\n"
                + "serviceLevel:\n"
                + "  availability: 0.999\n";
    }

    private CreateArtifactResponse createSchemaArtifact(String groupId, String artifactId) throws Exception {
        return createArtifact(groupId, artifactId, ArtifactType.AVRO, AVRO_SCHEMA,
                ContentTypes.APPLICATION_JSON, null, null);
    }

    @Test
    void testSubmitAndGetContract() throws Exception {
        String groupId = TestUtils.generateGroupId();
        String artifactId = "submit-get-" + UUID.randomUUID();
        String contractId = "contract-" + UUID.randomUUID();

        createSchemaArtifact(groupId, artifactId);
        String contract = createOdcsContract(groupId, artifactId, contractId);

        given()
                .when()
                .header("Content-Type", "application/x-yaml")
                .pathParam("groupId", groupId)
                .body(contract.getBytes())
                .post("/registry/v3/groups/{groupId}/contracts")
                .then()
                .statusCode(200)
                .body("contractId", notNullValue())
                .body("projection.rulesApplied", equalTo(1))
                .body("projection.labelsApplied", greaterThanOrEqualTo(1));

        Thread.sleep(1000);

        given()
                .when()
                .pathParam("groupId", groupId)
                .pathParam("contractId", contractId)
                .get("/registry/v3/groups/{groupId}/contracts/{contractId}")
                .then()
                .statusCode(200)
                .contentType("application/x-yaml");
    }

    @Test
    void testListContracts() throws Exception {
        String groupId = TestUtils.generateGroupId();
        String artifactId = "list-contracts-" + UUID.randomUUID();
        String contractId = "contract-" + UUID.randomUUID();

        createSchemaArtifact(groupId, artifactId);

        given()
                .when()
                .header("Content-Type", "application/x-yaml")
                .pathParam("groupId", groupId)
                .body(createOdcsContract(groupId, artifactId, contractId).getBytes())
                .post("/registry/v3/groups/{groupId}/contracts")
                .then()
                .statusCode(200);

        Thread.sleep(1000);

        given()
                .when()
                .pathParam("groupId", groupId)
                .get("/registry/v3/groups/{groupId}/contracts")
                .then()
                .statusCode(200)
                .body("$", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    void testUpdateContract() throws Exception {
        String groupId = TestUtils.generateGroupId();
        String artifactId = "update-contract-" + UUID.randomUUID();
        String contractId = "contract-" + UUID.randomUUID();

        createSchemaArtifact(groupId, artifactId);

        given()
                .when()
                .header("Content-Type", "application/x-yaml")
                .pathParam("groupId", groupId)
                .body(createOdcsContract(groupId, artifactId, contractId).getBytes())
                .post("/registry/v3/groups/{groupId}/contracts")
                .then()
                .statusCode(200);

        Thread.sleep(1000);

        String updatedContract = createOdcsContract(groupId, artifactId, contractId)
                .replace("title: Test Contract", "title: Updated Contract");

        given()
                .when()
                .header("Content-Type", "application/x-yaml")
                .pathParam("groupId", groupId)
                .pathParam("contractId", contractId)
                .body(updatedContract.getBytes())
                .put("/registry/v3/groups/{groupId}/contracts/{contractId}")
                .then()
                .statusCode(200)
                .body("contractId", notNullValue());
    }

    @Test
    void testDeleteContract() throws Exception {
        String groupId = TestUtils.generateGroupId();
        String artifactId = "delete-contract-" + UUID.randomUUID();
        String contractId = "contract-" + UUID.randomUUID();

        createSchemaArtifact(groupId, artifactId);

        given()
                .when()
                .header("Content-Type", "application/x-yaml")
                .pathParam("groupId", groupId)
                .body(createOdcsContract(groupId, artifactId, contractId).getBytes())
                .post("/registry/v3/groups/{groupId}/contracts")
                .then()
                .statusCode(200);

        Thread.sleep(1000);

        given()
                .when()
                .pathParam("groupId", groupId)
                .pathParam("contractId", contractId)
                .delete("/registry/v3/groups/{groupId}/contracts/{contractId}")
                .then()
                .statusCode(204);

        given()
                .when()
                .pathParam("groupId", groupId)
                .pathParam("contractId", contractId)
                .get("/registry/v3/groups/{groupId}/contracts/{contractId}")
                .then()
                .statusCode(404);
    }

    @Test
    void testExportContractAsOdcs() throws Exception {
        String groupId = TestUtils.generateGroupId();
        String artifactId = "export-contract-" + UUID.randomUUID();
        String contractId = "contract-" + UUID.randomUUID();

        createSchemaArtifact(groupId, artifactId);

        given()
                .when()
                .header("Content-Type", "application/x-yaml")
                .pathParam("groupId", groupId)
                .body(createOdcsContract(groupId, artifactId, contractId).getBytes())
                .post("/registry/v3/groups/{groupId}/contracts")
                .then()
                .statusCode(200);

        Thread.sleep(1000);

        String exportedYaml = given()
                .when()
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .get("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/export")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        LOGGER.info("Exported ODCS YAML:\n{}", exportedYaml);
        org.junit.jupiter.api.Assertions.assertTrue(exportedYaml.contains("kind: DataContract"),
                "Exported YAML should contain DataContract kind");
    }

    @Test
    void testContractMetadata() throws Exception {
        String groupId = TestUtils.generateGroupId();
        String artifactId = "metadata-contract-" + UUID.randomUUID();
        String contractId = "contract-" + UUID.randomUUID();

        createSchemaArtifact(groupId, artifactId);

        given()
                .when()
                .header("Content-Type", "application/x-yaml")
                .pathParam("groupId", groupId)
                .body(createOdcsContract(groupId, artifactId, contractId).getBytes())
                .post("/registry/v3/groups/{groupId}/contracts")
                .then()
                .statusCode(200);

        Thread.sleep(1000);

        given()
                .when()
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .get("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/metadata")
                .then()
                .statusCode(200)
                .body("status", notNullValue());
    }

    @Test
    void testContractRuleset() throws Exception {
        String groupId = TestUtils.generateGroupId();
        String artifactId = "ruleset-contract-" + UUID.randomUUID();

        createSchemaArtifact(groupId, artifactId);

        String rulesetJson = """
                {
                  "domainRules": [
                    {
                      "name": "positive-amount",
                      "kind": "VALIDATE",
                      "type": "CEL",
                      "mode": "INGRESS",
                      "expr": "record.totalAmount > 0",
                      "onFailure": "FAIL"
                    }
                  ]
                }
                """;

        given()
                .when()
                .contentType("application/json")
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .body(rulesetJson)
                .post("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/ruleset")
                .then()
                .statusCode(200)
                .body("domainRules", hasSize(1));

        given()
                .when()
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .get("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/ruleset")
                .then()
                .statusCode(200)
                .body("domainRules", hasSize(1))
                .body("domainRules[0].name", equalTo("positive-amount"));

        given()
                .when()
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .delete("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/ruleset")
                .then()
                .statusCode(204);
    }

    @Test
    void testContractQualityScore() throws Exception {
        String groupId = TestUtils.generateGroupId();
        String artifactId = "quality-contract-" + UUID.randomUUID();
        String contractId = "contract-" + UUID.randomUUID();

        createSchemaArtifact(groupId, artifactId);

        given()
                .when()
                .header("Content-Type", "application/x-yaml")
                .pathParam("groupId", groupId)
                .body(createOdcsContract(groupId, artifactId, contractId).getBytes())
                .post("/registry/v3/groups/{groupId}/contracts")
                .then()
                .statusCode(200);

        Thread.sleep(1000);

        given()
                .when()
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .queryParam("contractId", contractId)
                .get("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/quality")
                .then()
                .statusCode(200)
                .body("overall", notNullValue());
    }

    @Test
    void testPromoteContract() throws Exception {
        String groupId = TestUtils.generateGroupId();
        String artifactId = "promote-contract-" + UUID.randomUUID();
        String contractId = "contract-" + UUID.randomUUID();

        createSchemaArtifact(groupId, artifactId);

        given()
                .when()
                .header("Content-Type", "application/x-yaml")
                .pathParam("groupId", groupId)
                .body(createOdcsContract(groupId, artifactId, contractId).getBytes())
                .post("/registry/v3/groups/{groupId}/contracts")
                .then()
                .statusCode(200);

        Thread.sleep(1000);

        given()
                .when()
                .contentType("application/json")
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .body("{\"contractId\":\"" + contractId + "\",\"targetStage\":\"DEV\"}")
                .post("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/promote")
                .then()
                .statusCode(200)
                .body("stage", equalTo("DEV"));

        given()
                .when()
                .contentType("application/json")
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .body("{\"contractId\":\"" + contractId + "\",\"targetStage\":\"STAGE\"}")
                .post("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/promote")
                .then()
                .statusCode(200)
                .body("stage", equalTo("STAGE"));
    }

    @Test
    void testPromoteInvalidStageReturns400() throws Exception {
        String groupId = TestUtils.generateGroupId();
        String artifactId = "promote-invalid-" + UUID.randomUUID();

        createSchemaArtifact(groupId, artifactId);

        given()
                .when()
                .contentType("application/json")
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .body("{\"contractId\":\"test\",\"targetStage\":\"INVALID\"}")
                .post("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/promote")
                .then()
                .statusCode(400);
    }

    @Test
    void testSubmitInvalidYamlReturns400() {
        String groupId = TestUtils.generateGroupId();

        given()
                .when()
                .header("Content-Type", "application/x-yaml")
                .pathParam("groupId", groupId)
                .body("not valid yaml {{{".getBytes())
                .post("/registry/v3/groups/{groupId}/contracts")
                .then()
                .statusCode(400);
    }

    @Test
    void testSubmitContractWithMissingSchemaReturns404() {
        String groupId = TestUtils.generateGroupId();
        String contractId = "contract-" + UUID.randomUUID();
        String contract = createOdcsContract(groupId, "nonexistent-artifact", contractId);

        given()
                .when()
                .header("Content-Type", "application/x-yaml")
                .pathParam("groupId", groupId)
                .body(contract.getBytes())
                .post("/registry/v3/groups/{groupId}/contracts")
                .then()
                .statusCode(404);
    }

    @Test
    void testExecuteContractRules() throws Exception {
        String groupId = TestUtils.generateGroupId();
        String artifactId = "execute-rules-" + UUID.randomUUID();

        createSchemaArtifact(groupId, artifactId);

        String rulesetJson = """
                {
                  "domainRules": [
                    {
                      "name": "positive-amount",
                      "kind": "VALIDATE",
                      "type": "CEL",
                      "mode": "INGRESS",
                      "expr": "record.totalAmount > 0",
                      "onFailure": "FAIL"
                    }
                  ]
                }
                """;

        given()
                .when()
                .contentType("application/json")
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .body(rulesetJson)
                .post("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/ruleset")
                .then()
                .statusCode(200);

        String validRecord = """
                {
                  "mode": "INGRESS",
                  "record": {"orderId": "123", "customerEmail": "test@example.com", "totalAmount": 99.99}
                }
                """;

        given()
                .when()
                .contentType("application/json")
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .pathParam("versionExpression", "latest")
                .body(validRecord)
                .post("/registry/v3/groups/{groupId}/artifacts/{artifactId}/versions/{versionExpression}/contract/execute")
                .then()
                .statusCode(200)
                .body("passed", equalTo(true));
    }

    @Test
    void testFullContractLifecycle() throws Exception {
        String groupId = TestUtils.generateGroupId();
        String artifactId = "lifecycle-" + UUID.randomUUID();
        String contractId = "contract-" + UUID.randomUUID();

        LOGGER.info("Starting full contract lifecycle test: groupId={}, artifactId={}", groupId, artifactId);

        // 1. Create the schema artifact
        createSchemaArtifact(groupId, artifactId);

        // 2. Submit the ODCS contract
        String contract = createOdcsContract(groupId, artifactId, contractId);
        given()
                .when()
                .header("Content-Type", "application/x-yaml")
                .pathParam("groupId", groupId)
                .body(contract.getBytes())
                .post("/registry/v3/groups/{groupId}/contracts")
                .then()
                .statusCode(200)
                .body("contractId", notNullValue())
                .body("projection.rulesApplied", greaterThanOrEqualTo(1))
                .body("projection.labelsApplied", greaterThanOrEqualTo(1));

        Thread.sleep(1000);

        // 3. Verify metadata was projected
        given()
                .when()
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .get("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/metadata")
                .then()
                .statusCode(200)
                .body("status", notNullValue());

        // 4. Check quality score
        given()
                .when()
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .queryParam("contractId", contractId)
                .get("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/quality")
                .then()
                .statusCode(200)
                .body("overall", notNullValue());

        // 5. Promote through stages
        given()
                .when()
                .contentType("application/json")
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .body("{\"contractId\":\"" + contractId + "\",\"targetStage\":\"DEV\"}")
                .post("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/promote")
                .then()
                .statusCode(200)
                .body("stage", equalTo("DEV"));

        // 6. Export as ODCS YAML
        String exported = given()
                .when()
                .pathParam("groupId", groupId)
                .pathParam("artifactId", artifactId)
                .get("/registry/v3/groups/{groupId}/artifacts/{artifactId}/contract/export")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        org.junit.jupiter.api.Assertions.assertTrue(exported.contains("kind: DataContract"));

        // 7. Delete the contract
        given()
                .when()
                .pathParam("groupId", groupId)
                .pathParam("contractId", contractId)
                .delete("/registry/v3/groups/{groupId}/contracts/{contractId}")
                .then()
                .statusCode(204);

        LOGGER.info("Full contract lifecycle test completed successfully");
    }
}
