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
                    sf.setQuantityUnit("kg");
                    shorts.add(sf);
                }
            }
            return new MaterialCheckResult(shorts.isEmpty(), shorts, allocs);
        });
        BomRecipeRepository recipes = mock(BomRecipeRepository.class);
        BomRecipe recipe = new BomRecipe();
        recipe.setId("QA-RECIPE-JNR");
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

        ProductTypeRepository products = mock(ProductTypeRepository.class);
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
}
