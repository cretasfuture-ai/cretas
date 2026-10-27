package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.processentry.FinishedGoodsStockItem;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsAdjustmentLogRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.InternalTransferItemRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.cretas.aims.service.inventory.impl.FinishedGoodsFeedServiceImpl;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard.Owner;
import com.cretas.aims.service.wip.ProductFamilyResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 缺口 16 跨客户领料 —— 成品作投料来源, 用<b>真的</b>归属闸 (只 mock 仓库)。
 *
 * <p>阴性: 代工客户的成品投进本厂计划 ⇒ 409 {@code INTERMEDIATE_CROSS_OWNER_FORBIDDEN}, 产量一点没减;
 * 本厂成品投进代工计划同样拒。阳性: 同归属照常扣。下拉: 带领用归属只列同归属, 不带全列。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FinishedGoodsFeedCrossCustomerTest - 成品投料跨客户领料 (缺口 16)")
class FinishedGoodsFeedCrossCustomerTest {

    private static final String F = "LIUSHANMEN";
    private static final String CUST_A = "eb0254ca-4f5a-4062-b81f-de1e8a45fb60";
    private static final Owner OWNER_A = new Owner(InventoryOwnership.CUSTOMER_OWNED, CUST_A);

    @Mock private FinishedGoodsBatchRepository fgRepo;
    @Mock private ProductFamilyResolver productFamilyResolver;
    @Mock private FinishedGoodsAdjustmentLogRepository adjustmentLogRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private InternalTransferItemRepository transferItemRepo;
    @Mock private InternalTransferRepository transferRepo;
    @Mock private ProductionPlanRepository planRepo;
    @Mock private ProductionBatchRepository batchRepo;
    @Mock private CustomerRepository customerRepo;

    private FinishedGoodsFeedServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FinishedGoodsFeedServiceImpl(fgRepo, productFamilyResolver, adjustmentLogRepo,
                productTypeRepo, transferItemRepo, transferRepo,
                new IntermediateOwnershipGuard(planRepo, batchRepo, customerRepo));
    }

    private FinishedGoodsBatch fg(String no, InventoryOwnership ownership, String customerId) {
        FinishedGoodsBatch b = new FinishedGoodsBatch();
        b.setFactoryId(F);
        b.setBatchNumber(no);
        b.setProductTypeId("PT-FG");
        b.setProducedQuantity(new BigDecimal("10"));
        b.setShippedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setUnit("kg");
        b.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        b.setOwnership(ownership);
        b.setOwnerCustomerId(customerId);
        when(fgRepo.findByFactoryIdAndBatchNumberForUpdate(F, no)).thenReturn(Optional.of(b));
        return b;
    }

    private static void assertCrossOwner(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(IntermediateOwnershipGuard.CODE_CROSS_OWNER));
    }

    @Test
    @DisplayName("客户 A 的成品不能投进本厂计划, 产量一点没减; 投进 A 自己的计划照常扣")
    void customerGoodsIntoCompanyPlanRejected() {
        FinishedGoodsBatch a = fg("FG-A", InventoryOwnership.CUSTOMER_OWNED, CUST_A);
        assertCrossOwner(() -> service.consumeForFeedStrict(F, Owner.COMPANY, "FG-A", new BigDecimal("3"), "kg"));
        assertThat(a.getProducedQuantity()).isEqualByComparingTo("10");

        assertThat(service.consumeForFeedStrict(F, OWNER_A, "FG-A", new BigDecimal("3"), "kg"))
                .isEqualByComparingTo("3");
        assertThat(a.getProducedQuantity()).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("本厂成品不能投进代工计划; 旧行 (归属 null) 按本厂读")
    void companyGoodsIntoTollPlanRejected() {
        FinishedGoodsBatch own = fg("FG-C", InventoryOwnership.COMPANY_OWNED, null);
        FinishedGoodsBatch legacy = fg("FG-L", null, null);
        assertCrossOwner(() -> service.consumeForFeedStrict(F, OWNER_A, "FG-C", new BigDecimal("1"), "kg"));
        assertCrossOwner(() -> service.consumeForFeedStrict(F, OWNER_A, "FG-L", new BigDecimal("1"), "kg"));
        assertThat(own.getProducedQuantity()).isEqualByComparingTo("10");
        assertThat(legacy.getProducedQuantity()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("投料下拉: 带领用归属只列同归属的成品; 不带 = 全列")
    void dropdownFiltersByOwner() {
        FinishedGoodsBatch a = fg("FG-A", InventoryOwnership.CUSTOMER_OWNED, CUST_A);
        FinishedGoodsBatch c = fg("FG-C", InventoryOwnership.COMPANY_OWNED, null);
        FinishedGoodsBatch legacy = fg("FG-L", null, null);
        when(fgRepo.findAvailableForFeedByFactory(F)).thenReturn(List.of(a, c, legacy));

        assertThat(service.listAvailableForFeed(F, null, OWNER_A))
                .extracting(FinishedGoodsStockItem::getBatchNumber).containsExactly("FG-A");
        assertThat(service.listAvailableForFeed(F, null, Owner.COMPANY))
                .extracting(FinishedGoodsStockItem::getBatchNumber).containsExactly("FG-C", "FG-L");
        assertThat(service.listAvailableForFeed(F, null)).hasSize(3);
    }
}
