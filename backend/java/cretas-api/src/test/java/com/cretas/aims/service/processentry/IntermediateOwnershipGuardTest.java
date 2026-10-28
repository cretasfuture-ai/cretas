package com.cretas.aims.service.processentry;

import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.CustomerRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.processentry.IntermediateOwnershipGuard.Owner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺口 16 跨客户领料: 半成品 / 在制品归属闸的口径。
 *
 * <p>每条拒绝配一条放行: 拒绝要看错误码 (证明是这道闸拦的), 放行证明闸没把同归属的正常领用也拦掉。
 */
@DisplayName("IntermediateOwnershipGuard - 半成品归属闸 (缺口 16)")
class IntermediateOwnershipGuardTest {

    private static final String F = "LIUSHANMEN";
    private static final String CUST_A = "eb0254ca-4f5a-4062-b81f-de1e8a45fb60";
    private static final String CUST_B = "015b3329-4112-4f6c-96ce-4f2b65ab0fe9";

    private ProductionPlanRepository planRepo;
    private ProductionBatchRepository batchRepo;
    private CustomerRepository customerRepo;
    private IntermediateOwnershipGuard guard;

    @BeforeEach
    void setUp() {
        planRepo = mock(ProductionPlanRepository.class);
        batchRepo = mock(ProductionBatchRepository.class);
        customerRepo = mock(CustomerRepository.class);
        guard = new IntermediateOwnershipGuard(planRepo, batchRepo, customerRepo);
        Customer a = new Customer();
        a.setId(CUST_A);
        a.setName("六膳门代工客户");
        when(customerRepo.findByIdAndFactoryId(CUST_A, F)).thenReturn(Optional.of(a));
    }

