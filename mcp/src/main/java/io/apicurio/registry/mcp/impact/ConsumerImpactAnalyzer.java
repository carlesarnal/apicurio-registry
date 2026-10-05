package io.apicurio.registry.mcp.impact;

import io.apicurio.registry.mcp.impact.ConsumerImpactReport.ConsumerResult;
import io.apicurio.registry.mcp.impact.ConsumerImpactReport.RulesCheck;
import io.apicurio.registry.mcp.impact.ConsumerImpactReport.Status;
import io.apicurio.registry.mcp.impact.ConsumerImpactReport.Verdict;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Deterministic analysis of a candidate Avro schema against declared consumers. It answers the
 * question the configured compatibility rule does not: can each consumer, running the reader
 * schema it runs today, read events written with the candidate, and does it still receive every
 * field it depends on?
 *
 * <p>The read check uses Avro schema resolution ({@link SchemaCompatibility}) with the consumer's
 * reader schema and the candidate as writer. The contract check verifies each required field is
 * still written by the candidate (so the consumer does not silently get the reader default) and is
 * not nullable.
 */
public final class ConsumerImpactAnalyzer {

    static final String AVRO = "AVRO";

    private ConsumerImpactAnalyzer() {
    }

    /**
     * @param artifactType   Registry artifact type of the schema.
     * @param candidate      Candidate schema content.
     * @param inventory      Declared consumers, or {@code null} if no inventory exists.
     * @param inventoryId    Artifact ID where the inventory was looked up.
     * @param readerSchemas  Resolves a reader version to its schema content; empty if not found.
     * @param rules          Result of the Registry dry run under the configured rules.
     */
    public static ConsumerImpactReport analyze(String artifactType, String candidate,
            ConsumerInventory inventory, String inventoryId,
            Function<String, Optional<String>> readerSchemas, RulesCheck rules) {

        List<String> limitations = new ArrayList<>();
        limitations.add("Only consumers declared in the inventory are checked.");
        List<ConsumerResult> results = new ArrayList<>();

        if (!AVRO.equals(artifactType)) {
            limitations.add("Consumer checks are only implemented for AVRO; artifact type is "
                    + artifactType + ".");
            return report(rules, inventoryId, results, limitations, true);
        }
        Schema writer;
        try {
            writer = new Schema.Parser().parse(candidate);
        } catch (SchemaParseException e) {
            limitations.add("Candidate is not a valid Avro schema: " + e.getMessage());
            return report(rules, inventoryId, results, limitations, true);
        }
        if (inventory == null || inventory.consumers() == null || inventory.consumers().isEmpty()) {
            limitations.add("No consumer inventory found at artifact '" + inventoryId
                    + "'. Consumer impact is unknown.");
            return report(rules, inventoryId, results, limitations, true);
        }

        for (ConsumerInventory.Consumer consumer : inventory.consumers()) {
            results.add(checkConsumer(consumer, writer, readerSchemas));
        }
        return report(rules, inventoryId, results, limitations, false);
    }

    private static ConsumerResult checkConsumer(ConsumerInventory.Consumer consumer, Schema writer,
            Function<String, Optional<String>> readerSchemas) {
        List<String> findings = new ArrayList<>();
        String version = consumer.readerVersion();
        if (version == null || version.isBlank()) {
            findings.add("Reader version not declared; ask the owner which schema version it runs.");
            return result(consumer, Status.UNVERIFIED, Status.UNVERIFIED, findings);
        }
        Optional<String> readerContent = readerSchemas.apply(version);
        if (readerContent.isEmpty()) {
            findings.add("Reader version " + version + " not found in the Registry.");
            return result(consumer, Status.UNVERIFIED, Status.UNVERIFIED, findings);
        }
        Schema reader;
        try {
            reader = new Schema.Parser().parse(readerContent.get());
        } catch (SchemaParseException e) {
            findings.add("Reader version " + version + " is not a valid Avro schema: " + e.getMessage());
            return result(consumer, Status.UNVERIFIED, Status.UNVERIFIED, findings);
        }

        Status read = Status.PASS;
        var compat = SchemaCompatibility.checkReaderWriterCompatibility(reader, writer);
        if (compat.getType() != SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE) {
            read = Status.FAIL;
            for (var inc : compat.getResult().getIncompatibilities()) {
                findings.add("Cannot read new events (" + inc.getType() + "): " + inc.getMessage()
                        + " at " + inc.getLocation());
            }
        }

        Status contract = checkContract(consumer.requiredFields(), reader, writer, findings);
        return result(consumer, read, contract, findings);
    }

    private static Status checkContract(List<String> required, Schema reader, Schema writer,
            List<String> findings) {
        if (required == null) {
            findings.add("Required fields not declared; consumer contract not verified.");
            return Status.UNVERIFIED;
        }
        if (reader.getType() != Schema.Type.RECORD || writer.getType() != Schema.Type.RECORD) {
            findings.add("Contract check needs record schemas; not verified.");
            return Status.UNVERIFIED;
        }
        Status status = Status.PASS;
        for (String name : required) {
            Schema.Field readerField = reader.getField(name);
            if (readerField == null) {
                findings.add("Required field '" + name + "' is not in the consumer's reader schema.");
                status = Status.FAIL;
                continue;
            }
            Schema.Field writerField = findWriterField(writer, readerField);
            if (writerField == null) {
                findings.add("Required field '" + name + "' is no longer written; the consumer "
                        + "would only see the reader default (if any).");
                status = Status.FAIL;
            } else if (isNullable(writerField.schema())) {
                findings.add("Required field '" + name + "' can be null in new events.");
                status = Status.FAIL;
            }
        }
        return status;
    }

    private static Schema.Field findWriterField(Schema writer, Schema.Field readerField) {
        Schema.Field byName = writer.getField(readerField.name());
        if (byName != null) {
            return byName;
        }
        for (String alias : readerField.aliases()) {
            Schema.Field byAlias = writer.getField(alias);
            if (byAlias != null) {
                return byAlias;
            }
        }
        return null;
    }

    private static boolean isNullable(Schema schema) {
        if (schema.getType() == Schema.Type.NULL) {
            return true;
        }
        return schema.getType() == Schema.Type.UNION
                && schema.getTypes().stream().anyMatch(s -> s.getType() == Schema.Type.NULL);
    }

    private static ConsumerResult result(ConsumerInventory.Consumer c, Status read, Status contract,
            List<String> findings) {
        return new ConsumerResult(c.name(), c.owner(), c.readerVersion(), read, contract, findings);
    }

    private static ConsumerImpactReport report(RulesCheck rules, String inventoryId,
            List<ConsumerResult> results, List<String> limitations, boolean incomplete) {
        Verdict verdict;
        if (!rules.passed()) {
            verdict = Verdict.REJECTED_BY_REGISTRY_RULES;
        } else if (results.stream().anyMatch(r -> r.readCheck() == Status.FAIL
                || r.contractCheck() == Status.FAIL)) {
            verdict = Verdict.BREAKS_DECLARED_CONSUMERS;
        } else if (incomplete || results.stream().anyMatch(r -> r.readCheck() == Status.UNVERIFIED
                || r.contractCheck() == Status.UNVERIFIED)) {
            verdict = Verdict.INCOMPLETE;
        } else {
            verdict = Verdict.SAFE_FOR_DECLARED_CONSUMERS;
        }
        return new ConsumerImpactReport(verdict, rules, inventoryId, results, limitations);
    }
}
