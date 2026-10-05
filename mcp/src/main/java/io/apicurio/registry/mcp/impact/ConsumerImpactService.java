package io.apicurio.registry.mcp.impact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apicurio.registry.mcp.RegistryService;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Loads the schema artifact, its consumer inventory and each consumer's reader schema from the
 * Registry, then runs {@link ConsumerImpactAnalyzer}. Read-only: rules are evaluated with a dry run.
 */
@ApplicationScoped
public class ConsumerImpactService {

    public static final String INVENTORY_SUFFIX = ".consumers";

    @Inject
    RegistryService registry;

    @Inject
    ObjectMapper mapper;

    public ConsumerImpactReport check(String groupId, String artifactId, String candidate,
            String candidateContentType, String inventoryArtifactId) throws IOException {
        String inventoryId = inventoryArtifactId == null || inventoryArtifactId.isBlank()
                ? artifactId + INVENTORY_SUFFIX : inventoryArtifactId;
        String contentType = candidateContentType == null || candidateContentType.isBlank()
                ? "application/json" : candidateContentType;

        String artifactType = registry.getArtifactMetadata(groupId, artifactId).getArtifactType();
        var rules = registry.checkSchemaRules(groupId, artifactId, candidate, contentType);
        ConsumerInventory inventory = registry.findVersionContent(groupId, inventoryId, "branch=latest")
                .map(this::parseInventory)
                .orElse(null);

        return ConsumerImpactAnalyzer.analyze(artifactType, candidate, inventory, inventoryId,
                version -> readerSchema(groupId, artifactId, version), rules);
    }

    private Optional<String> readerSchema(String groupId, String artifactId, String version) {
        try {
            return registry.findVersionContent(groupId, artifactId, version);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    ConsumerInventory parseInventory(String json) {
        try {
            return mapper.readValue(json, ConsumerInventory.class);
        } catch (JsonProcessingException e) {
            throw new ToolCallException("Consumer inventory is not valid JSON in the expected format "
                    + "({\"consumers\":[{\"name\",\"owner\",\"readerVersion\",\"requiredFields\"}]}): "
                    + e.getOriginalMessage());
        }
    }
}
