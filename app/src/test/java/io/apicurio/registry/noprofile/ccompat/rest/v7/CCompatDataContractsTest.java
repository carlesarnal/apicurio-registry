package io.apicurio.registry.noprofile.ccompat.rest.v7;

import io.apicurio.registry.AbstractResourceTestBase;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
public class CCompatDataContractsTest extends AbstractResourceTestBase {

    private static final String AVRO_SCHEMA = "{\"type\":\"record\",\"name\":\"Order\","
            + "\"fields\":[{\"name\":\"orderId\",\"type\":\"string\"},"
            + "{\"name\":\"amount\",\"type\":\"double\"}]}";

    @Test
    public void testRegisterWithMetadataAndRuleSet_RoundTrip() {
        String subject = "testRoundTrip-" + System.currentTimeMillis();

        given().when().contentType(CT_JSON)
                .body("{\"schemaType\":\"AVRO\","
                        + "\"schema\":" + escapeJson(AVRO_SCHEMA) + ","
                        + "\"metadata\":{\"properties\":{\"owner\":\"team-a\",\"env\":\"prod\"}},"
                        + "\"ruleSet\":{\"domainRules\":["
                        + "{\"name\":\"pos-amount\",\"kind\":\"CONDITION\",\"type\":\"CEL\","
                        + "\"mode\":\"WRITE\",\"expr\":\"message.amount > 0\","
                        + "\"onFailure\":\"ERROR\",\"disabled\":false}],"
                        + "\"migrationRules\":[]}}")
                .post("/ccompat/v7/subjects/{subject}/versions", subject)
                .then().statusCode(200)
                .body("id", notNullValue());

        given().when()
                .get("/ccompat/v7/subjects/{subject}/versions/1", subject)
                .then().statusCode(200)
                .body("subject", equalTo(subject))
                .body("schema", notNullValue())
                .body("metadata.properties.owner", equalTo("team-a"))
                .body("metadata.properties.env", equalTo("prod"))
                .body("ruleSet.domainRules", hasSize(1))
                .body("ruleSet.domainRules[0].name", equalTo("pos-amount"))
                .body("ruleSet.domainRules[0].kind", equalTo("CONDITION"))
                .body("ruleSet.domainRules[0].type", equalTo("CEL"))
                .body("ruleSet.domainRules[0].mode", equalTo("WRITE"))
                .body("ruleSet.domainRules[0].expr", equalTo("message.amount > 0"))
                .body("ruleSet.migrationRules", hasSize(0));
    }

    @Test
    public void testRegisterWithoutMetadata_NoContractData() {
        String subject = "testNoMeta-" + System.currentTimeMillis();

        given().when().contentType(CT_JSON)
                .body("{\"schemaType\":\"AVRO\",\"schema\":" + escapeJson(AVRO_SCHEMA) + "}")
                .post("/ccompat/v7/subjects/{subject}/versions", subject)
                .then().statusCode(200);

        given().when()
                .get("/ccompat/v7/subjects/{subject}/versions/1", subject)
                .then().statusCode(200)
                .body("metadata", nullValue())
                .body("ruleSet", nullValue());
    }

    @Test
    public void testRegisterMetadataOnly_MultipleProperties() {
        String subject = "testMultiProps-" + System.currentTimeMillis();

        given().when().contentType(CT_JSON)
                .body("{\"schemaType\":\"AVRO\","
                        + "\"schema\":" + escapeJson(AVRO_SCHEMA) + ","
                        + "\"metadata\":{\"properties\":{"
                        + "\"owner\":\"data-team\","
                        + "\"classification\":\"CONFIDENTIAL\","
                        + "\"application.major.version\":\"3\"}}}")
                .post("/ccompat/v7/subjects/{subject}/versions", subject)
                .then().statusCode(200);

        given().when()
                .get("/ccompat/v7/subjects/{subject}/versions/1", subject)
                .then().statusCode(200)
                .body("metadata.properties.owner", equalTo("data-team"))
                .body("metadata.properties.classification", equalTo("CONFIDENTIAL"))
                .body("metadata.properties.'application.major.version'", equalTo("3"));
    }

    @Test
    public void testRegisterRuleSetOnly_NoMetadata() {
        String subject = "testRulesOnly-" + System.currentTimeMillis();

        given().when().contentType(CT_JSON)
                .body("{\"schemaType\":\"AVRO\","
                        + "\"schema\":" + escapeJson(AVRO_SCHEMA) + ","
                        + "\"ruleSet\":{\"domainRules\":["
                        + "{\"name\":\"r1\",\"kind\":\"CONDITION\",\"type\":\"CEL\","
                        + "\"mode\":\"WRITE\",\"expr\":\"true\",\"onFailure\":\"ERROR\"}],"
                        + "\"migrationRules\":[]}}")
                .post("/ccompat/v7/subjects/{subject}/versions", subject)
                .then().statusCode(200);

        given().when()
                .get("/ccompat/v7/subjects/{subject}/versions/1", subject)
                .then().statusCode(200)
                .body("metadata", nullValue())
                .body("ruleSet.domainRules", hasSize(1))
                .body("ruleSet.domainRules[0].name", equalTo("r1"));
    }

