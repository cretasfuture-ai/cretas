package com.cretas.aims.service.orchestration;

import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.enums.PurchaseType;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 采购建议服务
 *
 * <p>根据BOM展开后发现的原辅料短缺，自动生成草稿状态的采购订单（一种短缺物料对应一张采购单）。
 * 生成的采购单处于 {@link PurchaseOrderStatus#DRAFT} 状态，供采购员补充供应商、单价等信息后提交。</p>
 *
 * <h3>注意事项</h3>
 * <ul>
 *   <li>自动生成的采购单中 {@code supplierId} <b>留空(NULL)</b>，
 *       采购员必须在提交前补上真实供应商ID。
 *       (2026-08-28 前这里填的是占位字符串 {@code "PENDING"}，撞 {@code fk_po_supplier} 21 次。)</li>
 *   <li>{@code createdBy} 由调用方传入 —— 这条链的触发者是<b>销售订单的财务审批人</b>
 *       （{@code SalesOrderFinanceApprovedEvent.approvedBy}）。见下方「为什么不再写死 0L」。</li>
 *   <li>单价默认为 0，需采购员填写。</li>
 * </ul>
 *
 * <h3>🔴 为什么不再写死 {@code createdBy = 0L}（2026-08-19）</h3>
 *
 * <p>这里原来有一个 {@code private static final long SYSTEM_USER_ID = 0L}，
 * 注释写着「0 = 系统账号」。<b>{@code users} 表里没有 id=0 这一行</b>，而
 * {@code purchase_orders.created_by} 是 {@code NOT NULL} 且带外键
 * {@code fk_po_created_by → users(id)}。prod 实测（{@code users} 79 行，
 * {@code min(id) = 1309}，{@code id <= 10} 的行数为 <b>0</b>）：那个「系统账号」从来不存在。
 *
 * <p>后果：这个方法<b>一次都没有成功写入过</b>。
 * prod {@code purchase_orders} 里 {@code order_number LIKE 'PO-AUTO%'} 的行数是 <b>0</b>，
 * 而 error 流里 {@code fk_po_created_by} + {@code Key (created_by)=(0)} 的
 * DETAIL 行有 <b>13</b> 条（提及约束名的行 104 条），分布在
 * 2026-06-21 / 07-02 / 07-05 / 07-13 / 07-14。
 *
 * <p>⚠️ 它「7-14 之后不再报错」不是被修好了，是<b>上游先坏了</b>：
 * 从那之后 {@code createProductionPlanFromSO} 自己就建不出计划
 * （先是 {@code planned_unit} NOT NULL，再是 8-07 起 {@code created_by=1} 的同类外键违约），
 * 于是根本走不到本方法。<b>上游在 2026-08-19 修好之后，这条路会立刻重新通电</b> ——
 * 所以这不是「登记待办」，是「下一次财审就会撞」。
 *
 * <p>⛔ 不要换一个新的写死 id。写死的 user id 有保质期：
 * {@code id=1 / factory_admin1 / F001} 就是 2026-08-07 被
 * {@code V20261029_68__purge_nonkept_factory_tenants} 连同租户一起删掉的，
 * 3 小时 57 分后开始报同一类外键。租户清理 / 账号停用 / 建库重来都会让它失效，
 * 而失效方式是异步线程里的<b>静默</b>失败。
 *
 * @author Cretas Team
 * @since 2026-02-19
 */
@Service
@RequiredArgsConstructor
public class ProcurementSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(ProcurementSuggestionService.class);

    /**
     * 🔴 2026-08-28: 这里原来是 {@code private static final String SUPPLIER_PENDING = "PENDING"},
     * 注释写着「占位值, 采购员提交前必须替换」。
     *
     * <p>{@code purchase_orders.supplier_id} 有外键 {@code fk_po_supplier → suppliers(id)},
     * 而 {@code suppliers} 里<b>没有</b> id='PENDING' 这一行(prod 实测 0 行)。
     * ⇒ 每一次自动生成采购建议都撞外键。prod error 流实测 {@code Key (supplier_id)=(PENDING)}
     * <b>21 次</b>, 而 {@code purchase_orders} 里 {@code order_number LIKE 'PO-AUTO%'}
     * 的行数是 <b>0</b> —— <b>这个方法一次都没有成功写入过</b>。
     *
     * <p>⚠️ 这与本类 javadoc 里记的 {@code createdBy = 0L} 是<b>同一个形状</b>:
     * 「拿一个假值填有外键的列」。2026-08-19 修了 created_by 那一个, 隔壁这个留下了,
     * 于是方法从「撞 fk_po_created_by」换成「撞 fk_po_supplier」, 依旧一行没写进去。
     *
     * <p>正确做法是<b>留空</b>: {@code supplier_id} 可空(prod 实测 is_nullable=YES),
     * 而外键允许 NULL。「还不知道供应商」本来就该表示成 NULL,
     * ⛔ 不是一个查无此人的假 id —— 那是把「不知道」写成「就是它」(形态 A¹⁰)。
     */

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;

    /**
     * 根据原辅料短缺清单，为每种短缺物料生成一张草稿采购订单。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>验证短缺列表非空。</li>
     *   <li>为每种短缺物料创建一张 {@link PurchaseOrderStatus#DRAFT} 状态的采购单。</li>
     *   <li>从 {@link RawMaterialType} 中获取计量单位（若查询失败则默认为 "kg"）。</li>
     *   <li>先保存采购单主体（获得UUID），再保存行项目，最后更新金额。</li>
     * </ol>
     *
     * @param factoryId        工厂ID
     * @param productionPlanId 触发此建议的生产计划ID（写入备注，便于追溯）
     * @param shortfalls       原辅料短缺信息列表
     * @param createdBy        这批采购建议归给谁 —— 触发本链的人（销售订单财审的 {@code approvedBy}）。
     *                         {@code purchase_orders.created_by} 是 NOT NULL 且有外键指向 {@code users}，
     *                         ⛔ 不接受 {@code null}，也不接受写死的 id。
     * @return 已持久化的草稿采购订单列表
     * @throws BusinessException     若 shortfalls 为空
     * @throws IllegalStateException 若 {@code createdBy} 为空
     */
    @Transactional
    public List<PurchaseOrder> generateSuggestions(String factoryId,
                                                    String productionPlanId,
                                                    List<MaterialShortfall> shortfalls,
                                                    Long createdBy) {
        if (shortfalls == null || shortfalls.isEmpty()) {
            throw new BusinessException(400, "原辅料短缺列表为空，无需生成采购建议")
                    .withHint("请先确认生产计划存在原辅料短缺").withHintTarget("shortfalls");
        }
        if (createdBy == null) {
            // 形态 A¹⁰: 兜底一个假 id 会把「不知道是谁」写成「就是那个人」, 而且下一次
            // 那个人被删掉时又是一次静默失败(上一个写死的 0L 已经这样失败了 13 次)。
            throw new IllegalStateException(
                    "生成采购建议缺少触发人 (createdBy 为空): planId=" + productionPlanId
                            + " —— purchase_orders.created_by 是 NOT NULL 且有外键 fk_po_created_by"
                            + " → users(id), ⛔ 不要用写死的 user id 兜底"
                            + " (上一个写死的 0L 在 users 表里从来不存在, prod 实测 min(id)=1309)");
        }

        List<PurchaseOrder> suggestions = new ArrayList<>();
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        // 接着当天已有的编号往下排, ⛔ 不从 1 开始 —— uk_po_factory_order 是真实约束,
        // 同一天第二次生成建议会撞唯一键(见 countAutoSuggestionsOn 的文档)。
        long alreadyToday = purchaseOrderRepository.countAutoSuggestionsOn(factoryId, dateStr);

        for (int i = 0; i < shortfalls.size(); i++) {
            MaterialShortfall sf = shortfalls.get(i);

            // ── 1. 构建采购订单主体 ──────────────────────────────────────────
            PurchaseOrder po = new PurchaseOrder();
            po.setFactoryId(factoryId);
            po.setOrderNumber("PO-AUTO-" + dateStr + "-" + (alreadyToday + i + 1));
            po.setOrderDate(LocalDate.now());
            po.setStatus(PurchaseOrderStatus.DRAFT);
            po.setPurchaseType(PurchaseType.DIRECT);
            po.setRemark("系统自动生成 - 生产计划[" + productionPlanId + "]原辅料缺口采购建议");
            // supplierId 未知 → 留 NULL(外键允许), 由采购员在提交前补真实供应商。
            // ⛔ 不要填占位字符串: 见上方常量位置的说明, 那会 100% 撞 fk_po_supplier。
            po.setSupplierId(null);
            po.setCreatedBy(createdBy);
            po.setTotalAmount(BigDecimal.ZERO);
            po.setTaxAmount(BigDecimal.ZERO);
            // @PrePersist 会自动生成 UUID
            PurchaseOrder savedPo = purchaseOrderRepository.save(po);

            // ── 2. 查询物料类型以获取计量单位 ───────────────────────────────
            String unit = "kg"; // 默认单位
            String materialName = sf.getMaterialTypeName();
            try {
                RawMaterialType mt = rawMaterialTypeRepository
                        .findById(sf.getMaterialTypeId())
                        .orElse(null);
                if (mt != null) {
                    if (mt.getUnit() != null && !mt.getUnit().isBlank()) {
                        unit = mt.getUnit();
                    }
                    if (mt.getName() != null && !mt.getName().isBlank()) {
                        materialName = mt.getName();
                    }
                }
            } catch (Exception e) {
                log.warn("采购建议: 查询物料类型失败，使用默认单位。materialTypeId={}, error={}",
                        sf.getMaterialTypeId(), e.getMessage());
            }

            // ── 3. 构建行项目 ────────────────────────────────────────────────
            PurchaseOrderItem item = new PurchaseOrderItem();
            item.setPurchaseOrderId(savedPo.getId());
            item.setMaterialTypeId(sf.getMaterialTypeId());
            item.setMaterialName(materialName);
            item.setQuantity(sf.getShortfallQuantity());
            item.setUnit(unit);
            item.setPriceUnit(unit);
            item.setQuantityToPriceFactor(BigDecimal.ONE);
            item.setUnitPrice(BigDecimal.ZERO); // 采购员填写实际单价
            item.setTaxRate(BigDecimal.ZERO);
            item.setReceivedQuantity(BigDecimal.ZERO);
            item.setRemark("缺口量: " + sf.getShortfallQuantity() + " " + unit
                    + "，已有库存: " + sf.getAvailableQuantity() + " " + unit);
            purchaseOrderItemRepository.save(item);

            suggestions.add(savedPo);

            log.info("采购建议已生成: material={} ({}), shortfall={} {}, PO={}",
                    materialName, sf.getMaterialTypeId(),
                    sf.getShortfallQuantity(), unit,
                    savedPo.getOrderNumber());
        }

        log.info("采购建议批量生成完成: factoryId={}, planId={}, 共生成{}张草稿采购单",
                factoryId, productionPlanId, suggestions.size());
        return suggestions;
    }
}
