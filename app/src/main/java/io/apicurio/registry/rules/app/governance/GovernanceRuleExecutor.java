package io.apicurio.registry.rules.app.governance;

import io.apicurio.registry.cdi.Current;
import io.apicurio.registry.contracts.ContractLabels;
import io.apicurio.registry.logging.Logged;
import io.apicurio.registry.rules.violation.RuleViolation;
import io.apicurio.registry.rules.violation.RuleViolationException;
import io.apicurio.registry.storage.RegistryStorage;
import io.apicurio.registry.storage.dto.ArtifactMetaDataDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
@Logged
public class GovernanceRuleExecutor {

    @Inject
    @Current
    RegistryStorage storage;

    public void check(String groupId, String artifactId, String contractId,
            GovernanceLevel level) throws RuleViolationException {
        if (level == GovernanceLevel.NONE) {
            return;
        }

        ArtifactMetaDataDto meta = storage.getArtifactMetaData(groupId, artifactId);
        Map<String, String> labels = meta.getLabels() != null ? meta.getLabels() : Map.of();
        String prefix = ContractLabels.PREFIX + contractId + ".";

        Set<RuleViolation> violations = new HashSet<>();

        String status = labels.get(prefix + "status");
        if ("DEPRECATED".equals(status)) {
            violations.add(new RuleViolation(
                    "Updates to deprecated contracts are not allowed",
                    prefix + "status"));
        }

        String owner = labels.get(prefix + "owner.team");
        if (owner == null || owner.isBlank()) {
            violations.add(new RuleViolation(
                    "Contract owner team is required",
                    prefix + "owner.team"));
        }

        if (level == GovernanceLevel.FULL) {
            String classification = labels.get(prefix + "classification");
            if (classification == null || classification.isBlank()) {
                violations.add(new RuleViolation(
                        "Data classification is required",
                        prefix + "classification"));
            }

            String contact = labels.get(prefix + "support.contact");
            if (contact == null || contact.isBlank()) {
                violations.add(new RuleViolation(
                        "Support contact is required",
                        prefix + "support.contact"));
            }

            String stage = labels.get(prefix + "stage");
            if ("PROD".equals(stage) && !"STABLE".equals(status)) {
                violations.add(new RuleViolation(
                        "PROD promotion requires STABLE status",
                        prefix + "stage"));
            }
        }

        if (!violations.isEmpty()) {
            throw new RuleViolationException("Governance rule violations found",
                    null, level.name(), violations);
        }
    }
}