    @Test
    public void testRuleSetWithMigrationRules() {
        String subject = "testMigration-" + System.currentTimeMillis();

        given().when().contentType(CT_JSON)
                .body("{\"schemaType\":\"AVRO\","
                        + "\"schema\":" + escapeJson(AVRO_SCHEMA) + ","
                        + "\"ruleSet\":{\"domainRules\":[],"
                        + "\"migrationRules\":["
                        + "{\"name\":\"add-field\",\"kind\":\"TRANSFORM\",\"type\":\"JSONATA\","
                        + "\"mode\":\"UPGRADE\",\"expr\":\"$ ~> |$|{\\\"currency\\\": \\\"USD\\\"}|\","
                        + "\"onFailure\":\"ERROR\"}]}}")
                .post("/ccompat/v7/subjects/{subject}/versions", subject)
                .then().statusCode(200);

        given().when()
                .get("/ccompat/v7/subjects/{subject}/versions/1", subject)
                .then().statusCode(200)
                .body("ruleSet.migrationRules", hasSize(1))
                .body("ruleSet.migrationRules[0].name", equalTo("add-field"))
                .body("ruleSet.migrationRules[0].kind", equalTo("TRANSFORM"))
                .body("ruleSet.migrationRules[0].type", equalTo("JSONATA"))
                .body("ruleSet.migrationRules[0].mode", equalTo("UPGRADE"));
    }

    @Test
    public void testModeMapping_UpdownToWriteread() {
        String subject = "testModeMap-" + System.currentTimeMillis();

        given().when().contentType(CT_JSON)
                .body("{\"schemaType\":\"AVRO\","
                        + "\"schema\":" + escapeJson(AVRO_SCHEMA) + ","
                        + "\"ruleSet\":{\"domainRules\":["
                        + "{\"name\":\"rw\",\"kind\":\"CONDITION\",\"type\":\"CEL\","
                        + "\"mode\":\"WRITEREAD\",\"expr\":\"true\",\"onFailure\":\"ERROR\"}],"
                        + "\"migrationRules\":[]}}")
                .post("/ccompat/v7/subjects/{subject}/versions", subject)
                .then().statusCode(200);

        given().when()
                .get("/ccompat/v7/subjects/{subject}/versions/1", subject)
                .then().statusCode(200)
                .body("ruleSet.domainRules[0].mode", equalTo("UPDOWN"));
    }

    @Test
    public void testMetadataTagsRoundTrip() {
        String subject = "testTags-" + System.currentTimeMillis();

        given().when().contentType(CT_JSON)
                .body("{\"schemaType\":\"AVRO\","
                        + "\"schema\":" + escapeJson(AVRO_SCHEMA) + ","
                        + "\"metadata\":{\"tags\":{\"orderId\":[\"ID\"],\"amount\":[\"CURRENCY\",\"PII\"]}}}")
                .post("/ccompat/v7/subjects/{subject}/versions", subject)
                .then().statusCode(200);

        given().when()
                .get("/ccompat/v7/subjects/{subject}/versions/1", subject)
                .then().statusCode(200)
                .body("metadata.tags.orderid", hasSize(1))
                .body("metadata.tags.amount", hasSize(2));
    }

    @Test
    public void testFullContractRoundTrip_MetadataTagsAndRules() {
        String subject = "testFull-" + System.currentTimeMillis();

        given().when().contentType(CT_JSON)
                .body("{\"schemaType\":\"AVRO\","
                        + "\"schema\":" + escapeJson(AVRO_SCHEMA) + ","
                        + "\"metadata\":{\"properties\":{\"owner\":\"team-x\",\"env\":\"staging\"},"
                        + "\"tags\":{\"orderId\":[\"ID\"],\"amount\":[\"MONEY\"]}},"
                        + "\"ruleSet\":{\"domainRules\":["
                        + "{\"name\":\"r1\",\"kind\":\"CONDITION\",\"type\":\"CEL\","
                        + "\"mode\":\"WRITE\",\"expr\":\"true\",\"onFailure\":\"ERROR\"},"
                        + "{\"name\":\"r2\",\"kind\":\"TRANSFORM\",\"type\":\"JSONATA\","
                        + "\"mode\":\"UPGRADE\",\"expr\":\"$\",\"onFailure\":\"ERROR\"}],"
                        + "\"migrationRules\":[]}}")
                .post("/ccompat/v7/subjects/{subject}/versions", subject)
                .then().statusCode(200);

        given().when()
                .get("/ccompat/v7/subjects/{subject}/versions/1", subject)
                .then().statusCode(200)
                .body("metadata.properties.owner", equalTo("team-x"))
                .body("metadata.properties.env", equalTo("staging"))
                .body("metadata.tags.orderid", hasSize(1))
                .body("metadata.tags.amount", hasSize(1))
                .body("ruleSet.domainRules", hasSize(2))
                .body("ruleSet.domainRules[0].name", equalTo("r1"))
                .body("ruleSet.domainRules[1].name", equalTo("r2"));
    }

    private String escapeJson(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
