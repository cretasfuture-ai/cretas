package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionSettlementResponse;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionSettlementRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 查结单状态的<b>读</b>接口不许把「还没有结单行」抛成 404。
 *
 * <h2>客户实报（2026-08-19 10:54~11:15）</h2>
 *
 * <pre>
 * 「核对结单后就报错，之后再来到生产计划页面还是报错」
 * 「小结提交了右上角还是会报错，但是不知道是不是针对于这个新的，还是过往的」
 * 「会重新生成，在筛选『已结单』的时候，『未完成』就不会有」
 * </pre>
 *
 * <p>生产计划列表给每个 {@code COMPLETED} 计划批量调
 * {@code GET /production-plans/{id}/settlement}。列表里有一条<b>过往</b>的
 * 存货生产计划（{@code PLAN-1786724332978}，走「小结」），
 * {@code production_settlements} 里本来就没有行 → 404 → 前端弹全局红条。
 *
 * <p>▎<b>一个合法状态被当成了错误</b>，而且报的是<b>别的计划</b>。
 * 本仓形态 A¹¹：算「缺了什么」之前，先问「这里的空是不是一种合法状态」。
 *
 * <p>prod 复现（只读，三条阳性对照）：
 * <pre>
 * d0c18d36 (COMPLETED/SAFETY_STOCK/无结单行) → 404
 * d0271f84 / 0abc3312 / 61cb04bf (有结单行)   → 200
 * </pre>
 *
 * <p>⚠️ 只改<b>读</b>路径。<b>写</b>路径（确认入库 / 中转挂账桥接 / 停产）照旧 404 ——
 * 那里拦住是对的，下面第二条用例就是钉这个的。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("契约 — 查结单状态的读接口不许把合法空态抛成 404")
class SettlementStatusReadIsNotAnErrorContractTest {

    private static final String FACTORY = "F006";
    private static final String PLAN_ID = "d0c18d36-8d92-4551-baff-70fb70c48874";

    @Mock private ProductionSettlementRepository productionSettlementRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;

    @InjectMocks private ProductionPlanServiceImpl service;

    @BeforeEach
    void wireOptionalRepositories() {
        // productionSettlementRepository 是 @Autowired(required = false) 字段 ——
        // @InjectMocks 走了构造器就不会填它, 不显式注入的话拿到的是「生产结单服务未初始化」。
        ReflectionTestUtils.setField(service, "productionSettlementRepository", productionSettlementRepository);
    }

    private ProductionPlan plan(PlanSourceType sourceType) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY);
        plan.setPlanNumber("PLAN-1786724332978-6013FEBE");
        plan.setStatus(ProductionPlanStatus.COMPLETED);
        plan.setSourceType(sourceType);
        return plan;
    }

    @Test
    @DisplayName("🔴 没有结单行时如实回答 settled=false, ⛔ 不抛 404")
    void readReturnsNotSettledInsteadOfThrowing() {
        when(productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN_ID))
                .thenReturn(Optional.empty());
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY))
                .thenReturn(Optional.of(plan(PlanSourceType.SAFETY_STOCK)));

        ProductionSettlementResponse response = service.getProductionSettlement(FACTORY, PLAN_ID);

        assertNotNull(response, "查状态不该抛 —— 抛了列表就会弹一个红条, 说的还是别的计划");
        assertEquals(Boolean.FALSE, response.getSettled(),
                "必须如实说「没有仓库确认单」, ⛔ 不许留 null 让调用方去猜");
        assertEquals(PLAN_ID, response.getProductionPlanId());
        assertEquals("PLAN-1786724332978-6013FEBE", response.getPlanNumber(),
                "带上计划号, 否则用户不知道弹的是哪一条");
        assertNull(response.getSettlementId(), "没有结单就不该编一个 id 出来");
        assertNull(response.getPostingStatus(), "没有结单就没有入库状态, ⛔ 不许兜一个假状态");
        assertTrue(response.getOutputLines().isEmpty());
        assertTrue(response.getWarnings().isEmpty());
    }

    @Test
    @DisplayName("⛔ 阴性对照: 写路径(中转挂账桥接)对非存货生产计划照旧 404 —— 没被一起放松")
    void writePathStillRefuses() {
        when(productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN_ID))
                .thenReturn(Optional.empty());
        when(productionPlanRepository.findByIdForUpdate(PLAN_ID))
                .thenReturn(Optional.of(plan(PlanSourceType.CUSTOMER_ORDER)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.bridgeByStockSettlement(FACTORY, PLAN_ID),
                "读路径放行不代表写路径也该放行 —— 真要确认入库时, 没有结单就必须拦住");
        assertEquals(404, ex.getCode());
        assertEquals("PRODUCTION_SETTLEMENT_NOT_FOUND", ex.getErrorCode());
        assertNotNull(ex.getActionHint(), "拦住人就要给下一步");
    }

    @Test
    @DisplayName("阳性对照: 计划本身查不到时也不抛, 只是没有计划号可带")
    void unknownPlanStillAnswersInsteadOfThrowing() {
        when(productionSettlementRepository
                .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(FACTORY, PLAN_ID))
                .thenReturn(Optional.empty());
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY))
                .thenReturn(Optional.empty());

        ProductionSettlementResponse response = service.getProductionSettlement(FACTORY, PLAN_ID);

        assertEquals(Boolean.FALSE, response.getSettled());
        assertNull(response.getPlanNumber(), "查不到计划就别编一个号, 留空即可");
        assertFalse(response.getSettled(), "布尔别退化成 null");
    }
}
