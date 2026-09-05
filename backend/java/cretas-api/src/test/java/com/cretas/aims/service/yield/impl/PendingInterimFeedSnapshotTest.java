package com.cretas.aims.service.yield.impl;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.PendingInterimFeedService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「待扣投料」读数的两处收口 (2026-09-06, 跨计划半成品抵扣 + 预扣):
 *
 * <ol>
 *   <li><b>{@code stockConsumedAtSubmit} 漂移</b> —— 回料在正式报工事务内已经严格扣减过, 而它所在的行
 *       仍然 {@code interim_settled_at IS NULL}, 于是照样落进 {@code findUnsettledStockFeedRows}。
 *       两条真正的扣减路径都跳过它 ({@code InterimSettleServiceImpl}:293 小结 /
 *       {@code ProductionPlanServiceImpl}:6830 结单), 只有这个聚合器没跳 ⇒ 同一笔货被算两遍。</li>
 *   <li><b>「没有待扣」与「待扣量算不出来」必须分得开</b> —— 在 {@code pendingSemiFeedByBatchNo} 里
 *       两者都是「key 不在 map 里」。盘点侧不减是对的; 投料下拉侧照抄就会把一个「待扣未知」的批次
 *       画成「余量干净」。⇒ {@code Snapshot.unresolvedBatchNos} 把第二种单独端出来。</li>
 * </ol>
 */
@DisplayName("PendingInterimFeed Snapshot — 待扣读数 + 读数的来源标记")
class PendingInterimFeedSnapshotTest {

    private static final String F = "F006";

