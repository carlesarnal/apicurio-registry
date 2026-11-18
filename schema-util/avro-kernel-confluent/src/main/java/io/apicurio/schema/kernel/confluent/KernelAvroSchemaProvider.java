package io.apicurio.schema.kernel.confluent;

import io.apicurio.schema.kernel.AvroSchemaKernel;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.avro.AvroSchemaProvider;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;

import java.util.Map;

/** Installed with Confluent's schema.providers configuration, not a proxy or second REST facade. */
public final class KernelAvroSchemaProvider extends AvroSchemaProvider {

    private boolean validateDefaults;

    @Override
    public void configure(Map<String, ?> configs) {
        super.configure(configs);
        validateDefaults = Boolean.parseBoolean((String) configs.get(AVRO_VALIDATE_DEFAULTS));
    }

    @Override
    public ParsedSchema parseSchemaOrElseThrow(Schema schema, boolean isNew, boolean normalize) {
        var references = resolveReferences(schema);
        boolean strict = (validateDefaults || normalize) && isNew;
        new AvroSchemaKernel().parse(schema.getSchema(), references, strict);
        return new KernelAvroSchema(new AvroSchema(schema.getSchema(), schema.getReferences(), references,
                schema.getMetadata(), schema.getRuleSet(), null, strict));
    }
}
