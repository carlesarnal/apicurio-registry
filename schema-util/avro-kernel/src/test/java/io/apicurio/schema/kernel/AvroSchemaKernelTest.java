package io.apicurio.schema.kernel;

import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvroSchemaKernelTest {

    private final AvroSchemaKernel kernel = new AvroSchemaKernel();

    private Schema parse(String text) {
        return kernel.parse(text, Map.of(), true);
    }

    @ParameterizedTest
    @CsvSource({
            "NONE,true", "BACKWARD,true", "BACKWARD_TRANSITIVE,true", "FORWARD,false",
            "FORWARD_TRANSITIVE,false", "FULL,false", "FULL_TRANSITIVE,false"
    })
    void numericPromotionHasTheCorrectDirection(AvroSchemaKernel.Level level, boolean compatible) {
        var differences = kernel.check(level, parse("\"long\""), List.of(parse("\"int\"")));
        assertEquals(compatible, differences.isEmpty());
        if (!compatible) {
            assertEquals("/", differences.get(0).location());
            assertEquals("reader type: INT not compatible with writer type: LONG", differences.get(0).message());
        }
    }

    @ParameterizedTest
    @CsvSource({"BACKWARD,true", "BACKWARD_TRANSITIVE,false", "FORWARD,true",
            "FORWARD_TRANSITIVE,false", "FULL,true", "FULL_TRANSITIVE,false"})
    void transitiveRulesInspectOlderVersions(AvroSchemaKernel.Level level, boolean compatible) {
        assertEquals(compatible, kernel.check(level, parse("\"string\""),
                List.of(parse("\"int\""), parse("\"string\""))).isEmpty());
    }

    @Test
    void firstVersionAndNoneHaveNoCompatibilityConstraint() {
        for (var level : AvroSchemaKernel.Level.values()) {
            assertEquals(List.of(), kernel.check(level, parse("\"int\""), List.of()));
        }
        assertEquals(List.of(), kernel.check(AvroSchemaKernel.Level.NONE, parse("\"int\""),
                List.of(parse("\"boolean\""))));
    }

    @Test
    void validatesSyntaxNamesAndDefaultsIndependentlyOfCompatibility() {
        for (String invalid : List.of("{", "\"missing\"",
                "{\"type\":\"record\",\"name\":\"bad-name\",\"fields\":[]}",
                "{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"x\",\"type\":\"int\",\"default\":\"bad\"}]}")) {
            var failure = assertThrows(AvroSchemaKernel.InvalidSchemaException.class, () -> parse(invalid), invalid);
            assertEquals("Invalid Avro schema or references", failure.getMessage());
        }
        var lax = kernel.parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"x\",\"type\":\"int\",\"default\":\"bad\"}]}",
                Map.of(), false);
        assertEquals("R", lax.getName());
        assertTrue(lax.getField("x").hasDefaultValue());
    }

    @Test
    void fieldDefaultsAliasesUnionsAndRecursion() {
        var original = parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[]}");
        var withDefault = parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"x\",\"type\":\"int\",\"default\":0}]}");
        var required = parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"x\",\"type\":\"int\"}]}");
        assertEquals(List.of(), kernel.backward(withDefault, original));
        assertEquals("/fields/0", kernel.backward(required, original).get(0).location());
        var renamed = parse("{\"type\":\"record\",\"name\":\"S\",\"aliases\":[\"R\"],\"fields\":[]}");
        assertEquals(List.of(), kernel.backward(renamed, original));
        assertEquals(List.of(), kernel.backward(parse("[\"null\",\"long\"]"), parse("\"int\"")));
        assertFalse(kernel.backward(parse("\"int\""), parse("[\"null\",\"int\"]")).isEmpty());
        var recursive = parse("{\"type\":\"record\",\"name\":\"Node\",\"fields\":[{\"name\":\"next\",\"type\":[\"null\",\"Node\"],\"default\":null}]}");
        assertEquals(List.of(), kernel.backward(recursive, recursive));
    }

    @Test
    void referencesAreLocalAndResolvedInDependencyOrderWithoutPoisonedParserState() {
        var refs = new LinkedHashMap<String, String>();
        refs.put("A", "{\"type\":\"record\",\"name\":\"A\",\"fields\":[{\"name\":\"b\",\"type\":\"B\"}]}");
        refs.put("B", "{\"type\":\"record\",\"name\":\"B\",\"fields\":[]}");
        var schema = kernel.parse("\"A\"", refs, true);
        assertEquals("B", schema.getField("b").schema().getFullName());
        assertThrows(AvroSchemaKernel.InvalidSchemaException.class, () -> parse("\"A\""));
        assertThrows(IllegalArgumentException.class, () -> kernel.parse("\"int\"",
                Map.of("broken", refs.get("A")), true));
        assertEquals(Schema.Type.INT, parse("\"int\"").getType());
    }

    @Test
    void concurrentRequestsDoNotShareParserState() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        var barrier = new CyclicBarrier(4);
        try {
            var tasks = new ArrayList<Future<String>>();
            for (int i = 0; i < 4; i++) {
                final int index = i;
                tasks.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return parse("{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"f"
                            + index + "\",\"type\":\"int\"}]}").getFields().get(0).name();
                }));
            }
            for (int i = 0; i < 4; i++) {
                assertEquals("f" + i, tasks.get(i).get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
