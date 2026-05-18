package io.apicurio.registry.ccompat.rest.v7.impl;

import io.apicurio.registry.contracts.ContractLabels;
import io.apicurio.registry.storage.dto.ContractRuleDto;
import io.apicurio.registry.storage.dto.ContractRuleSetDto;
import io.apicurio.registry.storage.dto.RuleAction;
import io.apicurio.registry.storage.dto.RuleKind;
import io.apicurio.registry.storage.dto.RuleMode;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class ConfluentContractTranslator {

    private static final Logger log = LoggerFactory.getLogger(ConfluentContractTranslator.class);

    @SuppressWarnings("unchecked")
    public Map<String, String> translateMetadataToLabels(Map<String, Object> metadata,
            String contractId) {
        if (metadata == null) {
            return Collections.emptyMap();
        }
        String prefix = ContractLabels.contractPrefix(
                contractId != null ? contractId : "default");
        Map<String, String> labels = new HashMap<>();

        Object properties = metadata.get("properties");
        if (properties instanceof Map) {
            ((Map<String, Object>) properties).forEach((k, v) -> {
                if (v != null) {
                    labels.put(prefix + k, v.toString());
                }
            });
        }

        Object sensitive = metadata.get("sensitive");
        if (sensitive instanceof List) {
            for (Object s : (List<?>) sensitive) {
                labels.put(prefix + "sensitive." + s, "true");
            }
        }

        return labels;
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> translateTagsToVersionLabels(Map<String, Object> metadata,
            String contractId) {
        if (metadata == null) {
            return Collections.emptyMap();
        }
        Map<String, String> labels = new HashMap<>();

        Object tags = metadata.get("tags");
        if (tags instanceof Map) {
            String cid = contractId != null ? contractId : "default";
            ((Map<String, Object>) tags).forEach((fieldPath, tagList) -> {
                if (tagList instanceof List) {
                    for (Object tag : (List<?>) tagList) {
                        String key = "field-tag." + cid + ":" + fieldPath.replace(".", "|")
                                + "|" + tag;
                        labels.put(key, "true");
                    }
                }
            });
        }

        return labels;
    }

    @SuppressWarnings("unchecked")
    public ContractRuleSetDto translateRuleSet(Map<String, Object> ruleSet) {
        if (ruleSet == null) {
            return null;
        }

        List<ContractRuleDto> domainRules = translateRules(
                (List<Map<String, Object>>) ruleSet.get("domainRules"));
        List<ContractRuleDto> migrationRules = translateRules(
                (List<Map<String, Object>>) ruleSet.get("migrationRules"));

        return ContractRuleSetDto.builder()
                .domainRules(domainRules)
                .migrationRules(migrationRules)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<ContractRuleDto> translateRules(List<Map<String, Object>> rules) {
        if (rules == null) {
            return Collections.emptyList();
        }
        List<ContractRuleDto> result = new ArrayList<>();
        int orderIndex = 0;
        for (Map<String, Object> rule : rules) {
            Map<String, String> params = null;
            Object rawParams = rule.get("params");
            if (rawParams instanceof Map) {
                params = new HashMap<>();
                Map<String, String> finalParams = params;
                ((Map<String, Object>) rawParams).forEach(
                        (k, v) -> finalParams.put(k, v != null ? v.toString() : null));
            }

            String doc = rule.get("doc") != null ? rule.get("doc").toString() : null;
            if (doc != null) {
                if (params == null) {
                    params = new HashMap<>();
                }
                params.put("_doc", doc);
            }

            Set<String> tags = null;
            Object rawTags = rule.get("tags");
            if (rawTags instanceof List) {
                tags = new HashSet<>();
                for (Object t : (List<?>) rawTags) {
                    tags.add(t.toString());
                }
            }

            String modeStr = rule.get("mode") != null ? rule.get("mode").toString() : null;
            if ("UPDOWN".equals(modeStr)) {
                modeStr = "WRITEREAD";
            }

            result.add(ContractRuleDto.builder()
                    .name(rule.get("name") != null ? rule.get("name").toString() : null)
                    .kind(rule.get("kind") != null
                            ? RuleKind.valueOf(rule.get("kind").toString()) : null)
                    .type(rule.get("type") != null ? rule.get("type").toString() : null)
                    .mode(modeStr != null ? RuleMode.valueOf(modeStr) : null)
                    .expr(rule.get("expr") != null ? rule.get("expr").toString() : null)
                    .params(params)
                    .tags(tags)
                    .onSuccess(rule.get("onSuccess") != null
                            ? RuleAction.valueOf(rule.get("onSuccess").toString()) : null)
                    .onFailure(rule.get("onFailure") != null
                            ? RuleAction.valueOf(rule.get("onFailure").toString()) : null)
                    .disabled(Boolean.TRUE.equals(rule.get("disabled")))
                    .orderIndex(orderIndex++)
                    .build());
        }
        return result;
    }

    public Map<String, Object> toConfluentMetadata(Map<String, String> artifactLabels,
            Map<String, String> versionLabels) {
        Map<String, Object> metadata = new HashMap<>();
        Map<String, String> properties = new HashMap<>();
        Map<String, List<String>> tags = new HashMap<>();
        List<String> sensitive = new ArrayList<>();

        if (artifactLabels != null) {
            for (var entry : artifactLabels.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith(ContractLabels.PREFIX)) {
                    String suffix = key.substring(key.indexOf('.', ContractLabels.PREFIX.length()) + 1);
                    if (suffix.startsWith("sensitive.")) {
                        sensitive.add(suffix.substring("sensitive.".length()));
                    } else {
                        properties.put(suffix, entry.getValue());
                    }
                }
            }
        }

        if (versionLabels != null) {
            for (var entry : versionLabels.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith("field-tag.")) {
                    String rest = key.substring("field-tag.".length());
                    int colonIdx = rest.indexOf(':');
                    if (colonIdx > 0) {
                        String fieldAndTag = rest.substring(colonIdx + 1);
                        int lastPipe = fieldAndTag.lastIndexOf('|');
                        if (lastPipe > 0) {
                            String fieldPath = fieldAndTag.substring(0, lastPipe)
                                    .replace("|", ".");
                            String tagName = fieldAndTag.substring(lastPipe + 1);
                            tags.computeIfAbsent(fieldPath, k -> new ArrayList<>()).add(tagName);
                        }
                    }
                }
            }
        }

        if (!properties.isEmpty()) {
            metadata.put("properties", properties);
        }
        if (!tags.isEmpty()) {
            metadata.put("tags", tags);
        }
        if (!sensitive.isEmpty()) {
            metadata.put("sensitive", sensitive);
        }

        return metadata.isEmpty() ? null : metadata;
    }

    public Map<String, Object> toConfluentRuleSet(ContractRuleSetDto ruleset) {
        if (ruleset == null) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("domainRules", toConfluentRuleList(ruleset.getDomainRules()));
        result.put("migrationRules", toConfluentRuleList(ruleset.getMigrationRules()));
        return result;
    }

    private List<Map<String, Object>> toConfluentRuleList(List<ContractRuleDto> rules) {
        if (rules == null) {
            return Collections.emptyList();
        }
        return rules.stream().map(this::toConfluentRule).toList();
    }

    private Map<String, Object> toConfluentRule(ContractRuleDto dto) {
        Map<String, Object> rule = new HashMap<>();
        rule.put("name", dto.getName());
        rule.put("kind", dto.getKind() != null ? dto.getKind().name() : null);
        rule.put("type", dto.getType());

        String mode = dto.getMode() != null ? dto.getMode().name() : null;
        if ("WRITEREAD".equals(mode)) {
            mode = "UPDOWN";
        }
        rule.put("mode", mode);

        rule.put("expr", dto.getExpr());
        rule.put("onSuccess", dto.getOnSuccess() != null ? dto.getOnSuccess().name() : null);
        rule.put("onFailure", dto.getOnFailure() != null ? dto.getOnFailure().name() : null);
        rule.put("disabled", dto.isDisabled());
        rule.put("tags", dto.getTags() != null ? new ArrayList<>(dto.getTags()) : null);
        rule.put("params", dto.getParams());

        String doc = dto.getParams() != null ? dto.getParams().get("_doc") : null;
        rule.put("doc", doc != null ? doc : "");

        return rule;
    }
}
