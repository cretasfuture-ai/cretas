package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.TransferItemType;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.enums.TransferType;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.entity.inventory.InternalTransferItem;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.InternalTransferItemRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.MaterialBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调拨确认时，调入方新建的批次必须能追回调拨单。
 *
 * <h2>它守什么</h2>
 *
 * <p>{@code createTargetInventory} 直接 {@code new MaterialBatch()} + {@code repo.save()}，
 * 绕开 {@code MaterialBatchServiceImpl.createMaterialBatch} 上那道
 * 「入库必须有发起单」的 fail-closed 守卫。2026-08-25 实测：它
 * {@code source_doc_type} / {@code source_doc_id} <b>一个字都不写</b>，
 * 于是调入建出来的批次<b>追不回它是哪张调拨单进来的</b>。</p>
 *
 * <p>这与 {@code TransferServiceImpl} 里那条「2026-08-14 生产实测：调拨扣减此前
 * 完全不写流水，100 个活跃批次因此『库存少了但说不出去哪了』」是<b>同一件事的两侧</b>：
 * 出库侧的账当时补上了，入库侧的来源一直没补。</p>
 *
 * <h2>为什么写入是行为中性的</h2>
 *
 * <p>全仓按值消费 {@code sourceDocType} 的只有三处
 * （{@code ProductionStockAllocationServiceImpl} 与 {@code OrderCostBreakdownService} ×2），
 * 全部是与 {@code "PRODUCTION_BATCH"} 比相等；而
 * {@code "PRODUCTION_BATCH".equals(null)} 与
 * {@code "PRODUCTION_BATCH".equals("TRANSFER_IN")} 同为 {@code false}，
 * 且没有任何 JPQL/native 查询按这一列过滤 ⇒ 把原本的 {@code null} 写成
 * {@code TRANSFER_IN} 不改变任何既有行为，只是把追溯补上。</p>
 */
@DisplayName("调拨确认：调入批次必须带来源单据")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferTargetBatchSourceDocContractTest {

    @Mock private InternalTransferRepository transferRepository;
    @Mock private InternalTransferItemRepository transferItemRepository;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;

    private TransferServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TransferServiceImpl(
                transferRepository,
                transferItemRepository,
                materialBatchRepository,
                finishedGoodsBatchRepository,
                applicationEventPublisher,
                materialBatchService,
                rawMaterialTypeRepository);
    }

    /**
     * 跨厂调拨（RECEIVED → 确认）最短路径。
     *
     * <p>刻意走跨厂而非同厂：{@code confirmTransfer} 只在<b>同厂</b>分支里先调
     * {@code deductSourceInventory}（要 FEFO 源批次、消耗流水、低库存事件一整套桩），
     * 跨厂分支则直接进 {@code createTargetInventory} —— 而本文件要守的正是后者。
     * 用最短的那条路，桩少、噪声小。</p>
     *
     * <p>显式设 {@code targetWarehouseId}，走非 null 分支，避开只在 Spring 里注入的
     * {@code warehouseResolver}（7 参构造器里没有它，为 null）。</p>
     */
    private InternalTransfer buildReceivedCrossFactoryTransfer(TransferItemType itemType) {
        InternalTransferItem item = new InternalTransferItem();
        item.setId(10L);
        item.setTransferId("TRF-001");
        item.setItemType(itemType);
        item.setMaterialTypeId("MT-001");
        item.setQuantity(new BigDecimal("100"));
        item.setReceivedQuantity(new BigDecimal("100"));
        item.setUnit("kg");

        InternalTransfer transfer = new InternalTransfer();
        transfer.setId("TRF-001");
        transfer.setTransferNumber("TR-001");
        transfer.setSourceFactoryId("F001");          // 跨厂
        transfer.setTargetFactoryId("F006");
        transfer.setSourceWarehouseId("WH-SRC");
        transfer.setTargetWarehouseId("WH-DST");      // 非 null, 绕开 warehouseResolver
        transfer.setStatus(TransferStatus.RECEIVED);
        transfer.setTransferType(TransferType.HQ_TO_BRANCH);
        transfer.getItems().add(item);
        return transfer;
    }

    private MaterialBatch confirmAndCaptureTargetBatch(InternalTransfer transfer) {
        when(transferRepository.findByIdAndEitherFactoryId("TRF-001", "F006"))
                .thenReturn(Optional.of(transfer));
        when(transferRepository.save(any(InternalTransfer.class))).thenAnswer(i -> i.getArgument(0));
        when(transferItemRepository.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));

        service.confirmTransfer("F006", "TRF-001", 1L);

        ArgumentCaptor<MaterialBatch> captor = ArgumentCaptor.forClass(MaterialBatch.class);
        verify(materialBatchRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }

    @Test
    @DisplayName("原料调入：批次写 sourceDocType=TRANSFER_IN 且 sourceDocId=调拨单号")
    void rawMaterialTransferInCarriesSourceDoc() {
        MaterialBatch saved = confirmAndCaptureTargetBatch(
                buildReceivedCrossFactoryTransfer(TransferItemType.RAW_MATERIAL));

        assertThat(saved.getSourceDocType())
                .as("没有它, 调入建出来的批次追不回是哪张调拨单")
                .isEqualTo("TRANSFER_IN");
        assertThat(saved.getSourceDocId())
                .as("只有类型没有单号, 追溯仍然断在这里")
                .isEqualTo("TRF-001");
    }

    @Test
    @DisplayName("包材调入：同样带来源单据（两种 itemType 都走这条路）")
    void packagingTransferInCarriesSourceDoc() {
        MaterialBatch saved = confirmAndCaptureTargetBatch(
                buildReceivedCrossFactoryTransfer(TransferItemType.PACKAGING_MATERIAL));

        assertThat(saved.getSourceDocType()).isEqualTo("TRANSFER_IN");
        assertThat(saved.getSourceDocId()).isEqualTo("TRF-001");
    }

    /**
     * 阳性对照：确认这条路<b>真的</b>建了批次并落库。
     *
     * <p>没有它，上面两条断言若因为「根本没走到建批次那一步」而失败，
     * 会被读成「来源没写对」而不是「压根没建」—— 两者的修法完全不同。</p>
     */
    @Test
    @DisplayName("阳性对照 — 确认时确实建了目标批次（工厂/物料/数量都对）")
    void confirmActuallyCreatesTargetBatch() {
        MaterialBatch saved = confirmAndCaptureTargetBatch(
                buildReceivedCrossFactoryTransfer(TransferItemType.RAW_MATERIAL));

        assertThat(saved.getFactoryId()).isEqualTo("F006");
        assertThat(saved.getMaterialTypeId()).isEqualTo("MT-001");
        assertThat(saved.getReceiptQuantity()).isEqualByComparingTo("100");
        assertThat(saved.getWarehouseId()).isEqualTo("WH-DST");
    }
}
