package com.cretas.aims.ai.tool.gateway.descriptor;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.DescriptorProvenance;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.StringReader;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolDescriptorCatalogTest {

    private final ToolDescriptorInventoryLoader loader = new ToolDescriptorInventoryLoader();

    @Test
    void loadsCurrentInventoryWithExactAuditStatisticsAndReviewDebt() {
        ToolDescriptorCatalog catalog = new ToolDescriptorCatalog(loader.loadDefault());
        ToolDescriptorStatistics statistics = catalog.statistics();

        assertThat(catalog.inventory().schemaVersion()).isEqualTo(1);
        assertThat(catalog.inventory().expectedToolCount()).isEqualTo(585);
        assertThat(catalog.inventory().expectedLegacyCount()).isEqualTo(569);
        assertThat(statistics.total()).isEqualTo(585);
        assertThat(statistics.legacy()).isEqualTo(569);
        // 2026-09-15: #815 新增 source_individual_query，EXPLICIT/READ/LOW/REVIEW_REQUIRED；
        // 总数 584 → 585，LEGACY_INFERRED 不变。
        // 2026-08-27: 579 → 584 (+5, legacy 计数不变 — 全部落在 EXPLICIT 一侧)。
        // 这是一个**复合**delta, 至少叠加了两类已分别 merge 的改动:
        //   (a) 新增 EXPLICIT/REVIEW_REQUIRED 工具, 已逐个查过源码 getAccessMode() 与清册一致
        //       (READ/LOW/无需权限): unsupported_capability(#141/#185)、order_today、
        //       system_ai_value_summary、system_finding_action_plan、workdesk_role_capabilities
        //   (b) #185: 解析器盲区(方法签名限定返回类型读不到)曾把 3 个 WRITE/HIGH/需权限的工具
        //       误读成 READ/LOW/免权限, 修复后这 3 个工具的分类和风险等级同时改变
        //   restaurant_peak_hours_gold 的 getAccessMode() 缺失已由 #186 修复(改的是
        //   GoldBackedRestaurantTool.java 基类声明, 不改 YAML), 与本次计数变化无关。
        // ⚠️ 不逐工具手工拆分 (a)/(b) 对每个桶的贡献 —— 下面这组数字取自
        //   ToolDescriptorInventoryDriftTest(逐字段比对源码 585 个工具与清册, 该闸独立通过)
        //   跑出的真实统计, 不是手推算出来的, 避免编造一个自洽但不准的归因。
        // 2026-10-05: READ 445 → 443 / UPDATE 26 → 28 (各 ±2, 其余桶不动): work_process_task_start /
        // work_process_task_complete 由「按工具名后缀推断成 READ」改为显式声明 UPDATE (它们改任务状态,
        // 确认卡的影响说明要用「将修改……现有数据」措辞)。同 ToolDescriptorInventoryDriftTest 的
        // override actionType 61 → 63。riskLevel 桶不变 (UPDATE 的默认风险仍是 LOW)。
        assertThat(statistics.actionTypes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                ToolExecutor.ActionType.READ, 443L,
                ToolExecutor.ActionType.WRITE, 61L,
                ToolExecutor.ActionType.UPDATE, 28L,
                ToolExecutor.ActionType.DELETE, 11L,
                ToolExecutor.ActionType.ANALYZE, 19L,
                ToolExecutor.ActionType.GENERATE, 16L,
                ToolExecutor.ActionType.NOTIFY, 7L));
        assertThat(statistics.riskLevels()).containsExactlyInAnyOrderEntriesOf(Map.of(
                ToolExecutor.RiskLevel.LOW, 510L,
                ToolExecutor.RiskLevel.MEDIUM, 67L,
                ToolExecutor.RiskLevel.HIGH, 8L,
                ToolExecutor.RiskLevel.CRITICAL, 0L));
        // previewSupported 36 → 37 (+1): RestaurantDishDeleteTool 新增 doPreview() 实现 —— 安全改进
        // (高风险软删除工具现在支持"确认前先看变更预览"), 不是退化。见 ApprovedToolSourceMetadataTest
        // / RuntimeToolPolicyLoaderTest 同步更新。requiresPermission 41 → 44 (+3): 同上 (b) 的重新分类。
        // 2026-10-04: 37 → 39 (+2): work_process_task_start / work_process_task_complete 新增只读
        // doPreview() (报工在 AI 对话里走「预览 → 确认」), 同 ToolDescriptorInventoryDriftTest 42 → 44。
        assertThat(statistics.previewSupported()).isEqualTo(39);
        // 2026-10-06: 44 → 45 (+1): report_sku_gross_margin 现在声明 procurement:price:view
        // (它返回售价/收入/成本/毛利, 门与 App 里同一份数据的入口同口径; 见下方登记)。
        assertThat(statistics.requiresPermission()).isEqualTo(45);
        // governanceStatus: REVIEW_REQUIRED 553 → 558 (+5), #815 再增 1 → 559;
        //   REVIEW_REQUIRED_P0 / APPROVED 集合逐一核对未变化(仍是同一组 9 个 APPROVED 工具)
        assertThat(statistics.governanceStatuses()).containsExactlyInAnyOrderEntriesOf(Map.of(
                ToolGovernanceStatus.REVIEW_REQUIRED, 559L,
                ToolGovernanceStatus.REVIEW_REQUIRED_P0, 17L,
                ToolGovernanceStatus.APPROVED, 9L,
                ToolGovernanceStatus.WAIVED, 0L));

        // 2026-08-27 (#185 修复解析器盲区后浮现): 这 3 个 LEGACY_INFERRED 的 HIGH 风险 WRITE
        // 财务工具, 源码里其实一直声明着 getRequiredPermissions() —— 此前被"限定返回类型读不到"
        // 的解析器盲区误读成"没有权限"。#185 已按判据"闸报出记录比源码更严时先怀疑尺子"修好了
        // 解析器, 不能靠删空这条断言把刚恢复的正确数据又含糊过去。⇒ 显式登记这 3 个已知例外
        // (而不是把 requiredPermissions().isEmpty() 整条撤掉), 这样以后再有 LEGACY_INFERRED
        // 工具悄悄长出未登记的权限仍然会被这道闸抓到。
        Set<String> legacyToolsWithKnownRequiredPermissions = Set.of(
                "sales_create_invoice", "finance_invoice_request", "finance_invoice_request_from_order");
        // 2026-10-06: 价格权限门 —— 显式登记而不是放宽上面的判据 (其它 LEGACY 工具仍必须是空集)
        Set<String> legacyToolsWithPriceViewPermission = Set.of("report_sku_gross_margin");
        assertThat(catalog.inventory().descriptors())
                .filteredOn(entry -> entry.provenance() == DescriptorProvenance.LEGACY_INFERRED)
                .allSatisfy(entry -> {
                    assertThat(entry.version()).isEqualTo("1.0.0");
                    if (legacyToolsWithKnownRequiredPermissions.contains(entry.toolName())) {
                        assertThat(entry.requiredPermissions()).containsExactly("finance:read_write");
                    } else if (legacyToolsWithPriceViewPermission.contains(entry.toolName())) {
                        assertThat(entry.requiredPermissions()).containsExactly("procurement:price:view");
                    } else {
                        assertThat(entry.requiredPermissions()).isEmpty();
                    }
                    assertThat(entry.governanceStatus()).isIn(
                            ToolGovernanceStatus.REVIEW_REQUIRED,
                            ToolGovernanceStatus.REVIEW_REQUIRED_P0);
                });
        assertThat(catalog.inventory().descriptors())
                .filteredOn(entry -> entry.governanceStatus() == ToolGovernanceStatus.APPROVED)
                .extracting(ToolDescriptorInventoryEntry::toolName)
                .containsExactlyInAnyOrder(
                        "user_disable",
                        "restaurant_dish_delete",
                        "restaurant_owner_action_advisor",
                        "canvas_product_work_process_config",
                        "canvas_work_process_catalog",
                        "product_create",
                        "material_stock_summary",
                        "material_batch_query",
                        "material_expired_query");
    }

    @Test
    void exposesUniqueLookupsAndKeepsTheRemainingP0SetReviewBlocked() {
        ToolDescriptorCatalog catalog = ToolDescriptorCatalog.loadDefault();
        Set<String> actualP0 = catalog.inventory().descriptors().stream()
                .filter(entry -> entry.governanceStatus() == ToolGovernanceStatus.REVIEW_REQUIRED_P0)
                .map(ToolDescriptorInventoryEntry::toolName)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> approvedP0 = Set.of("user_disable", "restaurant_dish_delete");

        assertThat(actualP0).containsExactlyInAnyOrderElementsOf(
                ToolDescriptorInventoryLoader.P0_TOOL_NAMES.stream()
                        .filter(toolName -> !approvedP0.contains(toolName))
                        .collect(Collectors.toUnmodifiableSet()));
        // 18 → 17: bom_version_approve 随 9 个老式 BOM 写入工具退役, 从 P0_TOOL_NAMES 摘除。
        // 这个数量的是「仍待审的 P0 工具数」—— 少一个工具就该少一个, 不是把闸放松。
        assertThat(actualP0).hasSize(17);
        for (String toolName : actualP0) {
            ToolDescriptorInventoryEntry byName = catalog.findByToolName(toolName).orElseThrow();
            assertThat(byName.governanceStatus()).isEqualTo(ToolGovernanceStatus.REVIEW_REQUIRED_P0);
            assertThat(catalog.findByImplementationClass(byName.implementationClass()))
                    .containsSame(byName);
        }
        for (String toolName : approvedP0) {
            assertThat(catalog.findByToolName(toolName).orElseThrow().governanceStatus())
                    .isEqualTo(ToolGovernanceStatus.APPROVED);
        }
        assertThat(catalog.findByToolName("does_not_exist")).isEmpty();
        assertThat(catalog.findByImplementationClass("com.example.DoesNotExist")).isEmpty();
    }

    @Test
    void rejectsDuplicateKeysAliasesAndOversizedDocuments() {
        String valid = oneExplicitDescriptorYaml();

        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("schemaVersion: 1", "schemaVersion: 1\nschemaVersion: 1"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid tool descriptor inventory YAML")
                .hasCauseInstanceOf(YAMLException.class);

        String aliased = valid
                .replace("expectedToolCount: 1", "expectedToolCount: 2")
                .replace("  - toolName:", "  - &entry\n    toolName:")
                + "  - *entry\n";
        assertThatThrownBy(() -> loader.load(new StringReader(aliased)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid tool descriptor inventory YAML")
                .hasCauseInstanceOf(YAMLException.class);

        String oversized = valid.replace(
                "domainTags: [test]",
                "domainTags: ['" + "a".repeat(ToolDescriptorInventoryLoader.YAML_CODE_POINT_LIMIT)
                        + "']");
        assertThatThrownBy(() -> loader.load(new StringReader(oversized)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid tool descriptor inventory YAML")
                .hasCauseInstanceOf(YAMLException.class);
    }

    @Test
    void rejectsUnknownFieldsIllegalEnumsBlankIdentityAndMissingResources() {
        String valid = oneExplicitDescriptorYaml();

        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("schemaVersion: 1", "schemaVersion: 1\nunknownRoot: true"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown=[unknownRoot]");
        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("actionType: READ", "actionType: EXECUTE"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("illegal value EXECUTE");
        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("implementationClass: com.example.SafeTool",
                        "implementationClass: ''"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("implementationClass must be a non-blank string");
        assertThatThrownBy(() -> loader.load(new StringReader(
                valid.replace("    allowedRoles: []\n", ""))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit descriptor is missing allowedRoles");
        assertThatThrownBy(() -> loader.loadResource("ai/tool/gateway/missing.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resource not found");
    }

    @Test
    void rejectsWaiverOrIncompleteApprovalForP0Tools() {
        String incompleteApproval = oneExplicitDescriptorYaml()
                .replace("safe_test", "canvas_set_user_permission")
                .replace("requiredPermissions: [test:execute]", "requiredPermissions: []");
        assertThatThrownBy(() -> loader.load(new StringReader(incompleteApproval)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("complete explicit source authorization metadata");

        String waiver = incompleteApproval.replace(
                "governanceStatus: APPROVED", "governanceStatus: WAIVED");
        assertThatThrownBy(() -> loader.load(new StringReader(waiver)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("P0 tool must be REVIEW_REQUIRED_P0 or fully explicit APPROVED");
    }

    private String oneExplicitDescriptorYaml() {
        return """
                schemaVersion: 1
                expectedToolCount: 1
                expectedLegacyCount: 0
                descriptors:
                  - toolName: safe_test
                    implementationClass: com.example.SafeTool
                    provenance: EXPLICIT
                    actionType: READ
                    riskLevel: LOW
                    supportsPreview: false
                    requiresPermission: true
                    requiredPermissions: [test:execute]
                    allowedRoles: []
                    version: 1.0.0
                    domainTags: [test]
                    overrideFlags:
                      actionType: true
                      riskLevel: true
                      supportsPreview: true
                      requiresPermission: true
                      hasPermission: true
                      requiredPermissions: true
                      version: true
                      domainTags: true
                    governanceStatus: APPROVED
                """;
    }
}
