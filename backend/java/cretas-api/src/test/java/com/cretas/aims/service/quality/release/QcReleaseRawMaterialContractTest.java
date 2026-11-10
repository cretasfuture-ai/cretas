package com.cretas.aims.service.quality.release;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.quality.QcReleaseRecord;
import com.cretas.aims.event.MaterialBatchReleasedEvent;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-S411-04: 原料收货批次处于「质检中」时, 质量角色在「质检放行」里放行 / 判不合格。
 *
 * <ul>
 *   <li>a 待检清单列出本厂原料 INSPECTING 批 (物料名、批次号、数量单位)</li>
 *   <li>b 放行 → AVAILABLE + 发 MaterialBatchReleasedEvent (缺料实例据此重算) + 写放行记录</li>
 *   <li>c 非 INSPECTING 原料批放行被拒且不变 (不落库、不发事件、不写记录)</li>
 *   <li>d 别厂批次 404 且不变</li>
 *   <li>e 原有在制 / 成品放行行为不变; 原料判不合格 = DEFECTIVE 且不发放行事件</li>
 * </ul>
 * 仓储是桩; 查询口径在 {@code QcReleaseRepositoryQueryValidationTest} (H2) 与
 * {@code QcReleaseRawMaterialPostgresContractTest} (真 PG) 上执行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("质检放行: 原料收货批次 (D-S411-04)")
class QcReleaseRawMaterialContractTest {

    private static final String F = "F-RAW";
    private static final String OTHER = "F-RAW-OTHER";

    @Mock private SemiFinishedInventoryRepository semiRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private FinishedGoodsBatchRepository fgRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private QcReleaseRecordRepository recordRepo;
    @Mock private RawMaterialTypeRepository rawTypeRepo;
    @Mock private ApplicationEventPublisher publisher;

    private QcReleaseService service;

