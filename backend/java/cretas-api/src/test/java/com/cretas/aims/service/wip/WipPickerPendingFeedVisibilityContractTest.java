package com.cretas.aims.service.wip;

import com.cretas.aims.dto.yield.WipRowDTO;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.service.yield.PendingInterimFeedService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 🔴 跨计划预扣 —— <b>选料下拉里的余量必须是「现在还能领多少」，不是「账面还剩多少」</b>。
 *
 * <h2>它守什么</h2>
 *
 * <p>文员逐道录入是延迟扣减：计划 A 把某个半成品批次录进 {@code upstreamSources} 之后，SFI 的
 * {@code availableQuantity} 要到「小结 / 结单」才真的减。在那之前，计划 B 的操作员在下拉里看到的
 * 是<b>计划 A 已经承诺掉的那部分货</b> —— 两个计划各自录满，后一个到结算时撞
 * {@code SFI_INSUFFICIENT}，而那时活已经干完了。
 *
 * <h2>三条不能省的判据</h2>
 *
 * <ol>
 *   <li><b>阴性对照</b>：没有待扣的行必须<b>一个字都不动</b> —— 而且返回的还得是<b>同一个实例</b>
 *       （见下）。只断言「减对了」而不断言「不该减的没减」，这道闸就分不清是逻辑对还是恒减。</li>
 *   <li><b>⛔ 不许改被 Hibernate 管着的那一行</b>：{@code listAvailableWip} 返回的是持久化上下文里的
 *       实体，在只读查询里改它的 {@code availableQuantity} 会被脏检查<b>写进库</b> —— 那不是显示问题，
 *       那是库存事故。所以被调整过的行必须是脱管副本，原实体的读数必须一动不动。</li>
 *   <li><b>待扣 &gt; 可用不是舍入误差</b>，是账实漂移。floor 到 0 可以，静默 clamp 不行 ——
 *       DTO 上要能读出被减掉的量。</li>
 * </ol>
 *
 * <p><b>单位</b>：待扣量与 {@code availableQuantity} 同为<b>这一行自己的原生单位</b> ——
 * {@code resolveSemiFeedQtyInSourceUnit} 拿同一个 {@code (factoryId, intermediateBatchNo)}
 * 查同一行、走同一段 {@code toSourceUnit} 折算（库上该键对未软删行唯一，2026-09-06 prod 实测 0 条重复）。
 * 下面 {@code countUnitRow_subtractsInBoxesNotKg} 把这条钉住：待扣读数是<b>盒</b>，不是 kg。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("投料下拉预扣可见性 — 别的计划已承诺的量不再出现在余量里")
class WipPickerPendingFeedVisibilityContractTest {

    private static final String FACTORY_ID = "F006";

    @InjectMocks
    private WipInventoryServiceImpl service;

    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private SemiFinishedInventoryTransactionRepository txnRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private BatchLineageEdgeRepository lineageEdgeRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository workProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private ProductProcessWorkflowRepository productProcessWorkflowRepo;
    @Mock private ProductFamilyResolver productFamilyResolver;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PendingInterimFeedService pendingInterimFeedService;

