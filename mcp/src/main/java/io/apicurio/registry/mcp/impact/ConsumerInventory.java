package io.apicurio.registry.mcp.impact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Declared consumers of a schema artifact. Stored in the Registry as a JSON artifact next to the
 * schema (by default {@code <artifactId>.consumers} in the same group), so that it is versioned
 * and governed like any other artifact.
 *
 * <pre>
 * {
 *   "consumers": [
 *     {"name": "billing", "owner": "team-billing", "readerVersion": "1",
 *      "requiredFields": ["orderId", "amount", "currency"]}
 *   ]
 * }
 * </pre>
 *
 * A {@code null} {@code readerVersion} or {@code requiredFields} means "unknown"; the analysis
 * reports the affected checks as unverified instead of assuming they pass.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsumerInventory(List<Consumer> consumers) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Consumer(String name, String owner, String readerVersion, List<String> requiredFields) {
    }
}
