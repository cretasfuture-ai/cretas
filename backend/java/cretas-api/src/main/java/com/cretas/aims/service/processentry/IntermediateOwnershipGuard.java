package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 半成品 / 在制品的归属闸 (缺口 16 跨客户领料, 2026-10-27)。
 *
 * <p>原料有 {@link ProductionInventoryOwnershipGuard}; 中间产物此前没有任何归属检查 ——
 * 代工客户的半成品能被领进另一个客户或本厂自有的计划。本类是唯一口径:
 * <ul>
 *   <li><b>谁的</b>: 产出它的计划的 {@code outputOwnership} (代工 = 客户所有 + 客户 id, 其余 = 公司自有)。
 *       没有计划的旧批次 = 公司自有 (没有客户合同)。</li>
 *   <li><b>能不能领</b>: 领用计划的归属 == 料的归属 才放行; 公司 ↔ 客户、客户 A ↔ 客户 B 一律 409。
 *       与原料「来料加工只能用该客户的主料」同一立场, 不对半成品放宽。</li>
 * </ul>
 *
 * <p>常驻半成品行 ({@link SemiFinishedInventory}) 归属落在行上 (按产品码跨批次共用的行没有单一计划);
 * 逐道录入的在制批次 ({@link MaterialBatch}, sourceDocType=PRODUCTION_BATCH) 的归属<b>从产出它的生产批次的计划现算</b>,
 * ⛔ 不写 {@code MaterialBatch.ownership}: 那一列在订单成本拆解里表示「客供原料, 成本记 0」,
 * 给在制批次打上客户所有会把上游公司自有辅料/包材的成本一并清零。
 */
@Component
@RequiredArgsConstructor
public class IntermediateOwnershipGuard {

    public static final String CODE_CROSS_OWNER = "INTERMEDIATE_CROSS_OWNER_FORBIDDEN";
    public static final String CODE_POOL_OWNER_MISMATCH = "SEMI_ROW_OWNER_MISMATCH";
    public static final String CODE_CONTEXT_REQUIRED = "INTERMEDIATE_OWNER_CONTEXT_REQUIRED";

    /** 按产品码共用的半成品行, 客户所有的那一份另起一行: {@code <产品码>@<客户 id 前 8 位>}。 */
    static final String CUSTOMER_ROW_SEPARATOR = "@";

    private final ProductionPlanRepository planRepository;
    private final ProductionBatchRepository batchRepository;
    private final CustomerRepository customerRepository;

    /** 归属: 公司自有 (customerId 恒 null) 或某客户所有。 */
    public record Owner(InventoryOwnership ownership, String customerId) {
        public static final Owner COMPANY = new Owner(InventoryOwnership.COMPANY_OWNED, null);

        public boolean isCustomer() {
            return ownership == InventoryOwnership.CUSTOMER_OWNED;
        }
    }

    // ===================== 纯函数 =====================

    /** 计划产出归谁。代工计划缺客户快照 ⇒ 409 (不猜成公司自有)。 */
    public static Owner ofPlan(ProductionPlan plan) {
        if (plan == null || plan.getOutputOwnership() != InventoryOwnership.CUSTOMER_OWNED) {
            return Owner.COMPANY;
        }
        if (isBlank(plan.getCustomerId())) {
            throw new BusinessException(409, "代工计划 " + plan.getPlanNumber() + " 缺少客户归属, 不能判断半成品归谁")
                    .withCode(CODE_CONTEXT_REQUIRED)
                    .withHint("请从已审批的代工销售订单重新建计划");
        }
        return new Owner(InventoryOwnership.CUSTOMER_OWNED, plan.getCustomerId());
    }

    /** 半成品行归谁; 迁移前的旧行 (null) 按公司自有读。 */
    public static Owner ofSemi(SemiFinishedInventory sfi) {
        if (sfi == null || sfi.getOwnership() != InventoryOwnership.CUSTOMER_OWNED) {
            return Owner.COMPANY;
        }
        return new Owner(InventoryOwnership.CUSTOMER_OWNED, sfi.getOwnerCustomerId());
    }

