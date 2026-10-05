package io.apicurio.registry.mcp.impact;

import java.util.List;

/**
 * Result of checking a candidate schema version against the configured Registry rules and the
 * declared consumers of the artifact.
 */
public record ConsumerImpactReport(
        Verdict verdict,
        RulesCheck registryRules,
        String inventoryArtifactId,
        List<ConsumerResult> consumers,
        List<String> limitations) {

    public enum Verdict {
        /** The candidate violates a configured Registry rule and cannot be registered. */
        REJECTED_BY_REGISTRY_RULES,
        /** At least one declared consumer cannot read the candidate or loses a field it requires. */
        BREAKS_DECLARED_CONSUMERS,
        /** No failure found, but some checks could not be performed. Do not treat as safe. */
        INCOMPLETE,
        /** Every declared consumer was fully checked and passed. */
        SAFE_FOR_DECLARED_CONSUMERS
    }

    public enum Status {
        PASS, FAIL, UNVERIFIED
    }

    public record RulesCheck(boolean passed, String detail) {
    }

    public record ConsumerResult(
            String name,
            String owner,
            String readerVersion,
            Status readCheck,
            Status contractCheck,
            List<String> findings) {
    }
}
