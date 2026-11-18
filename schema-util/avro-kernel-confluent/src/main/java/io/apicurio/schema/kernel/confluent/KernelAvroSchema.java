package io.apicurio.schema.kernel.confluent;

import io.apicurio.schema.kernel.AvroSchemaKernel;
import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.ParsedSchemaHolder;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.rest.entities.Metadata;
import io.confluent.kafka.schemaregistry.client.rest.entities.RuleSet;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaEntity;
import org.apache.avro.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Preserve Confluent's Avro representation and metadata; replace only schema rules. */
public final class KernelAvroSchema extends AvroSchema {

    private static final AvroSchemaKernel KERNEL = new AvroSchemaKernel();

    KernelAvroSchema(AvroSchema source) {
        super(source.canonicalString(), source.references(), source.resolvedReferences(),
                source.metadata(), source.ruleSet(), source.version(), source.isNew());
    }

    @Override
    public List<String> isBackwardCompatible(ParsedSchema previous) {
        if (!schemaType().equals(previous.schemaType())) {
            return List.of("Incompatible because of different schema type");
        }
        return KERNEL.backward(rawSchema(), ((AvroSchema) previous).rawSchema()).stream()
                .map(item -> item.location() + ": " + item.message()).toList();
    }

    @Override
    public List<String> isCompatible(CompatibilityLevel level, List<ParsedSchemaHolder> previous) {
        if (level == CompatibilityLevel.NONE || previous.isEmpty()) {
            return List.of();
        }
        var selected = level.name().endsWith("_TRANSITIVE") ? previous
                : previous.subList(previous.size() - 1, previous.size());
        var history = new ArrayList<Schema>();
        for (var holder : selected) {
            try {
                var schema = holder.schema();
                if (!schemaType().equals(schema.schemaType())) {
                    return List.of("Incompatible because of different schema type");
                }
                history.add(((AvroSchema) schema).rawSchema());
            } finally {
                holder.clear();
            }
        }
        return KERNEL.check(AvroSchemaKernel.Level.valueOf(level.name()), rawSchema(), history).stream()
                .map(item -> item.location() + ": " + item.message()).toList();
    }

    @Override
    public AvroSchema copy() {
        return new KernelAvroSchema(super.copy());
    }

    @Override
    public AvroSchema copy(Integer version) {
        return new KernelAvroSchema(super.copy(version));
    }

    @Override
    public AvroSchema copy(Metadata metadata, RuleSet ruleSet) {
        return new KernelAvroSchema(super.copy(metadata, ruleSet));
    }

    @Override
    public ParsedSchema copy(Map<SchemaEntity, Set<String>> add, Map<SchemaEntity, Set<String>> remove) {
        return new KernelAvroSchema((AvroSchema) super.copy(add, remove));
    }

    @Override
    public AvroSchema normalize() {
        return new KernelAvroSchema(super.normalize());
    }
}
