package io.apicurio.schema.kernel.confluent;

import io.apicurio.schema.kernel.AvroSchemaKernel;
import io.confluent.kafka.schemaregistry.CompatibilityLevel;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.ParsedSchemaHolder;
import io.confluent.kafka.schemaregistry.SimpleParsedSchemaHolder;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.avro.AvroSchemaProvider;
import io.confluent.kafka.schemaregistry.client.rest.entities.Metadata;
import io.confluent.kafka.schemaregistry.client.rest.entities.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KernelAvroSchemaProviderTest {

    private ParsedSchema parse(String content, boolean strict, boolean normalize) {
        var provider = new KernelAvroSchemaProvider();
        provider.configure(Map.of(AvroSchemaProvider.AVRO_VALIDATE_DEFAULTS, Boolean.toString(strict)));
        var schema = new Schema("test", 1, 1, "AVRO", List.of(), content);
        return provider.parseSchemaOrElseThrow(schema, true, normalize);
    }

    @Test
    void providerAndAllCopyPathsKeepTheKernel() {
        var schema = parse("\"long\"", true, false);
        var metadata = new Metadata(Map.of(), Map.of("owner", "poc"), Set.of());
        for (var copy : List.of(schema, schema.copy(), schema.copy(7),
                schema.copy(metadata, null), schema.copy(Map.of(), Map.of()), schema.normalize())) {
            assertInstanceOf(KernelAvroSchema.class, copy);
            assertEquals(List.of(), copy.isBackwardCompatible(new AvroSchema("\"int\"")));
            assertEquals(List.of("/: reader type: INT not compatible with writer type: LONG"),
                    copy.isCompatible(CompatibilityLevel.FORWARD,
                            List.of(new SimpleParsedSchemaHolder(new AvroSchema("\"int\"")))));
        }
        assertEquals(7, schema.copy(7).version());
        assertEquals("poc", schema.copy(metadata, null).metadata().getProperties().get("owner"));
        assertEquals(List.of("Incompatible because of different schema type"),
                schema.isBackwardCompatible(new OtherSchema()));
    }

    @Test
    void validityPreservesConfluentDefaultPolicyAndNormalization() {
        String invalidDefault = "{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"x\",\"type\":\"int\",\"default\":\"bad\"}]}";
        assertThrows(AvroSchemaKernel.InvalidSchemaException.class, () -> parse(invalidDefault, true, false));
        assertThrows(AvroSchemaKernel.InvalidSchemaException.class, () -> parse(invalidDefault, false, true));
        assertEquals("R", parse(invalidDefault, false, false).name());
        assertThrows(AvroSchemaKernel.InvalidSchemaException.class, () -> parse("{", false, false));
    }

    @Test
    void lazyHistoryIsSelectedAndReleased() {
        var proposed = parse("\"long\"", true, false);
        var reads = new AtomicInteger();
        var clears = new AtomicInteger();
        ParsedSchemaHolder holder = new ParsedSchemaHolder() {
            @Override
            public ParsedSchema schema() {
                reads.incrementAndGet();
                return new AvroSchema("\"int\"");
            }

            @Override
            public void clear() {
                clears.incrementAndGet();
            }
        };
        assertEquals(List.of(), proposed.isCompatible(CompatibilityLevel.NONE, List.of(holder)));
        assertEquals(0, reads.get());
        assertEquals(0, clears.get());
        assertEquals(List.of(), proposed.isCompatible(CompatibilityLevel.BACKWARD, List.of(holder, holder)));
        assertEquals(1, reads.get());
        assertEquals(1, clears.get());
        assertEquals(List.of(), proposed.isCompatible(CompatibilityLevel.BACKWARD_TRANSITIVE,
                List.of(holder, holder)));
        assertEquals(3, reads.get());
        assertEquals(3, clears.get());
        assertEquals(List.of("Incompatible because of different schema type"), proposed.isCompatible(
                CompatibilityLevel.FULL, List.of(new SimpleParsedSchemaHolder(new OtherSchema()))));
    }

    private static class OtherSchema extends AvroSchema {
        OtherSchema() {
            super("\"int\"");
        }

        @Override
        public String schemaType() {
            return "OTHER";
        }
    }
}
