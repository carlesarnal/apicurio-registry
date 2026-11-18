package io.apicurio.registry.avro.rules.compatibility;

import io.apicurio.registry.content.TypedContent;
import io.apicurio.registry.rules.compatibility.AbstractCompatibilityChecker;
import io.apicurio.registry.rules.compatibility.CompatibilityExecutionResult;
import io.apicurio.registry.rules.compatibility.CompatibilityLevel;
import io.apicurio.registry.rules.compatibility.SimpleCompatibilityDifference;
import io.apicurio.registry.rules.violation.UnprocessableSchemaException;
import io.apicurio.schema.kernel.AvroSchemaKernel;
import org.apache.avro.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public class AvroCompatibilityChecker extends AbstractCompatibilityChecker<SimpleCompatibilityDifference> {

    private final AvroSchemaKernel kernel = new AvroSchemaKernel();

    @Override
    public CompatibilityExecutionResult testCompatibility(CompatibilityLevel level,
            List<TypedContent> existing, TypedContent proposed, Map<String, TypedContent> references) {
        requireNonNull(level, "compatibilityLevel MUST NOT be null");
        requireNonNull(existing, "existingSchemas MUST NOT be null");
        requireNonNull(proposed, "proposedSchema MUST NOT be null");
        if (level == CompatibilityLevel.NONE || existing.isEmpty()) {
            return CompatibilityExecutionResult.compatible();
        }
        try {
            Map<String, String> resolved = resolve(references);
            // Registry history may be LazyContentList: subList() and streaming are unsupported.
            var history = new ArrayList<Schema>();
            int first = level.name().endsWith("_TRANSITIVE") ? 0 : existing.size() - 1;
            for (int i = first; i < existing.size(); i++) {
                history.add(kernel.parse(existing.get(i).getContent().content(), resolved, true));
            }
            var differences = kernel.check(AvroSchemaKernel.Level.valueOf(level.name()),
                    kernel.parse(proposed.getContent().content(), resolved, true), history);
            return CompatibilityExecutionResult.incompatibleOrEmpty(differences.stream()
                    .map(item -> new SimpleCompatibilityDifference(item.message(), item.location()))
                    .collect(Collectors.toSet()));
        } catch (Exception e) {
            throw new UnprocessableSchemaException(
                    "Could not execute compatibility rule on invalid Avro schema", e);
        }
    }

    @Override
    protected Set<SimpleCompatibilityDifference> isBackwardsCompatibleWith(String existing, String proposed,
            Map<String, TypedContent> references) {
        try {
            Map<String, String> resolved = resolve(references);
            return kernel.backward(kernel.parse(proposed, resolved, true), kernel.parse(existing, resolved, true))
                    .stream().map(item -> new SimpleCompatibilityDifference(item.message(), item.location()))
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            throw new UnprocessableSchemaException(
                    "Could not execute compatibility rule on invalid Avro schema", e);
        }
    }

    private Map<String, String> resolve(Map<String, TypedContent> references) {
        return references == null ? Map.of() : references.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey, entry -> entry.getValue().getContent().content()));
    }
}
