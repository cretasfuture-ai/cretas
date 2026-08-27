package com.cretas.aims.service.factory;

import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryStocktakeItem;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.service.factory.impl.FactoryStocktakeServiceImpl;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 契约：盘点提交进 OA 时，随流程带过去的上下文里必须有<b>可判的差异量</b>。
 *
 * <h4>为什么钉这个</h4>
 *
 * <p>2026-08-27 实测: 采购与调拨各有一个 {@code buildXxxWorkflowContext} 带 {@code amount},
 * 它们的条件节点靠它做金额分级(采购 ¥30000 / 销售 ¥5000)。
 * 而盘点原本是手写的 {@code Map.of} 三个字段(单号/期间/仓库) —— <b>一个可判的量都没有</b>。</p>
 *
 * <p>⇒ 「盘亏多少以内厂长就能过」这种分级<b>不是没人配, 是配了也没得判</b>。
 * 本测试钉住那些变量确实被发出去了。</p>
 *
 * <h4>它守的是「发出去了」, 不是「有人在判」</h4>
 *
 * <p>⚠️ 条件节点是 SpEL, 每个 context 键都会变成一个变量
 * ({@code #shortageCount > 5}, {@code #shortageQuantityByUnit['kg'] > 100})。
 * 但当前 F006 那条已发布的流程<b>没有 condition 节点</b> —— 所以这些变量现在
 * 没有消费方。这是有意的: 先把可判的量发出去, 阈值怎么定是产品决定。
 * ⛔ 不要因为「现在没人用」就把它们删掉 —— 删了就退回「配了也没得判」。</p>
 *
 * <h4>⛔ 为什么不断言金额</h4>
 *
 * <p>盘点明细上<b>没有金额</b>。要算差异金额得 join 批次单价, 那是在造第二份
 * 「这批货值多少」的口径, 而本仓对成本口径已经有主。这里只钉<b>已经存在且精确</b>的量。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("盘点提交进 OA 时必须带上可判的差异量")
class StocktakeWorkflowContextContractTest {

    private static final String FACTORY = "F006";
    private static final String STOCKTAKE = "ST-1";

    @Mock private FactoryStocktakeRepository stocktakeRepo;
    @Mock private WorkflowEngineService workflowEngine;

    @InjectMocks private FactoryStocktakeServiceImpl service;

    @Captor private ArgumentCaptor<Map<String, Object>> contextCaptor;

    private FactoryStocktake stocktake;

    @BeforeEach
    void setUp() {
        stocktake = new FactoryStocktake();
        stocktake.setId(STOCKTAKE);
        stocktake.setFactoryId(FACTORY);
        stocktake.setStocktakeNo("ST-202608-TEST");
        stocktake.setPeriodMonth("2026-08");
        stocktake.setWarehouseId("WH-1");
        stocktake.setStatus(FactoryStocktake.Status.COUNTING);
        stocktake.setItems(new ArrayList<>());
        when(stocktakeRepo.findByIdAndFactoryIdForUpdate(STOCKTAKE, FACTORY)).thenReturn(Optional.of(stocktake));
        when(stocktakeRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        // ⚠️ workflowEngine 在 service 里是 @Autowired(required=false) 的**字段**,
        //    而 @InjectMocks 一旦走了构造器注入就不再做字段注入 —— 不显式设进去,
        //    service 会在 `workflowEngine == null` 那里抛 OA_RUNTIME_UNAVAILABLE,
        //    于是这组测试全部红在一个与被测行为无关的地方。
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "workflowEngine", workflowEngine);
    }

    /** 账面 system / 实盘 actual —— 差异由服务端自己算(actual − system), ⛔ 不信前端传的。 */
    private void item(String unit, String system, String actual) {
        FactoryStocktakeItem it = new FactoryStocktakeItem();
        it.setId("IT-" + stocktake.getItems().size());
        it.setQuantityUnit(unit);
        it.setSystemQty(new BigDecimal(system));
        it.setActualQty(new BigDecimal(actual));
        stocktake.getItems().add(it);
    }

    private Map<String, Object> submitAndCaptureContext() {
        ApprovalWorkflowInstance instance = new ApprovalWorkflowInstance();
        instance.setId("WF-1");
        when(workflowEngine.startWorkflowIfConfigured(
                eq(FACTORY), eq("INVENTORY_ADJUSTMENT"), eq(STOCKTAKE), contextCaptor.capture(), anyLong()))
                .thenReturn(Optional.of(instance));
        service.submitForApproval(STOCKTAKE, FACTORY, 42L);
        return contextCaptor.getValue();
    }

    @Test
    @DisplayName("阳性对照: 上下文确实被捕获到了, 而且不是空的")
    void contextIsActuallyCaptured() {
        item("kg", "100", "100");
        Map<String, Object> ctx = submitAndCaptureContext();
        assertThat(ctx)
                .as("一个键都没捕获到 —— 那后面所有断言都不作数")
                .isNotEmpty();
        assertThat(ctx).containsKey("stocktakeNo");
    }

    @Test
    @DisplayName("🔴 有差异时, 条件节点判得到条数")
    void differenceCountsAreAvailableToConditions() {
        item("kg", "100", "108");    // 盘盈 +8
        item("kg", "50", "42");      // 盘亏 -8
        item("件", "10", "7");       // 盘亏 -3
        item("kg", "20", "20");      // 无差异

        Map<String, Object> ctx = submitAndCaptureContext();

        assertThat(ctx.get("totalCount")).isEqualTo(4);
        assertThat(ctx.get("surplusCount")).isEqualTo(1);
        assertThat(ctx.get("shortageCount")).isEqualTo(2);
        assertThat(ctx.get("matchCount")).isEqualTo(1);
        assertThat(ctx.get("differenceCount")).isEqualTo(3);
        assertThat(ctx.get("hasDifference")).isEqualTo(true);
    }

    @Test
    @DisplayName("🔴 数量按单位分开发 —— ⛔ 1kg + 1件 不许加成 2")
    void quantitiesAreKeyedByUnitNeverSummedAcrossUnits() {
        item("kg", "100", "108");    // 盘盈 kg +8
        item("kg", "50", "42");      // 盘亏 kg -8
        item("件", "10", "7");       // 盘亏 件 -3

        Map<String, Object> ctx = submitAndCaptureContext();

        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> surplus = (Map<String, BigDecimal>) ctx.get("surplusQuantityByUnit");
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> shortage = (Map<String, BigDecimal>) ctx.get("shortageQuantityByUnit");

        assertThat(surplus).containsOnlyKeys("kg");
        assertThat(surplus.get("kg")).isEqualByComparingTo("8");
        // 盘亏两个单位各自成条, ⛔ 不是 11
        assertThat(shortage).containsOnlyKeys("kg", "件");
        assertThat(shortage.get("kg")).isEqualByComparingTo("8");
        assertThat(shortage.get("件")).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("零差异时 hasDifference=false, 且两个数量表是空的(不是缺键)")
    void zeroDifferenceStillReportsTheShape() {
        item("kg", "100", "100");
        item("件", "10", "10");

        Map<String, Object> ctx = submitAndCaptureContext();

        assertThat(ctx.get("hasDifference")).isEqualTo(false);
        assertThat(ctx.get("differenceCount")).isEqualTo(0);
        // ⚠️ 键必须在: SpEL 引用一个不存在的变量会求值失败并**静默当 false**,
        //    那样「零差异」和「表达式写错了」就分不开了。
        assertThat(ctx).containsKeys("surplusQuantityByUnit", "shortageQuantityByUnit");
        assertThat((Map<?, ?>) ctx.get("surplusQuantityByUnit")).isEmpty();
        assertThat((Map<?, ?>) ctx.get("shortageQuantityByUnit")).isEmpty();
    }

    @Test
    @DisplayName("差异由服务端按 实盘−账面 重算 —— ⛔ 不信前端传来的 differenceQty")
    void differenceIsRecomputedServerSide() {
        item("kg", "100", "108");
        // 前端谎报成 0
        stocktake.getItems().get(0).setDifferenceQty(BigDecimal.ZERO);

        // normalizeAndValidateEvidence 会发现漂移并拒绝 —— 这正是它该做的
        org.assertj.core.api.Assertions.assertThatThrownBy(this::submitAndCaptureContext)
                .hasMessageContaining("漂移");
    }
}
