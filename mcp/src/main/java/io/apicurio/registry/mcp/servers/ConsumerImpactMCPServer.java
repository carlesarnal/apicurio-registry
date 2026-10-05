package io.apicurio.registry.mcp.servers;

import io.apicurio.registry.mcp.Utils;
import io.apicurio.registry.mcp.impact.ConsumerImpactService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;

import static io.apicurio.registry.mcp.Descriptions.ARTIFACT_ID;
import static io.apicurio.registry.mcp.Descriptions.GROUP_ID;
import static io.apicurio.registry.mcp.Utils.handleError;

/**
 * MCP server for checking schema changes against the declared consumers of an artifact.
 */
public class ConsumerImpactMCPServer {

    @Inject
    ConsumerImpactService service;

    @Inject
    Utils utils;

    @Tool(description = """
            Check whether a candidate new schema version is safe for the declared consumers of an \
            artifact, not only for the configured compatibility rule. Read-only: nothing is stored. \
            Runs the configured Registry rules as a dry run, loads the consumer inventory (a JSON \
            artifact, by default '<artifactId>.consumers' in the same group, listing each consumer's \
            reader version and required fields), and checks that each consumer can read events \
            written with the candidate and still receives every field it requires (present and not \
            nullable). Returns a verdict: REJECTED_BY_REGISTRY_RULES, BREAKS_DECLARED_CONSUMERS, \
            INCOMPLETE (some checks could not run; do not treat as safe; ask the consumer owners), \
            or SAFE_FOR_DECLARED_CONSUMERS. Use this before shipping a producer change when \
            consumers release independently. Consumer checks support AVRO only.""")
    String check_consumer_impact(
            @ToolArg(description = GROUP_ID) String groupId,
            @ToolArg(description = ARTIFACT_ID) String artifactId,
            @ToolArg(description = "The raw content of the candidate schema version.") String candidateContent,
            @ToolArg(description = "Content type of the candidate (default application/json).", required = false)
            String candidateContentType,
            @ToolArg(description = "Artifact ID of the consumer inventory in the same group "
                    + "(default '<artifactId>.consumers').", required = false)
            String inventoryArtifactId
    ) {
        return handleError(() -> utils.toPrettyJson(service.check(groupId, artifactId, candidateContent,
                candidateContentType, inventoryArtifactId)));
    }
}