    public static boolean sameOwner(Owner a, Owner b) {
        return a != null && b != null && a.ownership() == b.ownership()
                && Objects.equals(a.customerId(), b.customerId());
    }

    /**
     * 按产品码共用的行 (画布 semiCode, 如 {@code PTSEMI-LSM-0005}) 落哪一行: 公司自有沿用产品码本身
     * (存量行零变化), 客户所有另起 {@code <产品码>@<客户前 8 位>}。前缀撞号由入账时的
     * {@link #assertRowCreditable} 兜底 (fail-closed), 不会把两个客户混进一行。
     */
    public static String pooledRowKey(String semiCode, Owner producer) {
        if (semiCode == null || producer == null || !producer.isCustomer()) {
            return semiCode;
        }
        String customer = producer.customerId();
        String tag = customer.length() > 8 ? customer.substring(0, 8) : customer;
        return semiCode + CUSTOMER_ROW_SEPARATOR + tag;
    }

    /** 新建行时写归属 (行上原来没有归属 = 迁移前旧行, 也按本次产出补上)。 */
    public static void stamp(SemiFinishedInventory sfi, Owner owner) {
        Owner effective = owner == null ? Owner.COMPANY : owner;
        sfi.setOwnership(effective.ownership());
        sfi.setOwnerCustomerId(effective.customerId());
    }

    /**
     * 入账前: 这一行已经有归属且与本次产出不同 ⇒ 拒 (一行只装一个主人的料)。
     * 行上没有归属 (刚建的占位行 / 旧行) ⇒ 盖上本次产出的归属。
     */
    public static void assertRowCreditable(SemiFinishedInventory sfi, Owner producer) {
        Owner effective = producer == null ? Owner.COMPANY : producer;
        if (sfi.getOwnership() == null) {
            stamp(sfi, effective);
            return;
        }
        if (!sameOwner(ofSemi(sfi), effective)) {
            throw new BusinessException(409, "半成品批次 " + sfi.getIntermediateBatchNo()
                    + " 已装着" + describe(ofSemi(sfi), null) + "的料, 不能再把" + describe(producer, null)
                    + "的产出记进同一行")
                    .withCode(CODE_POOL_OWNER_MISMATCH)
                    .withSeverity("BLOCKING")
                    .withHint("请联系管理员核对该半成品批次的归属");
        }
    }

    // ===================== 查库的那一半 =====================

