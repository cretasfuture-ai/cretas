package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CreateReceiveRecordRequest;
import com.cretas.aims.entity.Supplier;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.repository.inventory.PurchaseReceiveRecordRepository;
import com.cretas.aims.service.inventory.impl.PurchaseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 采购超收上限【默认值与优先级梯子】的回归测试。
 *
 * <h3>它守的是哪个行为</h3>
 * owner 2026-08-25 裁定：<b>默认允许无限超收</b>，配了才拦，超出的打回。
 * 在此之前 {@code @Value("${cretas.purchase.over-receive-rate:0.00}")} 的默认是
 * {@code 0.00}，而该属性在 {@code src/main/resources}、生产 {@code .env.prod}、
 * systemd unit 里<b>都没有设过</b> —— 于是生产上一件都不能多收，而 Workdesk 界面
 * 还在告诉仓管员"30% 超收上限 = 130 箱"。供应商送 105 箱，仓管员点确认收货吃 409，
 * 货堵在门口。
 *
 * <h3>变异对照（怎么证明这些断言不是恒真式）</h3>
 * 把默认改回 0.00 —— 即在 {@link OverReceiveLimit#resolve} 的兜底分支写
 * {@code return of(BigDecimal.ZERO, SOURCE_UNCONFIGURED);} ——
 * {@link Ladder#unconfigured_meansUnlimited_notZero} 与
 * {@link Ladder#unconfigured_acceptsFarBeyondOrderedQty} 必须变红。
 * ⛔ 变异不红时先证明变异到达了被测代码，再下"断言没用"的结论。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("采购超收上限: 默认不限制 + 工厂级/全局属性优先级梯子")
class PurchaseServiceImplOverReceiveDefaultTest {

    @Mock private PurchaseOrderRepository purchaseOrderRepository;
    @Mock private PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Mock private PurchaseReceiveRecordRepository receiveRecordRepository;
    @Mock private SupplierRepository supplierRepository;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock private PurchaseOverReceivePolicy overReceivePolicy;

    private PurchaseServiceImpl service;

    private static final String FACTORY_ID = "F002";
    private static final String SUPPLIER_ID = "SUP-001";
    private static final String PO_ID = "PO-001";
    private static final String MAT_ID = "MAT-001";
    private static final String MAT_NAME = "辣椒";

    /** 超收上限拒绝时 message 里必然出现的串 —— 用它把"被上限拦下"与"因别的原因失败"分开。 */
    private static final String CAP_REJECTION_MARKER = "超出可入库上限";

    @BeforeEach
    void setUp() {
        service = new PurchaseServiceImpl(
                purchaseOrderRepository,
                purchaseOrderItemRepository,
                receiveRecordRepository,
                supplierRepository,
                rawMaterialTypeRepository,
                /* materialBatchRepository */ null,
                /* bomItemRepository */ null,
                /* arApService */ null,
                /* applicationEventPublisher */ null,
                /* materialBatchService */ null);
        // ⚠️ 刻意【不】设 overReceiveRate —— 手动构造的实例上它就是 null,
        // 这正是生产上"属性没人配"的样子。每个用例自己决定要不要给它一个值。
    }

    // ─────────────────────────── 夹具 ───────────────────────────

    private CreateReceiveRecordRequest buildRequest(BigDecimal qty) {
        CreateReceiveRecordRequest req = new CreateReceiveRecordRequest();
        req.setPurchaseOrderId(PO_ID);
        req.setSupplierId(SUPPLIER_ID);
        req.setReceiveDate(LocalDate.of(2026, 8, 25));
        CreateReceiveRecordRequest.ReceiveItemDTO item = new CreateReceiveRecordRequest.ReceiveItemDTO();
        item.setMaterialTypeId(MAT_ID);
        item.setMaterialName(MAT_NAME);
        item.setReceivedQuantity(qty);
        item.setUnit("kg");
        item.setUnitPrice(new BigDecimal("10.00"));
        req.setItems(List.of(item));
        return req;
    }

    /** 下单 orderedQty，已收 alreadyReceived 的一张待收 PO。 */
    private PurchaseOrderItem mockOrderFixture(BigDecimal orderedQty, BigDecimal alreadyReceived) {
        Supplier supplier = new Supplier();
        supplier.setId(SUPPLIER_ID);
        supplier.setFactoryId(FACTORY_ID);
        when(supplierRepository.findByIdAndFactoryId(SUPPLIER_ID, FACTORY_ID))
                .thenReturn(Optional.of(supplier));

        PurchaseOrder order = new PurchaseOrder();
        order.setId(PO_ID);
        order.setOrderNumber("PO-2026-0825-001");
        order.setFactoryId(FACTORY_ID);
        order.setSupplierId(SUPPLIER_ID);
        order.setStatus(PurchaseOrderStatus.FINANCE_APPROVED);
        when(purchaseOrderRepository.findByIdAndFactoryIdForUpdate(PO_ID, FACTORY_ID))
                .thenReturn(Optional.of(order));
        when(purchaseOrderRepository.findById(PO_ID)).thenReturn(Optional.of(order));
        when(receiveRecordRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY_ID, PO_ID))
                .thenReturn(List.of());

        PurchaseOrderItem orderItem = new PurchaseOrderItem();
        orderItem.setPurchaseOrderId(PO_ID);
        orderItem.setMaterialTypeId(MAT_ID);
        orderItem.setMaterialName(MAT_NAME);
        orderItem.setUnit("kg");
        orderItem.setPriceUnit("kg");
        orderItem.setQuantity(orderedQty);
        orderItem.setReceivedQuantity(alreadyReceived);
        when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(orderItem));
        return orderItem;
    }

    /** 待收货任务(getPendingReceivingTasks)用的夹具 —— 走 findByIdAndFactoryId, 与收货入口不同。 */
    private void mockReceivingTaskFixture(BigDecimal orderedQty, BigDecimal alreadyReceived) {
        PurchaseOrder order = new PurchaseOrder();
        order.setId(PO_ID);
        order.setOrderNumber("PO-2026-0825-001");
        order.setFactoryId(FACTORY_ID);
        order.setSupplierId(SUPPLIER_ID);
        order.setStatus(PurchaseOrderStatus.FINANCE_APPROVED);
        when(purchaseOrderRepository.findByIdAndFactoryId(PO_ID, FACTORY_ID))
                .thenReturn(Optional.of(order));
        when(receiveRecordRepository.findByFactoryIdAndPurchaseOrderIdOrderByCreatedAtAsc(FACTORY_ID, PO_ID))
                .thenReturn(List.of());

        PurchaseOrderItem orderItem = new PurchaseOrderItem();
        orderItem.setId(1L);
        orderItem.setPurchaseOrderId(PO_ID);
        orderItem.setMaterialTypeId(MAT_ID);
        orderItem.setMaterialName(MAT_NAME);
        orderItem.setUnit("kg");
        orderItem.setPriceUnit("kg");
        orderItem.setQuantity(orderedQty);
        orderItem.setReceivedQuantity(alreadyReceived);
        when(purchaseOrderItemRepository.findByPurchaseOrderId(PO_ID)).thenReturn(List.of(orderItem));
    }

    // ─────────────────────── 断言 helper ───────────────────────

    /** 走产品真实入口 createReceiveRecord，返回它抛出的东西（没抛就是 null）。 */
    private Throwable receive(BigDecimal qty) {
        try {
            service.createReceiveRecord(FACTORY_ID, buildRequest(qty), 1L);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    /**
     * 断言这个数量【没有被超收上限拦下】。
     *
     * <p>⚠️ 阴性断言 —— 单独看它可能恒真（如果上限校验根本没跑，它永远绿）。
     * 阳性对照是同一夹具下的 {@code assertRejectedByCap}：每个 @Nested 至少各有一条。
     */
    private void assertNotRejectedByCap(BigDecimal qty, String why) {
        Throwable t = receive(qty);
        if (t instanceof BusinessException be
                && be.getMessage() != null
                && be.getMessage().contains(CAP_REJECTION_MARKER)) {
            fail(why + " —— 却被超收上限拦下了: " + be.getMessage());
        }
    }

    /** 断言这个数量【被超收上限拦下】，并返回那个 409 供进一步断言。 */
    private BusinessException assertRejectedByCap(BigDecimal qty, String why) {
        Throwable t = receive(qty);
        assertNotNull(t, why + " —— 却什么都没抛");
        assertInstanceOf(BusinessException.class, t,
                why + " —— 抛的不是 BusinessException: " + t);
        BusinessException be = (BusinessException) t;
        assertEquals(409, be.getCode(), why + " —— 状态码不是 409: " + be.getMessage());
        assertTrue(be.getMessage().contains(CAP_REJECTION_MARKER),
                why + " —— 抛的是别的 409, 不是超收上限: " + be.getMessage());
        return be;
    }

    // ═══════════════════ 1. 优先级梯子 / 默认值 ═══════════════════

    @Nested
    @DisplayName("梯子: 工厂级 > 全局属性 > 不限制")
    class Ladder {

        @Test
        @DisplayName("⭐ 未配置任何上限 → 不限制 (⛔ 不是 0%) —— 变异靶子")
        void unconfigured_meansUnlimited_notZero() {
            // 手动构造的 service: overReceiveRate=null, overReceivePolicy=null
            OverReceiveLimit limit = (OverReceiveLimit) ReflectionTestUtils.invokeMethod(
                    service, "resolveOverReceiveLimit", FACTORY_ID);

            assertNotNull(limit);
            assertTrue(limit.isUnlimited(),
                    "谁也没配过时必须是「不限制」。读到 " + limit
                            + " 说明默认值又变回了一个具体的限制 —— 那正是货堵在门口的成因");
            assertNull(limit.rate(), "不限制的 rate 必须是 null, ⛔ 不能是 0 也不能是哨兵大数");
            assertEquals(OverReceiveLimit.SOURCE_UNCONFIGURED, limit.source(),
                    "来源标记必须说明它是兜底出来的, 否则分不清是配的还是默认的");
            assertEquals("不限制", limit.label());
            assertNull(limit.maxAllowed(new BigDecimal("100")),
                    "不限制时没有 maxAllowed; 返回一个数字会让展示侧显示出一个不存在的上限");
        }

        @Test
        @DisplayName("⭐ 未配置 → 下单 100kg 收 500kg 也放行 (真实入口) —— 变异靶子")
        void unconfigured_acceptsFarBeyondOrderedQty() {
            mockOrderFixture(new BigDecimal("100"), BigDecimal.ZERO);
            assertNotRejectedByCap(new BigDecimal("500"),
                    "默认不限制超收: 下单 100kg 收 500kg 应当放行");
        }

        @Test
        @DisplayName("全局属性 0.30 → 来源标记是 property, 上限 130")
        void propertyRate_takesEffect() {
            ReflectionTestUtils.setField(service, "overReceiveRate", new BigDecimal("0.30"));

            OverReceiveLimit limit = (OverReceiveLimit) ReflectionTestUtils.invokeMethod(
                    service, "resolveOverReceiveLimit", FACTORY_ID);

            assertFalse(limit.isUnlimited());
            assertEquals(OverReceiveLimit.SOURCE_PROPERTY, limit.source());
            assertEquals(0, new BigDecimal("130.00").compareTo(limit.maxAllowed(new BigDecimal("100"))));
            assertEquals("30%", limit.label());
        }

        @Test
        @DisplayName("工厂级 0.50 压过全局属性 0.10 (工厂级优先)")
        void factorySetting_overridesProperty() {
            ReflectionTestUtils.setField(service, "overReceiveRate", new BigDecimal("0.10"));
            ReflectionTestUtils.setField(service, "overReceivePolicy", overReceivePolicy);
            when(overReceivePolicy.factoryConfiguredRate(FACTORY_ID))
                    .thenReturn(Optional.of(new BigDecimal("0.50")));

            OverReceiveLimit limit = (OverReceiveLimit) ReflectionTestUtils.invokeMethod(
                    service, "resolveOverReceiveLimit", FACTORY_ID);

            assertEquals(OverReceiveLimit.SOURCE_FACTORY_SETTINGS, limit.source(),
                    "工厂配了就该用工厂的, 读到 " + limit.source() + " 说明梯子顺序反了");
            assertEquals(0, new BigDecimal("0.50").compareTo(limit.rate()));
        }

        @Test
        @DisplayName("工厂级没配 → 回落全局属性, 不是直接变成不限制")
        void factoryUnset_fallsBackToProperty() {
            ReflectionTestUtils.setField(service, "overReceiveRate", new BigDecimal("0.10"));
            ReflectionTestUtils.setField(service, "overReceivePolicy", overReceivePolicy);
            when(overReceivePolicy.factoryConfiguredRate(FACTORY_ID)).thenReturn(Optional.empty());

            OverReceiveLimit limit = (OverReceiveLimit) ReflectionTestUtils.invokeMethod(
                    service, "resolveOverReceiveLimit", FACTORY_ID);

            assertEquals(OverReceiveLimit.SOURCE_PROPERTY, limit.source());
            assertEquals(0, new BigDecimal("0.10").compareTo(limit.rate()));
        }

        @Test
        @DisplayName("⭐ 显式配 0.00 仍是一条真实限制 (一件都不许多收) —— 与「没配」可区分")
        void explicitZero_isARealLimit_notUnlimited() {
            ReflectionTestUtils.setField(service, "overReceiveRate", BigDecimal.ZERO);
            mockOrderFixture(new BigDecimal("100"), BigDecimal.ZERO);

            // 阳性对照: 同一夹具下 0.00 会拦
            BusinessException be = assertRejectedByCap(new BigDecimal("101"),
                    "显式配 0% 时, 多收 1kg 就该拦");
            assertTrue(be.getMessage().contains("0%"),
                    "文案应说明上限是 0%, 实际: " + be.getMessage());

            OverReceiveLimit limit = (OverReceiveLimit) ReflectionTestUtils.invokeMethod(
                    service, "resolveOverReceiveLimit", FACTORY_ID);
            assertFalse(limit.isUnlimited(),
                    "0 是限制不是「不限制」—— 这两者被同一个 0.00 混为一谈, 正是本次缺陷的根");
        }
    }

    // ═══════════════════ 2. 四个边界 (限制 = 30%) ═══════════════════

    @Nested
    @DisplayName("边界: 下单 100kg, 上限 30% → 最多 130kg")
    class Boundaries {

        @BeforeEach
        void limitTo30Percent() {
            ReflectionTestUtils.setField(service, "overReceiveRate", new BigDecimal("0.30"));
            mockOrderFixture(new BigDecimal("100"), BigDecimal.ZERO);
        }

        @Test
        @DisplayName("① 不超收: 收 100kg → 放行")
        void exactOrderedQty_accepted() {
            assertNotRejectedByCap(new BigDecimal("100"), "收满不超收, 不该被上限拦");
        }

        @Test
        @DisplayName("② 超收但在限制内: 收 130kg (正好上限) → 放行")
        void withinCapAtBoundary_accepted() {
            assertNotRejectedByCap(new BigDecimal("130"), "正好 130kg 等于上限, 边界应放行");
        }

        @Test
        @DisplayName("③ 超收且超出限制: 收 131kg → 409, 文案带上限与百分比")
        void beyondCap_rejectedWith409() {
            BusinessException be = assertRejectedByCap(new BigDecimal("131"), "131kg 超出 130kg 上限");
            assertTrue(be.getMessage().contains(MAT_NAME), "文案要指名物料: " + be.getMessage());
            assertTrue(be.getMessage().contains("最大可收 130"), "文案要给出上限数量: " + be.getMessage());
            assertTrue(be.getMessage().contains("30%"), "文案要给出上限比例: " + be.getMessage());
            // 打回：系统不会替他收下、也不会自动退 —— 文案必须说清下一步动作
            assertTrue(be.getActionHint() != null && be.getActionHint().contains("拒收"),
                    "hint 要告诉仓管员超出的部分怎么办, 实际: " + be.getActionHint());
        }

        @Test
        @DisplayName("④ 未配置限制 (无限): 同一张单收 131kg → 放行 (与③形成对照)")
        void sameQtyPassesWhenUnlimited() {
            // 唯一变量是"有没有配上限"：③ 拦 131，这里放行 131。
            ReflectionTestUtils.setField(service, "overReceiveRate", null);
            assertNotRejectedByCap(new BigDecimal("131"),
                    "未配置上限时 131kg 应当放行 —— 它和 ③ 的唯一差别就是上限配没配");
        }

        @Test
        @DisplayName("分批累计也按上限算: 已收 50 + 本次 81 = 131 → 409")
        void cumulativeAcrossBatches_rejected() {
            mockOrderFixture(new BigDecimal("100"), new BigDecimal("50"));
            BusinessException be = assertRejectedByCap(new BigDecimal("81"), "累计 131kg 超出 130kg");
            assertTrue(be.getMessage().contains("累计 131"), be.getMessage());
        }
    }

    // ═══════════════════ 3. @Value 的默认值本身 ═══════════════════

    /**
     * 上面所有用例都手动 {@code new PurchaseServiceImpl(...)}，
     * <b>@Value 的默认值那一段字符串一次都没被执行到</b> —— 而生产上出事的恰恰是它
     * （{@code :0.00} 配上"属性从来没人设过"）。所以这一组直接钉那个注解，
     * 外加一条证明该写法在 Spring 里真的解析成 null 的实测（⛔ 不从注释推行为）。
     */
    @Nested
    @DisplayName("@Value 默认值: 属性没设时必须是 null(不限制), 不能是一个数")
    class ValueAnnotationDefault {

        private static final String EXPECTED =
                "${cretas.purchase.over-receive-rate:#{null}}";

        @Test
        @DisplayName("⭐ overReceiveRate 的 @Value 默认段必须是 #{null} —— 变异靶子")
        void valueAnnotationDefaultsToNull() throws Exception {
            java.lang.reflect.Field f =
                    PurchaseServiceImpl.class.getDeclaredField("overReceiveRate");
            org.springframework.beans.factory.annotation.Value ann =
                    f.getAnnotation(org.springframework.beans.factory.annotation.Value.class);

            assertNotNull(ann, "overReceiveRate 必须仍由 @Value 注入");
            assertEquals(EXPECTED, ann.value(),
                    "默认值一旦写成一个数字(例如旧的 :0.00), 「没人配过」就会被执行成一条真实限制。"
                            + " 生产上 cretas.purchase.over-receive-rate 在 resources / .env.prod /"
                            + " systemd unit 里都没有设过 —— 这个默认段就是实际生效的值。");
        }

        @Test
        @DisplayName("实测: 属性缺席时 #{null} 真的给出 null; 设了值时正常解析")
        void springResolvesTheIdiomAsWritten() {
            assertNull(resolveWith(null), "属性缺席 → 必须是 null(不限制)");
            assertEquals(0, new BigDecimal("0.25").compareTo(resolveWith("0.25")),
                    "属性设了 0.25 → 必须解析成 0.25");
            assertNull(resolveWith(""),
                    "属性设成空串 → Spring 的 String→Number 转换给 null, 即不限制");
        }

        /** 用产品里那一模一样的 @Value 表达式，跑一次真的 Spring 解析。 */
        private BigDecimal resolveWith(String propertyValue) {
            try (org.springframework.context.annotation.AnnotationConfigApplicationContext ctx =
                         new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                if (propertyValue != null) {
                    ctx.getEnvironment().getPropertySources().addFirst(
                            new org.springframework.core.env.MapPropertySource("test-override",
                                    Map.of("cretas.purchase.over-receive-rate", propertyValue)));
                }
                ctx.register(
                        org.springframework.context.support.PropertySourcesPlaceholderConfigurer.class,
                        RateHolder.class);
                ctx.refresh();
                return ctx.getBean(RateHolder.class).rate;
            }
        }
    }

    /** 只承载那一个 @Value 表达式 —— 必须与 PurchaseServiceImpl 上的字面量一致（上一条断言钉住）。 */
    static class RateHolder {
        @org.springframework.beans.factory.annotation.Value(
                "${cretas.purchase.over-receive-rate:#{null}}")
        BigDecimal rate;
    }

    // ═══════════════════ 4. 展示侧读到的是执行侧的真值 ═══════════════════

    @Nested
    @DisplayName("展示侧 getCumulativeReceived 暴露的上限 = 执行侧用的上限")
    class DisplayReadsExecutionTruth {

        @Test
        @DisplayName("未配置 → rate=null / label=不限制 / maxReceivableQty=null")
        void unlimited_isReportedAsNull_notZero() {
            mockOrderFixture(new BigDecimal("100"), new BigDecimal("20"));

            Map<String, Object> summary = service.getCumulativeReceived(FACTORY_ID, PO_ID);

            assertNull(summary.get("overReceiveLimitRate"),
                    "不限制必须报 null; 报 0 会让界面显示「最多多收 0%」");
            assertEquals("不限制", summary.get("overReceiveLimitLabel"));
            assertEquals(OverReceiveLimit.SOURCE_UNCONFIGURED, summary.get("overReceiveLimitSource"));
            assertNull(summary.get("maxReceivableTotal"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lines = (List<Map<String, Object>>) summary.get("lines");
            assertEquals(1, lines.size());
            assertNull(lines.get(0).get("maxReceivableQty"));
        }

        @Test
        @DisplayName("⭐ 配了 30% → 展示的 130 与执行侧拦 131 是同一个上限")
        void displayedCapMatchesWhatExecutionEnforces() {
            ReflectionTestUtils.setField(service, "overReceiveRate", new BigDecimal("0.30"));
            mockOrderFixture(new BigDecimal("100"), BigDecimal.ZERO);

            Map<String, Object> summary = service.getCumulativeReceived(FACTORY_ID, PO_ID);
            BigDecimal displayedCap = (BigDecimal) summary.get("maxReceivableTotal");

            assertEquals("30%", summary.get("overReceiveLimitLabel"));
            assertEquals(0, new BigDecimal("130.00").compareTo(displayedCap),
                    "展示的上限应是 130");

            // 展示说 130 → 执行侧就必须在 130 放行、131 拦。
            // 这两条断言用的是同一个 displayedCap，展示与执行漂开时它必红。
            assertNotRejectedByCap(displayedCap, "展示说最多能收 " + displayedCap + ", 执行侧就该放行");
            assertRejectedByCap(displayedCap.add(BigDecimal.ONE),
                    "超过展示的上限 " + displayedCap + " 就该被拦");
        }

        /**
         * 仓管员真正输数量的那个页面（web-admin {@code PendingPurchaseReceivingPanel}）
         * 读的是待收货任务，不是 {@code getCumulativeReceived}。它过去把输入框上限绑到
         * {@code remainingReceivableQuantity}（= 还差多少收齐），于是下单 100 送 105 时
         * <b>仓管员根本输不进 105</b> —— 界面把货挡在门口，而后端此刻允许超收。
         */
        @Test
        @DisplayName("⭐ 待收货任务上有「最多还能收多少」, 且它 ≠ 「还差多少收齐」")
        void receivingTask_carriesCapDistinctFromOutstanding() {
            ReflectionTestUtils.setField(service, "overReceiveRate", new BigDecimal("0.30"));
            mockReceivingTaskFixture(new BigDecimal("100"), new BigDecimal("20"));

            var task = service.getPendingReceivingTasks(FACTORY_ID, PO_ID, null).get(0);
            var line = task.getItems().get(0);

            assertEquals("30%", task.getOverReceiveLimitLabel());
            assertEquals(0, new BigDecimal("80").compareTo(line.getRemainingReceivableQuantity()),
                    "还差多少收齐 = 100 - 20");
            assertEquals(0, new BigDecimal("110.00").compareTo(line.getMaxReceivableQuantity()),
                    "最多还能收 = 100×1.3 - 20 = 110");
            assertNotEquals(0,
                    line.getRemainingReceivableQuantity().compareTo(line.getMaxReceivableQuantity()),
                    "这两个数必须不同 —— 相同就说明上限又被算成了待收量, 超收的货照样进不来");
        }

        @Test
        @DisplayName("⭐ 未配置上限 → 任务上的 maxReceivableQuantity 是 null (不是待收量)")
        void receivingTask_unlimitedIsNull() {
            mockReceivingTaskFixture(new BigDecimal("100"), new BigDecimal("20"));

            var task = service.getPendingReceivingTasks(FACTORY_ID, PO_ID, null).get(0);
            var line = task.getItems().get(0);

            assertEquals("不限制", task.getOverReceiveLimitLabel());
            assertNull(task.getOverReceiveLimitRate());
            assertNull(line.getMaxReceivableQuantity(),
                    "不限制时必须是 null; 兜底成待收量 80 就等于把「不限制」显示成「最多 80」");
            assertEquals(0, new BigDecimal("80").compareTo(line.getRemainingReceivableQuantity()),
                    "待收量本身不受影响");
        }

        @Test
        @DisplayName("工厂级配置也会出现在展示侧 (来源标记指向 factory-settings)")
        void factoryConfiguredLimit_isVisibleToDisplay() {
            ReflectionTestUtils.setField(service, "overReceivePolicy", overReceivePolicy);
            when(overReceivePolicy.factoryConfiguredRate(anyString()))
                    .thenReturn(Optional.of(new BigDecimal("0.05")));
            mockOrderFixture(new BigDecimal("200"), BigDecimal.ZERO);

            Map<String, Object> summary = service.getCumulativeReceived(FACTORY_ID, PO_ID);

            assertEquals("5%", summary.get("overReceiveLimitLabel"));
            assertEquals(OverReceiveLimit.SOURCE_FACTORY_SETTINGS, summary.get("overReceiveLimitSource"));
            assertEquals(0, new BigDecimal("210.00")
                    .compareTo((BigDecimal) summary.get("maxReceivableTotal")));
        }
    }
}
