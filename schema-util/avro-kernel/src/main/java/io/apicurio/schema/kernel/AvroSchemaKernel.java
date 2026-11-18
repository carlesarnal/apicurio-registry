package io.apicurio.schema.kernel;

import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stateless, framework-independent Avro rules. Hosts own history selection, persistence, reference
 * retrieval and error/status translation. Reference values must already be resolved locally.
 */
public final class AvroSchemaKernel {

    public enum Level {
        NONE, BACKWARD, BACKWARD_TRANSITIVE, FORWARD, FORWARD_TRANSITIVE, FULL, FULL_TRANSITIVE
    }

    public record Difference(String message, String location) {
    }

    public static final class InvalidSchemaException extends IllegalArgumentException {
        private InvalidSchemaException(RuntimeException cause) {
            super("Invalid Avro schema or references", cause);
        }
    }

    /** A fresh parser per document prevents named types leaking across requests or schema versions. */
    public Schema parse(String content, Map<String, String> references, boolean validateDefaults) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(references, "references");
        try {
            return parseDocument(content, references, validateDefaults);
        } catch (RuntimeException e) {
            // Avro 1.12.2 can throw NPE for unresolved names. Expose one stable invalid-input contract.
            throw new InvalidSchemaException(e);
        }
    }

    private Schema parseDocument(String content, Map<String, String> references, boolean validateDefaults) {
        Map<String, Schema> types = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>(references.values());
        while (!pending.isEmpty()) {
            int before = pending.size();
            AvroRuntimeException failure = null;
            var iterator = pending.iterator();
            while (iterator.hasNext()) {
                // A failed Avro parse can mutate its name table. Never retry with that parser.
                Schema.Parser parser = parser(validateDefaults, types);
                try {
                    parser.parse(iterator.next());
                    types.putAll(parser.getTypes());
                    iterator.remove();
                } catch (AvroRuntimeException e) {
                    failure = e;
                }
            }
            if (pending.size() == before) {
                throw new IllegalArgumentException("Unresolved or invalid Avro references", failure);
            }
        }
        return parser(validateDefaults, types).parse(content);
    }

    private Schema.Parser parser(boolean validateDefaults, Map<String, Schema> types) {
        return new Schema.Parser().setValidateDefaults(validateDefaults).addTypes(types);
    }

    /** Reader = proposed, writer = existing. All registries use the same pairwise operation. */
    public List<Difference> backward(Schema proposed, Schema existing) {
        return SchemaCompatibility.checkReaderWriterCompatibility(proposed, existing)
                .getResult().getIncompatibilities().stream()
                .map(item -> new Difference(item.getMessage(), item.getLocation())).toList();
    }

    /** History is oldest first; non-transitive rules compare only its last entry. */
    public List<Difference> check(Level level, Schema proposed, List<Schema> history) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(proposed, "proposed");
        Objects.requireNonNull(history, "history");
        if (level == Level.NONE || history.isEmpty()) {
            return List.of();
        }
        boolean transitive = level.name().endsWith("_TRANSITIVE");
        boolean backward = level != Level.FORWARD && level != Level.FORWARD_TRANSITIVE;
        boolean forward = level != Level.BACKWARD && level != Level.BACKWARD_TRANSITIVE;
        var differences = new LinkedHashSet<Difference>();
        for (int i = history.size() - 1; i >= (transitive ? 0 : history.size() - 1); i--) {
            if (backward) {
                differences.addAll(backward(proposed, history.get(i)));
            }
            if (forward) {
                differences.addAll(backward(history.get(i), proposed));
            }
        }
        return List.copyOf(differences);
    }
}