    public Owner ofPlanId(String factoryId, String planId) {
        if (isBlank(planId)) {
            return Owner.COMPANY;
        }
        ProductionPlan plan = planRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new BusinessException(409, "生产计划不存在, 不能判断半成品归属: " + planId)
                        .withCode(CODE_CONTEXT_REQUIRED)
                        .withHint("请刷新页面, 从仍存在的生产计划重新录入"));
        return ofPlan(plan);
    }

    /** 生产批次所属计划的归属; 不挂计划的旧批次 = 公司自有。 */
    public Owner ofProductionBatch(String factoryId, Long productionBatchId) {
        if (productionBatchId == null) {
            return Owner.COMPANY;
        }
        ProductionBatch batch = batchRepository.findByIdAndFactoryId(productionBatchId, factoryId)
                .orElseThrow(() -> new BusinessException(409, "生产批次不存在, 不能判断半成品归属: " + productionBatchId)
                        .withCode(CODE_CONTEXT_REQUIRED)
                        .withHint("请刷新页面, 从仍存在的生产批次重新报工"));
        return ofPlanId(factoryId, batch.getProductionPlanId());
    }

    /**
     * 逐道录入的半成品锚 {@code CLK-SEMI-<计划 id 前 8 位>-<产品前 8 位>} 归哪个计划
     * ({@code WipInventoryService.clerkSemiAnchor} 是唯一造锚处)。前缀命中不唯一 ⇒ 409, 不猜。
     * 不是这种锚 (旧调用) ⇒ 公司自有。
     */
    public Owner ofClerkAnchor(String factoryId, String anchor) {
        if (anchor == null || !anchor.startsWith(CLERK_ANCHOR_PREFIX)) {
            return Owner.COMPANY;
        }
        String rest = anchor.substring(CLERK_ANCHOR_PREFIX.length());
        int dash = rest.indexOf('-');
        String planPrefix = dash >= 0 ? rest.substring(0, dash) : rest;
        if (planPrefix.isEmpty() || "00000000".equals(planPrefix)) {
            return Owner.COMPANY;   // 计划 id 为空时造锚的占位段
        }
        java.util.List<ProductionPlan> plans = planRepository.findByFactoryIdAndIdStartingWith(factoryId, planPrefix);
        if (plans == null || plans.size() != 1) {
            throw new BusinessException(409, "半成品批次 " + anchor + " 对应的计划不唯一, 不能判断归属")
                    .withCode(CODE_CONTEXT_REQUIRED)
                    .withHint("请联系管理员核对该半成品批次属于哪个生产计划");
        }
        return ofPlan(plans.get(0));
    }

    static final String CLERK_ANCHOR_PREFIX = "CLK-SEMI-";

    /** 能领用这一行吗 (只判归属; 余量 / 质检另有闸)。 */
    public void assertSemiDrawable(String factoryId, Owner consumer, SemiFinishedInventory sfi, String hintTarget) {
        if (sfi == null) {
            return;
        }
        requireConsumer(consumer, hintTarget);
        Owner material = ofSemi(sfi);
        if (!sameOwner(consumer, material)) {
            throw crossOwner(factoryId, "半成品批次 " + sfi.getIntermediateBatchNo(), material, consumer, hintTarget);
        }
    }

    /** 逐道录入的在制批次: 归属看产出它的生产批次的计划。 */
    public void assertWipBatchDrawable(String factoryId, Owner consumer, ProductionBatch sourceBatch,
                                       String label, String hintTarget) {
        requireConsumer(consumer, hintTarget);
        Owner material = sourceBatch == null ? Owner.COMPANY
                : ofPlanId(factoryId, sourceBatch.getProductionPlanId());
        if (!sameOwner(consumer, material)) {
            throw crossOwner(factoryId, "在制批次 " + label, material, consumer, hintTarget);
        }
    }

    public boolean drawable(Owner consumer, SemiFinishedInventory sfi) {
        return sameOwner(consumer, ofSemi(sfi));
    }

    private static void requireConsumer(Owner consumer, String hintTarget) {
        if (consumer == null) {
            throw new BusinessException(409, "缺少领用计划的归属, 不能领用半成品")
                    .withCode(CODE_CONTEXT_REQUIRED)
                    .withHint("请从具体的生产计划里领用半成品")
                    .withHintTarget(hintTarget);
        }
    }

    private BusinessException crossOwner(String factoryId, String what, Owner material, Owner consumer,
                                         String hintTarget) {
        return new BusinessException(409, what + " 是" + describe(material, customerName(factoryId, material))
                + "的料, 不能领进" + describe(consumer, customerName(factoryId, consumer)) + "的计划")
                .withCode(CODE_CROSS_OWNER)
                .withSeverity("BLOCKING")
                .withHint("代工客户的半成品只能用于同一客户的计划; 请改选本计划自己的半成品批次")
                .withHintTarget(hintTarget);
    }

    private String customerName(String factoryId, Owner owner) {
        if (owner == null || !owner.isCustomer() || isBlank(owner.customerId())) {
            return null;
        }
        return customerRepository.findByIdAndFactoryId(owner.customerId(), factoryId)
                .map(c -> c.getName())
                .orElse(null);
    }

    private static String describe(Owner owner, String customerName) {
        if (owner == null || !owner.isCustomer()) {
            return "本厂自有";
        }
        return "客户「" + (isBlank(customerName) ? owner.customerId() : customerName) + "」";
    }

    private static boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