    private static ProductionPlan tollPlan(String id, String customerId) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(F);
        p.setPlanNumber("PLAN-" + id);
        p.setOutputOwnership(InventoryOwnership.CUSTOMER_OWNED);
        p.setCustomerId(customerId);
        return p;
    }

    private static ProductionPlan companyPlan(String id) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(F);
        p.setPlanNumber("PLAN-" + id);
        p.setOutputOwnership(InventoryOwnership.COMPANY_OWNED);
        // 普通销售订单计划也带 customerId —— 归属看 outputOwnership, 不看有没有客户
        p.setCustomerId(CUST_B);
        return p;
    }

    private static SemiFinishedInventory semi(String no, InventoryOwnership ownership, String customerId) {
        return SemiFinishedInventory.builder().factoryId(F).intermediateBatchNo(no)
                .ownership(ownership).ownerCustomerId(customerId).build();
    }

    private static Owner customer(String id) {
        return new Owner(InventoryOwnership.CUSTOMER_OWNED, id);
    }

    private static void assertCrossOwner(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(409);
                    assertThat(be.getErrorCode()).isEqualTo(IntermediateOwnershipGuard.CODE_CROSS_OWNER);
                });
    }

    @Test
    @DisplayName("计划归属: 代工 = 客户所有; 普通销售带客户也是公司自有; 代工缺客户 ⇒ 409 不猜")
    void ownerOfPlan() {
        assertThat(IntermediateOwnershipGuard.ofPlan(tollPlan("p1", CUST_A))).isEqualTo(customer(CUST_A));
        assertThat(IntermediateOwnershipGuard.ofPlan(companyPlan("p2"))).isEqualTo(Owner.COMPANY);
        assertThat(IntermediateOwnershipGuard.ofPlan(null)).isEqualTo(Owner.COMPANY);
        ProductionPlan legacy = companyPlan("p3");
        legacy.setOutputOwnership(null);
        assertThat(IntermediateOwnershipGuard.ofPlan(legacy)).isEqualTo(Owner.COMPANY);
        assertThatThrownBy(() -> IntermediateOwnershipGuard.ofPlan(tollPlan("p4", " ")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(IntermediateOwnershipGuard.CODE_CONTEXT_REQUIRED));
    }

    @Test
    @DisplayName("客户 A 的半成品领进本厂自有计划 ⇒ 409, 消息点名客户")
    void customerSemiIntoCompanyPlanRejected() {
        SemiFinishedInventory a = semi("S-A", InventoryOwnership.CUSTOMER_OWNED, CUST_A);
        assertCrossOwner(() -> guard.assertSemiDrawable(F, Owner.COMPANY, a, "sourceWipNo"));
        assertThatThrownBy(() -> guard.assertSemiDrawable(F, Owner.COMPANY, a, "sourceWipNo"))
                .hasMessageContaining("六膳门代工客户");
    }

    @Test
    @DisplayName("客户 A 的半成品领进客户 B 的计划 ⇒ 409; 领进客户 A 自己的计划 ⇒ 放行")
    void customerToOtherCustomerRejectedSameCustomerAllowed() {
        SemiFinishedInventory a = semi("S-A", InventoryOwnership.CUSTOMER_OWNED, CUST_A);
        assertCrossOwner(() -> guard.assertSemiDrawable(F, customer(CUST_B), a, "x"));
        assertThatCode(() -> guard.assertSemiDrawable(F, customer(CUST_A), a, "x")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("本厂自有半成品领进代工计划 ⇒ 409 (与原料主料同一立场); 领进本厂计划 ⇒ 放行; 旧行 null 按本厂读")
    void companySemi() {
        SemiFinishedInventory own = semi("S-C", InventoryOwnership.COMPANY_OWNED, null);
        SemiFinishedInventory legacy = semi("S-L", null, null);
        assertCrossOwner(() -> guard.assertSemiDrawable(F, customer(CUST_A), own, "x"));
        assertCrossOwner(() -> guard.assertSemiDrawable(F, customer(CUST_A), legacy, "x"));
        assertThatCode(() -> guard.assertSemiDrawable(F, Owner.COMPANY, own, "x")).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertSemiDrawable(F, Owner.COMPANY, legacy, "x")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("领用方归属缺失 ⇒ 409, 不当成放行")
    void missingConsumerRejected() {
        SemiFinishedInventory own = semi("S-C", InventoryOwnership.COMPANY_OWNED, null);
        assertThatThrownBy(() -> guard.assertSemiDrawable(F, null, own, "x"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(IntermediateOwnershipGuard.CODE_CONTEXT_REQUIRED));
    }

    @Test
    @DisplayName("在制批次: 归属看产出它的生产批次的计划")
    void wipBatchOwnerFromSourcePlan() {
        when(planRepo.findByIdAndFactoryId("toll", F)).thenReturn(Optional.of(tollPlan("toll", CUST_A)));
        ProductionBatch src = new ProductionBatch();
        src.setId(11L);
        src.setProductionPlanId("toll");
        assertCrossOwner(() -> guard.assertWipBatchDrawable(F, Owner.COMPANY, src, "WIP-1", "x"));
        assertThatCode(() -> guard.assertWipBatchDrawable(F, customer(CUST_A), src, "WIP-1", "x"))
                .doesNotThrowAnyException();
        ProductionBatch noPlan = new ProductionBatch();
        noPlan.setId(12L);
        assertThatCode(() -> guard.assertWipBatchDrawable(F, Owner.COMPANY, noPlan, "WIP-2", "x"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("生产批次 → 计划 → 归属; 批次不存在 ⇒ 409")
    void ofProductionBatch() {
        ProductionBatch b = new ProductionBatch();
        b.setId(7L);
        b.setProductionPlanId("toll");
        when(batchRepo.findByIdAndFactoryId(7L, F)).thenReturn(Optional.of(b));
        when(planRepo.findByIdAndFactoryId("toll", F)).thenReturn(Optional.of(tollPlan("toll", CUST_A)));
        assertThat(guard.ofProductionBatch(F, 7L)).isEqualTo(customer(CUST_A));
        assertThat(guard.ofProductionBatch(F, null)).isEqualTo(Owner.COMPANY);
        when(batchRepo.findByIdAndFactoryId(8L, F)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.ofProductionBatch(F, 8L)).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("逐道录入锚 CLK-SEMI-<计划前8>-: 唯一命中取该计划; 不唯一 ⇒ 409; 占位段 ⇒ 本厂")
    void ofClerkAnchor() {
        when(planRepo.findByFactoryIdAndIdStartingWith(F, "20341065"))
                .thenReturn(List.of(tollPlan("20341065-d63d", CUST_A)));
        assertThat(guard.ofClerkAnchor(F, "CLK-SEMI-20341065-c76f35be")).isEqualTo(customer(CUST_A));
        when(planRepo.findByFactoryIdAndIdStartingWith(F, "deadbeef"))
                .thenReturn(List.of(companyPlan("deadbeef-1"), companyPlan("deadbeef-2")));
        assertThatThrownBy(() -> guard.ofClerkAnchor(F, "CLK-SEMI-deadbeef-c76f35be"))
                .isInstanceOf(BusinessException.class);
        assertThat(guard.ofClerkAnchor(F, "CLK-SEMI-00000000-c76f35be")).isEqualTo(Owner.COMPANY);
    }

    @Test
    @DisplayName("产品码共用行: 本厂沿用产品码 (存量零变化), 客户另起 <产品码>@<客户前8>")
    void pooledRowKey() {
        assertThat(IntermediateOwnershipGuard.pooledRowKey("PTSEMI-LSM-0005", Owner.COMPANY))
                .isEqualTo("PTSEMI-LSM-0005");
        assertThat(IntermediateOwnershipGuard.pooledRowKey("PTSEMI-LSM-0005", customer(CUST_A)))
                .isEqualTo("PTSEMI-LSM-0005@eb0254ca");
    }

    @Test
    @DisplayName("入账: 空归属行盖上本次产出的归属; 已有归属且不同 ⇒ 409 (一行只装一个主人的料)")
    void rowCreditable() {
        SemiFinishedInventory fresh = semi("S", null, null);
        IntermediateOwnershipGuard.assertRowCreditable(fresh, customer(CUST_A));
        assertThat(fresh.getOwnership()).isEqualTo(InventoryOwnership.CUSTOMER_OWNED);
        assertThat(fresh.getOwnerCustomerId()).isEqualTo(CUST_A);

        assertThatCode(() -> IntermediateOwnershipGuard.assertRowCreditable(fresh, customer(CUST_A)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> IntermediateOwnershipGuard.assertRowCreditable(fresh, Owner.COMPANY))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(IntermediateOwnershipGuard.CODE_POOL_OWNER_MISMATCH));
    }
}
