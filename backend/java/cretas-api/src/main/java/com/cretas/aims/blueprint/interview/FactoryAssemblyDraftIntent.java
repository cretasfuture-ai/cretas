package com.cretas.aims.blueprint.interview;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Explicit factory assembly capabilities that may be requested as empty drafts.
 *
 * <p>The capability id is the wire value.  An intent is only a request to open
 * a draft lane; it is deliberately not a source of business facts or a formal
 * configuration record.</p>
 */
public enum FactoryAssemblyDraftIntent {
    HYGIENIC_ZONES("hygienic-zones", "卫生分区", "卫生分区草稿"),
    EQUIPMENT_CALIBRATION("equipment-calibration", "设备校准", "设备校准草稿"),
    ALLERGEN_LABEL("allergen-label", "过敏原", "过敏原标签草稿"),
    SANITATION_CIP("sanitation-cip", "清洗消毒", "清洗消毒草稿"),
    ENVIRONMENTAL_MONITORING("environmental-monitoring", "环境监测", "环境监测草稿"),
    SUPPLIER_RECEIVING("supplier-receiving", "供应商来料", "供应商来料草稿"),
    CONTRACT_MATERIAL("contract-material", "客供料", "合同物料草稿"),
    HAZARD_ANALYSIS("hazard-analysis", "危害分析", "危害分析草稿"),
    QUALITY_RELEASE("quality-release", "质量放行", "质量放行草稿"),
    DEVIATION_CAPA("deviation-capa", "偏差CAPA", "偏差 CAPA 草稿"),
    TRACE_RECALL("trace-recall", "追溯召回", "追溯召回草稿"),
    EMERGENCY_DEFENSE("emergency-defense", "食品防护", "应急防护草稿");

    private final String capabilityId;
    private final String groupLabel;
    private final String label;

    FactoryAssemblyDraftIntent(String capabilityId, String groupLabel, String label) {
        this.capabilityId = capabilityId;
        this.groupLabel = groupLabel;
        this.label = label;
    }

    @JsonValue
    public String capabilityId() {
        return capabilityId;
    }

    public String groupLabel() {
        return groupLabel;
    }

    public String label() {
        return label;
    }

    @JsonCreator
    public static FactoryAssemblyDraftIntent fromCapabilityId(String value) {
        for (FactoryAssemblyDraftIntent intent : values()) {
            if (intent.capabilityId.equals(value)) {
                return intent;
            }
        }
        throw new IllegalArgumentException("未知工厂装配草稿能力 ID: " + value);
    }

    /** Return user-facing validation problems without mutating the request. */
    public static List<String> validationErrors(List<FactoryAssemblyDraftIntent> intents) {
        if (intents == null || intents.isEmpty()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        Set<FactoryAssemblyDraftIntent> seen = new HashSet<>();
        for (int i = 0; i < intents.size(); i++) {
            FactoryAssemblyDraftIntent intent = intents.get(i);
            if (intent == null) {
                errors.add("draftCapabilityIntents[" + i + "] 为空");
            } else if (!seen.add(intent)) {
                errors.add("draftCapabilityIntents[" + i + "] 重复: " + intent.capabilityId);
            }
        }
        return List.copyOf(errors);
    }

    public static void requireValid(List<FactoryAssemblyDraftIntent> intents) {
        List<String> errors = validationErrors(intents);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
    }

    public static boolean isDraftIntentEligible(String capabilityId) {
        for (FactoryAssemblyDraftIntent intent : values()) {
            if (intent.capabilityId.equals(capabilityId)) {
                return true;
            }
        }
        return false;
    }
}
