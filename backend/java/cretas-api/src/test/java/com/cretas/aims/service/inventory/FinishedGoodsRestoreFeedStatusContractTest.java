package com.cretas.aims.service.inventory;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.InternalTransferItemRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.inventory.impl.FinishedGoodsFeedServiceImpl;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 缺口 11 审查中 6 —— 撤销小结把成品投料还回 ({@link FinishedGoodsFeedServiceImpl#restoreForFeed}) 只能把
 * 「因量耗尽而变」的状态 (DEPLETED / REVERSED) 恢复成可售; 待检、不良品、冻结、过期是质量 / 业务判定, 不许被洗成可售。
 *
 * <p>每行同一个输入 (70 → 还回 30), 只改起始状态; 前两行是阳性 (恢复), 其余是阴性 (原样)。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("撤销小结还回成品: 只恢复耗尽类状态, 不洗白待检/不良品")
class FinishedGoodsRestoreFeedStatusContractTest {

    private static final String FACTORY = "F006";
    private static final String BATCH = "FG-RF-001";

    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private ProductFamilyResolver productFamilyResolver;
    @Mock private FinishedGoodsAdjustmentLogRepository finishedGoodsAdjustmentLogRepository;
    @Mock private InternalTransferItemRepository internalTransferItemRepository;
    @Mock private InternalTransferRepository internalTransferRepository;
    @InjectMocks private FinishedGoodsFeedServiceImpl service;

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "DEPLETED, AVAILABLE",
            "REVERSED, AVAILABLE",
            "PENDING_INSPECTION, PENDING_INSPECTION",
            "DEFECTIVE, DEFECTIVE",
            "FROZEN, FROZEN",
            "EXPIRED, EXPIRED",
            "AVAILABLE, AVAILABLE",
    })
    void restoreOnlyRevivesQuantityDrivenStates(String before, String expected) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setId("fg-rf");
        b.setFactoryId(FACTORY);
        b.setBatchNumber(BATCH);
        b.setProductTypeId("PT1");
        b.setProducedQuantity(new BigDecimal("70"));
        b.setShippedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setUnit("kg");
        b.setStatus(before);
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumberForUpdate(FACTORY, BATCH))
                .thenReturn(Optional.of(b));

        service.restoreForFeed(FACTORY, BATCH, new BigDecimal("30"), 7L);

        // 必然发生的读数: 量一定还回了 (否则「状态没变」可能只是根本没跑到)
        assertThat(b.getProducedQuantity()).isEqualByComparingTo("100");
        assertThat(b.getStatus()).as("起始 " + before).isEqualTo(expected);
    }
}
