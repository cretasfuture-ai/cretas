package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.material.MaterialMainCategory;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.gate.GateDiagnosis;
import com.cretas.aims.service.gate.GateId;
import com.cretas.aims.service.gate.GateRoutes;

import java.util.Objects;

/**
 * Single fail-closed ownership contract shared by plan assignment, reporting
 * allocation, direct consumption and settlement posting.
 */
public final class ProductionInventoryOwnershipGuard {

    private ProductionInventoryOwnershipGuard() {
    }

    // 🔴 2026-09-02 (缺陷 38 第三次漏改后, 对抗终审裁定): 曾有 3-arg 兼容重载
    // (materialMainCategory 隐式传 null = 按最严格口径), 目的是让未升级的调用点"安全地"继续
    // 走严格路径。它反而成了三次漏改的载体 —— 结单路径 (ProductionPlanServiceImpl) 一直调用
    // 这个 3-arg 版本, 编译器不会提示"你漏传了大类", 于是辅料/包材在结单时被当原料核对客户
    // 血缘, LIUSHANMEN prod 08:0x 结单报错。⛔ 已删除 3-arg 重载, 不再留一个"默认最严格"的
    // 静默入口 —— 每个调用点必须显式决定 materialMainCategory (拿不到就显式传 null,
    // 而不是靠重载解析帮你悄悄选中它)。

    /**
     * @param materialMainCategory 物料大类 (原料/辅料/包材, 权威来源
     *        {@code RawMaterialType.mainCategory}, 见 {@link MaterialMainCategory})。
     *        null / 未知一律按<b>最严格</b>口径(等同原料), ⛔ 不兜底放宽。
     */
    public static void assertMaterialBatchAllowed(ProductionPlan plan,
                                                  MaterialBatch batch,
                                                  String materialMainCategory,
                                                  String hintTarget) {
        if (plan == null || batch == null) {
            throw new BusinessException(409, "生产计划或原料批次归属信息缺失")
                    .withCode("PRODUCTION_MATERIAL_OWNERSHIP_CONTEXT_REQUIRED")
                    .withHintTarget(hintTarget);
        }
        if (!Objects.equals(plan.getFactoryId(), batch.getFactoryId())) {
            throw new BusinessException(403, "原料批次不属于当前生产计划的工厂")
                    .withCode("PRODUCTION_MATERIAL_CROSS_FACTORY_FORBIDDEN")
                    .withHintTarget(hintTarget);
        }

        if (plan.getMaterialSupplyMode() == MaterialSupplyMode.CUSTOMER_SUPPLIED) {
            // 🔒 2026-09-02 owner 裁定: 来料加工(客户供主料)下, 辅料/包材(含调料, 经
            // MaterialMainCategory 归一)本就该由工厂自有库存出、计入加工费而非客户物料成本 ——
            // 与「工厂备料生产」同一条件, 直接放行。⛔ RAW 不受影响: 走不到这里, 仍必须
            // 是同客户、同销售订单归属的客供批次, 绝不回退公司库存 (下面的严格分支逐字未动)。
            if (isCompanyStockEligibleCategory(materialMainCategory)
                    && batch.getOwnership() != InventoryOwnership.CUSTOMER_OWNED) {
                return;
            }
            requireCustomerSuppliedPlanLineage(plan, hintTarget);
            boolean sameCustomer = Objects.equals(plan.getCustomerId(), batch.getOwnerCustomerId());
            boolean inventoryProduction = isBlank(plan.getSourceOrderId());
            boolean sameSalesOrder = inventoryProduction
                    ? isBlank(batch.getSourceSalesOrderId())
                    : Objects.equals(plan.getSourceOrderId(), batch.getSourceSalesOrderId());
            boolean sameSalesOrderItem = isBlank(plan.getSourceOrderItemId())
                    || isBlank(batch.getSourceSalesOrderItemId())
                    || Objects.equals(plan.getSourceOrderItemId(), batch.getSourceSalesOrderItemId());
            if (batch.getOwnership() != InventoryOwnership.CUSTOMER_OWNED
                    || !sameCustomer || !sameSalesOrder || !sameSalesOrderItem) {
                throw new BusinessException(409, inventoryProduction
                        ? "客户专属库存生产只能领用该客户尚未绑定销售订单的原料"
                        : "客供料只能用于同一客户、同一销售订单的生产计划")
                        .withCode("CUSTOMER_SUPPLIED_MATERIAL_SCOPE_MISMATCH")
                        .withHint(inventoryProduction
                                ? "请选择归属该客户且来源销售订单为空的原料批次"
                                : "请使用该销售订单对应的客户来料批次")
                        .withHintTarget(hintTarget)
                    .withDiagnosis(GateDiagnosis.of(GateId.MATERIAL_OWNERSHIP, "CUSTOMER_SUPPLIED_MATERIAL_SCOPE_MISMATCH", "BATCH", batch.getBatchNumber()).resolve(inventoryProduction ? "改选归属该客户且尚未绑定销售订单的原料批次" : "改用该销售订单对应的客户来料批次", null).build());
            }
            return;
        }

        if (batch.getOwnership() == InventoryOwnership.CUSTOMER_OWNED) {
            throw new BusinessException(409, "工厂备料生产不能领用客户所有的库存")
                    .withCode("CUSTOMER_OWNED_MATERIAL_FORBIDDEN")
                    .withHint("请选择公司自有原料批次；客供料仅可用于其绑定的客户订单")
                    .withHintTarget(hintTarget)
                    .withDiagnosis(GateDiagnosis.of(GateId.MATERIAL_OWNERSHIP, "CUSTOMER_OWNED_MATERIAL_FORBIDDEN", "BATCH", batch.getBatchNumber()).resolve("批次 " + batch.getBatchNumber() + " 是客户所有的料, 工厂备料生产不能领; 改选公司自有原料批次", null).build());
        }
    }

