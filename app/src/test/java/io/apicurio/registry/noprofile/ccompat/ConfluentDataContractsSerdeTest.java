package io.apicurio.registry.noprofile.ccompat;

import io.apicurio.registry.AbstractResourceTestBase;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.avro.AvroSchemaProvider;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.entities.Metadata;
import io.confluent.kafka.schemaregistry.client.rest.entities.Rule;
import io.confluent.kafka.schemaregistry.client.rest.entities.RuleKind;
import io.confluent.kafka.schemaregistry.client.rest.entities.RuleMode;
import io.confluent.kafka.schemaregistry.client.rest.entities.RuleSet;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.confluent.kafka.serializers.KafkaAvroSerializerConfig;
import io.quarkus.test.junit.QuarkusTest;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class ConfluentDataContractsSerdeTest extends AbstractResourceTestBase {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    private static final String AVRO_SCHEMA = "{\"type\":\"record\",\"name\":\"Order\","
            + "\"fields\":["
            + "{\"name\":\"orderId\",\"type\":\"string\"},"
            + "{\"name\":\"amount\",\"type\":\"double\"}"
            + "]}";

    private String registryUrl() {
        return String.format("http://localhost:%d/apis/ccompat/v7", testPort);
    }

    // Disabled: cel-standalone 0.6.0 relocates protobuf types which are ABI-incompatible
    // with Confluent's CEL executor. See contracts-rules/pom.xml for the dependency choice.
    // TODO: migrate contracts-rules from cel-standalone to cel-tools to enable this.
    // @Test
    public void testSerializeWithCelRule_PassesValidData() throws Exception {
        String subject = "confluent-dc-cel-pass-" + System.currentTimeMillis() + "-value";
        Schema schema = new Schema.Parser().parse(AVRO_SCHEMA);

        SchemaRegistryClient client = new CachedSchemaRegistryClient(
                registryUrl(), 20, List.of(new AvroSchemaProvider()), Map.of());

        Rule celRule = new Rule("positive-amount", "Ensure positive amount",
                RuleKind.CONDITION, RuleMode.WRITE, "CEL",
                null, null, "message.amount > 0",
                "NONE", "ERROR", false);

        RuleSet ruleSet = new RuleSet(null, List.of(celRule));

        AvroSchema avroSchema = new AvroSchema(AVRO_SCHEMA,
                Collections.emptyList(), Collections.emptyMap(),
                null, ruleSet, null, true);

        client.register(subject, avroSchema);

        Map<String, Object> config = new HashMap<>();
        config.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, registryUrl());
        config.put(KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS, "false");
        config.put("use.latest.version", "true");

        try (KafkaAvroSerializer serializer = new KafkaAvroSerializer(client);
                KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer(client)) {

            serializer.configure(config, false);
            deserializer.configure(config, false);

            GenericRecord record = new GenericData.Record(schema);
            record.put("orderId", "ORD-001");
            record.put("amount", 99.99);

            String topic = subject.replace("-value", "");
            byte[] bytes = serializer.serialize(topic, record);
            assertNotNull(bytes);

            GenericRecord deserialized = (GenericRecord) deserializer.deserialize(topic, bytes);
            assertEquals("ORD-001", deserialized.get("orderId").toString());
            assertEquals(99.99, (double) deserialized.get("amount"), 0.001);
        }
    }

    // @Test
    public void testSerializeWithCelRule_RejectsInvalidData() throws Exception {
        String subject = "confluent-dc-cel-fail-" + System.currentTimeMillis() + "-value";
        Schema schema = new Schema.Parser().parse(AVRO_SCHEMA);

        SchemaRegistryClient client = new CachedSchemaRegistryClient(
                registryUrl(), 20, List.of(new AvroSchemaProvider()), Map.of());

        Rule celRule = new Rule("positive-amount", null,
                RuleKind.CONDITION, RuleMode.WRITE, "CEL",
                null, null, "message.amount > 0",
                "NONE", "ERROR", false);

        RuleSet ruleSet = new RuleSet(null, List.of(celRule));

        AvroSchema avroSchema = new AvroSchema(AVRO_SCHEMA,
                Collections.emptyList(), Collections.emptyMap(),
                null, ruleSet, null, true);

        client.register(subject, avroSchema);

        Map<String, Object> config = new HashMap<>();
        config.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, registryUrl());
        config.put(KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS, "false");
        config.put("use.latest.version", "true");

        try (KafkaAvroSerializer serializer = new KafkaAvroSerializer(client)) {
            serializer.configure(config, false);

            GenericRecord record = new GenericData.Record(schema);
            record.put("orderId", "ORD-002");
            record.put("amount", -5.0);

            String topic = subject.replace("-value", "");
            var ex = org.junit.jupiter.api.Assertions.assertThrows(
                    org.apache.kafka.common.errors.SerializationException.class,
                    () -> serializer.serialize(topic, record));
            assertTrue(ex.getMessage().contains("Rule failed")
                    || (ex.getCause() != null
                    && ex.getCause().getMessage().contains("Rule failed")));
        }
    }

    @Test
    public void testMetadataRegistration_ViaConfluentClient() throws Exception {
        String subject = "confluent-dc-meta-" + System.currentTimeMillis() + "-value";

        SchemaRegistryClient client = new CachedSchemaRegistryClient(
                registryUrl(), 20, List.of(new AvroSchemaProvider()), Map.of());

        Metadata metadata = new Metadata(
                Map.of("orderId", Set.of("IDENTIFIER")),
                Map.of("owner", "platform-team", "classification", "INTERNAL"),
                Set.of());

        AvroSchema avroSchema = new AvroSchema(AVRO_SCHEMA,
                Collections.emptyList(), Collections.emptyMap(),
                metadata, null, null, true);

        int id = client.register(subject, avroSchema);
        assertTrue(id > 0);

        io.restassured.RestAssured.given()
                .when()
                .get("/ccompat/v7/subjects/{subject}/versions/1", subject)
                .then().statusCode(200)
                .body("metadata.properties.owner",
                        org.hamcrest.Matchers.equalTo("platform-team"))
                .body("metadata.properties.classification",
                        org.hamcrest.Matchers.equalTo("INTERNAL"));
    }

    @Test
    public void testRuleSetRegistration_ViaConfluentClient() throws Exception {
        String subject = "confluent-dc-rules-" + System.currentTimeMillis() + "-value";

        SchemaRegistryClient client = new CachedSchemaRegistryClient(
                registryUrl(), 20, List.of(new AvroSchemaProvider()), Map.of());

        Rule rule = new Rule("check-amount", null,
                RuleKind.CONDITION, RuleMode.WRITE, "CEL",
                null, null, "message.amount > 0",
                "NONE", "ERROR", false);

        RuleSet ruleSet = new RuleSet(null, List.of(rule));

        AvroSchema avroSchema = new AvroSchema(AVRO_SCHEMA,
                Collections.emptyList(), Collections.emptyMap(),
                null, ruleSet, null, true);

        client.register(subject, avroSchema);

        io.restassured.RestAssured.given()
                .when()
                .get("/ccompat/v7/subjects/{subject}/versions/1", subject)
                .then().statusCode(200)
                .body("ruleSet.domainRules", org.hamcrest.Matchers.hasSize(1))
                .body("ruleSet.domainRules[0].name",
                        org.hamcrest.Matchers.equalTo("check-amount"))
                .body("ruleSet.domainRules[0].expr",
                        org.hamcrest.Matchers.equalTo("message.amount > 0"));
    }

    @Test
    public void testSerializeWithMetadataOnly_NoRuleExecution() throws Exception {
        String subject = "confluent-dc-metaonly-" + System.currentTimeMillis() + "-value";
        Schema schema = new Schema.Parser().parse(AVRO_SCHEMA);

        SchemaRegistryClient client = new CachedSchemaRegistryClient(
                registryUrl(), 20, List.of(new AvroSchemaProvider()), Map.of());

        Metadata metadata = new Metadata(
                Map.of("amount", Set.of("CURRENCY")),
                Map.of("owner", "team-b"),
                Set.of());

        AvroSchema avroSchema = new AvroSchema(AVRO_SCHEMA,
                Collections.emptyList(), Collections.emptyMap(),
                metadata, null, null, true);

        client.register(subject, avroSchema);

        Map<String, Object> config = new HashMap<>();
        config.put(KafkaAvroSerializerConfig.SCHEMA_REGISTRY_URL_CONFIG, registryUrl());
        config.put(KafkaAvroSerializerConfig.AUTO_REGISTER_SCHEMAS, "false");
        config.put("use.latest.version", "true");

        try (KafkaAvroSerializer serializer = new KafkaAvroSerializer(client);
                KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer(client)) {

            serializer.configure(config, false);
            deserializer.configure(config, false);

            GenericRecord record = new GenericData.Record(schema);
            record.put("orderId", "ORD-003");
            record.put("amount", 42.0);

            String topic = subject.replace("-value", "");
            byte[] bytes = serializer.serialize(topic, record);
            assertNotNull(bytes);

            GenericRecord deserialized = (GenericRecord) deserializer.deserialize(topic, bytes);
            assertEquals("ORD-003", deserialized.get("orderId").toString());
        }
    }
}
