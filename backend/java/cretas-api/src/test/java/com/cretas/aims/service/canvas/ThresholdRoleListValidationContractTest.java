package com.cretas.aims.service.canvas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 岗位名单 / 信用模式这类阈值保存前要校验: 打错一个字不能静默变成「只有厂长」(审查建议)。
 */
@DisplayName("阈值保存校验: 岗位码必须存在, 信用模式只能是 OFF/WARN/BLOCK")
class ThresholdRoleListValidationContractTest {

    @Test
    @DisplayName("岗位名单: 合法码(含空格 / 空串)通过; 打错或不存在的岗位码被拒并点名")
    void roleListKeysRejectUnknownRoleCodes() {
        String key = ThresholdKeys.INVENTORY_PERMISSION_WASTAGE_APPROVE;
        assertThat(ThresholdKeys.validateSemantics(key, "finance_manager, production_manager")).isNull();
        assertThat(ThresholdKeys.validateSemantics(key, "")).isNull(); // 空 = 只有厂长
        assertThat(ThresholdKeys.validateSemantics(key, "finance_manger")) // 阴性对照: 打错一个字
                .contains("finance_manger");
        assertThat(ThresholdKeys.validateSemantics(key, "仓储主管")).isNotNull();
        assertThat(ThresholdKeys.validateSemantics(key, "Finance_Manager")).isNotNull(); // 岗位码是小写, 不放宽
    }

    @Test
    @DisplayName("所有接收人 / 权限名单 key 都在校验范围内; 其它 key 不受影响")
    void everyRoleListKeyIsCovered() {
        assertThat(ThresholdKeys.ROLE_LIST_KEYS).hasSize(14); // 阳性对照: 集合没被清空 (+ 质检待决定通知 + 取消已批准销售单 + 报废审批 + 付款执行, 2026-11-11)
        for (String key : ThresholdKeys.ROLE_LIST_KEYS) {
            assertThat(ThresholdKeys.validateSemantics(key, "nobody")).as(key).isNotNull();
            assertThat(ThresholdKeys.validateSemantics(key, "warehouse_manager,factory_super_admin")).as(key).isNull();
        }
        assertThat(ThresholdKeys.validateSemantics(ThresholdKeys.BOM_MAX_DEPTH, "whatever")).isNull();
    }

    @Test
    @DisplayName("信用检查模式: 只认 OFF / WARN / BLOCK")
    void creditModeOnlyKnownValues() {
        String key = ThresholdKeys.CREDIT_CHECK_MODE;
        assertThat(ThresholdKeys.validateSemantics(key, "warn")).isNull();
        assertThat(ThresholdKeys.validateSemantics(key, "BLOCK")).isNull();
        assertThat(ThresholdKeys.validateSemantics(key, "BLCOK")).isNotNull();
    }

    @Test
    @DisplayName("开关类 key (缺料收尾 / 包装单位产能): 只认登记的代码, 大小写不敏感; 打错字被拒")
    void switchKeysOnlyKnownValues() {
        assertThat(ThresholdKeys.validateSemantics(ThresholdKeys.SHORTAGE_CASE_CLOSE_MODE, "auto")).isNull();
        assertThat(ThresholdKeys.validateSemantics(ThresholdKeys.SHORTAGE_CASE_CLOSE_MODE, "MANUAL")).isNull();
        assertThat(ThresholdKeys.validateSemantics(ThresholdKeys.SHORTAGE_CASE_CLOSE_MODE, "AUTOMATIC"))
                .contains("MANUAL").contains("AUTO");
        assertThat(ThresholdKeys.validateSemantics(ThresholdKeys.PRODUCTION_CAPACITY_PACKAGE_UNIT_MODE, "net_content")).isNull();
        assertThat(ThresholdKeys.validateSemantics(ThresholdKeys.PRODUCTION_CAPACITY_PACKAGE_UNIT_MODE, "FACTORY_UNIT")).isNull();
        assertThat(ThresholdKeys.validateSemantics(ThresholdKeys.PRODUCTION_CAPACITY_PACKAGE_UNIT_MODE, "BAG")).isNotNull();
        // 换线产能会挡住换线方案 ⇒ 闸门类 (短缓存); 缺料自动关闭不挡动作 ⇒ 不登记
        assertThat(ThresholdKeys.isGating(ThresholdKeys.PRODUCTION_CAPACITY_PACKAGE_UNIT_MODE)).isTrue();
        assertThat(ThresholdKeys.isGating(ThresholdKeys.SHORTAGE_CASE_CLOSE_MODE)).isFalse();
    }

    @Test
    @DisplayName("质检整张判不合格范围: 只认 ANY_ITEM / MINOR_FOR_REVIEW / CRITICAL_ONLY, 且是闸门类")
    void inspectionFailScopeOnlyKnownValues() {
        String key = ThresholdKeys.QUALITY_INSPECTION_FAIL_SCOPE;
        assertThat(ThresholdKeys.validateSemantics(key, "minor_for_review")).isNull();
        assertThat(ThresholdKeys.validateSemantics(key, "CRITICAL_ONLY")).isNull();
        assertThat(ThresholdKeys.validateSemantics(key, "MINOR")).isNotNull();
        assertThat(ThresholdKeys.isGating(key)).isTrue();
    }
}
