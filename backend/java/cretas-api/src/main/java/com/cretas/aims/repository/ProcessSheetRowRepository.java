package com.cretas.aims.repository;

import com.cretas.aims.entity.processentry.ProcessSheetRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * SP-F Task 1.1: ProcessSheetRow repository.
 *
 * <p>主键方法 findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId 对应 uk_sheet_row 唯一约束,
 * 供后续 upsert 端点使用。
 */
public interface ProcessSheetRowRepository extends JpaRepository<ProcessSheetRow, Long> {

    Optional<ProcessSheetRow> findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
            String factoryId, String planId, String processCode, String clientRowId);

    /**
     * 正式提交前加行锁读取已存在的行 (缺口 13 第三轮审查 M-2): 「已正式提交就 409」是先查再改,
     * 不加锁时两个并发重放都读到「还没提交」, 双双入账、CCP 也记两条。锁住之后后到的那个等前一个提交完,
     * 再读到 SUBMITTED 被拒。行不存在时由 uk_sheet_row 唯一约束挡并发插入 (既有「该行已存在 (并发提交)」)。
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ProcessSheetRow r WHERE r.factoryId = :factoryId AND r.planId = :planId "
            + "AND r.processCode = :processCode AND r.clientRowId = :clientRowId")
    Optional<ProcessSheetRow> findForUpdateByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
            @org.springframework.data.repository.query.Param("factoryId") String factoryId,
            @org.springframework.data.repository.query.Param("planId") String planId,
            @org.springframework.data.repository.query.Param("processCode") String processCode,
            @org.springframework.data.repository.query.Param("clientRowId") String clientRowId);

    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndProcessCode(
            String factoryId, String planId, String processCode);

    /**
     * SP-F role-mode fix: 双键查询 (factory, plan, processCode, processOrder)。
     *
     * <p>role-mode 下多道普通工序共享同一 archetype processCode (如 'chaoshui'),
     * 仅 processCode 过滤会把多道的行/库存混在一起。加 processOrder (链内唯一) 后
     * 每道分别隔离。仅当 processOrder 非空时由 service 调用; 旧客户端不传 processOrder
     * 时回退到 code-only finder (向后兼容)。
     */
    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndProcessCodeAndProcessOrder(
            String factoryId, String planId, String processCode, Integer processOrder);

    List<ProcessSheetRow> findByFactoryIdAndPlanId(
            String factoryId, String planId);

    /**
     * 按 (factory, plan, processOrder) 取一道的行。processOrder 链内唯一, 已足够隔离各道;
     * ⛔ 不再拿 processCode 一起过滤 —— processCode 是前端按工序名 / 投入形态推出来的 archetype,
     * 推导规则一变, 旧行的 processCode 就对不上 (2026-10-25 F006 L4: 分割 4 行存的是 shuzhi,
     * 现在页面按 chaoshui 查 ⇒ 分割 tab 显示「待报工」, 下游来源批次为空)。
     */
    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndProcessOrder(
            String factoryId, String planId, Integer processOrder);

    /**
     * R4 (2026-07-04): 按物化后的 {@code batchId} (= ProductionBatch.id) 查行 —— 成本拆分
     * ({@code OrderCostBreakdownService}) 逐批读该批 process-row 的 SFI/FG 投料边, 补计投料成本。
     * factory-scoped 🔒。纯 SFI 中间道 (SAVED_SFI, batchId==null) 不物化, 不在此集合内 (其成本经
     * 下游道 semiFinished 投料的 getSemiUnitCost 移动均价传导, 无需在此单独读)。
     */
    List<ProcessSheetRow> findByFactoryIdAndBatchId(String factoryId, Long batchId);

    List<ProcessSheetRow> findByFactoryIdAndBatchIdIn(String factoryId, Collection<Long> batchIds);

    /**
     * SP-F Task 1.8: 按 (factory, plan, clientRowId) 查行 —— delete 端点路径不含 processCode。
     * 正常情况下 clientRowId 在同一 plan 内跨工序不重复，返回 1 条；边缘情形返多条则全删。
     */
    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndClientRowId(
            String factoryId, String planId, String clientRowId);

    /**
     * 撤销小结: 查某次小结 (postedAt 时间戳) 打戳的全部产出行 (interim_settled_at == postedAt)。
     * 逆转时逐行清 interim_settled_at → 行恢复未结 (可再编辑/删除/重新小结)。
     */
    List<ProcessSheetRow> findByFactoryIdAndPlanIdAndInterimSettledAt(
            String factoryId, String planId, LocalDateTime interimSettledAt);

    /**
     * 🔴🔒🔒 延迟扣减盲区 (sibling of #1216/#1219, 2026-07-05 根因闭合): 查整厂全部<b>待结</b>
     * (interim_settled_at IS NULL) 逐工序行 —— 覆盖<b>所有计划族</b> (SAFETY_STOCK 待小结 + 结单族结单前)。
     *
     * <p>用途: 结算前, SFI/FG 投料 (row_payload.upstreamSources 的 semiFinished/finishedGoods 引用)
     * 已物理消耗常驻半成品/成品库存, 但 {@code availableQuantity/producedQuantity} 要到结算才扣减:
     * SAFETY_STOCK 走「小结」({@code InterimSettleServiceImpl} §②), 结单族走「结单」
     * ({@code ProductionPlanServiceImpl.deductProcessSheetStockFeeds}, R2) —— 都是延迟扣减。
     * 半成品盘点 / 成品销售发货 / 调拨 须减去这些「待扣」投料量, 否则拿 stale-high 账面 → 假盘亏(SFI)
     * 双重扣减 或 超发/超调 → 结算阶段 {@code SFI_INSUFFICIENT / FG_INSUFFICIENT} 409 卡死。
     *
     * <p><b>2026-07-05 移除 SAFETY_STOCK <u>族</u>门控</b>: 旧口径把结单族排除, 错误假设「结单族行早已落库扣减」——
     * 实为结单<b>前</b>尚未扣减 (延迟扣减), 排除 → mid-production 窗口 stale-high 假盘亏。结单族结单时经
     * {@link #stampInterimSettledForPlan} 打戳 → {@code interim_settled_at IS NOT NULL} 天然落谓词外, 不双减;
     * SAFETY_STOCK 经小结打戳同理。故 IS NULL 现对所有族统一 = 「尚未结算、待扣」。Factory-scoped 防跨租户。
     */
    /*
     * 🔴🔒 本查询只回答「这一行结了没有」, ⛔ 不回答「这一行还结不结得掉」
     * (BUG-GHOST-PENDING-FEED-DELETED-PLANS, 2026-09-06)。计划被硬删/软删/取消之后, 小结与结单
     * 两条打戳路径都够不着这些行 ⇒ 它们永久 interim_settled_at IS NULL ⇒ 贡献的待扣量【永远不会清】,
     * 而没有任何操作能释放它 —— 那个批次的可用量被永久压低。
     * ⇒ 这一层【刻意不加】计划连接: 排除必须【连同计数一起】发生, 否则静默剔除与「本来就没有这些行」
     * 完全同形。排除谓词与留痕都在唯一消费方 PendingInterimFeedServiceImpl#pendingSemiFeedSnapshot 里,
     * 由 PendingFeedGhostPlanExclusionContractTest 钉住「本方法只有那一个生产调用方」。
     * ⛔ 新增调用方之前先读那道闸 —— 直接用本方法的原始结果 = 把幽灵预扣再造一遍。
     */
    @Query("SELECT r FROM ProcessSheetRow r "
            + "WHERE r.factoryId = :factoryId AND r.interimSettledAt IS NULL")
    List<ProcessSheetRow> findUnsettledStockFeedRows(@org.springframework.data.repository.query.Param("factoryId") String factoryId);

    /**
     * 🔴🔒🔒 结单族「结单即打戳」根修 (2026-07-05, 镜像 {@code MaterialConsumptionRepository.stampInterimSettledForPlan}
     * 的 process_sheet_rows 侧): 把本计划全部<b>未结</b> (interim_settled_at IS NULL) 逐工序行一次性盖为
     * {@code settledAt} (= 结单时间戳)。返回受影响行数。
     *
     * <p><b>为何需要</b>: SFI/FG 投料的延迟扣减对 SAFETY_STOCK 由「小结」打戳产出行 (见
     * {@code InterimSettleServiceImpl} 逐 UnsettledRow setInterimSettledAt); 结单族走「结单」经
     * {@code deductProcessSheetStockFeeds} 扣减 SFI/FG 却<b>从不</b>回头给 process_sheet_rows 打戳 → 其行永久
     * {@code interim_settled_at IS NULL}。若 {@link #findUnsettledStockFeedRows} 去掉族门控却不在结单时打戳,
     * 结单族行会<b>永久</b>计入 pending → 结单后 (SFI/FG 已扣) 仍被减 → 双减。此处在结单时打戳, 使 IS NULL
     * 对所有族统一代表「待扣」。⚠️ 仅结单族 (非 SAFETY_STOCK) 调用: SAFETY_STOCK 打戳由小结原子完成,
     * 提前打戳会让小结漏处理产出行 → 漏入库。幂等 (已戳不再戳), planId 直接定位 (行有 plan_id 列),
     * factory-scoped 防跨租户。{@code deletedAt IS NULL} 显式附加 (bulk UPDATE 绕过类级 {@code @Where})。
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE ProcessSheetRow r SET r.interimSettledAt = :settledAt "
            + "WHERE r.factoryId = :factoryId AND r.planId = :planId "
            + "AND r.interimSettledAt IS NULL AND r.deletedAt IS NULL")
    int stampInterimSettledForPlan(@org.springframework.data.repository.query.Param("factoryId") String factoryId,
                                   @org.springframework.data.repository.query.Param("planId") String planId,
                                   @org.springframework.data.repository.query.Param("settledAt") java.time.LocalDateTime settledAt);
}
