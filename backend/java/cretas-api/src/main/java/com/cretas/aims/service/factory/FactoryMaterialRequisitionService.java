package com.cretas.aims.service.factory;

import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 工厂领料单 Service (P0-5, W2-3).
 *
 * <p>核心流程: 排产 → 按 BOM 展开 → 备料 → 调拨 → 签收 → 生产 → 关单退料.</p>
 */
public interface FactoryMaterialRequisitionService {

    /**
     * 按生产计划 BOM 自动展开生成领料单. 等价于 {@code plannedRawInputKg=null}
     * (辅料/包材配比展开留空「按实际领用」, 与历史行为一致).
     */
    default FactoryMaterialRequisition generateFromPlan(String factoryId, String productionPlanId, Long requestedBy) {
        return generateFromPlan(factoryId, productionPlanId, requestedBy, null);
    }

    /**
     * 按生产计划 BOM 自动展开生成领料单, 并显式给出<b>本单计划投入的原料质量</b>(kg)。
     *
     * <p>口径 (owner 2026-09-01 拍板): 早上定产量 → 人据此决定「这批领多少原料」→
     * 把这个数字填进领料单 → 系统用 workflow BOM 里已经录好的调料配比(g/kg)
     * 把辅料/包材需求量展开出来。<b>原料本身的数量不是主数据、不是能反推的东西,
     * 是人对这一单的当场决定</b> —— {@code plannedRawInputKg} 就是这个决定的落点。
     *
     * @param plannedRawInputKg 可选。{@code null} 或非正数 = 沿用旧行为
     *      (辅料/包材需求量留空「按实际领用」，不猜、不编 0)。
     */
    FactoryMaterialRequisition generateFromPlan(String factoryId, String productionPlanId, Long requestedBy,
                                                BigDecimal plannedRawInputKg);

    /** 详情 (含明细). */
    FactoryMaterialRequisition getById(String factoryId, String id);

    /** 分页列表, status 可为 null. */
    Page<FactoryMaterialRequisition> list(String factoryId, Status status, Pageable pageable);

    /** 按计划反查. */
    List<FactoryMaterialRequisition> listByPlan(String factoryId, String productionPlanId);

    /** 开始备料. */
    FactoryMaterialRequisition startPicking(String factoryId, String id, Long operatorId);

    /**
     * 确认备料 — items: [{itemId, pickedQty, batchNumbers:[{batchNo,qty},..]}].
     */
    FactoryMaterialRequisition confirmPicking(String factoryId, String id, Long operatorId, List<Map<String, Object>> items);

    /** 调拨到工厂仓. */
    FactoryMaterialRequisition transferToFactory(String factoryId, String id, Long operatorId);

    /** 工厂签收. */
    FactoryMaterialRequisition receive(String factoryId, String id, Long operatorId);

    /** 关单 + 自动退料 (returned = issued - consumed). */
    FactoryMaterialRequisition close(String factoryId, String id, Long operatorId);

    /** 关单 + 人工损耗确认: closeItems: [{itemId, wastageQty}]. */
    FactoryMaterialRequisition close(String factoryId, String id, Long operatorId, List<Map<String, Object>> closeItems);

    /** 取消. */
    FactoryMaterialRequisition cancel(String factoryId, String id, Long operatorId, String reason);
}