    /**
     * 领料 / 投料 / AI 出库入口共用的归属判断 (排查 2026-11-10 A4/A5)。
     *
     * @param plan        领给的生产计划; 没有计划传 null
     * @param planClaimed 调用方声称关联了计划 (传了计划 id) —— 声称了却查不到 (不存在 / 别厂) 一律拒绝,
     *                    ⛔ 不退化成「无计划」口径放过去
     */
    public static void assertIssuable(ProductionPlan plan, boolean planClaimed, MaterialBatch batch,
                                      String materialMainCategory, String hintTarget) {
        if (plan != null) {
            assertMaterialBatchAllowed(plan, batch, materialMainCategory, hintTarget);
            return;
        }
        if (planClaimed) {
            throw new BusinessException(404, "关联的生产计划不存在或不属于本厂")
                    .withCode("PRODUCTION_MATERIAL_PLAN_NOT_FOUND")
                    .withHint("请确认生产计划; 不关联计划时请不要传计划")
                    .withHintTarget(hintTarget);
        }
        if (batch != null && batch.getOwnership() == InventoryOwnership.CUSTOMER_OWNED) {
            throw new BusinessException(409, "批次 " + batch.getBatchNumber() + " 是客户所有的料, 不关联生产计划不能领用")
                    .withCode("CUSTOMER_OWNED_MATERIAL_REQUIRES_PLAN")
                    .withHint("客户来料只能领给该客户订单的生产计划: 请走「生产计划 → 领料单」领料, 或改选公司自有批次")
                    .withHintTarget(hintTarget);
        }
    }

    /**
     * 辅料/包材(含调料, 经 {@link MaterialMainCategory} 归一到「辅料」大类)在来料加工计划下
     * 允许用工厂自有库存。RAW(「原料」)不在此列 —— 那是这条规则存在的全部理由(客户主料的
     * 归属守卫必须原样保留)。
     */
    public static boolean isCompanyStockEligibleCategory(String materialMainCategory) {
        return MaterialMainCategory.AUXILIARY.equals(materialMainCategory)
                || MaterialMainCategory.PACKAGING.equals(materialMainCategory);
    }

    public static void requireCustomerSuppliedPlanLineage(ProductionPlan plan, String hintTarget) {
        if (plan == null || isBlank(plan.getCustomerId())) {
            throw new BusinessException(409, "客户所有库存生产计划缺少客户归属快照")
                    .withCode("CUSTOMER_SUPPLIED_PLAN_LINEAGE_INCOMPLETE")
                    .withHint("销售订单生产需从已审批订单创建；库存生产需明确选择归属客户")
                    .withHintTarget(hintTarget);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
