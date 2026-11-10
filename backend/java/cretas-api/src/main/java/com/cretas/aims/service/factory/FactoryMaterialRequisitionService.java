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
     * @return 新单据; {@link FactoryMaterialRequisition#getSeasoningItemsStatus()} 说明调料行这一段的结果
     *      ({@link #SEASONING_ITEMS_OK} / {@link #SEASONING_ITEMS_UNAVAILABLE})
     */
    FactoryMaterialRequisition generateFromPlan(String factoryId, String productionPlanId, Long requestedBy,
                                                BigDecimal plannedRawInputKg);

    /**
     * {@link FactoryMaterialRequisition#getSeasoningItemsStatus()} 的取值 (只有 {@code generateFromPlan} 填)。
     * 调料表读取成功: 有调料就已按配方追加, 配方没有调料则本来就没有调料行。
     */
    String SEASONING_ITEMS_OK = "OK";
    /**
     * 调料表读取失败: 领料单 (原料 / 包材等 BOM 行) 照常生成并保存, 但<b>一行调料都没有追加</b>,
     * 需要人工补上调料 (或稍后重新按计划生成)。⛔ 不是「这个配方没有调料」。
     */
    String SEASONING_ITEMS_UNAVAILABLE = "UNAVAILABLE";

    /** 详情 (含明细). */
    FactoryMaterialRequisition getById(String factoryId, String id);

    /** {@link FactoryMaterialRequisition#getAuxiliaryAutoCalc()} 的四个取值。 */
    String AUX_AUTO_CALC_AVAILABLE = "AVAILABLE";
    String AUX_AUTO_CALC_NO_RATIO = "NO_RATIO";
    String AUX_AUTO_CALC_PROCESS_SECRET = "PROCESS_SECRET";
    /** 读配比时出错 (计划 / BOM / 保密名单查询失败): 详情照常返回, 辅料改人工填。 */
    String AUX_AUTO_CALC_UNAVAILABLE = "UNAVAILABLE";

    /**
     * 详情接口用: {@link #getById} + 给辅料行填确认领料要用的配比
     * ({@code FactoryMaterialRequisitionItem#dosagePerKgG}) 和 {@code auxiliaryAutoCalc}。
     *
     * <p>仓管只读得到领料单 (读不了生产计划 / BOM), 所以配比由这里按建单同一份 BOM 调料表带出来;
     * 产品工艺保密时按调用者的完整查看权判 ({@code ProcessSecretAccessService#permitsViewFull}),
     * 无权就不给配比, 状态为 {@code PROCESS_SECRET}。
     *
     * @param actor 调用者 (从请求取); null 时受保护产品一律不给配比
     */
    FactoryMaterialRequisition getDetailForPicking(String factoryId, String id,
                                                   com.cretas.aims.security.AuthenticatedActor actor);

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

    /**
     * C1 (Steve 2026-11-10 裁定 B): 整单撤回报工把 {@code qty} 放回了生产仓批次 {@code workshopBatchId};
     * 如果这批料属于一张<b>已关单</b>的领料单, 生产仓已没有出路, 就把这 {@code qty} 直接退回原料仓源批次,
     * 留一笔退料流水 (负数 MATERIAL_RETURN 消耗 + 已执行的生产退料单), 并把生产仓批次重新划平。
     *
     * <p>必须在调用方事务内、且调用方<b>已锁住</b>该生产仓批次之后调用 (锁序: 生产仓批次 → 领料单 → 原料批次,
     * 与关单一致), 这样「是否已关单」读到的是并发关单提交后的状态, 不会重复退料。
     *
     * @return 实际退回原料仓的量; 不是领料单物化的生产仓批次、或领料单未关单 ⇒ 0 (料留在生产仓, 原路径不变)
     */
    BigDecimal returnToSourceIfRequisitionClosed(String factoryId, String workshopBatchId, BigDecimal qty,
                                                 Long operatorId, String reasonRef);
}
