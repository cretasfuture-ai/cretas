package com.cretas.aims.service.impl;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.scheduling.RushOrderImpactTool;
import com.cretas.aims.dto.orchestration.MaterialAllocation;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.InsertSlotPreviewRepository;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.shortage.ShortageSupplyQueryRepository;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.ImpactAnalysisService.ResourceCheckResult;
import com.cretas.aims.service.UrgentInsertService;
import com.cretas.aims.service.orchestration.BomExpansionService;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.service.scheduling.UrgentInsertMaterialAssessor;
import com.cretas.aims.service.scheduling.WorkerShiftAvailabilityCheck;
import com.cretas.aims.service.unit.UnitContractService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 急单影响分析 (BP 第 8 页): 用 QA- 数据构造场景, 数字由真实代码算出来再对数。
 *
 * <p>真的: {@link RushOrderImpactTool}、{@link UrgentInsertServiceImpl#analyzeInsertImpact} (只读 GET 口径)、
 * {@link ImpactAnalysisServiceImpl#calculateChainImpact} (链式延误)、{@link UrgentInsertMaterialAssessor}。
 * 桩: 仓库 (返回 QA 数据)、配方展开 / 可领库存 (按「每千克用量 × 数量」现算, 对照 QA 库存)、产能设备 / 排班核对。
 *
 * <p>今天 = 2026-11-17 (周二), 「周五」= 2026-11-20。
 */
class RushOrderImpactToolDataContractTest {

    private static final String F = "F006";
    private static final String PT = "QA-PT-JNR";
    private static final String SAID = "周五要交 2 吨酱牛肉急单，原料够不够，产线排不排得开？";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 17, 9, 0);

    private final ObjectMapper mapper = new ObjectMapper();
    private BomExpansionService bom;
    private InsertSlotRepository slots;
    private ProductionPlanRepository plans;
    private InsertSlotPreviewRepository previews;
    private ShortageSupplyQueryRepository supply;
    private WorkerShiftAvailabilityCheck workers;
    private final Map<String, BigDecimal> stock = new HashMap<>();
    private final List<ProductionPlan> pendingPlans = new ArrayList<>();
    private RushOrderImpactTool tool;
    private ProductTypeRepository products;
    private String shortUnit = "kg";

    /** 每千克酱牛肉: 牛腱 1.25 kg, 酱料包 0.05 kg。 */
    private static final Map<String, BigDecimal> PER_KG = new LinkedHashMap<>();

    static {
        PER_KG.put("QA-RM-NJ", new BigDecimal("1.25"));
        PER_KG.put("QA-RM-JL", new BigDecimal("0.05"));
    }

    private static final Map<String, String> NAMES = Map.of("QA-RM-NJ", "QA-牛腱", "QA-RM-JL", "QA-酱料包");

    @BeforeEach
    void setUp() {
        bom = mock(BomExpansionService.class);
        when(bom.expandBOM(eq(F), eq(PT), any())).thenAnswer(inv -> {
            BigDecimal qty = inv.getArgument(2);
            List<MaterialRequirement> out = new ArrayList<>();
            PER_KG.forEach((id, per) -> {
                MaterialRequirement r = new MaterialRequirement();
                r.setMaterialTypeId(id);
                r.setMaterialTypeName(NAMES.get(id));
                r.setRequiredQuantity(per.multiply(qty));
                out.add(r);
            });
            return out;
        });
        when(bom.checkPickableAvailability(eq(F), anyList())).thenAnswer(inv -> {
            List<MaterialRequirement> reqs = inv.getArgument(1);
            List<MaterialShortfall> shorts = new ArrayList<>();
            List<MaterialAllocation> allocs = new ArrayList<>();
            for (MaterialRequirement r : reqs) {
                BigDecimal have = stock.getOrDefault(r.getMaterialTypeId(), BigDecimal.ZERO);
                if (have.compareTo(r.getRequiredQuantity()) >= 0) {
                    MaterialAllocation a = new MaterialAllocation();
                    a.setMaterialTypeId(r.getMaterialTypeId());
                    a.setAllocatedQuantity(r.getRequiredQuantity());
                    allocs.add(a);
                } else {
                    MaterialShortfall sf = new MaterialShortfall(r.getMaterialTypeId(), r.getMaterialTypeName(),
                            r.getRequiredQuantity(), have, r.getRequiredQuantity().subtract(have));
                    sf.setQuantityUnit(shortUnit);
                    shorts.add(sf);
                }
            }
            return new MaterialCheckResult(shorts.isEmpty(), shorts, allocs);
        });
        BomRecipeRepository recipes = mock(BomRecipeRepository.class);
        BomRecipe recipe = new BomRecipe();
        recipe.setId("f0dfebf5-1c2d-4e3f-8a9b-0123456789ab");
        recipe.setVersion(2);
        when(recipes.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(F, PT, BomRecipe.Status.ACTIVE))
                .thenReturn(Optional.of(recipe));
        supply = mock(ShortageSupplyQueryRepository.class);
        when(supply.findOpenOrderRemaining(anyString(), anyString())).thenReturn(List.of());
        UnitContractService units = mock(UnitContractService.class);
        when(units.areEquivalent(anyString(), anyString(), anyString()))
                .thenAnswer(i -> i.getArgument(1).equals(i.getArgument(2)));
        ProcessSecretRecipeGuard secrets = mock(ProcessSecretRecipeGuard.class);
        when(secrets.permitsProduct(anyString(), anyString(), any(), anyString())).thenReturn(true);
        UrgentInsertMaterialAssessor assessor = UrgentInsertTestSupport.construct(UrgentInsertMaterialAssessor.class, Map.of(
                BomExpansionService.class, bom,
                BomRecipeRepository.class, recipes,
                ShortageSupplyQueryRepository.class, supply,
                UnitContractService.class, units,
                ProcessSecretRecipeGuard.class, secrets,
                PlatformTransactionManager.class, mock(PlatformTransactionManager.class)));

        products = mock(ProductTypeRepository.class);
        ProductType product = new ProductType();
        product.setId(PT);
        product.setFactoryId(F);
        product.setName("酱牛肉");
        product.setUnit("kg");
        ProductType other = new ProductType();
        other.setId("QA-PT-LY");
        other.setFactoryId(F);
        other.setName("卤鸭");
        other.setUnit("kg");
        when(products.findVisibleByFactoryIdAndIsActiveTrue(F)).thenReturn(List.of(product, other));
        when(products.findByIdAndFactoryId(PT, F)).thenReturn(Optional.of(product));

        plans = mock(ProductionPlanRepository.class);
        when(plans.findByFactoryIdAndStatus(F, ProductionPlanStatus.PENDING)).thenAnswer(i -> pendingPlans);
        when(plans.findByFactoryIdAndStatus(F, ProductionPlanStatus.IN_PROGRESS)).thenReturn(List.of());
        ImpactAnalysisService impact = UrgentInsertTestSupport.construct(ImpactAnalysisServiceImpl.class,
                Map.of(ProductionPlanRepository.class, plans, ProductTypeRepository.class, products));

        slots = mock(InsertSlotRepository.class);
        previews = mock(InsertSlotPreviewRepository.class);
        ResourceCheckResult ready = ResourceCheckResult.available("TEST", new BigDecimal("3000"), new BigDecimal("2000"));
        LineCapacityEquipmentCheck lines = mock(LineCapacityEquipmentCheck.class);
        when(lines.checkEquipment(anyString(), anyString())).thenReturn(ready);
        when(lines.checkCapacity(anyString(), anyString(), any(), any(), any(), any(), any())).thenReturn(ready);
        workers = mock(WorkerShiftAvailabilityCheck.class);
        staffed(6, 6);
        UrgentInsertService service = UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.ofEntries(
                Map.entry(InsertSlotRepository.class, slots),
                Map.entry(ProductionPlanRepository.class, plans),
                Map.entry(InsertSlotPreviewRepository.class, previews),
                Map.entry(ProductTypeRepository.class, products),
                Map.entry(ImpactAnalysisService.class, impact),
                Map.entry(LineCapacityEquipmentCheck.class, lines),
                Map.entry(WorkerShiftAvailabilityCheck.class, workers),
                Map.entry(SchedulingResourceWriteLock.class, mock(SchedulingResourceWriteLock.class)),
                Map.entry(UrgentInsertMaterialAssessor.class, assessor),
                Map.entry(ObjectMapper.class, new ObjectMapper())));

        tool = new RushOrderImpactTool() {
            @Override
            protected LocalDateTime now() {
                return NOW;
            }
        };
        ReflectionTestUtils.setField(tool, "productTypeRepository", products);
        ReflectionTestUtils.setField(tool, "materialAssessor", assessor);
        ReflectionTestUtils.setField(tool, "insertSlotRepository", slots);
        ReflectionTestUtils.setField(tool, "urgentInsertService", service);
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper().findAndRegisterModules());
    }

    // ------------------------------------------------------------------ fixtures

    private void staffed(int required, int available) {
        Set<Long> ids = new LinkedHashSet<>();
        for (long i = 1; i <= available; i++) ids.add(i);
        when(workers.check(anyString(), any(), any(), anyInt()))
                .thenReturn(new WorkerShiftAvailabilityCheck.Result(true, required, available, ids, "ok"));
    }

    /** 一号线 11-19 08:00–14:00 (6 小时) 的插单时段, 需 6 人。 */
    private void slotThursdayMorning() {
        InsertSlot slot = InsertSlot.builder().id("QA-SLOT-1").factoryId(F).productionLineId("QA-LINE-1")
                .productionLineName("QA-一号线").startTime(LocalDateTime.of(2026, 11, 19, 8, 0))
                .endTime(LocalDateTime.of(2026, 11, 19, 14, 0)).requiredWorkers(6).availableWorkers(6)
                .recommendScore(80).impactLevel("none").status("available").build();
        // 交期之后的时段: 不能作为候选
        InsertSlot late = InsertSlot.builder().id("QA-SLOT-LATE").factoryId(F).productionLineId("QA-LINE-1")
                .productionLineName("QA-一号线").startTime(LocalDateTime.of(2026, 11, 21, 8, 0))
                .endTime(LocalDateTime.of(2026, 11, 21, 14, 0)).requiredWorkers(6).recommendScore(99)
                .status("available").build();
        when(slots.findByFactoryIdAndStatus(F, "available")).thenReturn(new ArrayList<>(List.of(late, slot)));
        when(slots.findByIdAndFactoryId("QA-SLOT-1", F)).thenReturn(Optional.of(slot));
    }

    private static ProductionPlan plan(String id, String number, LocalDateTime start, LocalDateTime end, String customer,
                                       LocalDate due) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(F);
        p.setPlanNumber(number);
        p.setStartTime(start);
        p.setEndTime(end);
        p.setSourceCustomerName(customer);
        p.setExpectedCompletionDate(due);
        p.setStatus(ProductionPlanStatus.PENDING);
        return p;
    }

    private JsonNode run(String said) throws Exception {
        Map<String, Object> args = new HashMap<>();
        args.put("userInput", said);
        String json = tool.execute(ToolCall.of("t", "scheduling_rush_order_impact", mapper.writeValueAsString(args)),
                Map.of("factoryId", F, "userId", 1L));
        JsonNode root = mapper.readTree(json);
        assertThat(root.path("success").asBoolean()).as(json).isTrue();
        return root;
    }

    private static JsonNode section(JsonNode root, String key) {
        for (JsonNode s : root.path("data").path("data").path("sections")) {
            if (key.equals(s.path("key").asText())) return s;
        }
        throw new AssertionError("no section " + key + " in " + root);
    }

    private void assertNothingWritten() {
        verify(slots, never()).save(any());
        verify(slots, never()).claimAvailableSlot(anyString(), anyString(), any());
        verify(previews, never()).save(any());
        verify(plans, never()).save(any());
    }

    // ------------------------------------------------------------------ 场景

    @Test
    @DisplayName("缺料 + 挪原单: 2 吨 = 2000kg, 牛腱缺 700kg; 一号线 6 小时时段挤掉 2 张原单 (晚 6 小时 / 3 小时)")
    void shortageAndConflictNumbersMatch() throws Exception {
        stock.put("QA-RM-NJ", new BigDecimal("1800")); // 需要 2000 × 1.25 = 2500, 缺 700
        stock.put("QA-RM-JL", new BigDecimal("150"));  // 需要 2000 × 0.05 = 100, 够
        when(supply.findOpenOrderRemaining(F, "QA-RM-NJ")).thenReturn(List.<Object[]>of(new Object[]{"kg", new BigDecimal("300")}));
        slotThursdayMorning();
        // 直接冲突: 10:00–16:00 与插单时段重叠 ⇒ 晚 360 分钟; 级联: 17:00 开工, 紧随前一单 ⇒ 晚 180 分钟
        pendingPlans.add(plan("QA-PLAN-A", "QA-PP-A", LocalDateTime.of(2026, 11, 19, 10, 0),
                LocalDateTime.of(2026, 11, 19, 16, 0), "QA-客户甲", LocalDate.of(2026, 11, 19)));
        pendingPlans.add(plan("QA-PLAN-B", "QA-PP-B", LocalDateTime.of(2026, 11, 19, 17, 0),
                LocalDateTime.of(2026, 11, 19, 20, 0), "QA-客户乙", LocalDate.of(2026, 11, 25)));
        staffed(6, 4);

        JsonNode root = run(SAID);
        JsonNode data = root.path("data").path("data");
        assertThat(data.path("productName").asText()).isEqualTo("酱牛肉");
        assertThat(data.path("deadline").asText()).isEqualTo("2026-11-20");
        assertThat(data.path("quantityInProductUnit").decimalValue()).isEqualByComparingTo("2000");

        JsonNode material = section(root, "MATERIAL");
        assertThat(material.path("status").asText()).isEqualTo("SHORT");
        assertThat(material.path("shortLines")).hasSize(1);
        JsonNode nj = material.path("shortLines").get(0);
        assertThat(nj.path("materialName").asText()).isEqualTo("QA-牛腱");
        assertThat(nj.path("required").decimalValue()).isEqualByComparingTo("2500");
        assertThat(nj.path("available").decimalValue()).isEqualByComparingTo("1800");
        assertThat(nj.path("shortfall").decimalValue()).isEqualByComparingTo("700");
        assertThat(nj.path("onOrder").decimalValue()).isEqualByComparingTo("300");
        assertThat(nj.path("gapAfterOnOrder").decimalValue()).isEqualByComparingTo("400");
        assertThat(nj.path("onOrderUnitMismatchLines").asInt()).isZero();
        assertThat(material.path("text").asText()).startsWith("不够").contains("QA-牛腱 缺 700kg").contains("在途未到 300kg");

        JsonNode line = section(root, "LINE_AND_LABOR");
        assertThat(line.path("status").asText()).isEqualTo("FITS");
        assertThat(line.path("slotId").asText()).as("交期后的时段不能选").isEqualTo("QA-SLOT-1");
        assertThat(line.path("workerStatus").asText()).isEqualTo("SHORTAGE");
        assertThat(line.path("text").asText()).contains("QA-一号线").contains("需要 6 人，可用 4 人").contains("不硬拦");

        JsonNode moved = section(root, "MOVED_ORDERS");
        assertThat(moved.path("status").asText()).isEqualTo("MOVES");
        assertThat(moved.path("movedPlans")).hasSize(2);
        assertThat(moved.path("movedPlans").get(0).path("planNumber").asText()).isEqualTo("QA-PP-A");
        assertThat(moved.path("movedPlans").get(0).path("delayMinutes").asInt()).isEqualTo(360);
        assertThat(moved.path("movedPlans").get(0).path("exceedsDeadline").asBoolean()).as("A 晚到 11-19 22:00 仍在 11-19").isFalse();
        assertThat(moved.path("movedPlans").get(1).path("planNumber").asText()).isEqualTo("QA-PP-B");
        assertThat(moved.path("movedPlans").get(1).path("delayMinutes").asInt()).isEqualTo(180);
        assertThat(moved.path("text").asText()).contains("要挪 2 张原单").contains("QA-PP-A").contains("晚 6 小时")
                .contains("QA-PP-B").contains("晚 3 小时");

        JsonNode draft = section(root, "DRAFT");
        assertThat(draft.path("text").asText()).contains("待负责人确认").contains("酱牛肉 2000kg").contains("2026-11-20")
                .contains("QA-一号线").contains("补齐缺的 1 样原料").contains("2 张原单").contains("调人");
        assertThat(data.path("writesPerformed").asBoolean()).isFalse();
        assertThat(root.path("data").path("message").asText()).contains("1. 物料够不够").contains("2. 产线和人手排不排得开")
                .contains("3. 要挪哪几张原单、各晚多久").contains("4. 方案草案");
        assertNothingWritten();
    }

    @Test
    @DisplayName("LSMDEMO 计件单位缺料数量取整: 缺口向上、可领向下, 可领+缺口 ≥ 需要; 走工具真实入口")
    void countUnitShortageIsRoundedForDisplay() throws Exception {
        shortUnit = "个";
        stock.put("QA-RM-NJ", new BigDecimal("1709.614")); // 需要 2500, 缺 790.386
        stock.put("QA-RM-JL", new BigDecimal("150"));
        slotThursdayMorning();

        JsonNode material = section(run(SAID), "MATERIAL");
        String text = material.path("text").asText();
        assertThat(text).contains("缺 791个").contains("需要 2500个").contains("可领库存 1709个")
                .doesNotContain("790.386").doesNotContain("1709.614");
        assertThat(1709 + 791).isGreaterThanOrEqualTo(2500);
    }

    @Test
    @DisplayName("计量单位 kg 不取整 (阴性对照)")
    void measuredUnitShortageKeepsDecimals() throws Exception {
        shortUnit = "kg";
        stock.put("QA-RM-NJ", new BigDecimal("1709.614"));
        stock.put("QA-RM-JL", new BigDecimal("150"));
        slotThursdayMorning();

        String text = section(run(SAID), "MATERIAL").path("text").asText();
        assertThat(text).contains("缺 790.386kg").contains("可领库存 1709.614kg");
    }

    @Test
    @DisplayName("M1 在途有单位不同的行 →说「另有 N 行在途采购单位不同未计入」, ⛔ 不说「没有在途采购」; row 带计数")
    void onOrderUnitMismatchIsNotReportedAsNoOnOrder() throws Exception {
        stock.put("QA-RM-NJ", new BigDecimal("1800"));
        stock.put("QA-RM-JL", new BigDecimal("150"));
        when(supply.findOpenOrderRemaining(F, "QA-RM-NJ"))
                .thenReturn(List.<Object[]>of(new Object[]{"箱", new BigDecimal("10")}));
        slotThursdayMorning();

        JsonNode material = section(run(SAID), "MATERIAL");
        assertThat(material.path("status").asText()).isEqualTo("SHORT");
        JsonNode nj = material.path("shortLines").get(0);
        assertThat(nj.path("onOrderUnitMismatchLines").asInt()).isEqualTo(1);
        assertThat(material.path("text").asText()).contains("另有 1 行在途采购单位不同未计入")
                .doesNotContain("没有在途采购");
    }

    @Test
    @DisplayName("M1 在途同单位 300kg + 1 行单位不同 → 「到货后仍缺」注明不含单位不同的在途")
    void onOrderGapExcludesMismatchedLinesExplicitly() throws Exception {
        stock.put("QA-RM-NJ", new BigDecimal("1800"));
        stock.put("QA-RM-JL", new BigDecimal("150"));
        when(supply.findOpenOrderRemaining(F, "QA-RM-NJ")).thenReturn(List.<Object[]>of(
                new Object[]{"kg", new BigDecimal("300")}, new Object[]{"箱", new BigDecimal("10")}));
        slotThursdayMorning();

        JsonNode material = section(run(SAID), "MATERIAL");
        JsonNode nj = material.path("shortLines").get(0);
        assertThat(nj.path("onOrder").decimalValue()).isEqualByComparingTo("300");
        assertThat(nj.path("onOrderUnitMismatchLines").asInt()).isEqualTo(1);
        assertThat(material.path("text").asText()).contains("到货后仍缺 400kg（不含单位不同的在途）")
                .contains("另有 1 行在途采购单位不同未计入");
    }

    @Test
    @DisplayName("阴性对照: 料齐、时段空 → 第 1 项说「够」, 第 3 项不用挪原单")
    void enoughMaterialAndNoConflictSaysEnough() throws Exception {
        stock.put("QA-RM-NJ", new BigDecimal("5000"));
        stock.put("QA-RM-JL", new BigDecimal("500"));
        slotThursdayMorning();

        JsonNode root = run(SAID);
        JsonNode material = section(root, "MATERIAL");
        assertThat(material.path("status").asText()).isEqualTo("ENOUGH");
        assertThat(material.path("text").asText()).startsWith("够");
        assertThat(section(root, "MOVED_ORDERS").path("status").asText()).isEqualTo("NONE");
        assertThat(section(root, "LINE_AND_LABOR").path("workerStatus").asText()).isEqualTo("READY");
        assertThat(section(root, "DRAFT").path("text").asText()).contains("无阻塞项");
        assertNothingWritten();
    }

    @Test
    @DisplayName("交期前没有插单时段 → 第 2、3 项明说「算不出来，缺可插单时段」, 不编数; 第 1 项照算")
    void noSlotBeforeDeadlineSaysWhatIsMissing() throws Exception {
        stock.put("QA-RM-NJ", new BigDecimal("5000"));
        stock.put("QA-RM-JL", new BigDecimal("500"));
        when(slots.findByFactoryIdAndStatus(F, "available")).thenReturn(new ArrayList<>());

        JsonNode root = run(SAID);
        assertThat(section(root, "MATERIAL").path("status").asText()).isEqualTo("ENOUGH");
        for (String key : List.of("LINE_AND_LABOR", "MOVED_ORDERS")) {
            JsonNode s = section(root, key);
            assertThat(s.path("status").asText()).isEqualTo("UNVERIFIED");
            assertThat(s.path("text").asText()).startsWith("算不出来，缺").contains("2026-11-20").contains("可插单时段");
        }
        assertThat(section(root, "DRAFT").path("text").asText()).contains("第 2 项还算不出来");
    }

    @Test
    @DisplayName("单位对不上产品自带单位 (箱 vs kg) → 不换算, 第 1 项说缺单位换算")
    void unknownUnitIsNotGuessed() throws Exception {
        slotThursdayMorning();
        JsonNode root = run("周五要交 20 箱酱牛肉急单，原料够不够？");
        JsonNode material = section(root, "MATERIAL");
        assertThat(material.path("status").asText()).isEqualTo("UNVERIFIED");
        assertThat(material.path("text").asText()).contains("算不出来").contains("20箱").contains("「kg」");
        verify(bom, never()).expandBOM(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("没说产品 → 四项都在, 前三项说缺产品")
    void missingProductIsReported() throws Exception {
        JsonNode root = run("周五要交 2 吨急单，原料够不够，产线排不排得开？");
        assertThat(section(root, "MATERIAL").path("text").asText()).startsWith("算不出来，缺产品");
        assertThat(section(root, "LINE_AND_LABOR").path("text").asText()).startsWith("算不出来，缺产品");
        assertThat(section(root, "MOVED_ORDERS").path("text").asText()).startsWith("算不出来，缺产品");
        assertThat(section(root, "DRAFT").path("text").asText()).contains("产品未定");
    }

    // ------------------------------------------------------------------ 1118 急单回复修复: 产品容错 / 吨换盒 / 不外露内部信息

    private static ProductType demoProduct(String id, String name, String unit) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(F);
        p.setName(name);
        p.setUnit(unit);
        return p;
    }

    private void useProducts(ProductType... list) {
        when(products.findVisibleByFactoryIdAndIsActiveTrue(F)).thenReturn(List.of(list));
    }

    private static final String RECIPE_UUID = "f0dfebf5-1c2d-4e3f-8a9b-0123456789ab";
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-";

    @Test
    @DisplayName("B1+B2 老板原话「酱牛肉 2 吨」: 唯一命中「演示·酱卤牛肉250g」并说明, 2 吨 = 2000kg / 0.25kg = 8000 盒 并写出换算式")
    void bossPhraseMatchesDemoProductAndConvertsTonsToBoxes() throws Exception {
        useProducts(demoProduct(PT, "演示·酱卤牛肉250g", "盒"), demoProduct("QA-PT-LY", "演示·卤鸭500g", "盒"));
        stock.put("QA-RM-NJ", new BigDecimal("20000"));
        stock.put("QA-RM-JL", new BigDecimal("1000"));
        slotThursdayMorning();

        JsonNode root = run("插一个急单 酱牛肉 2 吨，周五交，原料够不够，产线排不排得开？");
        JsonNode data = root.path("data").path("data");
        String message = root.path("data").path("message").asText();
        assertThat(data.path("productName").asText()).isEqualTo("演示·酱卤牛肉250g");
        assertThat(data.path("quantityInProductUnit").decimalValue()).isEqualByComparingTo("8000");
        assertThat(message).contains("按「演示·酱卤牛肉250g」计算")
                .contains("2 吨 = 2000 千克 ÷ 0.25 千克/盒 = 8000 盒");
        assertThat(section(root, "MATERIAL").path("status").asText()).isEqualTo("ENOUGH");
    }

    @Test
    @DisplayName("B1 走真实入口: 模型抽出 productName=酱牛肉 / 2 / 吨 (原话里没有产品名) 同样唯一命中并换算; 容错不误撞短名")
    void productNameParamIsFuzzyMatched() throws Exception {
        useProducts(demoProduct(PT, "演示·酱卤牛肉250g", "盒"), demoProduct("QA-PT-LY", "演示·卤鸭500g", "盒"));
        stock.put("QA-RM-NJ", new BigDecimal("20000"));
        stock.put("QA-RM-JL", new BigDecimal("1000"));
        slotThursdayMorning();
        Map<String, Object> args = new HashMap<>();
        args.put("productName", "酱牛肉");
        args.put("quantity", 2);
        args.put("quantityUnit", "吨");
        args.put("deadline", "2026-11-20");
        JsonNode root = mapper.readTree(tool.execute(
                ToolCall.of("t", "scheduling_rush_order_impact", mapper.writeValueAsString(args)),
                Map.of("factoryId", F, "userId", 1L)));
        JsonNode data = root.path("data").path("data");
        assertThat(data.path("productName").asText()).isEqualTo("演示·酱卤牛肉250g");
        assertThat(data.path("quantityInProductUnit").decimalValue()).isEqualByComparingTo("8000");
        assertThat(RushOrderImpactTool.coreName("演示·酱卤牛肉250g")).isEqualTo("酱卤牛肉");
        assertThat(RushOrderImpactTool.fuzzyNameMatches("酱牛肉", "卤鸭")).isFalse();
        assertThat(RushOrderImpactTool.fuzzyNameMatches("酱鸭肉", "酱卤牛肉")).isFalse();
    }

    @Test
    @DisplayName("B1 多个候选 (250g / 500g 两个规格): 不猜, 列出全部候选让用户选, 前三项说缺唯一的产品")
    void ambiguousFuzzyMatchListsCandidates() throws Exception {
        useProducts(demoProduct(PT, "演示·酱卤牛肉250g", "盒"), demoProduct("QA-PT-NJ5", "演示·酱卤牛肉500g", "盒"));
        slotThursdayMorning();

        JsonNode root = run("插一个急单 酱牛肉 2 吨，周五交");
        JsonNode material = section(root, "MATERIAL");
        assertThat(material.path("status").asText()).isEqualTo("UNVERIFIED");
        assertThat(material.path("text").asText()).contains("演示·酱卤牛肉250g").contains("演示·酱卤牛肉500g")
                .contains("请说全名");
        verify(bom, never()).expandBOM(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("B2 产品没有规格重量: 不猜, 如实说无法把吨换算成产品单位, 请告诉我数量（盒）")
    void tonsWithoutSpecWeightAreNotGuessed() throws Exception {
        useProducts(demoProduct(PT, "演示·酱卤牛肉", "盒"));
        slotThursdayMorning();

        JsonNode root = run("插一个急单 演示·酱卤牛肉 2 吨，周五交");
        JsonNode material = section(root, "MATERIAL");
        assertThat(material.path("status").asText()).isEqualTo("UNVERIFIED");
        assertThat(material.path("text").asText()).contains("无法把2吨换算成该产品的单位")
                .contains("请告诉我数量（盒）");
        verify(bom, never()).expandBOM(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("B2 规格重量取结构化净含量 (500 g/盒) 优先于名称; 不整除向上取整并写明")
    void structuredNetContentWinsAndRoundsUp() throws Exception {
        ProductType p = demoProduct(PT, "演示·酱卤牛肉250g", "盒");
        p.setNetContentQuantity(new BigDecimal("300"));
        p.setNetContentUnit("g");
        useProducts(p);
        stock.put("QA-RM-NJ", new BigDecimal("20000"));
        stock.put("QA-RM-JL", new BigDecimal("1000"));
        slotThursdayMorning();

        JsonNode root = run("插一个急单 演示·酱卤牛肉250g 1 吨，周五交");
        assertThat(root.path("data").path("data").path("quantityInProductUnit").decimalValue()).isEqualByComparingTo("3334");
        assertThat(root.path("data").path("message").asText()).contains("1 吨 = 1000 千克 ÷ 0.3 千克/盒 = 3333.333333，向上取整为 3334 盒");
    }

    @Test
    @DisplayName("C 回复不外露内部信息: 无 UUID (配方 id 是 UUID 时也不出)、无「不落任何业务写」; 配方用产品名 + 当前配方第 N 版")
    void replyHasNoUuidOrDeveloperJargon() throws Exception {
        useProducts(demoProduct(PT, "演示·酱卤牛肉250g", "盒"));
        stock.put("QA-RM-NJ", new BigDecimal("20000"));
        stock.put("QA-RM-JL", new BigDecimal("1000"));
        slotThursdayMorning();

        JsonNode root = run("插一个急单 酱牛肉 2 吨，周五交，原料够不够，产线排不排得开？");
        String message = root.path("data").path("message").asText();
        assertThat(message).doesNotContainPattern(UUID_RE).doesNotContain("不落任何业务写").doesNotContain(RECIPE_UUID);
        assertThat(message).contains("当前配方第 2 版");
        assertThat(section(root, "DRAFT").path("basis").asText()).doesNotContain("不落任何业务写");
    }

    @Test
    @DisplayName("C 兜底: 上游文案万一带 UUID, 回复里也被抹掉")
    void scrubIdsRemovesUuids() {
        assertThat(RushOrderImpactTool.scrubIds("产线 f0dfebf5-1c2d-4e3f-8a9b-0123456789ab 满了")).doesNotContainPattern(UUID_RE);
    }
}
