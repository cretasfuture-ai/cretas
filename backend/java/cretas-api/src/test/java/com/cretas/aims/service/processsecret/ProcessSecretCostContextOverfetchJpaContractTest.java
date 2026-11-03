package com.cretas.aims.service.processsecret;

import com.cretas.aims.dto.ai.CostAIContext;
import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.impl.AIContextServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-11-03 线上实测的误伤: F006 只给一个产品开了保密, 无授权的本厂账号看<b>另一个不保密</b>产品的
 * AI 成本上下文 (/ai/context/cost-analysis), 成本合计、单位成本、各项占比全被遮, 提示语还写着「该产品已设为保密」。
 *
 * <p>成因: {@code buildCostContext} 先取全厂近 3 个月的批次再在内存里筛本产品。每个加载到的批次都经
 * 真实 Hibernate PostLoad 记进 {@link ProcessSecretReadTracker}; 保密产品的批次被读到、却没出现在输出里,
 * 出口 ({@link ProcessSecretPayloadScrubber}) 按「归属不可信」把整份输出按受限处理。</p>
 *
 * <p>这里走真实的 Hibernate 打标 + 真实出口裁剪 (与 ProcessSecretResponseAdvice 同一个调用),
 * 原来的 AIContextEndpointsProcessSecretTest 用的是 mock 仓库, 打标与出口都不在场, 所以照不出来。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({ProcessSecretAccessService.class, ProcessSecretAuditRecorder.class,
        ProcessSecretReadTrackerConfiguration.class, ProcessSecretAggregateExclusions.class,
        ProcessSecretLlmCostScope.class, ProcessSecretPayloadScrubber.class})
class ProcessSecretCostContextOverfetchJpaContractTest {

    static final String F = "F-PS-OVERFETCH";
    static final String SECRET = "PT-OF-SECRET";   // 线上: QA-S4-1029-成品, 设了保密
    static final String NORMAL = "PT-OF-NORMAL";   // 线上: 【演示L3】卤味三拼 400g, 没设保密
    static final long OWNER = 9101L;
    static final long COLLEAGUE = 9102L;          // 线上: f006_dept_admin, 无授权

    @Autowired EntityManager entityManager;
    @Autowired ProductionBatchRepository batches;
    @Autowired ProductTypeRepository productTypes;
    @Autowired ProcessSecretPolicyRepository policies;
    @Autowired ProcessSecretAccessService accessService;
    @Autowired ProcessSecretLlmCostScope costScope;
    @Autowired ProcessSecretPayloadScrubber scrubber;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private AIContextServiceImpl contexts;

