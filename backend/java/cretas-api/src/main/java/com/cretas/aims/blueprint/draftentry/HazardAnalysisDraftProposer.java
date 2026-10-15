package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.entity.bomdraft.BomRecipeDraft;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftRepository;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftCommand;
import com.cretas.aims.service.hazardanalysisdraft.HazardAnalysisDraftModels.*;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.List;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class HazardAnalysisDraftProposer implements CapabilityDraftProposer {
    private static final String WHY = "蓝图没有危害类型、原因、风险、预防措施和法规依据的完整事实";
    private static List<Option> options(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(value -> new Option(value.name(), value.name())).toList();
    }
    private static String enumConstraint(Class<? extends Enum<?>> type) {
        return String.join("、", options(type).stream().map(Option::value).toList()) + " 之一";
    }
    private static List<RequiredInput> itemFields() {
        return List.of(
                RequiredInput.text("routeNodeId", "路线节点", WHY, "当前路线节点标识，最长 128 位", 128),
                RequiredInput.text("bomItemId", "BOM 行", WHY, "当前 BOM 草稿行标识，最长 36 位；无直接物料时可留空", 36).allowNull(),
                RequiredInput.oneOf("hazardType", "危害类型", WHY, "BIOLOGICAL、CHEMICAL、PHYSICAL 之一", options(HazardType.class)),
                RequiredInput.text("hazardDescription", "危害描述", WHY, "1–500 字", 500),
                RequiredInput.text("causeSummary", "原因概述", WHY, "1–500 字", 500),
                RequiredInput.oneOf("severity", "严重度", WHY, enumConstraint(Severity.class), options(Severity.class)),
                RequiredInput.oneOf("likelihood", "发生可能性", WHY, enumConstraint(Likelihood.class), options(Likelihood.class)),
                RequiredInput.oneOf("controlMeasureType", "预防控制类型", WHY, enumConstraint(ControlMeasureType.class), options(ControlMeasureType.class)),
                RequiredInput.text("preventiveMeasure", "预防措施", WHY, "1–1000 字", 1000),
                RequiredInput.values("evidenceAuthorityCodes", "证据依据", WHY, "至少 1 项，最多 20 项", "依据代码", 1, 20,
                        RequiredInput.text("evidenceAuthorityCodes", "依据代码", WHY, "最长 128 位", 128)),
                RequiredInput.text("changeReason", "变更原因", WHY, "1–500 字", 500));
    }
    private final BomRecipeDraftRepository bomDrafts;
    public HazardAnalysisDraftProposer(BomRecipeDraftRepository bomDrafts) { this.bomDrafts = bomDrafts; }
    @Override public String capabilityId() { return HazardAnalysisDraftCommand.CAPABILITY_ID; }
    @Override public Proposed propose(Source source) {
        BomRecipeDraft bom = UpstreamDraftPins.latestBomDraft(bomDrafts, source).orElse(null);
        if (bom == null) return Proposed.blocked("UPSTREAM_MISSING",
                "危害分析必须绑定本蓝图同一版本、同一来源摘要且依赖闭包完整的 BOM 草稿，当前没有可验证的上游草稿",
                "先写 BOM 草稿");
        String routeProduct = source.blueprint().getRouteGraphs() == null || source.blueprint().getRouteGraphs().isEmpty()
                ? null : source.blueprint().getRouteGraphs().get(0).productCode();
        DraftRequest request = new DraftRequest(source.factoryId(), source.blueprint().getId(), source.blueprint().getVersion(),
                source.sourceDigest(), routeProduct, null, new BomPin(bom.getId(), bom.getDesiredDigest()), List.of(), null, null, 0L);
        List<RequiredInput> inputs = List.of(
                RequiredInput.text("routeProductCode", "路线产品", WHY, "当前 Blueprint 路线产品编码，最长 100 位", 100),
                RequiredInput.text("routeSnapshotDigest", "路线快照摘要", WHY, "当前路线图 SHA-256 摘要，长度 64", 64),
                RequiredInput.list("items", "危害条目", WHY, "至少 1 条，最多 200 条，每条字段均须填写", "危害", 1, 200, itemFields()));
        return Proposed.single(capabilityId(), "危害分析", request, inputs,
                List.of(new Evidence("bom", bom.getId() + " / " + bom.getDesiredDigest())));
    }
}
