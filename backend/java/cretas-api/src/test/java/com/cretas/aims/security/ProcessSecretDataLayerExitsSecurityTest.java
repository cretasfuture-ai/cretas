package com.cretas.aims.security;

import com.cretas.aims.controller.BomRecipeController;
import com.cretas.aims.controller.BomReverseQueryController;
import com.cretas.aims.controller.ConversionController;
import com.cretas.aims.controller.OrderCostBreakdownController;
import com.cretas.aims.controller.ProcessSheetController;
import com.cretas.aims.controller.ProductWorkProcessController;
import com.cretas.aims.controller.ProductionPlanController;
import com.cretas.aims.controller.RestockBoardController;
import com.cretas.aims.controller.YieldReportController;
import com.cretas.aims.controller.inventory.PurchaseController;
import com.cretas.aims.controller.inventory.SalesOrderShortageController;
import com.cretas.aims.dto.ConversionDTO;
import com.cretas.aims.dto.ProductWorkProcessDTO;
import com.cretas.aims.dto.bom.BomCopyCandidateDTO;
import com.cretas.aims.dto.bom.MaterialReplacementImpact;
import com.cretas.aims.dto.inventory.PurchaseSuggestionResponse;
import com.cretas.aims.dto.orchestration.LineItemMatch;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.production.ProductionPlanMaterialAdvisoryDTO;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.YieldLimitsDTO;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.inventory.SalesOrderShortageReport;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.inventory.SalesOrderShortageReportRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.service.ConversionService;
import com.cretas.aims.service.ProductWorkProcessService;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.yield.YieldReportService;
import com.cretas.aims.service.bom.BomCopyService;
import com.cretas.aims.service.bom.BomReverseQueryService;
import com.cretas.aims.service.inventory.PurchaseService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretPayloadScrubber;
import com.cretas.aims.service.restock.RestockBoardService;
import com.cretas.aims.service.restock.dto.RestockHorizonDTO;
import com.cretas.aims.service.restock.dto.RestockHorizonProductRow;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工艺保密 V2 的出口盘点表, 逐个出口走真实 Spring MVC 链路 (路由 → 拦截器 → 真实 controller →
 * 响应 advice + 统一裁剪 → 全局异常处理)。判定服务是真的; 只有业务服务和仓库是桩。
 *
 * <p>每个出口两侧: 有权的人 (保密负责人) 看得到配比; 无授权的本厂账号看不到配比、看得到本单总量,
 * 且审计里有这道闸的 DENY。真实服务读库时由 Hibernate PostLoad 打标 (见 ProcessSecretDataLayerJpaTest),
 * 这里在桩里用同一个入口 ({@code ProcessSecretReadTracker.record}) 模拟那次读取。</p>
 */
class ProcessSecretDataLayerExitsSecurityTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final String PLAIN = "PLAIN";
    static final long OWNER = 10L;
    static final long OPERATOR = 5L;

    final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    ProcessSecretAuditRecorder recorder;
    ProcessSecretAccessService access;
    BomRecipeRepository recipes;
    final Map<Class<?>, Object> deps = new HashMap<>();

    @BeforeEach
    void setUp() {
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        recorder = mock(ProcessSecretAuditRecorder.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
        access = new ProcessSecretAccessService(policies, grants, recorder);
        recipes = mock(BomRecipeRepository.class);
        BomRecipe recipe = new BomRecipe();
        recipe.setId("r1");
        recipe.setFactoryId(F);
        recipe.setProductTypeId(SECRET);
        when(recipes.findById("r1")).thenReturn(Optional.of(recipe));
        deps.put(ProcessSecretAccessService.class, access);
    }

    // ------------------------------------------------------------------ harness

    /** 用最长的构造器实例化真实 controller, 依赖取 {@link #deps} 里的, 没有的给桩。 */
    @SuppressWarnings("unchecked")
    <T> T controller(Class<T> type) throws Exception {
        Constructor<?> ctor = Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(ctor.getParameterTypes())
                .map(p -> deps.computeIfAbsent(p, k -> mock(k)))
                .toArray();
        return (T) ctor.newInstance(args);
    }

    @SuppressWarnings("unchecked")
    <T> T dep(Class<T> type) {
        return (T) deps.computeIfAbsent(type, k -> mock(k));
    }

    MockMvc mvc(Object controller) {
        ProcessSecretPayloadScrubber scrubber = com.cretas.aims.service.processsecret.ProcessSecretTestHooks.scrubber(access, recipes,
                mock(ProductionWorkflowInstanceRepository.class), mock(ProductionPlanRepository.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .addMappedInterceptors(new String[]{"/api/mobile/**"}, new ProcessSecretGuardInterceptor(access,
                        recipes, mock(BomRecipeItemRepository.class), mock(ProductProcessWorkflowRepository.class),
                        mock(BomVersionRepository.class), mock(ProductionBatchRepository.class),
                        mock(ProductionPlanRepository.class), mock(FactoryMaterialRequisitionRepository.class)))
                .setControllerAdvice(new GlobalExceptionHandler(), new ProcessSecretResponseAdvice(access, recipes,
                        mock(ProductionPlanRepository.class), scrubber, json))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(json))
                .build();
    }

    static AuthenticatedActor user(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    static AuthenticatedActor platform() {
        return new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "super_admin");
    }

    JsonNode call(MockMvc mvc, MockHttpServletRequestBuilder request, AuthenticatedActor actor) throws Exception {
        String body = mvc.perform(request.requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, actor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(body).get("data");
    }

    /** 模拟真实服务读到了受保护产品的配方明细 (生产上由 Hibernate PostLoad 打标)。 */
    static <T> Answer<T> afterReadingSecretRecipe(Supplier<T> result) {
        return invocation -> {
            BomRecipeItem item = new BomRecipeItem();
            item.setFactoryId(F);
            item.setRecipeId("r1");
            com.cretas.aims.service.processsecret.ProcessSecretTestHooks.record(item);
            return result.get();
        };
    }

    static <T> Answer<T> afterReadingSecretProcessChain(Supplier<T> result) {
        return invocation -> {
            ProductWorkProcess process = new ProductWorkProcess();
            process.setFactoryId(F);
            process.setProductTypeId(SECRET);
            com.cretas.aims.service.processsecret.ProcessSecretTestHooks.record(process);
            return result.get();
        };
    }

    void verifyDenied(AuthenticatedActor actor, String field) {
        verify(recorder).record(eq(F), eq(SECRET), eq(actor), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"),
                contains(field), anyString());
    }

    void verifyNothingDenied() {
        verify(recorder, never()).record(any(), any(), any(), any(), eq("DENY"), any(), any());
    }

    // ------------------------------------------------------------------ 成本拆分

    MockMvc costBreakdown() throws Exception {
        when(dep(OrderCostBreakdownService.class).compute(eq(F), eq("o1"), anyBoolean()))
                .thenAnswer(afterReadingSecretRecipe(() -> OrderCostBreakdownDTO.builder()
                        .estimatedAuxItems(List.of(OrderCostBreakdownDTO.EstimatedAuxItem.builder()
                                .materialName("秘方香料").bomRecipeId("r1").dosagePerKgG(new BigDecimal("12.5"))
                                .rawInputKg(new BigDecimal("40")).quantity(new BigDecimal("0.5")).build()))
                        .build()));
        return mvc(controller(OrderCostBreakdownController.class));
    }

    @Test
    void costBreakdownHidesSeasoningDosageButKeepsThisOrdersTotals() throws Exception {
        JsonNode item = call(costBreakdown(), get("/api/mobile/F-A/production/orders/o1/cost-breakdown"),
                user(OPERATOR)).at("/estimatedAuxItems/0");
        assertThat(item.get("dosagePerKgG").isNull()).isTrue();
        assertThat(item.get("rawInputKg").asInt()).isEqualTo(40);
        assertThat(item.get("materialName").asText()).isEqualTo("秘方香料");
        assertThat(item.get("processSecret").get("visibility").asText()).isEqualTo("TOTALS_ONLY");
        verifyDenied(user(OPERATOR), "dosagePerKgG");
    }

    @Test
    void costBreakdownShowsDosageToTheOwner() throws Exception {
        JsonNode item = call(costBreakdown(), get("/api/mobile/F-A/production/orders/o1/cost-breakdown"),
                user(OWNER)).at("/estimatedAuxItems/0");
        assertThat(item.get("dosagePerKgG").decimalValue()).isEqualByComparingTo("12.5");
        verifyNothingDenied();
    }

    // ------------------------------------------------------------------ 报工 workflow-config

    MockMvc workflowConfig() throws Exception {
        WorkflowClerkSheetConfigDTO.PortDescriptor port = new WorkflowClerkSheetConfigDTO.PortDescriptor();
        port.setMaterialName("牛肉");
        port.setInputShareRatio(new BigDecimal("0.65"));
        WorkflowClerkSheetConfigDTO.ProcessDescriptor process = new WorkflowClerkSheetConfigDTO.ProcessDescriptor();
        process.setProcessName("腌制");
        process.setInputs(List.of(port));
        WorkflowClerkSheetConfigDTO config = new WorkflowClerkSheetConfigDTO();
        config.setProductTypeId("__RAW_WORKFLOW_OWNER__x");
        config.setProcesses(List.of(process));
        when(dep(WorkflowClerkSheetService.class).getWorkflowSheetConfig(F, "plan-1"))
                .thenAnswer(afterReadingSecretProcessChain(() -> config));
        return mvc(controller(ProcessSheetController.class));
    }

    @Test
    void workflowConfigKeepsStepsAndMaterialsButHidesInputShares() throws Exception {
        JsonNode data = call(workflowConfig(),
                get("/api/mobile/F-A/production-plans/plan-1/process-sheet/workflow-config"), user(OPERATOR));
        assertThat(data.at("/processes/0/processName").asText()).isEqualTo("腌制");
        assertThat(data.at("/processes/0/inputs/0/materialName").asText()).isEqualTo("牛肉");
        assertThat(data.at("/processes/0/inputs/0/inputShareRatio").isNull()).isTrue();
        verifyDenied(user(OPERATOR), "inputShareRatio");
    }

    @Test
    void aVendorJsonAcceptHeaderDoesNotBypassTheScrubber() throws Exception {
        // 独立审查 #1: application/*+json 同样由 Jackson 写出, 不能因为「不是 application/json」就放过
        String body = workflowConfig().perform(
                        get("/api/mobile/F-A/production-plans/plan-1/process-sheet/workflow-config")
                                .header("Accept", "application/vnd.cretas+json")
                                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, user(OPERATOR)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("0.65");
        assertThat(json.readTree(body).at("/data/processes/0/inputs/0/inputShareRatio").isNull()).isTrue();
    }

    @Test
    void workflowConfigShowsInputSharesToTheOwner() throws Exception {
        JsonNode data = call(workflowConfig(),
                get("/api/mobile/F-A/production-plans/plan-1/process-sheet/workflow-config"), user(OWNER));
        assertThat(data.at("/processes/0/inputs/0/inputShareRatio").decimalValue()).isEqualByComparingTo("0.65");
    }

    // ------------------------------------------------------------------ 缺料 (快照)

    MockMvc shortageReport() throws Exception {
        SalesOrderShortageReport report = new SalesOrderShortageReport();
        report.setFactoryId(F);
        report.setSalesOrderId("so-1");
        MaterialRequirement need = new MaterialRequirement();
        need.setMaterialTypeName("秘方香料");
        need.setRequiredQuantity(new BigDecimal("130"));
        need.setWastageRate(new BigDecimal("7.5"));
        report.setTotalRequired(List.of(need));
        LineItemMatch line = new LineItemMatch();
        line.setProductTypeId(SECRET);
        report.setAvailable(List.of(line));
        when(dep(SalesOrderShortageReportRepository.class).findByFactoryIdAndSalesOrderId(F, "so-1"))
                .thenAnswer(invocation -> {
                    com.cretas.aims.service.processsecret.ProcessSecretTestHooks.record(report);
                    return Optional.of(report);
                });
        return mvc(controller(SalesOrderShortageController.class));
    }

    @Test
    void shortageSnapshotHidesWastageButKeepsRequiredTotals() throws Exception {
        JsonNode need = call(shortageReport(), get("/api/mobile/F-A/sales/orders/so-1/shortage-report"),
                user(OPERATOR)).at("/totalRequired/0");
        assertThat(need.get("wastageRate").isNull()).isTrue();
        assertThat(need.get("requiredQuantity").asInt()).isEqualTo(130);
        verifyDenied(user(OPERATOR), "wastageRate");
    }

    @Test
    void shortageSnapshotShowsWastageToTheOwner() throws Exception {
        JsonNode need = call(shortageReport(), get("/api/mobile/F-A/sales/orders/so-1/shortage-report"),
                user(OWNER)).at("/totalRequired/0");
        assertThat(need.get("wastageRate").decimalValue()).isEqualByComparingTo("7.5");
    }

    // ------------------------------------------------------------------ 缺料预警 / 采购建议: 本单总量

    MockMvc materialAdvisory() throws Exception {
        when(dep(ProductionPlanService.class).getMaterialAdvisory(F, "plan-1"))
                .thenAnswer(afterReadingSecretRecipe(() -> ProductionPlanMaterialAdvisoryDTO.builder()
                        .planId("plan-1").hasWarning(true).warnings(List.of(ProductionPlanMaterialAdvisoryDTO.Item
                                .builder().materialName("秘方香料").requiredQuantity(new BigDecimal("130")).build()))
                        .build()));
        return mvc(controller(ProductionPlanController.class));
    }

    @Test
    void materialAdvisoryKeepsThisPlansTotalsForFactoryStaff() throws Exception {
        JsonNode warning = call(materialAdvisory(), get("/api/mobile/F-A/production-plans/plan-1/material-advisory"),
                user(OPERATOR)).at("/warnings/0");
        assertThat(warning.get("requiredQuantity").asInt()).isEqualTo(130);
        verifyNothingDenied();
    }

    @Test
    void materialAdvisoryHidesTotalsFromPlatformStaff() throws Exception {
        JsonNode warning = call(materialAdvisory(), get("/api/mobile/F-A/production-plans/plan-1/material-advisory"),
                platform()).at("/warnings/0");
        assertThat(warning.get("requiredQuantity").isNull()).isTrue();
        assertThat(warning.get("processSecret").get("visibility").asText()).isEqualTo("RESTRICTED");
        verifyDenied(platform(), "requiredQuantity");
    }

    MockMvc purchaseSuggestion() throws Exception {
        when(dep(PurchaseService.class).generatePurchaseSuggestion(F, "so-1"))
                .thenAnswer(afterReadingSecretRecipe(() -> PurchaseSuggestionResponse.builder().salesOrderId("so-1")
                        .items(List.of(PurchaseSuggestionResponse.SuggestionItem.builder().materialName("秘方香料")
                                .sourceProductName("秘制牛肉").requiredQuantity(new BigDecimal("130")).build()))
                        .build()));
        return mvc(controller(PurchaseController.class));
    }

    @Test
    void purchaseSuggestionKeepsTotalsForFactoryStaff() throws Exception {
        JsonNode item = call(purchaseSuggestion(), get("/api/mobile/F-A/purchase/orders/suggestions/from-so/so-1"),
                user(OPERATOR)).at("/items/0");
        assertThat(item.get("requiredQuantity").asInt()).isEqualTo(130);
    }

    @Test
    void purchaseSuggestionHidesTotalsFromPlatformStaff() throws Exception {
        JsonNode item = call(purchaseSuggestion(), get("/api/mobile/F-A/purchase/orders/suggestions/from-so/so-1"),
                platform()).at("/items/0");
        assertThat(item.get("requiredQuantity").isNull()).isTrue();
        verifyDenied(platform(), "requiredQuantity");
    }

    // ------------------------------------------------------------------ 生产计划 DTO 上的转换率

    MockMvc planDetail(String product) throws Exception {
        when(dep(ProductionPlanService.class).getProductionPlanById(F, "plan-1"))
                .thenReturn(ProductionPlanDTO.builder().id("plan-1").productTypeId(product)
                        .conversionRate(new BigDecimal("0.82")).wastageRate(new BigDecimal("3.5")).build());
        return mvc(controller(ProductionPlanController.class));
    }

    @Test
    void planDetailHidesConversionRateOfAProtectedProduct() throws Exception {
        JsonNode plan = call(planDetail(SECRET), get("/api/mobile/F-A/production-plans/plan-1"), user(OPERATOR));
        assertThat(plan.get("conversionRate").isNull()).isTrue();
        assertThat(plan.get("wastageRate").isNull()).isTrue();
        verifyDenied(user(OPERATOR), "conversionRate");
    }

    @Test
    void planDetailOfAnUnprotectedProductAndForTheOwnerIsUnchanged() throws Exception {
        assertThat(call(planDetail(PLAIN), get("/api/mobile/F-A/production-plans/plan-1"), user(OPERATOR))
                .get("conversionRate").decimalValue()).isEqualByComparingTo("0.82");
        assertThat(call(planDetail(SECRET), get("/api/mobile/F-A/production-plans/plan-1"), user(OWNER))
                .get("conversionRate").decimalValue()).isEqualByComparingTo("0.82");
        verifyNothingDenied();
    }

    // ------------------------------------------------------------------ 转换率表 / 试算

    MockMvc conversions() throws Exception {
        ConversionService service = dep(ConversionService.class);
        when(service.getConversionsByProduct(F, SECRET)).thenReturn(List.of(ConversionDTO.builder()
                .productTypeId(SECRET).materialTypeId("M1").conversionRate(new BigDecimal("0.82"))
                .wastageRate(new BigDecimal("3.5")).standardUsage(new BigDecimal("1.22")).build()));
        ConversionService.ProductOutput secretOut = new ConversionService.ProductOutput();
        secretOut.setProductTypeId(SECRET);
        secretOut.setQuantity(new BigDecimal("82"));
        ConversionService.ProductOutput plainOut = new ConversionService.ProductOutput();
        plainOut.setProductTypeId(PLAIN);
        plainOut.setQuantity(new BigDecimal("70"));
        when(service.calculateProductOutput(eq(F), eq("M1"), any())).thenReturn(List.of(secretOut, plainOut));
        return mvc(controller(ConversionController.class));
    }

    @Test
    void conversionTableHidesRatesOfAProtectedProduct() throws Exception {
        JsonNode row = call(conversions(), get("/api/mobile/F-A/conversions/product/P17"), user(OPERATOR)).get(0);
        assertThat(row.get("conversionRate").isNull()).isTrue();
        assertThat(row.get("standardUsage").isNull()).isTrue();
        verifyDenied(user(OPERATOR), "conversionRate");
    }

    @Test
    void conversionTableShowsRatesToTheOwner() throws Exception {
        JsonNode row = call(conversions(), get("/api/mobile/F-A/conversions/product/P17"), user(OWNER)).get(0);
        assertThat(row.get("conversionRate").decimalValue()).isEqualByComparingTo("0.82");
    }

    @Test
    void materialRequirementCalculatorWithAFreeQuantityIsRefusedByTheGuard() throws Exception {
        MockMvc mvc = conversions();
        mvc.perform(post("/api/mobile/F-A/conversions/calculate/material-requirement")
                        .param("productTypeId", SECRET).param("productQuantity", "1")
                        .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, user(OPERATOR)))
                .andExpect(status().isForbidden());
        verify(dep(ConversionService.class), never()).calculateMaterialRequirement(any(), any(), any());
        verify(recorder).record(eq(F), eq(SECRET), eq(user(OPERATOR)), eq(ProcessSecretAction.VIEW_FULL),
                eq("DENY"), anyString(), contains("material-requirement"));

        mvc.perform(post("/api/mobile/F-A/conversions/calculate/material-requirement")
                        .param("productTypeId", SECRET).param("productQuantity", "1")
                        .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, user(OWNER)))
                .andExpect(status().isOk());
    }

    @Test
    void conversionSuggestionFromHistoryIsACrossBatchAggregateAndNeedsFullView() throws Exception {
        // 独立审查 #4: 按全部历史批次算的转换率 = 跨批汇总 (Steve 第三问 A)
        MockMvc mvc = conversions();
        mvc.perform(get("/api/mobile/F-A/conversions/suggest").param("materialTypeId", "M1")
                        .param("productTypeId", SECRET).requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, user(OPERATOR)))
                .andExpect(status().isForbidden());
        verify(dep(ConversionService.class), never()).suggestConversionFromHistory(any(), any(), any());
        mvc.perform(get("/api/mobile/F-A/conversions/suggest").param("materialTypeId", "M1")
                        .param("productTypeId", SECRET).requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, user(OWNER)))
                .andExpect(status().isOk());
    }

    @Test
    void crossBatchYieldByProcessForANamedProtectedProductIsOnlyForTheOwner() throws Exception {
        // 独立审查第二轮 #1: 原生 SQL 汇总不经过打标; 点名保密产品的跨批汇总由路由闸拦 (Steve 第三问 A)
        com.cretas.aims.service.yield.YieldAnalysisService service = dep(com.cretas.aims.service.yield.YieldAnalysisService.class);
        when(service.aggregateByProcess(eq(F), any(), any(), eq(SECRET))).thenReturn(List.of());
        MockMvc mvc = mvc(controller(com.cretas.aims.controller.YieldAnalysisController.class));
        mvc.perform(get("/api/mobile/F-A/production/yield/by-process").param("productTypeId", SECRET)
                        .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, user(OPERATOR)))
                .andExpect(status().isForbidden());
        verify(service, never()).aggregateByProcess(any(), any(), any(), any());
        mvc.perform(get("/api/mobile/F-A/production/yield/by-process").param("productTypeId", SECRET)
                        .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, user(OWNER)))
                .andExpect(status().isOk());
    }

    @Test
    void productOutputCalculatorDropsProtectedProductsTheCallerCannotSee() throws Exception {
        JsonNode rows = call(conversions(), post("/api/mobile/F-A/conversions/calculate/product-output")
                .param("materialTypeId", "M1").param("materialQuantity", "100"), user(OPERATOR));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("productTypeId").asText()).isEqualTo(PLAIN);

        JsonNode ownerRows = call(conversions(), post("/api/mobile/F-A/conversions/calculate/product-output")
                .param("materialTypeId", "M1").param("materialQuantity", "100"), user(OWNER));
        assertThat(ownerRows).hasSize(2);
    }

    // ------------------------------------------------------------------ 复制候选 / 换料影响

    MockMvc copyCandidates() throws Exception {
        when(dep(BomCopyService.class).listCandidates(F, "TARGET")).thenReturn(List.of(BomCopyCandidateDTO.builder()
                .sourceProductTypeId(SECRET).sourceRecipeId("r1")
                .bomItems(List.of(BomCopyCandidateDTO.BomItemRuleDTO.builder().materialName("秘方香料")
                        .standardQuantity(new BigDecimal("3.25")).build()))
                .build()));
        return mvc(controller(BomRecipeController.class));
    }

    // ------------------------------------------------------------------ 报工表调料工序标记 (F006 验收缺陷 2)

    MockMvc seasoningFlags() throws Exception {
        com.cretas.aims.entity.bom.BomSeasoningItem pot = new com.cretas.aims.entity.bom.BomSeasoningItem();
        pot.setFactoryId(F);
        pot.setWorkProcessId("WP-STEW");
        pot.setDosagePerKgG(new BigDecimal("1.5"));
        pot.setSubsequentPotRatio(new BigDecimal("0.6"));
        com.cretas.aims.entity.bom.BomSeasoningItem plain = new com.cretas.aims.entity.bom.BomSeasoningItem();
        plain.setFactoryId(F);
        plain.setWorkProcessId("WP-MIX");
        plain.setDosagePerKgG(new BigDecimal("2.5"));
        com.cretas.aims.dto.bom.BomSeasoningResponse response = new com.cretas.aims.dto.bom.BomSeasoningResponse();
        response.setProductTypeId(SECRET);
        response.setSeasoningItems(List.of(pot, plain));
        when(dep(com.cretas.aims.service.bom.BomRecipeService.class).getSeasoningByProduct(F, SECRET))
                .thenAnswer(afterReadingSecretRecipe(() -> Optional.of(response)));
        return mvc(controller(BomRecipeController.class));
    }

    @Test
    void processSheetSeasoningFlagsStayAvailableWithoutRatiosSoTheSheetStillOpens() throws Exception {
        JsonNode data = call(seasoningFlags(),
                get("/api/mobile/F-A/bom/recipes/by-product/P17/seasoning/process-flags"), user(OPERATOR));
        assertThat(data.at("/processes/0/workProcessId").asText()).isEqualTo("WP-STEW");
        assertThat(data.at("/processes/0/potEnabled").asBoolean()).isTrue();
        assertThat(data.at("/processes/1/potEnabled").asBoolean()).isFalse();
        assertThat(data.toString()).doesNotContain("1.5").doesNotContain("0.6").doesNotContain("2.5");
    }

    @Test
    void theFullSeasoningRecipeStaysBehindTheFullViewGuard() throws Exception {
        MockMvc mvc = seasoningFlags();
        mvc.perform(get("/api/mobile/F-A/bom/recipes/by-product/P17/seasoning")
                        .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, user(OPERATOR)))
                .andExpect(status().isForbidden());
        verify(recorder).record(eq(F), eq(SECRET), eq(user(OPERATOR)), eq(ProcessSecretAction.VIEW_FULL),
                eq("DENY"), anyString(), contains("/seasoning"));
    }

    @Test
    void copyCandidatesHideAnotherProductsQuantities() throws Exception {
        JsonNode item = call(copyCandidates(), get("/api/mobile/F-A/bom/recipes/copy-candidates")
                .param("targetProductTypeId", "TARGET"), user(OPERATOR)).at("/0/bomItems/0");
        assertThat(item.get("standardQuantity").isNull()).isTrue();
        assertThat(item.get("materialName").asText()).isEqualTo("秘方香料");
        verifyDenied(user(OPERATOR), "standardQuantity");
    }

    @Test
    void copyCandidatesShowQuantitiesToTheOwner() throws Exception {
        JsonNode item = call(copyCandidates(), get("/api/mobile/F-A/bom/recipes/copy-candidates")
                .param("targetProductTypeId", "TARGET"), user(OWNER)).at("/0/bomItems/0");
        assertThat(item.get("standardQuantity").decimalValue()).isEqualByComparingTo("3.25");
    }

    MockMvc replaceImpact() throws Exception {
        when(dep(BomReverseQueryService.class).analyzeReplacement(F, "M1", "M2"))
                .thenReturn(MaterialReplacementImpact.builder().oldMaterialId("M1").newMaterialId("M2")
                        .oldUnitPrice(new BigDecimal("10")).newUnitPrice(new BigDecimal("12"))
                        .recipeBreakdown(List.of(MaterialReplacementImpact.RecipeImpact.builder().recipeId("r1")
                                .productName("秘制牛肉").costDelta(new BigDecimal("6.5"))
                                .oldRecipeMaterialCost(new BigDecimal("32.5"))
                                .newRecipeMaterialCost(new BigDecimal("39")).build()))
                        .build());
        return mvc(controller(BomReverseQueryController.class));
    }

    @Test
    void replaceImpactHidesPerRecipeCostDeltasThatRevealQuantities() throws Exception {
        JsonNode impact = call(replaceImpact(), get("/api/mobile/F-A/bom/reverse-query/replace-impact")
                .param("oldMaterialId", "M1").param("newMaterialId", "M2"), user(OPERATOR)).at("/recipeBreakdown/0");
        assertThat(impact.get("costDelta").isNull()).isTrue();
        assertThat(impact.get("oldRecipeMaterialCost").isNull()).isTrue();
        verifyDenied(user(OPERATOR), "costDelta");
    }

    @Test
    void replaceImpactShowsDeltasToTheOwner() throws Exception {
        JsonNode impact = call(replaceImpact(), get("/api/mobile/F-A/bom/reverse-query/replace-impact")
                .param("oldMaterialId", "M1").param("newMaterialId", "M2"), user(OWNER)).at("/recipeBreakdown/0");
        assertThat(impact.get("costDelta").decimalValue()).isEqualByComparingTo("6.5");
    }

    // ------------------------------------------------------------------ 工序链 / 补货看板 / 出成上限

    MockMvc processChain() throws Exception {
        when(dep(ProductWorkProcessService.class).listByProduct(F, SECRET)).thenReturn(List.of(
                ProductWorkProcessDTO.builder().productTypeId(SECRET).workProcessId("WP-1").processOrder(1)
                        .standardYieldRate(new BigDecimal("0.81")).build()));
        return mvc(controller(ProductWorkProcessController.class));
    }

    @Test
    void processChainKeepsStepsButHidesStandardYield() throws Exception {
        JsonNode step = call(processChain(), get("/api/mobile/F-A/product-work-processes")
                .param("productTypeId", SECRET), user(OPERATOR)).get(0);
        assertThat(step.get("workProcessId").asText()).isEqualTo("WP-1");
        assertThat(step.get("standardYieldRate").isNull()).isTrue();
        verifyDenied(user(OPERATOR), "standardYieldRate");
    }

    @Test
    void processChainShowsStandardYieldToTheOwner() throws Exception {
        JsonNode step = call(processChain(), get("/api/mobile/F-A/product-work-processes")
                .param("productTypeId", SECRET), user(OWNER)).get(0);
        assertThat(step.get("standardYieldRate").decimalValue()).isEqualByComparingTo("0.81");
    }

    MockMvc restockBoard() throws Exception {
        when(dep(RestockBoardService.class).getRestockHorizon(eq(F), any(), any())).thenReturn(RestockHorizonDTO
                .builder().rows(List.of(RestockHorizonProductRow.builder().productTypeId(SECRET)
                        .totalDemandQty(new BigDecimal("500")).rawToFgYield(new BigDecimal("0.82")).build()))
                .build());
        return mvc(controller(RestockBoardController.class));
    }

    @Test
    void restockBoardHidesYieldsButKeepsDemand() throws Exception {
        JsonNode row = call(restockBoard(), get("/api/mobile/F-A/restock-board/horizon").param("startDate", "2026-10-26").param("endDate", "2026-10-30"), user(OPERATOR))
                .at("/rows/0");
        assertThat(row.get("rawToFgYield").isNull()).isTrue();
        assertThat(row.get("totalDemandQty").asInt()).isEqualTo(500);
        verifyDenied(user(OPERATOR), "rawToFgYield");
    }

    @Test
    void restockBoardShowsYieldsToTheOwner() throws Exception {
        JsonNode row = call(restockBoard(), get("/api/mobile/F-A/restock-board/horizon").param("startDate", "2026-10-26").param("endDate", "2026-10-30"), user(OWNER)).at("/rows/0");
        assertThat(row.get("rawToFgYield").decimalValue()).isEqualByComparingTo("0.82");
    }

    MockMvc yieldLimits() throws Exception {
        when(dep(YieldReportService.class).getLimits(eq(F), eq(7L), any(), any()))
                .thenAnswer(afterReadingSecretProcessChain(() -> YieldLimitsDTO.builder()
                        .standardYieldMax(new BigDecimal("0.85")).maxAllowed(new BigDecimal("40")).build()));
        return mvc(controller(YieldReportController.class));
    }

    @Test
    void yieldLimitsHideTheStandardYield() throws Exception {
        JsonNode limits = call(yieldLimits(), get("/api/mobile/F-A/production/batches/7/yield/limits")
                .param("workProcessTaskId", "3").param("inputQuantity", "47"), user(OPERATOR));
        assertThat(limits.get("standardYieldMax").isNull()).isTrue();
        assertThat(limits.get("maxAllowed").asInt()).isEqualTo(40);
        verifyDenied(user(OPERATOR), "standardYieldMax");
    }

    @Test
    void yieldLimitsShowTheStandardYieldToTheOwner() throws Exception {
        JsonNode limits = call(yieldLimits(), get("/api/mobile/F-A/production/batches/7/yield/limits")
                .param("workProcessTaskId", "3").param("inputQuantity", "47"), user(OWNER));
        assertThat(limits.get("standardYieldMax").decimalValue()).isEqualByComparingTo("0.85");
    }
}