    private ProcessSheetRowRepository rowRepository;
    private WipInventoryService wipInventoryService;
    private PendingInterimFeedServiceImpl service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        rowRepository = mock(ProcessSheetRowRepository.class);
        wipInventoryService = mock(WipInventoryService.class);
        service = new PendingInterimFeedServiceImpl(rowRepository, wipInventoryService, objectMapper);
    }

    private ProcessSheetRow row(ProcessSheetRowRequest.UpstreamRef... refs) {
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setClientRowId("cr-1");
        req.setProcessCode("chaoshui");
        req.setUpstreamSources(new ArrayList<>(List.of(refs)));
        ProcessSheetRow row = new ProcessSheetRow();
        row.setFactoryId(F);
        row.setPlanId("plan-1");
        try {
            row.setRowPayload(objectMapper.writeValueAsString(req));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return row;
    }

    private ProcessSheetRowRequest.UpstreamRef semiRef(String batchNo, String feedKg, boolean consumedAtSubmit) {
        ProcessSheetRowRequest.UpstreamRef r = new ProcessSheetRowRequest.UpstreamRef();
        r.setSourceBatchNumber(batchNo);
        r.setFeedQuantityKg(new BigDecimal(feedKg));
        r.setSemiFinished(true);
        r.setStockConsumedAtSubmit(consumedAtSubmit);
        return r;
    }

    // ── ① stockConsumedAtSubmit 漂移 ────────────────────────────────────────

    @Test
    @DisplayName("🔴 回料已在报工时扣减 (stockConsumedAtSubmit=true) → 不再算作待扣 (否则同一笔货扣两遍)")
    void reworkConsumedAtSubmit_isNotPending() {
        when(rowRepository.findUnsettledStockFeedRows(F)).thenReturn(List.of(
                row(semiRef("SFI-REWORK", "3", true))));

        PendingInterimFeedService.Snapshot snapshot = service.pendingSemiFeedSnapshot(F);

        assertThat(snapshot.pendingByBatchNo())
                .as("回料在正式报工事务内已经 consumeClerkSemiStrict 扣掉了, 账面已经是扣后的数; "
                        + "再算一次待扣 = 把同一笔货扣两遍 (盘点侧假盘盈 / 下拉侧凭空压低可领量)")
                .isEmpty();
        assertThat(snapshot.unresolvedBatchNos())
                .as("跳过它不是「算不出来」, 别把它标成未知")
                .isEmpty();
        // 连折算都不该调 —— 它根本不在待扣集合里
        verify(wipInventoryService, never())
                .resolveSemiFeedQtyInSourceUnit(eq(F), eq("SFI-REWORK"), any());
    }

    @Test
    @DisplayName("阴性对照: 同一条引用 stockConsumedAtSubmit=false → 照旧算作待扣 (证明上面那条不是恒真)")
    void sameRefWithoutTheFlag_isStillPending() {
        when(rowRepository.findUnsettledStockFeedRows(F)).thenReturn(List.of(
                row(semiRef("SFI-REWORK", "3", false))));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(F, "SFI-REWORK", new BigDecimal("3")))
                .thenReturn(new BigDecimal("3"));

        assertThat(service.pendingSemiFeedSnapshot(F).pendingByBatchNo())
                .containsOnlyKeys("SFI-REWORK");
    }

    @Test
    @DisplayName("同一行里两条引用: 已扣的跳过, 未扣的照算 (不是整行跳过)")
    void mixedRefsInOneRow_onlyTheConsumedOneIsSkipped() {
        when(rowRepository.findUnsettledStockFeedRows(F)).thenReturn(List.of(
                row(semiRef("SFI-DONE", "3", true), semiRef("SFI-TODO", "7", false))));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(F, "SFI-TODO", new BigDecimal("7")))
                .thenReturn(new BigDecimal("7"));

        assertThat(service.pendingSemiFeedSnapshot(F).pendingByBatchNo())
                .containsOnlyKeys("SFI-TODO")
                .containsEntry("SFI-TODO", new BigDecimal("7"));
    }

    // ── ② 「没有待扣」vs「算不出来」 ────────────────────────────────────────

    @Test
    @DisplayName("🔴 折算诚实 null → 不减, 但批次进 unresolvedBatchNos (待扣量【未知】, 不是 0)")
    void conversionNull_isRecordedAsUnresolvedNotAsZero() {
        when(rowRepository.findUnsettledStockFeedRows(F)).thenReturn(List.of(
                row(semiRef("SFI-BOX-NO-GRAMS", "100", false))));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(F, "SFI-BOX-NO-GRAMS", new BigDecimal("100")))
                .thenReturn(null);   // 计数单位来源缺每盒克重 → 诚实 null

        PendingInterimFeedService.Snapshot snapshot = service.pendingSemiFeedSnapshot(F);

        assertThat(snapshot.pendingByBatchNo())
                .as("算不出来就不减 —— 这一点与盘点侧口径不变")
                .isEmpty();
        assertThat(snapshot.unresolvedBatchNos())
                .as("但必须留痕: 下游据此把这一行标成「余量可能偏高」, 而不是当成干净余量")
                .containsExactly("SFI-BOX-NO-GRAMS");
        assertThat(snapshot.isUnresolved("SFI-BOX-NO-GRAMS")).isTrue();
        assertThat(snapshot.pendingOf("SFI-BOX-NO-GRAMS"))
                .as("pendingOf 仍是 0 —— 0 说的是「没有算出来的待扣」, 不是「没有待扣」")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("阴性对照: 折算成功的批次不进 unresolved (证明上面那条不是恒真)")
    void resolvedBatch_isNotMarkedUnresolved() {
        when(rowRepository.findUnsettledStockFeedRows(F)).thenReturn(List.of(
                row(semiRef("SFI-OK", "100", false))));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(F, "SFI-OK", new BigDecimal("100")))
                .thenReturn(new BigDecimal("100"));

        PendingInterimFeedService.Snapshot snapshot = service.pendingSemiFeedSnapshot(F);

        assertThat(snapshot.unresolvedBatchNos()).isEmpty();
        assertThat(snapshot.isUnresolved("SFI-OK")).isFalse();
        assertThat(snapshot.pendingOf("SFI-OK")).isEqualByComparingTo("100");
    }

    // ── ③ 两个出口必须同源 ────────────────────────────────────────────────

    @Test
    @DisplayName("pendingSemiFeedByBatchNo 就是 snapshot 的那一半 (⛔ 不许各遍历一遍)")
    void legacyMapIsTheSnapshotsMap() {
        when(rowRepository.findUnsettledStockFeedRows(F)).thenReturn(List.of(
                row(semiRef("SFI-1", "12", false), semiRef("SFI-2", "5", true))));
        when(wipInventoryService.resolveSemiFeedQtyInSourceUnit(F, "SFI-1", new BigDecimal("12")))
                .thenReturn(new BigDecimal("12"));

        assertThat(service.pendingSemiFeedByBatchNo(F))
                .isEqualTo(service.pendingSemiFeedSnapshot(F).pendingByBatchNo())
                .containsOnlyKeys("SFI-1");
    }

    @Test
    @DisplayName("Snapshot.empty(): 未接线的读数 = 没有待扣, 且没有未知 (向后兼容旧口径)")
    void emptySnapshotReadsAsNoPending() {
        PendingInterimFeedService.Snapshot empty = PendingInterimFeedService.Snapshot.empty();
        assertThat(empty.pendingOf("whatever")).isEqualByComparingTo("0");
        assertThat(empty.isUnresolved("whatever")).isFalse();
        assertThat(empty.pendingOf(null)).isEqualByComparingTo("0");
        assertThat(empty.isUnresolved(null)).isFalse();
    }
}
