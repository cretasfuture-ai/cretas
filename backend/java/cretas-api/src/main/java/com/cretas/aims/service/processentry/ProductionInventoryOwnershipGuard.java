package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.entity.material.MaterialMainCategory;
import com.cretas.aims.exception.BusinessException;

import java.util.Objects;

/**
 * Single fail-closed ownership contract shared by plan assignment, reporting
 * allocation, direct consumption and settlement posting.
 */
public final class ProductionInventoryOwnershipGuard {

    private ProductionInventoryOwnershipGuard() {
    }

    /**
     * 3-arg 兼容重载 —— 未升级传大类的调用点继续得到<b>逐字不变</b>的严格校验
     * (等价于 materialMainCategory=null, 见 4-arg 版本), ⛔ 不会因这次改动被动放宽。
     */
    public static void assertMaterialBatchAllowed(ProductionPlan plan,
                                                  MaterialBatch batch,
                                                  String hintTarget) {
        assertMaterialBatchAllowed(plan, batch, null, hintTarget);
    }

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
                        .withHintTarget(hintTarget);
            }
            return;
        }

        if (batch.getOwnership() == InventoryOwnership.CUSTOMER_OWNED) {
            throw new BusinessException(409, "工厂备料生产不能领用客户所有的库存")
                    .withCode("CUSTOMER_OWNED_MATERIAL_FORBIDDEN")
                    .withHint("请选择公司自有原料批次；客供料仅可用于其绑定的客户订单")
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