    @BeforeEach
    void wireFieldInjectedCollaborators() {
        ReflectionTestUtils.setField(service, "productProcessWorkflowRepo", productProcessWorkflowRepo);
        ReflectionTestUtils.setField(service, "pendingInterimFeedService", pendingInterimFeedService);
        when(productProcessWorkflowRepo.findByFactoryIdOrderByProductTypeIdAscDefinitionVersionDesc(FACTORY_ID))
                .thenReturn(List.of());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private SemiFinishedInventory row(Long id, String batchNo, String available, String unit) {
        SemiFinishedInventory w = new SemiFinishedInventory();
        w.setId(id);
        w.setFactoryId(FACTORY_ID);
        w.setIntermediateBatchNo(batchNo);
        w.setProductTypeId("PT-1");
        w.setProcessOrder(1);
        w.setProducedQuantity(new BigDecimal(available));
        w.setConsumedQuantity(BigDecimal.ZERO);
        w.setAvailableQuantity(new BigDecimal(available));
        w.setUnit(unit);
        w.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        return w;
    }

    private void pending(Map<String, BigDecimal> byBatchNo, Set<String> unresolved) {
        when(pendingInterimFeedService.pendingSemiFeedSnapshot(FACTORY_ID))
                .thenReturn(new PendingInterimFeedService.Snapshot(byBatchNo, unresolved));
    }

    private WipRowDTO dtoOf(List<WipRowDTO> rows, String batchNo) {
        return rows.stream().filter(r -> batchNo.equals(r.getIntermediateBatchNo())).findFirst().orElseThrow();
    }

    // ── ① 减 + 阴性对照 ──────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 listAvailableWip: 有待扣的行余量减掉待扣; 无待扣的行【原实例】原样返回")
    void subtractsPendingAndLeavesUntouchedRowsAlone() {
        SemiFinishedInventory committed = row(1L, "IB-1", "100", "kg");
        SemiFinishedInventory clean = row(2L, "IB-2", "50", "kg");
        when(wipRepo.findAvailableByFactory(FACTORY_ID)).thenReturn(List.of(committed, clean));
        pending(Map.of("IB-1", new BigDecimal("30")), Set.of());

        List<SemiFinishedInventory> result = service.listAvailableWip(FACTORY_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getAvailableQuantity())
                .as("计划 A 已承诺 30 → 计划 B 只能看到 70")
                .isEqualByComparingTo("70");
        // 🔴 阴性对照 + 不许改被管实体: 没有待扣的那一行连副本都不该造
        assertThat(result.get(1))
                .as("无待扣的行必须是【同一个实例】—— 造副本就说明这条路径在无条件重建对象")
                .isSameAs(clean);
        assertThat(clean.getAvailableQuantity()).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("🔴 被管实体一动不动: 调整只发生在脱管副本上 (改它 = 只读查询里写库存)")
    void managedEntityIsNeverMutated() {
        SemiFinishedInventory managed = row(1L, "IB-1", "100", "kg");
        when(wipRepo.findAvailableByFactory(FACTORY_ID)).thenReturn(List.of(managed));
        pending(Map.of("IB-1", new BigDecimal("30")), Set.of());

        List<SemiFinishedInventory> result = service.listAvailableWip(FACTORY_ID);

        assertThat(managed.getAvailableQuantity())
                .as("repo 返回的那一行是持久化上下文里的实体; 改了它 Hibernate 会在事务结束时 UPDATE 库存")
                .isEqualByComparingTo("100");
        assertThat(result.get(0)).isNotSameAs(managed);
        assertThat(result.get(0).getAvailableQuantity()).isEqualByComparingTo("70");
        // 副本得是【完整】的一行, 不是只有数量的壳 —— createSecondaryPlan 会读 id/batchNo 定位
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getIntermediateBatchNo()).isEqualTo("IB-1");
        assertThat(result.get(0).getUnit()).isEqualTo("kg");
        assertThat(result.get(0).getStatus()).isEqualTo(SemiFinishedInventory.Status.AVAILABLE);
    }

    @Test
    @DisplayName("阴性对照: 整厂没有待扣 → 返回的就是 repo 那个 list 本身 (零加工)")
    void noPendingAtAll_listPassesThroughUnchanged() {
        List<SemiFinishedInventory> fromRepo = List.of(row(1L, "IB-1", "100", "kg"));
        when(wipRepo.findAvailableByFactory(FACTORY_ID)).thenReturn(fromRepo);
        pending(Map.of(), Set.of());

        assertThat(service.listAvailableWip(FACTORY_ID)).isSameAs(fromRepo);
    }

    @Test
    @DisplayName("服务未接线 (null) → 回退旧口径减 0 (既有 @InjectMocks 单测不被这次改动弄红)")
    void serviceNotWired_fallsBackToRawAvailable() {
        ReflectionTestUtils.setField(service, "pendingInterimFeedService", null);
        List<SemiFinishedInventory> fromRepo = List.of(row(1L, "IB-1", "100", "kg"));
        when(wipRepo.findAvailableByFactory(FACTORY_ID)).thenReturn(fromRepo);

        assertThat(service.listAvailableWip(FACTORY_ID)).isSameAs(fromRepo);
    }

    // ── ② 全被认领 / 漂移 ────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 待扣吃光可用 → 该行不再出现在选料下拉里 (账面 >0 但没得领)")
    void fullyCommittedRow_disappearsFromThePicker() {
        when(wipRepo.findAvailableByFactory(FACTORY_ID)).thenReturn(List.of(
                row(1L, "IB-1", "100", "kg"), row(2L, "IB-2", "50", "kg")));
        pending(Map.of("IB-1", new BigDecimal("100")), Set.of());

        assertThat(service.listAvailableWip(FACTORY_ID))
                .extracting(SemiFinishedInventory::getIntermediateBatchNo)
                .containsExactly("IB-2");
    }

    @Test
    @DisplayName("🔴 待扣 > 可用 = 账实漂移: floor 到 0, 但被减掉的量在 DTO 上读得出来 (不静默 clamp)")
    void overdrawn_isFlooredButSurfaced() {
        when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(List.of(row(1L, "IB-1", "10", "kg")));
        pending(Map.of("IB-1", new BigDecimal("25")), Set.of());

        WipRowDTO dto = dtoOf(service.listWipByFactory(FACTORY_ID), "IB-1");

        assertThat(dto.getAvailableQuantity())
                .as("不给下拉一个负数")
                .isEqualByComparingTo("0");
        assertThat(dto.getPendingFeedQuantity())
                .as("🔴 25 必须端出来 —— 只留一个 0 的话, 一次库存事故就被压成「这个批次没货了」")
                .isEqualByComparingTo("25");
        assertThat(dto.getProducedQuantity())
                .as("账面原值仍可从 produced−consumed 还原 (没被这次调整改掉)")
                .isEqualByComparingTo("10");
        assertThat(dto.isPendingFeedExceedsAvailable())
                .as("🔴 界面不能指望有人去读 WARN 日志 —— availableQuantity=0 与「这批货正好领完了」"
                        + "长得一模一样, 靠这一位把「要去查账」和「换个批次继续干」分开")
                .isTrue();
    }

    // ── ③ 单位一致性 ────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 计数单位行: 减掉的是【盒】不是 kg (待扣读数与 availableQuantity 同为本行原生单位)")
    void countUnitRow_subtractsInBoxesNotKg() {
        // 20kg 投料 × (1000/500 每盒克重) = 40 盒 —— 折算已在 resolveSemiFeedQtyInSourceUnit 里做完,
        // 到这一层拿到的就该是 40(盒)。若哪天有人在这层再折一次, 这条断言会红。
        when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(List.of(row(1L, "IB-BOX", "100", "盒")));
        pending(Map.of("IB-BOX", new BigDecimal("40")), Set.of());

        WipRowDTO dto = dtoOf(service.listWipByFactory(FACTORY_ID), "IB-BOX");

        assertThat(dto.getUnit()).isEqualTo("盒");
        assertThat(dto.isPendingFeedExceedsAvailable())
                .as("阴性对照: 待扣 40 < 可用 100, 这是正常预扣不是漂移 —— 标了就是误报")
                .isFalse();
        assertThat(dto.getAvailableQuantity())
                .as("100盒 − 40盒 = 60盒。若这层误把 40 当 kg 再折一次, 结果会是 100−80=20")
                .isEqualByComparingTo("60");
        assertThat(dto.getPendingFeedQuantity()).isEqualByComparingTo("40");
    }

    // ── ④ 算不出来的批次 ────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 折算不出来的批次: 不减, 但标 pendingFeedUnresolved (余量偏高, 不是干净余量)")
    void unresolvedBatch_isFlaggedNotSilentlyTreatedAsClean() {
        when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(List.of(
                row(1L, "IB-UNKNOWN", "100", "盒"), row(2L, "IB-OK", "50", "kg")));
        pending(Map.of("IB-OK", new BigDecimal("5")), Set.of("IB-UNKNOWN"));

        List<WipRowDTO> rows = service.listWipByFactory(FACTORY_ID);

        WipRowDTO unknown = dtoOf(rows, "IB-UNKNOWN");
        assertThat(unknown.isPendingFeedUnresolved())
                .as("🔴 待扣量【未知】必须说出来 —— 否则就是把「我不知道」翻译成「是 0」")
                .isTrue();
        assertThat(unknown.getAvailableQuantity())
                .as("算不出来就不减 (与盘点侧同口径), 所以这个数是偏高的 —— 靠上面那个标记解释")
                .isEqualByComparingTo("100");
        assertThat(unknown.getPendingFeedQuantity()).isEqualByComparingTo("0");

        // 阴性对照: 折算得出来的那一行不该被标
        WipRowDTO ok = dtoOf(rows, "IB-OK");
        assertThat(ok.isPendingFeedUnresolved()).isFalse();
        assertThat(ok.getAvailableQuantity()).isEqualByComparingTo("45");
    }

    @Test
    @DisplayName("阴性对照: 完全没有待扣时, DTO 的两栏是 0/false 且余量与账面相同")
    void noPending_dtoCarriesZeroAndFalse() {
        when(wipRepo.findByFactoryIdForWeightView(FACTORY_ID)).thenReturn(List.of(row(1L, "IB-1", "100", "kg")));
        pending(Map.of(), Set.of());

        WipRowDTO dto = dtoOf(service.listWipByFactory(FACTORY_ID), "IB-1");

        assertThat(dto.getAvailableQuantity()).isEqualByComparingTo("100");
        assertThat(dto.getPendingFeedQuantity()).isEqualByComparingTo("0");
        assertThat(dto.isPendingFeedUnresolved()).isFalse();
        assertThat(dto.isPendingFeedExceedsAvailable()).isFalse();
    }
}
