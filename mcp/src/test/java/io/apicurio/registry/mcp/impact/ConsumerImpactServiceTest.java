package io.apicurio.registry.mcp.impact;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apicurio.registry.mcp.RegistryService;
import io.apicurio.registry.mcp.impact.ConsumerImpactReport.RulesCheck;
import io.apicurio.registry.mcp.impact.ConsumerImpactReport.Verdict;
import io.apicurio.registry.rest.client.models.ArtifactMetaData;
import io.quarkiverse.mcp.server.ToolCallException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerImpactServiceTest {

    private static final String V1 = """
            {"type":"record","name":"E","fields":[{"name":"a","type":"string"}]}""";
    private static final String V2_ADD = """
            {"type":"record","name":"E","fields":[{"name":"a","type":"string"},
              {"name":"b","type":"string","default":"x"}]}""";

    /** In-memory stand-in for the Registry client calls the service makes. */
    static class FakeRegistry extends RegistryService {
        final Map<String, String> contents = new HashMap<>();
        final List<String> lookups = new ArrayList<>();
        String lastRulesContentType;

        @Override
        public ArtifactMetaData getArtifactMetadata(String groupId, String artifactId) {
            var md = new ArtifactMetaData();
            md.setArtifactType("AVRO");
            return md;
        }

        @Override
        public RulesCheck checkSchemaRules(String groupId, String artifactId, String content, String contentType) {
            lastRulesContentType = contentType;
            return new RulesCheck(true, "Schema is valid and compatible.");
        }

        @Override
        public Optional<String> findVersionContent(String groupId, String artifactId, String version) {
            String key = groupId + "/" + artifactId + "@" + version;
            lookups.add(key);
            return Optional.ofNullable(contents.get(key));
        }
    }

    private static ConsumerImpactService service(FakeRegistry registry) {
        var s = new ConsumerImpactService();
        s.registry = registry;
        s.mapper = new ObjectMapper();
        return s;
    }

    @Test
    void loadsDefaultInventoryAndReaderVersionsFromTheSameGroup() throws Exception {
        var registry = new FakeRegistry();
        registry.contents.put("orders/order-created@1", V1);
        registry.contents.put("orders/order-created.consumers@branch=latest",
                "{\"consumers\":[{\"name\":\"billing\",\"owner\":\"team-billing\",\"readerVersion\":\"1\","
                        + "\"requiredFields\":[\"a\"],\"extra\":\"ignored\"}]}");

        var report = service(registry).check("orders", "order-created", V2_ADD, null, null);

        assertEquals(Verdict.SAFE_FOR_DECLARED_CONSUMERS, report.verdict());
        assertEquals("order-created.consumers", report.inventoryArtifactId());
        assertEquals("application/json", registry.lastRulesContentType);
        assertEquals(List.of("orders/order-created.consumers@branch=latest", "orders/order-created@1"),
                registry.lookups);
        assertEquals("billing", report.consumers().get(0).name());
    }

    @Test
    void usesExplicitInventoryArtifactId() throws Exception {
        var registry = new FakeRegistry();

        var report = service(registry).check("orders", "order-created", V2_ADD, "application/json", "my-inventory");

        assertEquals(Verdict.INCOMPLETE, report.verdict());
        assertEquals(List.of("orders/my-inventory@branch=latest"), registry.lookups);
    }

    @Test
    void malformedInventoryIsReportedWithExpectedFormat() {
        var registry = new FakeRegistry();
        registry.contents.put("orders/order-created.consumers@branch=latest", "not json");

        var ex = assertThrows(ToolCallException.class,
                () -> service(registry).check("orders", "order-created", V2_ADD, null, null));
        assertTrue(ex.getMessage().startsWith("Consumer inventory is not valid JSON in the expected format"),
                ex.getMessage());
    }
}