    @BeforeEach
    void setUp() {
        service = new QcReleaseService(semiRepo, materialBatchRepo, fgRepo, productTypeRepo, recordRepo);
        service.setRawMaterialTypeRepo(rawTypeRepo);
        service.setEventPublisher(publisher);
        when(recordRepo.save(any(QcReleaseRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchRepo.save(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        when(fgRepo.save(any(FinishedGoodsBatch.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static MaterialBatch batch(String id, String factory, String sourceDocType, MaterialBatchStatus status) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(factory);
        b.setBatchNumber("MT-" + id);
        b.setMaterialTypeId("RMT-PORK");
        b.setReceiptQuantity(new BigDecimal("120"));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(new BigDecimal("20"));
        b.setQuantityUnit("kg");
        b.setSourceDocType(sourceDocType);
        b.setStatus(status);
        return b;
    }

    private void lockable(MaterialBatch b) {
        when(materialBatchRepo.findByIdAndFactoryIdForUpdate(b.getId(), b.getFactoryId())).thenReturn(Optional.of(b));
    }

    private QcReleaseRecord decide(String factory, String type, String id, String decision) {
        return service.decide(factory, new QcReleaseService.DecisionCommand(type, id, decision, "感官检验合格"), 9L, "王质检");
    }

    @Test
    @DisplayName("a 待检清单: 原料 INSPECTING 批带物料名 / 批次号 / 货架实物量 (含预留) / 单位; 别厂物料名不显示")
    void pendingListIncludesInspectingRawBatches() {
        MaterialBatch raw = batch("R1", F, "PURCHASE_RECEIVE", MaterialBatchStatus.INSPECTING);
        MaterialBatch empty = batch("R0", F, "PURCHASE_RECEIVE", MaterialBatchStatus.INSPECTING);
        empty.setUsedQuantity(new BigDecimal("120"));       // 货已全部出库: 没有货可放, 不进待办
        when(materialBatchRepo.findRawBatchesByFactoryIdAndStatus(F, MaterialBatchStatus.INSPECTING))
                .thenReturn(List.of(raw, empty));
        RawMaterialType pork = new RawMaterialType();
        pork.setId("RMT-PORK");
        pork.setFactoryId(F);
        pork.setName("冻猪蹄");
        when(rawTypeRepo.findByIdIn(any())).thenReturn(List.of(pork));

        List<QcReleaseService.PendingItem> items = service.listPending(F);

        assertThat(items).hasSize(1);
        QcReleaseService.PendingItem it = items.get(0);
        assertThat(it.targetType()).isEqualTo(QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH);
        assertThat(it.targetId()).isEqualTo("R1");
        assertThat(it.formLabel()).isEqualTo("原料");
        assertThat(it.batchNumber()).isEqualTo("MT-R1");
        assertThat(it.productName()).isEqualTo("冻猪蹄");
        assertThat(it.materialTypeId()).isEqualTo("RMT-PORK");
        assertThat(it.quantity()).isEqualByComparingTo("120");
        assertThat(it.unit()).isEqualTo("kg");
        assertThat(it.openDeviationCount()).isZero();
        verify(materialBatchRepo).findRawBatchesByFactoryIdAndStatus(F, MaterialBatchStatus.INSPECTING);

        // 物料字典按 id 批量取不带工厂: 别厂的同 id 物料名不能显示到本厂
        pork.setFactoryId(OTHER);
        assertThat(service.listPending(F).get(0).productName()).isNull();
    }

    @Test
    @DisplayName("b 放行 → AVAILABLE + 发 MaterialBatchReleasedEvent (工厂/物料/批次) + 写放行记录")
    void releasePublishesShortageEventAndRecords() {
        MaterialBatch raw = batch("R1", F, "PURCHASE_RECEIVE", MaterialBatchStatus.INSPECTING);
        lockable(raw);

        QcReleaseRecord rec = decide(F, QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, "R1", "RELEASED");

        assertThat(raw.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
        verify(materialBatchRepo).save(raw);
        ArgumentCaptor<ApplicationEvent> event = ArgumentCaptor.forClass(ApplicationEvent.class);
        verify(publisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOfSatisfying(MaterialBatchReleasedEvent.class, e -> {
            assertThat(e.getFactoryId()).isEqualTo(F);
            assertThat(e.getMaterialTypeId()).isEqualTo("RMT-PORK");
            assertThat(e.getMaterialBatchId()).isEqualTo("R1");
        });
        assertThat(rec.getTargetType()).isEqualTo(QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH);
        assertThat(rec.getTargetId()).isEqualTo("R1");
        assertThat(rec.getDecision()).isEqualTo(QcReleaseRecord.Decision.RELEASED);
        assertThat(rec.getBatchNumber()).isEqualTo("MT-R1");
        assertThat(rec.getQuantity()).isEqualByComparingTo("120");
        assertThat(rec.getUnit()).isEqualTo("kg");
        assertThat(rec.getReleasedBy()).isEqualTo(9L);
        assertThat(rec.getFactoryId()).isEqualTo(F);
    }

    @Test
    @DisplayName("b' 判不合格 → DEFECTIVE, 不发放行事件, 写 REJECTED 记录")
    void rejectMarksDefectiveWithoutReleaseEvent() {
        MaterialBatch raw = batch("R1", F, null, MaterialBatchStatus.INSPECTING);   // sourceDocType 为空的老数据也是原料
        lockable(raw);

        QcReleaseRecord rec = decide(F, QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, "R1", "REJECTED");

        assertThat(raw.getStatus()).isEqualTo(MaterialBatchStatus.DEFECTIVE);
        verify(materialBatchRepo).save(raw);
        verify(publisher, never()).publishEvent(any(ApplicationEvent.class));
        verify(publisher, never()).publishEvent(any(Object.class));
        assertThat(rec.getDecision()).isEqualTo(QcReleaseRecord.Decision.REJECTED);
    }

    @Test
    @DisplayName("c 不是质检中的原料批 (可用 / 已判不合格 / 已用完) 放行被拒, 状态不变, 不落库不发事件不写记录")
    void nonInspectingRawBatchIsRefusedAndUnchanged() {
        for (MaterialBatchStatus s : List.of(MaterialBatchStatus.AVAILABLE, MaterialBatchStatus.DEFECTIVE,
                MaterialBatchStatus.USED_UP)) {
            MaterialBatch raw = batch("R-" + s, F, "PURCHASE_RECEIVE", s);
            lockable(raw);
            assertThatThrownBy(() -> decide(F, QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, raw.getId(), "RELEASED"))
                    .as("status %s", s)
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_NOT_PENDING);
                    });
            assertThat(raw.getStatus()).isEqualTo(s);
        }
        verify(materialBatchRepo, never()).save(any(MaterialBatch.class));
        verify(recordRepo, never()).save(any(QcReleaseRecord.class));
        verify(publisher, never()).publishEvent(any(ApplicationEvent.class));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("c' 在制批次不能冒充原料放行 (RAW_MATERIAL_BATCH 指向 PRODUCTION_BATCH 批 → 拒, 不变)")
    void wipBatchCannotBeReleasedAsRaw() {
        MaterialBatch wip = batch("W1", F, QcInspectionGuard.WIP_SOURCE_DOC_TYPE, MaterialBatchStatus.INSPECTING);
        lockable(wip);
        assertThatThrownBy(() -> decide(F, QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, "W1", "RELEASED"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_NOT_PENDING));
        assertThat(wip.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        verify(materialBatchRepo, never()).save(any(MaterialBatch.class));
    }

    @Test
    @DisplayName("d 别厂批次: 本厂账号放行 → 404, 别厂批次状态不变, 不落库不发事件不写记录")
    void otherFactoryBatchIsNotFoundAndUnchanged() {
        MaterialBatch foreign = batch("R-X", OTHER, "PURCHASE_RECEIVE", MaterialBatchStatus.INSPECTING);
        lockable(foreign);                                                   // 只在 (R-X, OTHER) 下查得到
        when(materialBatchRepo.findById("R-X")).thenReturn(Optional.of(foreign));   // 不带工厂的查法能拿到它

        assertThatThrownBy(() -> decide(F, QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, "R-X", "RELEASED"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(404);
                    assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_NOT_PENDING);
                });
        assertThat(foreign.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        verify(materialBatchRepo, never()).save(any(MaterialBatch.class));
        verify(recordRepo, never()).save(any(QcReleaseRecord.class));
        verify(publisher, never()).publishEvent(any(ApplicationEvent.class));
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("e 原有形态不变: 在制批次放行 AVAILABLE、成品放行 AVAILABLE; 原料不混进在制/成品的口径")
    void existingWipAndFinishedGoodsUnchanged() {
        MaterialBatch wip = batch("W1", F, QcInspectionGuard.WIP_SOURCE_DOC_TYPE, MaterialBatchStatus.INSPECTING);
        lockable(wip);
        decide(F, QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, "W1", "RELEASED");
        assertThat(wip.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);

        // 原料批不能按在制批次放行 (在制口径没被放宽)
        MaterialBatch raw = batch("R1", F, "PURCHASE_RECEIVE", MaterialBatchStatus.INSPECTING);
        lockable(raw);
        assertThatThrownBy(() -> decide(F, QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH, "R1", "RELEASED"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(QcReleaseService.CODE_NOT_PENDING));
        assertThat(raw.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);

        FinishedGoodsBatch fg = new FinishedGoodsBatch();
        fg.setId("FG1");
        fg.setFactoryId(F);
        fg.setBatchNumber("FG-1");
        fg.setProducedQuantity(BigDecimal.TEN);
        fg.setShippedQuantity(BigDecimal.ZERO);
        fg.setReservedQuantity(BigDecimal.ZERO);
        fg.setStatus(FinishedGoodsBatch.Status.PENDING_INSPECTION);
        when(fgRepo.findByIdAndFactoryIdForUpdate("FG1", F)).thenReturn(Optional.of(fg));
        decide(F, QcReleaseRecord.TargetType.FINISHED_GOODS, "FG1", "RELEASED");
        assertThat(fg.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);

        // 待检清单里在制 / 成品照旧, 原料查询不影响它们
        when(materialBatchRepo.findWipBatchesByFactoryIdAndStatus(F, MaterialBatchStatus.INSPECTING))
                .thenReturn(List.of(batch("W2", F, QcInspectionGuard.WIP_SOURCE_DOC_TYPE, MaterialBatchStatus.INSPECTING)));
        assertThat(service.listPending(F)).extracting(QcReleaseService.PendingItem::targetType)
                .containsExactly(QcReleaseRecord.TargetType.WIP_MATERIAL_BATCH);
    }

    @Test
    @DisplayName("没注入事件发布器时原料放行直接报错, 不静默放行而不通知缺料实例")
    void missingPublisherFailsLoud() {
        service.setEventPublisher(null);
        MaterialBatch raw = batch("R1", F, "PURCHASE_RECEIVE", MaterialBatchStatus.INSPECTING);
        lockable(raw);
        assertThatThrownBy(() -> decide(F, QcReleaseRecord.TargetType.RAW_MATERIAL_BATCH, "R1", "RELEASED"))
                .isInstanceOf(BusinessException.class);
        assertThat(raw.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        verify(materialBatchRepo, never()).save(any(MaterialBatch.class));
    }
}