    @BeforeEach
    void seed() {
        Factory factory = new Factory();
        factory.setId(F);
        factory.setName("Process Secret Overfetch Factory");
        entityManager.persist(factory);
        User user = new User();
        user.setFactoryId(F);
        user.setUsername("ps-overfetch-user");
        user.setPasswordHash("test");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        productTypes.saveAndFlush(product(SECRET, "QA-S4-SECRET", user.getId()));
        productTypes.saveAndFlush(product(NORMAL, "L3-NORMAL", user.getId()));

        batches.saveAndFlush(batch("B-OF-SECRET", SECRET, "7777.77", "100"));
        batches.saveAndFlush(batch("B-OF-NORMAL", NORMAL, "3442.39", "100"));
        // H2 把 jsonb 的 Map 存成带引号的字符串, 读回 Map 失败; 这些列与本测试无关, 置空即可
        entityManager.createNativeQuery("UPDATE production_batches SET selected_bom_recipe_ids_by_product = NULL, "
                + "selected_bom_versions_by_product = NULL, workflow_output_units_by_product = NULL, "
                + "target_finished_good_ids = NULL, custom_fields = NULL, ai_invocation_metadata = NULL").executeUpdate();

        policies.saveAndFlush(ProcessSecretPolicy.builder().factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build());
        accessService.invalidate(F);

        BomService bom = mock(BomService.class);
        when(bom.calculateProductCost(eq(F), anyString())).thenReturn(BomCostSummaryDTO.builder()
                .totalCost(new BigDecimal("6.41")).materialCostTotal(new BigDecimal("6.41")).build());
        contexts = new AIContextServiceImpl(bom, mock(ReportService.class), batches, productTypes, costScope);

        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void resetRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 与 ProcessSecretResponseAdvice 相同: 请求作用域里读数据, 出口按本次读数裁剪。 */
    private Response respond(AuthenticatedActor actor, Supplier<?> beforeBuild, String productTypeId) {
        // 与线上一致: 调用者挂在 HTTP 请求上 (有请求时 AuthenticatedActor.fromCurrentExecution 只认请求里的)
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        entityManager.clear();
        try {
            if (beforeBuild != null) beforeBuild.get();
            CostAIContext context = contexts.buildCostContextForCaller(F, productTypeId, 10);
            JsonNode tree = mapper.valueToTree(context);
            Set<ProcessSecretReadTracker.Read> reads = ProcessSecretReadTracker.reads();
            boolean scrubbed = scrubber.scrub(tree, actor, F, reads,
                    "GET /api/mobile/" + F + "/ai/context/cost-analysis", false);
            return new Response(tree, scrubbed, reads);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    record Response(JsonNode tree, boolean scrubbed, Set<ProcessSecretReadTracker.Read> reads) {
        int markers() {
            return tree.findValues(ProcessSecretPayloadScrubber.MARKER).size();
        }
    }

    @Test
    void aColleagueWithoutAGrantSeesTheFullCostOfTheProductThatIsNotSecret() {
        Response r = respond(actor(COLLEAGUE), null, NORMAL);

        // 一定发生: 本产品的批次确实经真实 Hibernate 打标被记录 (否则下面的「没遮」不说明任何事)
        assertThat(r.reads()).contains(new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCT, F, NORMAL));
        // 另一个保密产品的批次没有被读到
        assertThat(r.reads()).doesNotContain(new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCT, F, SECRET));
        assertThat(r.scrubbed()).isFalse();
        assertThat(r.markers()).isZero();
        assertThat(r.tree().path("avgActualUnitCost").decimalValue()).isEqualByComparingTo("34.4239");
        assertThat(r.tree().path("bomTotalCost").decimalValue()).isEqualByComparingTo("6.41");
        assertThat(r.tree().path("actualMaterialCostRatio").isNull()).isFalse();
        assertThat(r.tree().path("processSecretNote").isMissingNode() || r.tree().path("processSecretNote").isNull()).isTrue();
    }

    @Test
    void theSecretProductStaysHiddenFromTheSameColleague() {
        Response r = respond(actor(COLLEAGUE), null, SECRET);

        assertThat(r.tree().path("avgActualUnitCost").isNull() || r.tree().path("avgActualUnitCost").isMissingNode()).isTrue();
        assertThat(r.tree().path("bomTotalCost").isNull() || r.tree().path("bomTotalCost").isMissingNode()).isTrue();
        assertThat(r.tree().path("processSecretNote").asText()).contains("保密");
        assertThat(r.tree().path("totalQuantity").decimalValue()).isEqualByComparingTo("100");   // 数量照常
    }

    @Test
    void theOwnerSeesBothProductsInFull() {
        assertThat(respond(actor(OWNER), null, SECRET).tree().path("avgActualUnitCost").decimalValue())
                .isEqualByComparingTo("77.7777");
        Response normal = respond(actor(OWNER), null, NORMAL);
        assertThat(normal.markers()).isZero();
        assertThat(normal.tree().path("avgActualUnitCost").decimalValue()).isEqualByComparingTo("34.4239");
    }

    /**
     * 机制对照: 同一个请求里如果真的读到了保密产品的批次 (例如有人又改回「先取全厂再筛」),
     * 出口仍然会把整份按受限处理 —— 兜底规则本身没被放宽, 修的是「别读不输出的东西」。
     */
    @Test
    void readingTheSecretProductsBatchesInTheSameRequestStillMasksTheWholeResponse() {
        Response r = respond(actor(COLLEAGUE),
                () -> batches.findByFactoryIdAndCreatedAtAfter(F, LocalDateTime.now().minusMonths(3)), NORMAL);

        assertThat(r.reads()).contains(new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCT, F, SECRET));
        assertThat(r.scrubbed()).isTrue();
        assertThat(r.markers()).isPositive();
        assertThat(r.tree().path("avgActualUnitCost").isNull()).isTrue();
    }

    // ------------------------------------------------ 同形的其他出口 (2026-11-03 清点 A2 / A3 / A5)

