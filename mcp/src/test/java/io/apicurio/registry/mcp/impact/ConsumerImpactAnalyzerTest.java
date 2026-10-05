package io.apicurio.registry.mcp.impact;

import io.apicurio.registry.mcp.impact.ConsumerImpactReport.ConsumerResult;
import io.apicurio.registry.mcp.impact.ConsumerImpactReport.RulesCheck;
import io.apicurio.registry.mcp.impact.ConsumerImpactReport.Status;
import io.apicurio.registry.mcp.impact.ConsumerImpactReport.Verdict;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerImpactAnalyzerTest {

    private static final String V1 = """
            {"type":"record","name":"OrderCreated","namespace":"com.example.orders","fields":[
              {"name":"orderId","type":"string"},
              {"name":"customerId","type":"string"},
              {"name":"amount","type":"double"},
              {"name":"currency","type":"string"}]}""";

    private static final String ADD_FIELD = """
            {"type":"record","name":"OrderCreated","namespace":"com.example.orders","fields":[
              {"name":"orderId","type":"string"},
              {"name":"customerId","type":"string"},
              {"name":"amount","type":"double"},
              {"name":"currency","type":"string"},
              {"name":"channel","type":"string","default":"web"}]}""";

    /** Passes a BACKWARD rule (new field has a default) but v1 readers need "currency". */
    private static final String RENAME_FIELD = """
            {"type":"record","name":"OrderCreated","namespace":"com.example.orders","fields":[
              {"name":"orderId","type":"string"},
              {"name":"customerId","type":"string"},
              {"name":"amount","type":"double"},
              {"name":"currencyCode","type":"string","default":"EUR"}]}""";

    /** Passes a BACKWARD rule but v1 readers cannot read null customerId. */
    private static final String NULLABLE_FIELD = """
            {"type":"record","name":"OrderCreated","namespace":"com.example.orders","fields":[
              {"name":"orderId","type":"string"},
              {"name":"customerId","type":["null","string"],"default":null},
              {"name":"amount","type":"double"},
              {"name":"currency","type":"string"}]}""";

    private static final RulesCheck RULES_OK = new RulesCheck(true, "Schema is valid and compatible.");
    private static final Function<String, Optional<String>> REGISTRY = v -> Optional.ofNullable(Map.of("1", V1).get(v));

    private static ConsumerInventory.Consumer consumer(String name, String version, String... required) {
        return new ConsumerInventory.Consumer(name, "team-" + name, version,
                required == null ? null : Arrays.asList(required));
    }

    private static ConsumerImpactReport analyze(String candidate, ConsumerInventory.Consumer... consumers) {
        return ConsumerImpactAnalyzer.analyze("AVRO", candidate, new ConsumerInventory(List.of(consumers)),
                "orders.consumers", REGISTRY, RULES_OK);
    }

    @Test
    void additiveFieldWithDefaultIsSafeForAllDeclaredConsumers() {
        var report = analyze(ADD_FIELD,
                consumer("router", "1", "orderId", "customerId", "amount"),
                consumer("billing", "1", "orderId", "amount", "currency"));

        assertEquals(Verdict.SAFE_FOR_DECLARED_CONSUMERS, report.verdict());
        assertEquals(2, report.consumers().size());
        for (ConsumerResult r : report.consumers()) {
            assertEquals(Status.PASS, r.readCheck(), r.name());
            assertEquals(Status.PASS, r.contractCheck(), r.name());
            assertEquals(List.of(), r.findings(), r.name());
        }
    }

    @Test
    void renamedFieldBreaksLaggingReadersEvenWhenRegistryRulePasses() {
        var report = analyze(RENAME_FIELD,
                consumer("router", "1", "orderId", "customerId", "amount"),
                consumer("billing", "1", "orderId", "amount", "currency"));

        assertEquals(Verdict.BREAKS_DECLARED_CONSUMERS, report.verdict());
        var router = report.consumers().get(0);
        assertEquals(Status.FAIL, router.readCheck());
        assertEquals(Status.PASS, router.contractCheck());
        assertEquals(List.of("Cannot read new events (READER_FIELD_MISSING_DEFAULT_VALUE): currency at /fields/3"),
                router.findings());
        var billing = report.consumers().get(1);
        assertEquals(Status.FAIL, billing.readCheck());
        assertEquals(Status.FAIL, billing.contractCheck());
        assertTrue(billing.findings().contains("Required field 'currency' is no longer written; the consumer "
                + "would only see the reader default (if any)."), billing.findings().toString());
    }

    @Test
    void nullableRequiredFieldFailsReadAndContract() {
        var report = analyze(NULLABLE_FIELD, consumer("router", "1", "orderId", "customerId", "amount"));

        assertEquals(Verdict.BREAKS_DECLARED_CONSUMERS, report.verdict());
        var router = report.consumers().get(0);
        assertEquals(Status.FAIL, router.readCheck());
        assertEquals(Status.FAIL, router.contractCheck());
        assertTrue(router.findings().contains("Required field 'customerId' can be null in new events."),
                router.findings().toString());
    }

    @Test
    void unknownConsumerDetailsMakeTheResultIncompleteNotSafe() {
        var report = analyze(ADD_FIELD,
                consumer("router", "1", "orderId"),
                consumer("billing", null, (String[]) null),
                consumer("audit", "1", (String[]) null));

        assertEquals(Verdict.INCOMPLETE, report.verdict());
        var billing = report.consumers().get(1);
        assertEquals(Status.UNVERIFIED, billing.readCheck());
        assertEquals(Status.UNVERIFIED, billing.contractCheck());
        assertEquals(List.of("Reader version not declared; ask the owner which schema version it runs."),
                billing.findings());
        var audit = report.consumers().get(2);
        assertEquals(Status.PASS, audit.readCheck());
        assertEquals(Status.UNVERIFIED, audit.contractCheck());
    }

    @Test
    void readerVersionMissingFromRegistryIsUnverified() {
        var report = analyze(ADD_FIELD, consumer("router", "7", "orderId"));

        assertEquals(Verdict.INCOMPLETE, report.verdict());
        assertEquals(List.of("Reader version 7 not found in the Registry."), report.consumers().get(0).findings());
    }

    @Test
    void failureOutranksUnverifiedConsumers() {
        var report = analyze(RENAME_FIELD, consumer("billing", "1", "currency"), consumer("other", null));

        assertEquals(Verdict.BREAKS_DECLARED_CONSUMERS, report.verdict());
    }

    @Test
    void readerAliasCountsAsTheRequiredFieldBeingWritten() {
        String readerWithAlias = """
                {"type":"record","name":"OrderCreated","namespace":"com.example.orders","fields":[
                  {"name":"currency","type":"string","aliases":["currencyCode"]}]}""";
        var report = ConsumerImpactAnalyzer.analyze("AVRO", RENAME_FIELD,
                new ConsumerInventory(List.of(consumer("billing", "2", "currency"))), "orders.consumers",
                v -> Optional.of(readerWithAlias), RULES_OK);

        assertEquals(Verdict.SAFE_FOR_DECLARED_CONSUMERS, report.verdict());
    }

    @Test
    void missingInventoryIsIncompleteAndSaysSo() {
        var report = ConsumerImpactAnalyzer.analyze("AVRO", ADD_FIELD, null, "orders.consumers", REGISTRY, RULES_OK);

        assertEquals(Verdict.INCOMPLETE, report.verdict());
        assertEquals(List.of(), report.consumers());
        assertTrue(report.limitations().contains(
                "No consumer inventory found at artifact 'orders.consumers'. Consumer impact is unknown."));
    }

    @Test
    void registryRuleViolationTakesPrecedence() {
        var rules = new RulesCheck(false, "Schema rules check failed: incompatible");
        var report = ConsumerImpactAnalyzer.analyze("AVRO", RENAME_FIELD,
                new ConsumerInventory(List.of(consumer("billing", "1", "currency"))), "orders.consumers",
                REGISTRY, rules);

        assertEquals(Verdict.REJECTED_BY_REGISTRY_RULES, report.verdict());
        assertEquals(Status.FAIL, report.consumers().get(0).readCheck());
    }

    @Test
    void nonAvroArtifactIsIncompleteWithReason() {
        var report = ConsumerImpactAnalyzer.analyze("JSON", "{}",
                new ConsumerInventory(List.of(consumer("billing", "1", "currency"))), "orders.consumers",
                REGISTRY, RULES_OK);

        assertEquals(Verdict.INCOMPLETE, report.verdict());
        assertTrue(report.limitations().contains(
                "Consumer checks are only implemented for AVRO; artifact type is JSON."));
    }
}
