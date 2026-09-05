package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.PendingInterimFeedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 🔴🔒 <b>幽灵预扣</b>: 计划没了, 它的待扣投料却永远清不掉
 * (BUG-GHOST-PENDING-FEED-DELETED-PLANS, 2026-09-06)。
 *
 * <h2>缺陷长什么样</h2>
 *
 * <p>{@code findUnsettledStockFeedRows} 的谓词只有一句 {@code interim_settled_at IS NULL} ——
 * 它问「这一行结了没有」, <b>从来不问「这一行还结不结得掉」</b>。而给行盖戳的只有两条路,
 * 两条都要先把计划 load 出来:
 *
 * <ul>
 *   <li>小结 {@code InterimSettleServiceImpl} —— {@code findByIdAndFactoryId} (经
 *       {@code @Where(deleted_at IS NULL)}), 且显式拒绝 {@code COMPLETED/CANCELLED/PENDING_APPROVAL};</li>
 *   <li>结单 {@code ProductionPlanServiceImpl#settleProduction} —— 同样经
 *       {@code @Where}, 且 {@code validateSettlementRequest} 只收 {@code IN_PROGRESS/PENDING}。</li>
 * </ul>
 *
 * <p>⇒ 计划被硬删 (没有行) / 软删 ({@code deleted_at IS NOT NULL}) / 取消 (CANCELLED) 之后,
 * 这两条路<b>都够不着</b>那些行, {@code interim_settled_at} 永久 NULL, 它们贡献的待扣量
 * <b>永远不会清</b>, 而没有任何一个操作能释放它 —— 那个半成品批次的可用量被<b>永久压低</b>,
 * 盘点侧则永久多减一笔。这不是「暂时预扣」, 是账面漏洞。
 *
 * <h2>为什么现在 prod 上看不出来 (⚠️ 不要据此判定它不是缺陷)</h2>
 *
 * <p>2026-09-06 prod 实测: LIUSHANMEN 有 56 行挂在 5 个<b>已硬删</b>的计划上, 而它们碰巧
 * <b>全都已结</b> ⇒ 压根进不了 {@code findUnsettledStockFeedRows} 的谓词, 今天贡献 0。
 * 全 prod <b>唯一</b>一条带 {@code semiFinished:true} 的行 (row 1500) 恰好就在其中一个硬删计划上。
 * ⇒ <b>今天的 0 是运气, 不是设计</b>: 只要有一条这样的行还没结, 它就永久卡住。
 *
 * <h2>这道闸守什么</h2>
 *
 * <p>守的是<b>「结不掉 ⇒ 不算待扣」这条谓词本身</b>, 两侧都钉:
 * <ul>
 *   <li><b>不能漏排</b> —— 硬删/软删/取消上的未结行必须贡献 0 (T1/T2/T3);</li>
 *   <li><b>不能过排</b> —— 活计划 (PENDING/IN_PROGRESS) 上的未结行必须照常贡献 (T4 阴性对照);</li>
 *   <li><b>不能静默</b> —— 剔掉了几行、剔的哪些计划, 必须端出来 (T1/T2/T3 同时断言留痕栏);</li>
 *   <li><b>终态异常要喊出来而不是藏起来</b> —— COMPLETED 上还有未结行是缺陷信号, 照常计入
 *       待扣 + 标记 (T5)。</li>
 * </ul>
 *
 * <p>⚠️ 这里<b>不</b>用 mock 仓库: 软删那一支的正确性完全依赖
 * {@code ProductionPlan} 上的 {@code @Where(deleted_at IS NULL)} 在<b>真实 JPQL 投影</b>里生效 ——
 * 拿 mock 桩出来的「软删计划查不到」是把结论当前提, 一个字都证明不了。
 * 只有 {@link WipInventoryService} (kg → 来源单位折算) 是 mock, 它与本谓词无关。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Transactional
@DisplayName("幽灵预扣: 计划结不掉的行不许算作待扣, 且剔除必须留痕")
class PendingFeedGhostPlanExclusionContractTest {

    private static final String FACTORY_ID = "IT-GHOSTPEND-F";
    /** 折算是 1:1 桩出来的, 所以「贡献的待扣量」== feedQuantityKg。 */
    private static final BigDecimal FEED_KG = new BigDecimal("7");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private ProcessSheetRowRepository rowRepo;
    @Autowired private ProductionPlanRepository planRepo;

    private WipInventoryService wipInventoryService;
    private PendingInterimFeedServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY FALSE");
        wipInventoryService = mock(WipInventoryService.class);
        // 折算恒等 (1 kg → 1 来源单位): 让读数里唯一的变量就是「这一行算不算待扣」。
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(eq(FACTORY_ID), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        service = new PendingInterimFeedServiceImpl(
                rowRepo, wipInventoryService, objectMapper, planRepo);
    }

    // ── T0 阳性对照 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("T0 阳性对照: 活计划上的未结 SFI 投料行确实能被读成待扣 (仪器活着)")
    void positiveControl_livePlanProducesPending() {
        String planId = seedPlan(ProductionPlanStatus.IN_PROGRESS);
        seedUnsettledSemiFeedRow(planId, "SFI-LIVE");

        PendingInterimFeedService.Snapshot snapshot = service.pendingSemiFeedSnapshot(FACTORY_ID);

        assertThat(snapshot.pendingOf("SFI-LIVE"))
                .as("连活计划都读不出待扣 ⇒ 仪器没起来, 下面每一条「贡献 0」的断言都没有意义")
                .isEqualByComparingTo(FEED_KG);
    }

    // ── T1/T2/T3 不能漏排 ──────────────────────────────────────────────────

    @Test
    @DisplayName("T1 🔴 计划已【软删】: 未结行贡献 0, 并被记进 orphan 留痕栏")
    void softDeletedPlan_contributesNothing_andIsCounted() {
        String planId = seedPlan(ProductionPlanStatus.IN_PROGRESS);
        seedUnsettledSemiFeedRow(planId, "SFI-SOFTDEL");
        softDeletePlan(planId);

        PendingInterimFeedService.Snapshot snapshot = service.pendingSemiFeedSnapshot(FACTORY_ID);

        assertThat(snapshot.pendingOf("SFI-SOFTDEL"))
                .as("""
                        软删计划小结/结单都 load 不到 (两条路都经 @Where(deleted_at IS NULL)),
                        这一行永远盖不上戳 ⇒ 它的待扣【永远不会清】, 也没有任何操作能释放它。
                        算作待扣 = 把这个批次的可用量永久压低。""")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(snapshot.unsettlablePlans().orphanRowCount())
                .as("剔了必须留痕 —— 静默剔除与「本来就没有这些行」完全同形")
                .isEqualTo(1);
        assertThat(snapshot.unsettlablePlans().orphanPlanIds()).containsExactly(planId);
        assertThat(snapshot.unresolvedBatchNos())
                .as("⛔ 不许并进 unresolved: 那一栏说「算不出来」(未知), 这里是「确定是 0」(已排除)")
                .doesNotContain("SFI-SOFTDEL");
    }

    @Test
    @DisplayName("T2 🔴 计划已【硬删】(production_plans 里压根没有这行): 贡献 0 + orphan 留痕")
    void hardDeletedPlan_contributesNothing_andIsCounted() {
        String planId = seedPlan(ProductionPlanStatus.IN_PROGRESS);
        seedUnsettledSemiFeedRow(planId, "SFI-HARDDEL");
        hardDeletePlan(planId);

        PendingInterimFeedService.Snapshot snapshot = service.pendingSemiFeedSnapshot(FACTORY_ID);

        assertThat(snapshot.pendingOf("SFI-HARDDEL"))
                .as("prod 实测 LIUSHANMEN 有 5 个硬删计划、56 行残留 —— 只是碰巧全已结")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(snapshot.unsettlablePlans().orphanRowCount()).isEqualTo(1);
        assertThat(snapshot.unsettlablePlans().orphanPlanIds()).containsExactly(planId);
    }

    @Test
    @DisplayName("T3 🔴 计划已【取消】: 贡献 0 + cancelled 留痕 (取消审批路径已冲销恢复过库存)")
    void cancelledPlan_contributesNothing_andIsCounted() {
        String planId = seedPlan(ProductionPlanStatus.CANCELLED);
        seedUnsettledSemiFeedRow(planId, "SFI-CANCELLED");

        PendingInterimFeedService.Snapshot snapshot = service.pendingSemiFeedSnapshot(FACTORY_ID);

        assertThat(snapshot.pendingOf("SFI-CANCELLED"))
                .as("""
                        CANCELLED 是终态: 小结显式拒绝它, 结单只收 IN_PROGRESS/PENDING
                        ⇒ 永远盖不上戳。而取消审批路径本身已把报工冲销、库存恢复,
                        再算一次待扣就是对同一笔货预扣两遍。""")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(snapshot.unsettlablePlans().cancelledRowCount()).isEqualTo(1);
        assertThat(snapshot.unsettlablePlans().cancelledPlanIds()).containsExactly(planId);
        assertThat(snapshot.unsettlablePlans().orphanRowCount())
                .as("取消不是「计划够不着」—— 两个原因分开计数, 否则日志指不到正确的方向")
                .isZero();
    }

    // ── T4 阴性对照: 不能过排 ──────────────────────────────────────────────

    @Test
    @DisplayName("T4 🔴 阴性对照: 活计划 (PENDING / IN_PROGRESS) 上的未结行必须【照常】贡献待扣")
    void livePlans_stillContribute_andAreNotCounted() {
        String pendingPlan = seedPlan(ProductionPlanStatus.PENDING);
        String inProgressPlan = seedPlan(ProductionPlanStatus.IN_PROGRESS);
        seedUnsettledSemiFeedRow(pendingPlan, "SFI-PENDING");
        seedUnsettledSemiFeedRow(inProgressPlan, "SFI-INPROGRESS");

        PendingInterimFeedService.Snapshot snapshot = service.pendingSemiFeedSnapshot(FACTORY_ID);

        assertThat(snapshot.pendingOf("SFI-PENDING"))
                .as("过度排除会把真实的延迟扣减抹掉 ⇒ 盘点账面 stale-high ⇒ 假盘亏 + 小结二次扣减")
                .isEqualByComparingTo(FEED_KG);
        assertThat(snapshot.pendingOf("SFI-INPROGRESS")).isEqualByComparingTo(FEED_KG);
        assertThat(snapshot.unsettlablePlans().totalExcludedRows())
                .as("一行都不该被剔")
                .isZero();
        assertThat(snapshot.unsettlablePlans().isSilent())
                .as("没剔任何东西也没有异常 ⇒ 这一次快照不该打那行 WARN")
                .isTrue();
    }

    // ── T5 COMPLETED: 钉住那个决定 ─────────────────────────────────────────

    @Test
    @DisplayName("T5 🔴 计划已 COMPLETED 却还有未结行: 【不剔除】, 照常计入待扣 + 标记为异常")
    void completedPlanWithUnsettledRow_isFlaggedButNotDropped() {
        String planId = seedPlan(ProductionPlanStatus.COMPLETED);
        seedUnsettledSemiFeedRow(planId, "SFI-COMPLETED");

        PendingInterimFeedService.Snapshot snapshot = service.pendingSemiFeedSnapshot(FACTORY_ID);

        assertThat(snapshot.pendingOf("SFI-COMPLETED"))
                .as("""
                        结单会在同一事务里给本计划全部未结行盖戳, 所以 COMPLETED 上还有未结行只有两种可能:
                        (a) 盖戳漏了 —— 那是缺陷, 剔掉就把它藏起来了;
                        (b) 被撤回冲销后重新变回待结 —— 那待扣是【真的】(货已退回库存等重结), 剔掉就是幻库存。
                        两种都不该静默丢 ⇒ 照常计入, 只把计划 id 喊出来。
                        prod 2026-09-06 实测 F006 确有 1 个 COMPLETED 计划挂着 2 行未结。""")
                .isEqualByComparingTo(FEED_KG);
        assertThat(snapshot.unsettlablePlans().anomalousTerminalPlanIds()).containsExactly(planId);
        assertThat(snapshot.unsettlablePlans().totalExcludedRows())
                .as("标记 ≠ 排除")
                .isZero();
        assertThat(snapshot.unsettlablePlans().isSilent())
                .as("有异常就必须打那行 WARN, 否则这个缺陷信号没人看得到")
                .isFalse();
    }

    // ── T6 混合: 剔除只影响该剔的那些行 ────────────────────────────────────

    @Test
    @DisplayName("T6 混合一锅: 剔除是逐行的, 不许把同一次快照里的活行一起带走")
    void mixedFactory_excludesOnlyTheGhostRows() {
        String livePlan = seedPlan(ProductionPlanStatus.IN_PROGRESS);
        String cancelledPlan = seedPlan(ProductionPlanStatus.CANCELLED);
        String deletedPlan = seedPlan(ProductionPlanStatus.IN_PROGRESS);
        seedUnsettledSemiFeedRow(livePlan, "SFI-MIX-LIVE");
        seedUnsettledSemiFeedRow(cancelledPlan, "SFI-MIX-CANCELLED");
        seedUnsettledSemiFeedRow(deletedPlan, "SFI-MIX-GONE");
        hardDeletePlan(deletedPlan);

        PendingInterimFeedService.Snapshot snapshot = service.pendingSemiFeedSnapshot(FACTORY_ID);

        assertThat(snapshot.pendingByBatchNo())
                .as("只有活计划那一行留下 —— 键集合逐个点名, 不只看条数")
                .containsOnlyKeys("SFI-MIX-LIVE");
        assertThat(snapshot.unsettlablePlans().totalExcludedRows()).isEqualTo(2);
        assertThat(snapshot.unsettlablePlans().orphanPlanIds()).containsExactly(deletedPlan);
        assertThat(snapshot.unsettlablePlans().cancelledPlanIds()).containsExactly(cancelledPlan);
    }

    // ── T7 口径一致: 两个出口读的是同一次遍历 ──────────────────────────────

    @Test
    @DisplayName("T7 pendingSemiFeedByBatchNo 与 snapshot 同口径 (剔除不许只修一个出口)")
    void bothExitsShareTheSameExclusion() {
        String gonePlan = seedPlan(ProductionPlanStatus.IN_PROGRESS);
        seedUnsettledSemiFeedRow(gonePlan, "SFI-BOTH-EXITS");
        hardDeletePlan(gonePlan);

        assertThat(service.pendingSemiFeedByBatchNo(FACTORY_ID))
                .as("盘点侧走的是这个出口 (SemiFinishedStocktakeServiceImpl); "
                        + "只修 snapshot 会让同一个口径漂成两份")
                .isEqualTo(service.pendingSemiFeedSnapshot(FACTORY_ID).pendingByBatchNo())
                .doesNotContainKey("SFI-BOTH-EXITS");
    }

    // ── helpers ──

    private String seedPlan(ProductionPlanStatus status) {
        String planId = "IT-GHOST-PLAN-" + UUID.randomUUID().toString().substring(0, 12);
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("IT-GHOST-" + UUID.randomUUID().toString().substring(0, 8));
        plan.setProductTypeId("IT-GHOST-PT");
        plan.setPlannedQuantity(new BigDecimal("1000"));
        plan.setPlannedUnit("kg");
        plan.setStatus(status);
        plan.setSourceType(PlanSourceType.SAFETY_STOCK);
        plan.setCreatedBy(1L);
        plan.setIsLocked(false);
        plan.setSkipProcessReporting(false);
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());
        planRepo.saveAndFlush(plan);
        return planId;
    }

    /** 一条未结 (interim_settled_at IS NULL) 的 SFI 投料行。 */
    private void seedUnsettledSemiFeedRow(String planId, String sourceBatchNo) {
        ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
        ref.setSourceBatchNumber(sourceBatchNo);
        ref.setFeedQuantityKg(FEED_KG);
        ref.setSemiFinished(true);
        ref.setStockConsumedAtSubmit(false);   // 不是回料, 走延迟扣减那条路

        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("ghost-" + sourceBatchNo);
        req.setProcessCode("chaoshui");
        req.setUpstreamSources(new ArrayList<>(List.of(ref)));

        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(FACTORY_ID);
        row.setPlanId(planId);
        row.setProcessCode("chaoshui");
        row.setProcessOrder(1);
        row.setClientRowId("ghost-" + sourceBatchNo);
        row.setRowStatus(ProcessSheetRow.STATUS_SAVED_SFI);
        row.setInterimSettledAt(null);
        try {
            row.setRowPayload(objectMapper.writeValueAsString(req));
        } catch (Exception e) {
            throw new IllegalStateException("测试夹具序列化失败", e);
        }
        rowRepo.saveAndFlush(row);
    }

    /**
     * 软删计划。走 JDBC 而不是 {@code plan.setDeletedAt(...)}: 后者要经过 Hibernate 的
     * 脏检查与 {@code @Where}, 用它来<b>制造</b>被 {@code @Where} 过滤的状态是循环论证。
     */
    private void softDeletePlan(String planId) {
        int n = jdbcTemplate.update(
                "UPDATE production_plans SET deleted_at = ? WHERE id = ?", LocalDateTime.now(), planId);
        assertThat(n).as("夹具没生效: 软删没打中任何一行").isEqualTo(1);
        detachEverything();
    }

    private void hardDeletePlan(String planId) {
        int n = jdbcTemplate.update("DELETE FROM production_plans WHERE id = ?", planId);
        assertThat(n).as("夹具没生效: 硬删没打中任何一行").isEqualTo(1);
        detachEverything();
    }

    /** 让后续查询真的打到库, 而不是命中一级缓存里那份还活着的计划。 */
    private void detachEverything() {
        entityManager.flush();
        entityManager.clear();
    }
}
