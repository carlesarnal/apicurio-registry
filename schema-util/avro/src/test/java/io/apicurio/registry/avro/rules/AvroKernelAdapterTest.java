package io.apicurio.registry.avro.rules;

import io.apicurio.registry.avro.rules.compatibility.AvroCompatibilityChecker;
import io.apicurio.registry.avro.rules.validity.AvroContentValidator;
import io.apicurio.registry.content.ContentHandle;
import io.apicurio.registry.content.TypedContent;
import io.apicurio.registry.rules.compatibility.CompatibilityLevel;
import io.apicurio.registry.rules.validity.ValidityLevel;
import io.apicurio.registry.rules.violation.RuleViolationException;
import io.apicurio.registry.rules.violation.UnprocessableSchemaException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvroKernelAdapterTest {

    private TypedContent content(String value) {
        return TypedContent.create(ContentHandle.create(value), "application/json");
    }

    @ParameterizedTest
    @EnumSource(value = ValidityLevel.class, names = {"SYNTAX_ONLY", "FULL"})
    void validityMapsKernelFailureToApicurioRuleViolation(ValidityLevel level) {
        var validator = new AvroContentValidator();
        var failure = assertThrows(RuleViolationException.class,
                () -> validator.validate(level, content("{"), Map.of()));
        assertEquals("Syntax violation for Avro artifact.", failure.getMessage());
        assertDoesNotThrow(() -> validator.validate(level, content("\"int\""), null));
    }

    @Test
    void noneSkipsValidityButCompatibilityStillRejectsUnparseableSchemas() {
        assertDoesNotThrow(() -> new AvroContentValidator().validate(ValidityLevel.NONE, content("{"), Map.of()));
        var checker = new AvroCompatibilityChecker();
        var old = List.of(content("\"int\""));
        assertTrue(checker.testCompatibility(CompatibilityLevel.NONE, old, content("{"), Map.of()).isCompatible());
        var failure = assertThrows(UnprocessableSchemaException.class,
                () -> checker.testCompatibility(CompatibilityLevel.BACKWARD, old, content("{"), Map.of()));
        assertEquals("Could not execute compatibility rule on invalid Avro schema", failure.getMessage());
    }

    @Test
    void latestOnlyDoesNotParseIrrelevantHistoryAndReferencesStayLocal() {
        var checker = new AvroCompatibilityChecker();
        assertTrue(checker.testCompatibility(CompatibilityLevel.BACKWARD,
                List.of(content("{"), content("\"int\"")), content("\"long\""), null).isCompatible());
        assertFalse(checker.testCompatibility(CompatibilityLevel.FORWARD,
                List.of(content("\"int\"")), content("\"long\""), Map.of()).isCompatible());
        var refs = Map.of("R", content("{\"type\":\"record\",\"name\":\"R\",\"fields\":[]}"));
        var validator = new AvroContentValidator();
        assertDoesNotThrow(() -> validator.validate(ValidityLevel.FULL, content("\"R\""), refs));
        assertThrows(RuleViolationException.class,
                () -> validator.validate(ValidityLevel.FULL, content("\"R\""), Map.of()));
    }

    @Test
    void historyRequiresOnlyIndexedAccessLikeTheRegistrysLazyContentList() {
        var reads = new AtomicInteger();
        var history = new AbstractList<TypedContent>() {
            @Override
            public TypedContent get(int index) {
                reads.incrementAndGet();
                return content(index == 0 ? "\"string\"" : "\"int\"");
            }

            @Override
            public int size() {
                return 2;
            }

            @Override
            public List<TypedContent> subList(int from, int to) {
                throw new UnsupportedOperationException("lazy history has no sublists");
            }

            @Override
            public Spliterator<TypedContent> spliterator() {
                throw new UnsupportedOperationException("lazy history cannot be streamed");
            }
        };
        var checker = new AvroCompatibilityChecker();
        assertTrue(checker.testCompatibility(CompatibilityLevel.BACKWARD, history,
                content("\"long\""), Map.of()).isCompatible());
        assertEquals(1, reads.get());
        assertFalse(checker.testCompatibility(CompatibilityLevel.BACKWARD_TRANSITIVE, history,
                content("\"long\""), Map.of()).isCompatible());
        assertEquals(3, reads.get());
    }
}