    /** 在与线上相同的请求作用域里跑一段读取, 返回读数与按读数裁剪后的输出。 */
    private Response run(AuthenticatedActor actor, Supplier<?> call) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        entityManager.clear();
        try {
            JsonNode tree = mapper.valueToTree(call.get());
            Set<ProcessSecretReadTracker.Read> reads = ProcessSecretReadTracker.reads();
            boolean scrubbed = scrubber.scrub(tree, actor, F, reads, "GET /test", false);
            return new Response(tree, scrubbed, reads);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private static final ProcessSecretReadTracker.Read SECRET_PRODUCT_READ =
            new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCT, F, SECRET);
    private static final ProcessSecretReadTracker.Read NORMAL_PRODUCT_READ =
            new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCT, F, NORMAL);

    /** A2 GET /conversions/suggest: 按历史批次给转换率建议, 只该读本产品的批次。 */
    @Test
    void conversionSuggestionForTheNormalProductDoesNotReadTheSecretProductsBatches() {
        com.cretas.aims.service.impl.ConversionServiceImpl conversions = new com.cretas.aims.service.impl.ConversionServiceImpl(
                mock(com.cretas.aims.repository.ConversionRepository.class),
                mock(com.cretas.aims.repository.RawMaterialTypeRepository.class), productTypes, batches,
                mock(com.cretas.aims.repository.MaterialConsumptionRepository.class),
                mock(com.cretas.aims.repository.MaterialBatchRepository.class),
                mock(com.cretas.aims.repository.ConversionChangeHistoryRepository.class),
                mock(com.cretas.aims.repository.UserRepository.class), mock(ProcessSecretRecipeGuard.class));

        Response r = run(actor(COLLEAGUE), () -> conversions.suggestConversionFromHistory(F, "MAT-OF-1", NORMAL));

        assertThat(r.reads()).contains(NORMAL_PRODUCT_READ);          // 一定发生: 本产品的批次被读到
        assertThat(r.reads()).doesNotContain(SECRET_PRODUCT_READ);
        assertThat(r.markers()).isZero();
    }

    /** A3 GET /processing/material-consumptions/stats?productionBatchId=: 指定批次时只读这一批的消耗。 */
    @Test
    void consumptionStatsOfTheNormalBatchDoNotReadTheSecretBatchesConsumptions() {
        Long secretBatch = batches.findByFactoryIdAndProductTypeIdAndCreatedAtAfter(F, SECRET,
                LocalDateTime.now().minusDays(1)).get(0).getId();
        Long normalBatch = batches.findByFactoryIdAndProductTypeIdAndCreatedAtAfter(F, NORMAL,
                LocalDateTime.now().minusDays(1)).get(0).getId();
        // 原料批次外键与本测试无关 (统计只按消耗记录本身): 只在插入这两行时关掉 H2 的外键检查
        entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
        entityManager.persist(consumption(secretBatch, "88.80"));
        entityManager.persist(consumption(normalBatch, "12.30"));
        entityManager.flush();
        entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
        com.cretas.aims.security.PriceMaskResolver prices = mock(com.cretas.aims.security.PriceMaskResolver.class);
        com.cretas.aims.repository.MaterialBatchRepository materialBatches =
                mock(com.cretas.aims.repository.MaterialBatchRepository.class);
        when(materialBatches.findAllById(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of());
        com.cretas.aims.controller.MaterialConsumptionController controller =
                new com.cretas.aims.controller.MaterialConsumptionController(consumptionRepository, materialBatches,
                        mock(com.cretas.aims.repository.RawMaterialTypeRepository.class),
                        mock(com.cretas.aims.repository.UserRepository.class),
                        mock(com.cretas.aims.service.BatchConsumptionService.class), prices);

        Response r = run(actor(COLLEAGUE), () -> controller.getConsumptionStats(F, normalBatch, null, null, null).getData());

        ProcessSecretReadTracker.Read secretRead = new ProcessSecretReadTracker.Read(
                ProcessSecretReadTracker.Kind.PRODUCTION_BATCH, F, String.valueOf(secretBatch));
        assertThat(r.reads()).contains(new ProcessSecretReadTracker.Read(
                ProcessSecretReadTracker.Kind.PRODUCTION_BATCH, F, String.valueOf(normalBatch)));   // 一定发生
        assertThat(r.reads()).doesNotContain(secretRead);
        assertThat(r.markers()).isZero();
        assertThat(r.tree().path("totalCost").decimalValue()).isEqualByComparingTo("12.30");
    }

    /** A5 AI 工具 report_cost_variance 按产品查: 报表自己已剔除看不到的成本, 工具不再把全厂批次记进读数。 */
    @Test
    void costVarianceToolForTheNormalProductIsNotMaskedByTheSecretProductsBatches() {
        ReportService reports = mock(ReportService.class);
        when(reports.getCostVarianceReport(eq(F), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    // 与 ProductionReportServiceImpl.getCostVarianceReport 一样取全厂批次
                    batches.findByFactoryIdAndCreatedAtAfter(F, LocalDateTime.now().minusMonths(1));
                    return com.cretas.aims.dto.report.CostVarianceReportDTO.builder().factoryId(F)
                            .totalActualCost(new BigDecimal("3442.39"))
                            .productVariances(new java.util.ArrayList<>(java.util.List.of(
                                    com.cretas.aims.dto.report.CostVarianceReportDTO.ProductCostVariance.builder()
                                            .productTypeId(SECRET).productName("QA-S4-SECRET").quantity(new BigDecimal("100")).build(),
                                    com.cretas.aims.dto.report.CostVarianceReportDTO.ProductCostVariance.builder()
                                            .productTypeId(NORMAL).productName("L3-NORMAL").quantity(new BigDecimal("100"))
                                            .actualUnitCost(new BigDecimal("34.4239")).build())))
                            .build();
                });
        com.cretas.aims.ai.tool.impl.report.ReportCostVarianceTool tool = new com.cretas.aims.ai.tool.impl.report.ReportCostVarianceTool();
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "reportService", reports);
        org.springframework.test.util.ReflectionTestUtils.setField(tool, "llmCostScope", costScope);

        Response r = run(actor(COLLEAGUE), () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(tool,
                "doExecute", F, new java.util.HashMap<>(java.util.Map.of("productTypeId", NORMAL)), new java.util.HashMap<>()));

        assertThat(r.reads()).doesNotContain(SECRET_PRODUCT_READ);
        assertThat(r.tree().toString()).contains("34.4239");
        assertThat(r.tree().toString()).doesNotContain(SECRET);
    }

    @Autowired com.cretas.aims.repository.MaterialConsumptionRepository consumptionRepository;

    static com.cretas.aims.entity.MaterialConsumption consumption(Long productionBatchId, String totalCost) {
        com.cretas.aims.entity.MaterialConsumption c = new com.cretas.aims.entity.MaterialConsumption();
        c.setFactoryId(F);
        c.setBatchId("MB-OF-" + productionBatchId);
        c.setProductionBatchId(productionBatchId);
        c.setQuantity(new BigDecimal("1"));
        c.setUnitPrice(new BigDecimal(totalCost));
        c.setTotalCost(new BigDecimal(totalCost));
        c.setConsumptionTime(LocalDateTime.now());
        c.setRecordedBy(COLLEAGUE);
        return c;
    }

    static AuthenticatedActor actor(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    static ProductType product(String id, String code, Long createdBy) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(F);
        p.setCode(code);
        p.setName(code);
        p.setCategory("finished");
        p.setUnit("kg");
        p.setIsActive(true);
        p.setCreatedBy(createdBy);
        return p;
    }

    static ProductionBatch batch(String number, String productTypeId, String totalCost, String quantity) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(F);
        b.setBatchNumber(number);
        b.setProductTypeId(productTypeId);
        b.setProductName(productTypeId);
        b.setQuantity(new BigDecimal(quantity));
        b.setActualQuantity(new BigDecimal(quantity));
        b.setUnit("kg");
        b.setStatus(ProductionBatchStatus.COMPLETED);
        b.setTotalCost(new BigDecimal(totalCost));
        b.setMaterialCost(new BigDecimal(totalCost).multiply(new BigDecimal("0.6")));
        b.setLaborCost(new BigDecimal(totalCost).multiply(new BigDecimal("0.3")));
        b.setEquipmentCost(new BigDecimal(totalCost).multiply(new BigDecimal("0.1")));
        return b;
    }
}
