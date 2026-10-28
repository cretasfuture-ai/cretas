package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一出口裁剪的判定规则。判定服务是真的 ({@link ProcessSecretAccessService}), 只有仓库和审计写入是桩,
 * 所以「拒绝来自这道闸、审计里有记录」是对真实判定的断言, 不是对桩的断言。
 */
class ProcessSecretPayloadScrubberTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final String PLAIN = "PLAIN";
    static final long OWNER = 10L;

    final ObjectMapper json = new ObjectMapper();
    ProcessSecretAuditRecorder recorder;
    ProcessSecretPayloadScrubber scrubber;
    ProductionPlanRepository plans;
    com.cretas.aims.repository.ProductionBatchRepository batches;

    @BeforeEach
    void setUp() {
        // 打标的「本厂有无保密产品」探针是静态的; 同一 JVM 里先跑的 Spring 测试上下文会装上它自己的 (缓存的
        // 上下文不销毁), 这里恢复默认「有」, 让工艺图照常解析
        ProcessSecretReadTracker.useProtectedFactoryProbe(null);
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        recorder = mock(ProcessSecretAuditRecorder.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
        ProcessSecretAccessService access = new ProcessSecretAccessService(policies, grants, recorder);

        BomRecipeRepository recipes = mock(BomRecipeRepository.class);
        when(recipes.findById("r-secret")).thenReturn(Optional.of(recipe("r-secret", SECRET)));
        when(recipes.findById("r-plain")).thenReturn(Optional.of(recipe("r-plain", PLAIN)));
        plans = mock(ProductionPlanRepository.class);
        batches = mock(com.cretas.aims.repository.ProductionBatchRepository.class);
        scrubber = com.cretas.aims.service.processsecret.ProcessSecretTestHooks.scrubber(access, recipes,
                mock(ProductionWorkflowInstanceRepository.class), plans, batches);
    }

    static BomRecipe recipe(String id, String product) {
        BomRecipe r = new BomRecipe();
        r.setId(id);
        r.setFactoryId(F);
        r.setProductTypeId(product);
        return r;
    }

    static AuthenticatedActor user(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    static AuthenticatedActor platform() {
        return new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "super_admin");
    }

    ObjectNode tree(String text) throws Exception {
        return (ObjectNode) json.readTree(text.replace('\'', '"'));
    }

    static Set<ProcessSecretReadTracker.Read> reads(ProcessSecretReadTracker.Kind kind, String id) {
        return Set.of(new ProcessSecretReadTracker.Read(kind, F, id));
    }

    @Test
    void factoryUserWithoutGrantKeepsMaterialsAndTotalsButLosesRatiosAndTheDenyIsAudited() throws Exception {
        ObjectNode t = tree("{'productTypeId':'P17','materialName':'秘方香料','conversionRate':0.82,"
                + "'wastageRate':5.5,'requiredQuantity':120}");
        assertThat(scrubber.scrub(t, user(5), F, Set.of(), "GET /x", false)).isTrue();
        assertThat(t.get("conversionRate").isNull()).isTrue();
        assertThat(t.get("wastageRate").isNull()).isTrue();
        assertThat(t.get("requiredQuantity").asInt()).isEqualTo(120);
        assertThat(t.get("materialName").asText()).isEqualTo("秘方香料");
        assertThat(t.get("processSecret").get("visibility").asText()).isEqualTo("TOTALS_ONLY");
        assertThat(t.get("processSecret").get("hiddenFields").toString())
                .contains("conversionRate").contains("wastageRate");
        verify(recorder).record(eq(F), eq(SECRET), eq(user(5)), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"),
                contains("降级"), eq("GET /x"));
    }

    @Test
    void ownerSeesEverythingAndTheFullReadIsAuditedOnce() throws Exception {
        ObjectNode t = tree("{'list':[{'productTypeId':'P17','conversionRate':0.82},"
                + "{'productTypeId':'P17','conversionRate':0.7}]}");
        assertThat(scrubber.scrub(t, user(OWNER), F, Set.of(), "GET /x", false)).isFalse();
        assertThat(t.toString()).contains("0.82").contains("0.7").doesNotContain("processSecret");
        verify(recorder).record(eq(F), eq(SECRET), eq(user(OWNER)), eq(ProcessSecretAction.VIEW_FULL), eq("ALLOW"),
                anyString(), eq("GET /x"));
    }

    @Test
    void ownerIsDowngradedWhenTheAllowAuditCannotBeWritten() throws Exception {
        doThrow(new IllegalStateException("db down")).when(recorder).record(any(), any(), any(), any(),
                eq("ALLOW"), any(), any());
        ObjectNode t = tree("{'productTypeId':'P17','conversionRate':0.82}");
        assertThat(scrubber.scrub(t, user(OWNER), F, Set.of(), "GET /x", false)).isTrue();
        assertThat(t.get("conversionRate").isNull()).isTrue();
    }

    @Test
    void platformStaffLoseTotalsToo() throws Exception {
        ObjectNode t = tree("{'productTypeId':'P17','dosagePerKgG':12.5,'requiredQuantity':120,'materialName':'盐'}");
        assertThat(scrubber.scrub(t, platform(), F, Set.of(), "GET /x", false)).isTrue();
        assertThat(t.get("dosagePerKgG").isNull()).isTrue();
        assertThat(t.get("requiredQuantity").isNull()).isTrue();
        assertThat(t.get("processSecret").get("visibility").asText()).isEqualTo("RESTRICTED");
        verify(recorder).record(eq(F), eq(SECRET), eq(platform()), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"),
                contains("无权"), eq("GET /x"));
    }

    @Test
    void unprotectedProductsAreNotTouchedAndNotAudited() throws Exception {
        ObjectNode t = tree("{'productTypeId':'PLAIN','conversionRate':0.9,'standardQuantity':3}");
        assertThat(scrubber.scrub(t, user(5), F, Set.of(), "GET /x", false)).isFalse();
        assertThat(t.get("conversionRate").asDouble()).isEqualTo(0.9);
        verify(recorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unattributedRatiosAreHiddenOnlyWhenRestrictedRecipeContentWasReadInThisScope() throws Exception {
        String body = "{'items':[{'materialName':'秘方香料','dosagePerKgG':12.5,'rawInputKg':40}]}";
        ObjectNode untainted = tree(body);
        assertThat(scrubber.scrub(untainted, user(5), F, Set.of(), "GET /x", false)).isFalse();

        ObjectNode tainted = tree(body);
        assertThat(scrubber.scrub(tainted, user(5), F, reads(ProcessSecretReadTracker.Kind.RECIPE, "r-secret"),
                "GET /x", false)).isTrue();
        JsonNode item = tainted.get("items").get(0);
        assertThat(item.get("dosagePerKgG").isNull()).isTrue();
        assertThat(item.get("rawInputKg").asInt()).isEqualTo(40);
        verify(recorder).record(eq(F), eq(SECRET), any(), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"),
                contains("dosagePerKgG"), eq("GET /x"));
    }

    @Test
    void multiOutputAnchorIsNotAnAttribution() throws Exception {
        // 锚点的 id 就是原料 id (只有名字带 __RAW_WORKFLOW_OWNER__); 读过受保护产品而它没作为归属出现 → 整份受限
        ObjectNode t = tree("{'productTypeId':'RM-BEEF','processes':[{'inputs':"
                + "[{'materialName':'牛肉','inputShareRatio':0.65}]}]}");
        assertThat(scrubber.scrub(t, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCT, SECRET), "GET /x", false))
                .isTrue();
        assertThat(t.at("/processes/0/inputs/0/inputShareRatio").isNull()).isTrue();
        assertThat(t.at("/processes/0/inputs/0/materialName").asText()).isEqualTo("牛肉");
    }

    @Test
    void restrictedReadThatNeverShowsUpAsAnAttributionMakesTheWholeOutputRestricted() throws Exception {
        // 内容挂在一个不受保护的产品名下, 但本次读过的是受保护产品的配方: 宁可多遮
        ObjectNode t = tree("{'productTypeId':'PLAIN','conversionRate':0.9}");
        assertThat(scrubber.scrub(t, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCT, SECRET), "GET /x", false))
                .isTrue();
        assertThat(t.get("conversionRate").isNull()).isTrue();

        // 对照: 受保护产品自己也出现在输出里时, 不受保护产品照常
        ObjectNode both = tree("{'rows':[{'productTypeId':'PLAIN','conversionRate':0.9},"
                + "{'productTypeId':'P17','conversionRate':0.8}]}");
        scrubber.scrub(both, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCT, SECRET), "GET /x", false);
        assertThat(both.at("/rows/0/conversionRate").asDouble()).isEqualTo(0.9);
        assertThat(both.at("/rows/1/conversionRate").isNull()).isTrue();
    }

    // Steve 2026-10-26 第三问 = A「单批可看、汇总不给」

    static final String ONE_BATCH = "{'productTypeId':'P17','yieldRate':0.91,'cumulativeYieldRate':0.38,"
            + "'lossRate':0.09,'inputQuantity':100,'outputQuantity':91}";

    @Test
    void factoryStaffSeeSingleBatchMeasuredYieldInDocumentViews() throws Exception {
        ObjectNode batch = tree(ONE_BATCH);
        assertThat(scrubber.scrub(batch, user(5), F, Set.of(), "GET /batches/1/yield", false)).isFalse();
        assertThat(batch.get("yieldRate").asDouble()).isEqualTo(0.91);
        assertThat(batch.get("cumulativeYieldRate").asDouble()).isEqualTo(0.38);
        assertThat(batch.get("lossRate").asDouble()).isEqualTo(0.09);
    }

    @Test
    void inAggregateViewsTheSameFiguresAndTheCrossBatchTotalsAreHidden() throws Exception {
        // 汇总视图里 Σ产出 ÷ Σ投入 就是汇总出成率 (独立审查第二轮 #4), 总量一起不给
        ObjectNode batch = tree(ONE_BATCH);
        assertThat(scrubber.scrub(batch, user(5), F, Set.of(), "GET /production-analytics/by-product", true))
                .isTrue();
        assertThat(batch.get("yieldRate").isNull()).isTrue();
        assertThat(batch.get("cumulativeYieldRate").isNull()).isTrue();
        assertThat(batch.get("lossRate").isNull()).isTrue();
        assertThat(batch.get("inputQuantity").isNull()).isTrue();
        assertThat(batch.get("outputQuantity").isNull()).isTrue();
        verify(recorder).record(eq(F), eq(SECRET), eq(user(5)), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"),
                contains("yieldRate"), eq("GET /production-analytics/by-product"));
    }

    @Test
    void aggregateOnlyFiguresAreHiddenEvenOutsideAggregateRoutes() throws Exception {
        ObjectNode metrics = tree("{'productTypeId':'P17','yieldRate':0.9,'goodOutputRate':0.95,'todayYieldRate':0.9}");
        assertThat(scrubber.scrub(metrics, user(5), F, Set.of(), "GET /x", false)).isTrue();
        assertThat(metrics.get("goodOutputRate").isNull()).isTrue();
        assertThat(metrics.get("todayYieldRate").isNull()).isTrue();
        // 与 goodOutputRate 同处的 yieldRate 是按产品的汇总, 不是单批
        assertThat(metrics.get("yieldRate").isNull()).isTrue();
    }

    @Test
    void ownersSeeEverythingAndPlatformStaffSeeNoMeasuredYieldAnywhere() throws Exception {
        ObjectNode owner = tree(ONE_BATCH);
        assertThat(scrubber.scrub(owner, user(OWNER), F, Set.of(), "GET /production-analytics/x", true)).isFalse();
        assertThat(owner.get("yieldRate").asDouble()).isEqualTo(0.91);

        ObjectNode platformView = tree(ONE_BATCH);
        assertThat(scrubber.scrub(platformView, platform(), F, Set.of(), "GET /batches/1/yield", false)).isTrue();
        assertThat(platformView.get("yieldRate").isNull()).isTrue();
        assertThat(platformView.get("cumulativeYieldRate").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ 独立审查发现的口子

    @Test
    void aWorkflowOwnedByARawMaterialAnchorIsRestrictedThroughItsTerminalOutputs() throws Exception {
        // 独立审查 V2.1 M3: 多产出工艺图挂在原料锚点上 (锚点 id 就是原料 id, 不受保护), 受保护的是它产出的成品
        String[] graph = ProcessSecretTestHooks.workflowGraphJson(List.of("RAW-BEEF"), List.of("Q-OPEN", SECRET));
        com.cretas.aims.entity.ProductProcessWorkflow workflow = new com.cretas.aims.entity.ProductProcessWorkflow();
        workflow.setFactoryId(F);
        workflow.setProductTypeId("RAW-BEEF");
        workflow.setNodesJson(graph[0]);
        workflow.setEdgesJson(graph[1]);
        Set<ProcessSecretReadTracker.Read> reads = Set.copyOf(ProcessSecretReadTracker.readsOf(workflow));
        String body = "{'productTypeId':'RAW-BEEF','nodes':[{'id':'process','data':{'ports':["
                + "{'id':'in-1','inputShareRatio':0.6,'materialName':'牛肉'}]}}]}";

        ObjectNode operator = tree(body);
        assertThat(scrubber.scrub(operator, user(5), F, reads, "GET /product-process-workflows/RAW-BEEF", false))
                .isTrue();
        assertThat(operator.at("/nodes/0/data/ports/0/inputShareRatio").isNull()).isTrue();
        assertThat(operator.at("/nodes/0/data/ports/0/materialName").asText()).isEqualTo("牛肉");

        ObjectNode owner = tree(body);
        assertThat(scrubber.scrub(owner, user(OWNER), F, reads, "GET /product-process-workflows/RAW-BEEF", false))
                .isFalse();
        assertThat(owner.at("/nodes/0/data/ports/0/inputShareRatio").asDouble()).isEqualTo(0.6);

        // 对照 (修复前): 只按锚点记读数时原样放出
        ObjectNode anchorOnly = tree(body);
        scrubber.scrub(anchorOnly, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCT, "RAW-BEEF"),
                "GET /product-process-workflows/RAW-BEEF", false);
        assertThat(anchorOnly.at("/nodes/0/data/ports/0/inputShareRatio").asDouble()).isEqualTo(0.6);
    }

    @Test
    void workflowGraphsAreOnlyParsedForFactoriesThatHaveProtectedProducts() {
        // 独立审查 V2.1 复核: 请求里总有记录处; 无保密产品的工厂不该每次加载工艺图都解析 JSON
        String[] graph = ProcessSecretTestHooks.workflowGraphJson(List.of("RAW-BEEF"), List.of("Q-OPEN", SECRET));
        com.cretas.aims.entity.ProductProcessWorkflow workflow = new com.cretas.aims.entity.ProductProcessWorkflow();
        workflow.setFactoryId("F-NONE");
        workflow.setProductTypeId("RAW-BEEF");
        workflow.setNodesJson(graph[0]);
        workflow.setEdgesJson(graph[1]);
        try {
            ProcessSecretReadTracker.useProtectedFactoryProbe("F-A"::equals);
            assertThat(ProcessSecretReadTracker.readsOf(workflow))
                    .extracting(ProcessSecretReadTracker.Read::kind)
                    .containsOnly(ProcessSecretReadTracker.Kind.PRODUCT);
            // 对照: 有保密产品的工厂照常解析出成员
            workflow.setFactoryId("F-A");
            assertThat(ProcessSecretReadTracker.readsOf(workflow))
                    .extracting(ProcessSecretReadTracker.Read::member).contains(SECRET);
        } finally {
            ProcessSecretReadTracker.useProtectedFactoryProbe(null);
        }
    }

    @Test
    void aWorkflowWhoseGraphCannotBeReadFailsClosedInsteadOfFallingBackToTheAnchor() throws Exception {
        // 独立审查 V2.1 复核: 图解析不了就无法确认牵涉哪些产品, 不能退回只看 (不受保护的) 锚点
        com.cretas.aims.entity.ProductProcessWorkflow workflow = new com.cretas.aims.entity.ProductProcessWorkflow();
        workflow.setFactoryId(F);
        workflow.setProductTypeId("RAW-BEEF");
        workflow.setNodesJson("{not json");
        Set<ProcessSecretReadTracker.Read> reads = Set.copyOf(ProcessSecretReadTracker.readsOf(workflow));
        ObjectNode operator = tree("{'productTypeId':'RAW-BEEF','nodes':[{'data':{'inputShareRatio':0.6}}]}");
        assertThat(scrubber.scrub(operator, user(5), F, reads, "GET /product-process-workflows/RAW-BEEF", false))
                .isTrue();
        assertThat(operator.at("/nodes/0/data/inputShareRatio").isNull()).isTrue();
    }

    @Test
    void reworkAutoDeductionQuantitiesThatAddUpToTheRatioAreHidden() throws Exception {
        // 独立审查 V2.1 H2: 目标 = 回料配比 × 主料投入, 手工 + 自动 + 仍缺 = 目标, 批次明细加起来 = 自动
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-6");
        plan.setFactoryId(F);
        plan.setProductTypeId(SECRET);
        when(plans.findByIdAndFactoryId("plan-6", F)).thenReturn(Optional.of(plan));
        String body = "{'productTypeId':'P17','reworkAutoDeductions':[{'reworkSkuId':'SEMI-R','inputRatioPercent':20,"
                + "'mainInputKg':100,'targetKg':20,'manualKg':5,'drawnKg':15,'shortfallKg':0,"
                + "'batches':[{'batchNumber':'RW-1','quantityKg':15}]}]}";

        ObjectNode operator = tree(body);
        scrubber.scrub(operator, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-6"),
                "GET /process-sheet/rows", false);
        JsonNode deduction = operator.at("/reworkAutoDeductions/0");
        assertThat(deduction.get("inputRatioPercent").isNull()).isTrue();
        assertThat(deduction.get("targetKg").isNull()).isTrue();
        assertThat(deduction.get("drawnKg").isNull()).isTrue();
        assertThat(deduction.get("shortfallKg").isNull()).isTrue();
        assertThat(deduction.at("/batches/0/quantityKg").isNull()).isTrue();
        assertThat(deduction.at("/batches/0/batchNumber").asText()).isEqualTo("RW-1");
        assertThat(deduction.get("mainInputKg").asInt()).as("主料实际投入照常").isEqualTo(100);
        assertThat(deduction.get("manualKg").asInt()).as("手工实际投入照常").isEqualTo(5);

        ObjectNode owner = tree(body);
        scrubber.scrub(owner, user(OWNER), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-6"),
                "GET /process-sheet/rows", false);
        assertThat(owner.at("/reworkAutoDeductions/0/targetKg").asInt()).isEqualTo(20);
        assertThat(owner.at("/reworkAutoDeductions/0/batches/0/quantityKg").asInt()).isEqualTo(15);
    }

    ProductionPlan securedPlan(String id) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(id);
        plan.setFactoryId(F);
        plan.setProductTypeId(SECRET);
        when(plans.findByIdAndFactoryId(id, F)).thenReturn(Optional.of(plan));
        return plan;
    }

    @Test
    void storedReworkAutoDeductionFeedIsHiddenButAManualReworkFeedStays() throws Exception {
        // 独立审查 V2.1 M4: 落库的 row_payload 里回料自动扣减只剩 automaticRework 标志, 投料量 = 回料配比 × 主料投入
        securedPlan("plan-8");
        String body = "{'rows':[{'productTypeId':'P17','upstreamSources':["
                + "{'sourceBatchNumber':'RW-1','feedQuantityKg':23,'automaticRework':true},"
                + "{'sourceBatchNumber':'RW-2','feedQuantityKg':5,'automaticRework':false}]}]}";

        ObjectNode operator = tree(body);
        scrubber.scrub(operator, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-8"),
                "GET /process-sheet/rows", false);
        assertThat(operator.at("/rows/0/upstreamSources/0/feedQuantityKg").isNull()).isTrue();
        assertThat(operator.at("/rows/0/upstreamSources/0/sourceBatchNumber").asText()).isEqualTo("RW-1");
        assertThat(operator.at("/rows/0/upstreamSources/1/feedQuantityKg").asInt()).as("手工回料照常").isEqualTo(5);

        ObjectNode owner = tree(body);
        scrubber.scrub(owner, user(OWNER), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-8"),
                "GET /process-sheet/rows", false);
        assertThat(owner.at("/rows/0/upstreamSources/0/feedQuantityKg").asInt()).isEqualTo(23);
    }

    @Test
    void rowHistorySummariesThatSpellOutAutomaticLinesAreHidden() throws Exception {
        // 操作记录的「字段: 旧→新」是快照转成的文字, 自动带出行的数量也在里面
        securedPlan("plan-9");
        String body = "[{'id':1,'operation':'UPDATE','diffSummary':'rawMaterialInputs: [{skuId=S-CINNAMON, "
                + "quantity=0.1725, automatic=true}]→[{skuId=S-CINNAMON, quantity=0.18, automatic=true}]'},"
                + "{'id':2,'operation':'UPDATE','diffSummary':'remark: 空→二号锅'},"
                + "{'id':3,'operation':'CREATE','diffSummary':'新建行'}]";

        JsonNode operator = json.readTree(body.replace('\'', '"'));
        scrubber.scrub(operator, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-9"),
                "GET /process-sheet/row/history", false);
        assertThat(operator.at("/0/diffSummary").isNull()).isTrue();
        assertThat(operator.at("/1/diffSummary").asText()).isEqualTo("remark: 空→二号锅");
        assertThat(operator.at("/2/diffSummary").asText()).isEqualTo("新建行");

        JsonNode owner = json.readTree(body.replace('\'', '"'));
        scrubber.scrub(owner, user(OWNER), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-9"),
                "GET /process-sheet/row/history", false);
        assertThat(owner.at("/0/diffSummary").asText()).contains("0.1725");
    }

    @Test
    void sharesOfManualRowsNextToAnAutomaticRowAreHidden() throws Exception {
        // 独立审查 V2.1 复核 H3: 人工 115 kg 占 99.85% → 自动行 = 115 × 0.15 ÷ 99.85, 不需要价格权限
        securedPlan("plan-10");
        String body = "{'sources':[{'batchName':'BEEF-1','quantity':115,'weightSharePct':99.85,'automatic':false},"
                + "{'batchName':'AUTO-1','quantity':0.1725,'weightSharePct':0.15,'automatic':true,"
                + "'initialQuantity':20,'remainingQuantity':19.8275}],"
                + "'byproducts':[{'name':'牛油','quantity':3,'weightSharePct':60},{'name':'牛筋','quantity':2,'weightSharePct':40}]}";

        ObjectNode operator = tree(body);
        scrubber.scrub(operator, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-10"),
                "GET /production/orders/o1/cost-breakdown", false);
        assertThat(operator.at("/sources/0/quantity").asInt()).as("人工实际投料照常").isEqualTo(115);
        assertThat(operator.at("/sources/0/weightSharePct").isNull()).isTrue();
        assertThat(operator.at("/sources/1/quantity").isNull()).isTrue();
        assertThat(operator.at("/sources/1/weightSharePct").isNull()).isTrue();
        // 自动行上的原料批次库存是库存事实, 不是配比 (复核 L2)
        assertThat(operator.at("/sources/1/initialQuantity").asInt()).isEqualTo(20);
        // 阴性对照: 没有自动行的列表, 占比照常
        assertThat(operator.at("/byproducts/0/weightSharePct").asInt()).isEqualTo(60);

        ObjectNode owner = tree(body);
        scrubber.scrub(owner, user(OWNER), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-10"),
                "GET /production/orders/o1/cost-breakdown", false);
        assertThat(owner.at("/sources/0/weightSharePct").decimalValue()).isEqualByComparingTo("99.85");
    }

    @Test
    void costTotalsOfAProtectedProductAreHiddenFromUnauthorizedAccounts() throws Exception {
        // Steve 2026-10-28 选 A: 调料小计 = 自动行金额, ÷ 单价 ÷ 投入 = 配比; 总成本 − 可见各项同理
        securedPlan("plan-12");
        String body = "{'orderId':'o1','seasoningCost':12.5,'rawMaterialCost':800,'laborCost':60,'totalCost':872.5,"
                + "'perBoxCost':8.725,'grossProfit':128,'grossMargin':12.8,'totalAmount':1000,'boxCount':100,"
                + "'missingCostItemCount':0,'outputQuantity':100,"
                + "'rawMaterialDetails':[{'materialName':'牛肉','quantity':115,'unitPrice':7,'amount':805,'automatic':false},"
                + "{'materialName':'盐','quantity':0.1725,'unitPrice':2,'amount':0.345,'automatic':true}]}";

        ObjectNode operator = tree(body);
        scrubber.scrub(operator, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-12"),
                "GET /production/orders/o1/cost-breakdown", false);
        for (String field : List.of("seasoningCost", "rawMaterialCost", "laborCost", "totalCost", "perBoxCost",
                "grossProfit", "grossMargin")) {
            assertThat(operator.path(field).isNull()).as(field).isTrue();
        }
        assertThat(operator.path("totalAmount").asInt()).as("销售额与配比无关").isEqualTo(1000);
        assertThat(operator.path("outputQuantity").asInt()).as("本单总量档").isEqualTo(100);
        assertThat(operator.path("boxCount").asInt()).isEqualTo(100);
        assertThat(operator.path("missingCostItemCount").asInt()).as("计数不是金额").isZero();
        assertThat(operator.at("/rawMaterialDetails/0/amount").asInt()).as("人工行金额是实际值").isEqualTo(805);
        assertThat(operator.at("/rawMaterialDetails/1/amount").isNull()).isTrue();
        assertThat(operator.path("processSecret").path("hiddenFields").toString()).contains("totalCost");
        assertThat(operator.path("processSecret").path("message").asText()).contains("成本合计");
        verify(recorder).record(eq(F), eq(SECRET), eq(user(5)), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"),
                contains("totalCost"), eq("GET /production/orders/o1/cost-breakdown"));

        ObjectNode owner = tree(body);
        scrubber.scrub(owner, user(OWNER), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-12"),
                "GET /production/orders/o1/cost-breakdown", false);
        assertThat(owner.path("totalCost").decimalValue()).isEqualByComparingTo("872.5");
        assertThat(owner.path("seasoningCost").decimalValue()).isEqualByComparingTo("12.5");
    }

    @Test
    void persistedBatchAndInventoryCostsOfAProtectedProductAreHiddenButPlainProductsKeepTheirs() throws Exception {
        // 批次 / 半成品 / 成品的单位成本是报工时把调料、包材的配方金额滚进去的, 出口不带明细行
        String body = "[{'productTypeId':'P17','batchNumber':'B1','materialCost':812,'totalCost':900,'unitCost':9,"
                + "'quantity':100,'costUnitPrice':9},"
                + "{'productTypeId':'PLAIN','batchNumber':'B2','materialCost':805,'totalCost':880,'unitCost':8.8}]";
        JsonNode operator = json.readTree(body.replace('\'', '"'));
        scrubber.scrub(operator, user(5), F, Set.of(), "GET /processing/batches", false);
        for (String field : List.of("materialCost", "totalCost", "unitCost", "costUnitPrice")) {
            assertThat(operator.at("/0/" + field).isNull()).as(field).isTrue();
        }
        assertThat(operator.at("/0/quantity").asInt()).as("本单总量档").isEqualTo(100);
        // 阴性对照: 不受保护的产品, 成本照给
        assertThat(operator.at("/1/materialCost").asInt()).isEqualTo(805);
        assertThat(operator.at("/1/unitCost").decimalValue()).isEqualByComparingTo("8.8");

        JsonNode platformView = json.readTree(body.replace('\'', '"'));
        scrubber.scrub(platformView, platform(), F, Set.of(), "GET /processing/batches", false);
        assertThat(platformView.at("/0/totalCost").isNull()).isTrue();
        assertThat(platformView.at("/0/quantity").isNull()).as("平台连总量也不给").isTrue();
    }

    @Test
    void bomAchievementTotalsAndOverallRateAreHidden() throws Exception {
        // 计划量 × 单价可见, 总达成率 = 实际总成本 ÷ 计划总成本 → 一乘就是含自动行的实际总成本
        securedPlan("plan-14");
        String body = "{'items':[{'materialTypeName':'牛肉','plannedQty':115,'actualQty':115,'unitPrice':7,'cost':805,"
                + "'automatic':false},{'materialTypeName':'盐','plannedQty':0.17,'actualQty':0.1725,'unitPrice':2,"
                + "'cost':0.345,'variancePercent':1.5,'automatic':true}],"
                + "'totalPlannedCost':805.34,'totalActualCost':805.345,'overallAchievementRate':100.0}";
        ObjectNode operator = tree(body);
        scrubber.scrub(operator, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-14"),
                "GET /material-consumptions/batch/1/summary", false);
        assertThat(operator.path("totalPlannedCost").isNull()).isTrue();
        assertThat(operator.path("totalActualCost").isNull()).isTrue();
        assertThat(operator.path("overallAchievementRate").isNull()).isTrue();
        assertThat(operator.at("/items/0/plannedQty").asInt()).as("本单总量档").isEqualTo(115);
        assertThat(operator.at("/items/0/actualQty").asInt()).isEqualTo(115);
        assertThat(operator.at("/items/1/actualQty").isNull()).isTrue();
    }

    @Test
    void anOrderTotalCoveringAProtectedProductLineHidesItsCostsButNotItsRevenue() throws Exception {
        // 销售单: 整单实际成本 − 其他行可见的成本 = 保密产品那一行的成本
        String body = "{'orderNumber':'SO-1','totalAmount':3000,'actualCost':1700,'actualProfit':1300,"
                + "'profitMarginActual':43.3,'lines':[{'productId':'P17','lineAmount':1000,'actualLineCost':900,"
                + "'quantity':100},{'productId':'PLAIN','lineAmount':2000,'actualLineCost':800}]}";
        ObjectNode operator = tree(body);
        scrubber.scrub(operator, user(5), F, Set.of(), "GET /sales/orders/SO-1/cost-breakdown", false);
        for (String field : List.of("actualCost", "actualProfit", "profitMarginActual")) {
            assertThat(operator.path(field).isNull()).as(field).isTrue();
        }
        assertThat(operator.path("totalAmount").asInt()).as("销售额照给").isEqualTo(3000);
        assertThat(operator.at("/lines/0/actualLineCost").isNull()).as("productId 命中保密产品即归属").isTrue();
        assertThat(operator.at("/lines/0/quantity").asInt()).isEqualTo(100);
        assertThat(operator.at("/lines/1/actualLineCost").asInt()).as("不受保护的行照给").isEqualTo(800);
        verify(recorder).record(eq(F), eq(SECRET), eq(user(5)), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"),
                contains("actualCost"), eq("GET /sales/orders/SO-1/cost-breakdown"));

        // 负责人: 全部照给
        ObjectNode owner = tree(body);
        scrubber.scrub(owner, user(OWNER), F, Set.of(), "GET /sales/orders/SO-1/cost-breakdown", false);
        assertThat(owner.path("actualCost").asInt()).isEqualTo(1700);
        assertThat(owner.at("/lines/0/actualLineCost").asInt()).isEqualTo(900);

        // 阴性对照: 整单都是不受保护的产品 (productId 不是保护产品时不当归属, 也不连带上层)
        ObjectNode plain = tree("{'actualCost':800,'lines':[{'productId':'PLAIN','actualLineCost':800}]}");
        scrubber.scrub(plain, user(5), F, Set.of(), "GET /sales/orders/SO-2/cost-breakdown", false);
        assertThat(plain.path("actualCost").asInt()).isEqualTo(800);
    }

    @Test
    void semiFinishedUnitPricesAreRolledUpCostsButPurchasePricesStay() throws Exception {
        // 独立审查 PR3 H2: WIP 行的 unitPrice = 落库单位成本, × 产量 = rowTotalCost
        String body = "{'productTypeId':'P17','wip':[{'batchNumber':'W1','produced':50,'unitPrice':16.2,'rowTotalCost':810}],"
                + "'allocations':[{'sourceType':'REWORK','quantity':5,'unitPrice':16.2,'totalCost':81},"
                + "{'sourceType':'RAW_MATERIAL','materialName':'牛肉','quantity':115,'unitPrice':7}]}";
        ObjectNode operator = tree(body);
        scrubber.scrub(operator, user(5), F, Set.of(), "GET /production-plans/p1/process-sheet/inventory", false);
        assertThat(operator.at("/wip/0/unitPrice").isNull()).isTrue();
        assertThat(operator.at("/wip/0/produced").asInt()).isEqualTo(50);
        assertThat(operator.at("/allocations/0/unitPrice").isNull()).as("回料单价是半成品单位成本").isTrue();
        assertThat(operator.at("/allocations/1/unitPrice").asInt()).as("原料采购价照给").isEqualTo(7);
    }

    @Test
    void aSiblingSummaryWithoutItsOwnProductIsHiddenWhenTheOutputHasARestrictedProtectedRow() throws Exception {
        // 独立审查 PR3 M1: 合计和产品行同级 (不在上层), 合计 − 其他可见行 = 保密产品那一行
        String body = "{'rows':[{'productId':'P17','totalCost':900},{'productId':'PLAIN','totalCost':800}],"
                + "'summary':{'totalCost':1700,'orderCount':2}}";
        ObjectNode operator = tree(body);
        scrubber.scrub(operator, user(5), F, Set.of(), "GET /finance/report/x", false);
        assertThat(operator.at("/summary/totalCost").isNull()).isTrue();
        assertThat(operator.at("/summary/orderCount").asInt()).isEqualTo(2);
        assertThat(operator.at("/rows/1/totalCost").asInt()).isEqualTo(800);

        // 阴性对照: 输出里没有保密产品行, 合计照给
        ObjectNode plain = tree("{'rows':[{'productId':'PLAIN','totalCost':800}],'summary':{'totalCost':800}}");
        scrubber.scrub(plain, user(5), F, Set.of(), "GET /finance/report/x", false);
        assertThat(plain.at("/summary/totalCost").asInt()).isEqualTo(800);
    }

    @Test
    void aProductIdNeverSwitchesOffDistrustOfAnOutputThatHidesWhatWasRead() throws Exception {
        // 独立审查 PR3 M2: 读的是受限计划, 输出只用 productId 指它 —— 不能算「归属可信」把别的行放出来
        securedPlan("plan-15");
        String body = "{'lines':[{'productId':'P17','actualLineCost':900},"
                + "{'productTypeId':'PLAIN','actualLineCost':800,'conversionRate':0.8}]}";
        ObjectNode operator = tree(body);
        scrubber.scrub(operator, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-15"),
                "GET /sales/orders/x/cost-breakdown", false);
        assertThat(operator.at("/lines/1/conversionRate").isNull()).isTrue();
        assertThat(operator.at("/lines/1/actualLineCost").isNull()).isTrue();

        // 有权的人: productId 不当归属, 照常看
        ObjectNode owner = tree(body);
        scrubber.scrub(owner, user(OWNER), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-15"),
                "GET /sales/orders/x/cost-breakdown", false);
        assertThat(owner.at("/lines/0/actualLineCost").asInt()).isEqualTo(900);
    }

    @Test
    void platformStaffAlsoLoseTheParentQuantityTotalAboveAProtectedRow() throws Exception {
        // 独立审查 PR3 M3: 平台看不到保密行的数量, 上层总量 − 其他行 = 那一行
        String body = "{'totalQuantity':300,'totalAmount':3000,'lines':[{'productId':'P17','quantity':100},"
                + "{'productId':'PLAIN','quantity':200}]}";
        ObjectNode platformView = tree(body);
        scrubber.scrub(platformView, platform(), F, Set.of(), "GET /sales/orders/x", false);
        assertThat(platformView.at("/lines/0/quantity").isNull()).isTrue();
        assertThat(platformView.path("totalQuantity").isNull()).isTrue();

        // 本厂账号: 本单总量照给
        ObjectNode operator = tree(body);
        scrubber.scrub(operator, user(5), F, Set.of(), "GET /sales/orders/x", false);
        assertThat(operator.path("totalQuantity").asInt()).isEqualTo(300);
    }

    @Test
    void theOwnerSeeingOnlyCostTotalsDoesNotFloodTheAuditWithFullViewRecords() throws Exception {
        // 独立审查 PR3 L2: 成本合计不是工艺内容, 不为它写 ALLOW
        ObjectNode owner = tree("{'productTypeId':'P17','totalCost':900,'unitCost':9}");
        scrubber.scrub(owner, user(OWNER), F, Set.of(), "GET /processing/batches/1", false);
        assertThat(owner.path("totalCost").asInt()).isEqualTo(900);
        verify(recorder, never()).record(any(), any(), any(), any(), eq("ALLOW"), any(), any());
    }

    @Test
    void costFieldNamesAreRecognisedButPricesRevenueAndCountsAreNot() {
        for (String name : List.of("totalCost", "unitCost", "costUnitPrice", "seasoningCost", "grossProfit",
                "grossMarginPct", "marginRate", "overallAchievementRate", "varianceAbsolute", "rowTotalCost",
                "subtotal", "stageSubtotal", "contributionPerBox")) {
            assertThat(ProcessSecretPayloadScrubber.isCostTotalField(name)).as(name).isTrue();
        }
        for (String name : List.of("unitPrice", "totalAmount", "salePrice", "missingCostItemCount", "costVersion",
                "quantity")) {
            assertThat(ProcessSecretPayloadScrubber.isCostTotalField(name)).as(name).isFalse();
        }
    }

    @Test
    void rowHistorySummariesWithBlendSnapshotsAreHiddenAndPlatformStaffGetNoSummaryText() throws Exception {
        // 复核 M3: 配比核对快照 (recipeSharePercent) 也写进了摘要文字; 平台从摘要里能读到本单总量
        securedPlan("plan-11");
        String body = "[{'id':1,'diffSummary':'blendRatio: {recipeSharePercent=60.0, actualSharePercent=58.2}→空'},"
                + "{'id':2,'diffSummary':'inputQuantity: 100→120'}]";

        JsonNode operator = json.readTree(body.replace('\'', '"'));
        scrubber.scrub(operator, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-11"),
                "GET /process-sheet/row/history", false);
        assertThat(operator.at("/0/diffSummary").isNull()).isTrue();
        assertThat(operator.at("/1/diffSummary").asText()).as("本厂账号看本单总量").isEqualTo("inputQuantity: 100→120");

        JsonNode platform = json.readTree(body.replace('\'', '"'));
        scrubber.scrub(platform, platform(), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-11"),
                "GET /process-sheet/row/history", false);
        assertThat(platform.at("/1/diffSummary").isNull()).isTrue();
    }

    @Test
    void consumptionOfABatchThatCanNoLongerBeFoundIsTreatedAsProtected() throws Exception {
        // 复核 M4: 只有生产批次的 BOM 自动扣料记录, 批次已软删时原来没有任何受限读数 → 原样输出
        when(batches.findByIdAndFactoryId(99L, F)).thenReturn(Optional.empty());
        String body = "{'content':[{'materialTypeId':'S-CINNAMON','quantity':0.1725,'automatic':true}]}";

        ObjectNode operator = tree(body);
        scrubber.scrub(operator, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_BATCH, "99"),
                "GET /processing/material-consumptions", false);
        assertThat(operator.at("/content/0/quantity").isNull()).isTrue();
    }

    @Test
    void rowHistoryRecordsTheSnapshotsOwnProductsAsPlanMembers() {
        // 复核 L1: 多产出行快照里的半成品 SKU 不在计划产品集合里, 要像报工行一样记成成员
        com.cretas.aims.entity.processentry.ProcessSheetRowChangeLog history =
                com.cretas.aims.entity.processentry.ProcessSheetRowChangeLog.builder().factoryId(F).planId("plan-9")
                        .afterValue(java.util.Map.of("productTypeId", "SEMI-X", "rawMaterialInputs", List.of())).build();
        assertThat(ProcessSecretReadTracker.readsOf(history)).containsExactly(
                new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, F, "plan-9"),
                new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, F, "plan-9", "SEMI-X"));
    }

    @Test
    void storedConsumptionAndRowHistoryAreTrackedByTheirPlan() {
        com.cretas.aims.entity.MaterialConsumption planned = new com.cretas.aims.entity.MaterialConsumption();
        planned.setFactoryId(F);
        planned.setProductionPlanId("plan-7");
        planned.setProductionBatchId(70L);
        assertThat(ProcessSecretReadTracker.readsOf(planned)).containsExactly(
                new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, F, "plan-7"));

        // 批次完工 BOM 自动扣料的旧记录可能没有计划: 按生产批次记 (出口经批次 → 计划解析)
        com.cretas.aims.entity.MaterialConsumption batchOnly = new com.cretas.aims.entity.MaterialConsumption();
        batchOnly.setFactoryId(F);
        batchOnly.setProductionBatchId(70L);
        assertThat(ProcessSecretReadTracker.readsOf(batchOnly)).containsExactly(
                new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCTION_BATCH, F, "70"));

        com.cretas.aims.entity.processentry.ProcessSheetRowChangeLog history =
                com.cretas.aims.entity.processentry.ProcessSheetRowChangeLog.builder().factoryId(F).planId("plan-9").build();
        assertThat(ProcessSecretReadTracker.readsOf(history)).containsExactly(
                new ProcessSecretReadTracker.Read(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, F, "plan-9"));
    }

    @Test
    void consumptionSourceTypesWrittenByTheRecipeAreRecognised() {
        assertThat(ProcessSecretRecipeDerived.isAutomaticConsumption("SEASONING")).isTrue();
        assertThat(ProcessSecretRecipeDerived.isAutomaticConsumption(" packaging ")).isTrue();
        assertThat(ProcessSecretRecipeDerived.isAutomaticConsumption("AUTO_BOM")).isTrue();
        // 人工 / 显式投料与差异调整是实际值
        assertThat(ProcessSecretRecipeDerived.isAutomaticConsumption("RAW_MATERIAL")).isFalse();
        assertThat(ProcessSecretRecipeDerived.isAutomaticConsumption("ADJUSTMENT")).isFalse();
        assertThat(ProcessSecretRecipeDerived.isAutomaticConsumption(null)).isFalse();
    }

    @Test
    void quantitiesDerivedFromTheRecipeAreHiddenButManualActualsStay() throws Exception {
        // F006 验收 2026-10-27: 报工行里按配比自动带出的调料用量 0.1725 kg ÷ 投入 115 kg = 1.5 g/kg,
        // 就是保密配比。automatic=true 的行按配比处理; 人工录入的实际投料 (automatic=false) 是本单总量, 照给
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-5");
        plan.setFactoryId(F);
        plan.setProductTypeId(SECRET);
        when(plans.findByIdAndFactoryId("plan-5", F)).thenReturn(Optional.of(plan));
        String body = "{'rows':[{'productTypeId':'P17','inputQuantity':115,'rawMaterialInputs':["
                + "{'skuId':'S-CINNAMON','quantity':0.1725,'unit':'kg','automatic':true,'sourceType':'SEASONING'},"
                + "{'skuId':'M-BEEF','quantity':115,'unit':'kg','automatic':false}]}]}";

        ObjectNode operator = tree(body);
        assertThat(scrubber.scrub(operator, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-5"),
                "GET /process-sheet/rows", false)).isTrue();
        assertThat(operator.at("/rows/0/rawMaterialInputs/0/quantity").isNull()).isTrue();
        assertThat(operator.at("/rows/0/rawMaterialInputs/0/skuId").asText()).isEqualTo("S-CINNAMON");
        assertThat(operator.at("/rows/0/rawMaterialInputs/1/quantity").asInt()).as("人工实际投料照常").isEqualTo(115);
        assertThat(operator.at("/rows/0/inputQuantity").asInt()).isEqualTo(115);
        verify(recorder).record(eq(F), eq(SECRET), eq(user(5)), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"),
                contains("quantity"), eq("GET /process-sheet/rows"));

        ObjectNode owner = tree(body);
        assertThat(scrubber.scrub(owner, user(OWNER), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-5"),
                "GET /process-sheet/rows", false)).isFalse();
        assertThat(owner.at("/rows/0/rawMaterialInputs/0/quantity").decimalValue()).isEqualByComparingTo("0.1725");
    }

    @Test
    void savedReportRowsUnderTheSemiFinishedProductStillHideRecipeShares() {
        // 审查 #2: 报工行的 productTypeId 是本道产出的半成品 (不受保护), 配比快照挂在它下面;
        // 读的是受保护产品计划的报工行 → 归属不可信, 整份按受限处理
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-1");
        plan.setFactoryId(F);
        plan.setProductTypeId(SECRET);
        when(plans.findByIdAndFactoryId("plan-1", F)).thenReturn(Optional.of(plan));
        com.cretas.aims.entity.processentry.ProcessSheetRow row = new com.cretas.aims.entity.processentry.ProcessSheetRow();
        row.setFactoryId(F);
        row.setPlanId("plan-1");
        Set<ProcessSecretReadTracker.Read> reads = Set.copyOf(ProcessSecretReadTracker.readsOf(row));
        ObjectNode t;
        try {
            t = tree("{'rows':[{'productTypeId':'SEMI-1','blendRatio':{'lines':[{'materialName':'牛肉',"
                    + "'recipeSharePercent':30,'actualSharePercent':25,'deviationPp':-5}]}}]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(scrubber.scrub(t, user(5), F, reads, "GET /process-sheet/rows", false)).isTrue();
        JsonNode line = t.at("/rows/0/blendRatio/lines/0");
        assertThat(line.get("recipeSharePercent").isNull()).isTrue();
        assertThat(line.get("deviationPp").isNull()).isTrue();
        assertThat(line.get("actualSharePercent").asInt()).as("单批实测, 单据视图照常").isEqualTo(25);
    }

    @Test
    void anUnprotectedSiblingOutputOfARestrictedPlanCannotCarryTheBlendSnapshotOut() throws Exception {
        // 独立审查第二轮 #2: 多产出工序给每个产出行都挂同一份配比快照; 兄弟产出 Q 不受保护也不能放
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-2");
        plan.setFactoryId(F);
        plan.setProductTypeId(SECRET);
        plan.setTargetFinishedGoodIds(List.of(SECRET, "Q-SIZE-2"));
        when(plans.findByIdAndFactoryId("plan-2", F)).thenReturn(Optional.of(plan));
        ObjectNode t = tree("{'rows':["
                + "{'productTypeId':'P17','blendRatio':{'lines':[{'recipeSharePercent':30,'deviationPp':-5}]}},"
                + "{'productTypeId':'Q-SIZE-2','blendRatio':{'lines':[{'recipeSharePercent':30,'deviationPp':-5}]}}]}");
        assertThat(scrubber.scrub(t, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-2"),
                "GET /process-sheet/rows", false)).isTrue();
        assertThat(t.at("/rows/0/blendRatio/lines/0/recipeSharePercent").isNull()).isTrue();
        assertThat(t.at("/rows/1/blendRatio/lines/0/recipeSharePercent").isNull()).isTrue();
        assertThat(t.at("/rows/1/blendRatio/lines/0/deviationPp").isNull()).isTrue();
    }

    @Test
    void reportRowsOfAMultiOutputPlanResolveThroughTheBatchNotTheRawMaterialAnchor() throws Exception {
        // 独立审查第三轮 A: 多产出计划的报工行 productTypeId = 原料锚点 (id 就是原料 id, 不受保护);
        // 只按它记读数, 受保护成品的配比快照会原样放出。报工行再按批次记一笔, 经批次 → 计划解析全部产品
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-3");
        plan.setFactoryId(F);
        plan.setProductTypeId("RM-BEEF");
        plan.setTargetFinishedGoodIds(List.of("Q-SIZE-2", SECRET));
        when(plans.findByIdAndFactoryId("plan-3", F)).thenReturn(Optional.of(plan));
        com.cretas.aims.entity.ProductionBatch batch = new com.cretas.aims.entity.ProductionBatch();
        batch.setFactoryId(F);
        batch.setProductTypeId("RM-BEEF");
        batch.setProductionPlanId("plan-3");
        when(batches.findByIdAndFactoryId(31L, F)).thenReturn(Optional.of(batch));
        com.cretas.aims.entity.ProductionReport report = new com.cretas.aims.entity.ProductionReport();
        report.setFactoryId(F);
        report.setProductTypeId("RM-BEEF");
        report.setBatchId(31L);
        Set<ProcessSecretReadTracker.Read> reads = Set.copyOf(ProcessSecretReadTracker.readsOf(report));
        String body = "{'reports':[{'productTypeId':'RM-BEEF','batchId':31,'customFields':{'blendRatio':{'lines':"
                + "[{'materialName':'牛肉','recipeSharePercent':30,'deviationPp':-5,'actualSharePercent':25}]}}}]}";

        ObjectNode operator = tree(body);
        assertThat(scrubber.scrub(operator, user(5), F, reads, "GET /reports/batch/31", false)).isTrue();
        JsonNode line = operator.at("/reports/0/customFields/blendRatio/lines/0");
        assertThat(line.get("recipeSharePercent").isNull()).isTrue();
        assertThat(line.get("deviationPp").isNull()).isTrue();
        assertThat(line.get("actualSharePercent").asInt()).as("单批实测, 单据视图照常").isEqualTo(25);
        verify(recorder).record(eq(F), eq(SECRET), eq(user(5)), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"),
                anyString(), eq("GET /reports/batch/31"));

        ObjectNode owner = tree(body);
        assertThat(scrubber.scrub(owner, user(OWNER), F, reads, "GET /reports/batch/31", false)).isFalse();
        assertThat(owner.at("/reports/0/customFields/blendRatio/lines/0/recipeSharePercent").asInt()).isEqualTo(30);
    }

    @Test
    void semiFinishedAndByproductRowsOfARestrictedPlanAreSiblingsEvenWhenTheProductIsAttributed() throws Exception {
        // 独立审查第三轮 C: 输出里同时有受保护产品 (计划头) 时归属可信, 不会整份受限;
        // 半成品 / 副产行不在计划的产品集合里, 只有把它们记成计划成员才会按兄弟产出受限
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-4");
        plan.setFactoryId(F);
        plan.setProductTypeId(SECRET);
        when(plans.findByIdAndFactoryId("plan-4", F)).thenReturn(Optional.of(plan));
        com.cretas.aims.entity.processentry.ProcessSheetRow row = new com.cretas.aims.entity.processentry.ProcessSheetRow();
        row.setFactoryId(F);
        row.setPlanId("plan-4");
        row.setRowPayload("{\"productTypeId\":\"SEMI-2\",\"outputs\":[{\"productTypeId\":\"BY-1\"}]}");
        Set<ProcessSecretReadTracker.Read> reads = Set.copyOf(ProcessSecretReadTracker.readsOf(row));
        String body = "{'plan':{'productTypeId':'P17','plannedQuantity':100},'rows':["
                + "{'productTypeId':'SEMI-2','blendRatio':{'lines':[{'recipeSharePercent':30,'actualSharePercent':25}]}},"
                + "{'productTypeId':'BY-1','inputShareRatio':0.2}]}";

        ObjectNode operator = tree(body);
        assertThat(scrubber.scrub(operator, user(5), F, reads, "GET /process-sheet/rows", false)).isTrue();
        assertThat(operator.at("/rows/0/blendRatio/lines/0/recipeSharePercent").isNull()).isTrue();
        assertThat(operator.at("/rows/0/blendRatio/lines/0/actualSharePercent").asInt()).isEqualTo(25);
        assertThat(operator.at("/rows/1/inputShareRatio").isNull()).isTrue();
        assertThat(operator.at("/plan/plannedQuantity").asInt()).as("本单总量照常").isEqualTo(100);

        ObjectNode owner = tree(body);
        assertThat(scrubber.scrub(owner, user(OWNER), F, reads, "GET /process-sheet/rows", false)).isFalse();
        assertThat(owner.at("/rows/0/blendRatio/lines/0/recipeSharePercent").asInt()).isEqualTo(30);

        // 对照 (修复前的读数): 只记计划、不记成员时, 半成品行的配方份额原样放出
        ObjectNode planOnly = tree(body);
        scrubber.scrub(planOnly, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-4"),
                "GET /process-sheet/rows", false);
        assertThat(planOnly.at("/rows/0/blendRatio/lines/0/recipeSharePercent").asInt()).isEqualTo(30);
    }

    @Test
    void platformStaffLoseEveryNonIdentifierNumberNotJustTheListedTotals() throws Exception {
        // 审查 #6: 总量词表列不全 (pickedQty / totalInput ...), 无权档改为除标识类以外的数字一律去掉
        ObjectNode t = tree("{'productTypeId':'P17','id':7,'processOrder':2,'pickedQty':12,'totalInput':40,"
                + "'materialName':'牛肉','totalElements':31,'page':2}");
        assertThat(scrubber.scrub(t, platform(), F, Set.of(), "GET /x", false)).isTrue();
        assertThat(t.get("pickedQty").isNull()).isTrue();
        assertThat(t.get("totalInput").isNull()).isTrue();
        assertThat(t.get("id").asInt()).isEqualTo(7);
        assertThat(t.get("processOrder").asInt()).isEqualTo(2);
        // 第二轮 #9: 分页信息保留, 否则列表翻不了页
        assertThat(t.get("totalElements").asInt()).isEqualTo(31);
        assertThat(t.get("page").asInt()).isEqualTo(2);
        // 对照: 本厂账号看得到这些总量
        ObjectNode staff = tree("{'productTypeId':'P17','pickedQty':12,'totalInput':40}");
        assertThat(scrubber.scrub(staff, user(5), F, Set.of(), "GET /x", false)).isFalse();
    }

    @Test
    void actualQuantityOnARecipeLineIsPerUnitUsageNotAnOrderTotal() throws Exception {
        // 审查 #9
        ObjectNode bomLine = tree("{'productTypeId':'P17','standardQuantity':3,'actualQuantity':3.16}");
        scrubber.scrub(bomLine, user(5), F, Set.of(), "GET /x", false);
        assertThat(bomLine.get("actualQuantity").isNull()).isTrue();
        ObjectNode consumption = tree("{'productTypeId':'P17','actualQuantity':120}");
        assertThat(scrubber.scrub(consumption, user(5), F, Set.of(), "GET /x", false)).isFalse();
        assertThat(consumption.get("actualQuantity").asInt()).isEqualTo(120);
    }

    @Test
    void aProcessSecretKeyInsideUserDataDoesNotSwitchScrubbingOff() throws Exception {
        // 审查 #11: 自定义字段里出现同名键也不能让整棵子树跳过
        ObjectNode t = tree("{'productTypeId':'P17','processSecret':{'note':'x'},'conversionRate':0.8}");
        assertThat(scrubber.scrub(t, user(5), F, Set.of(), "GET /x", false)).isTrue();
        assertThat(t.get("conversionRate").isNull()).isTrue();
    }

    @Test
    void aggregateRoutesAreRecognisedFromTheMatchedRouteTemplate() {
        assertThat(ProcessSecretPayloadScrubber.isAggregateRoute(
                "/api/mobile/{factoryId}/production-analytics/daily-trend")).isTrue();
        assertThat(ProcessSecretPayloadScrubber.isAggregateRoute("/api/mobile/{factoryId}/indicators/tree")).isTrue();
        assertThat(ProcessSecretPayloadScrubber.isAggregateRoute(
                "/api/mobile/{factoryId}/conversions/statistics")).isTrue();
        assertThat(ProcessSecretPayloadScrubber.isAggregateRoute(
                "/api/mobile/{factoryId}/production/yield/by-process")).isTrue();
        assertThat(ProcessSecretPayloadScrubber.isAggregateRoute(
                "/api/mobile/{factoryId}/production/batches/{batchId}/yield")).isFalse();
        assertThat(ProcessSecretPayloadScrubber.isAggregateRoute(
                "/api/mobile/{factoryId}/production/orders/{orderId}/cost-breakdown")).isFalse();
        // 第二轮 #6: 单张报工单不是汇总 (模块段是 process-work-reporting, 不因路径里有 reports 就算)
        assertThat(ProcessSecretPayloadScrubber.isAggregateRoute(
                "/api/mobile/{factoryId}/process-work-reporting/reports/{id}")).isFalse();
        // 第三轮 F: 半成品出成率汇总算, 半成品库存 / 盘点单据不算
        assertThat(ProcessSecretPayloadScrubber.isAggregateRoute(
                "/api/mobile/{factoryId}/production/yield/semi-finished/{semiFinishedSkuId}")).isTrue();
        assertThat(ProcessSecretPayloadScrubber.isAggregateRoute(
                "/api/mobile/{factoryId}/semi-finished/{id}")).isFalse();
    }

    @Test
    void copyCandidatesAreAttributedBySourceProductAndRecipeIds() throws Exception {
        ObjectNode t = tree("{'candidates':[{'sourceProductTypeId':'P17','bomItems':[{'standardQuantity':3.25}]},"
                + "{'sourceRecipeId':'r-secret','seasoningItems':[{'dosagePerKgG':8}]},"
                + "{'sourceRecipeId':'r-plain','seasoningItems':[{'dosagePerKgG':9}]}]}");
        assertThat(scrubber.scrub(t, user(5), F, Set.of(), "GET /x", false)).isTrue();
        assertThat(t.at("/candidates/0/bomItems/0/standardQuantity").isNull()).isTrue();
        assertThat(t.at("/candidates/1/seasoningItems/0/dosagePerKgG").isNull()).isTrue();
        assertThat(t.at("/candidates/2/seasoningItems/0/dosagePerKgG").asInt()).isEqualTo(9);
    }

    @Test
    void requisitionReadsResolveThroughThePlansProducts() throws Exception {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("plan-1");
        plan.setFactoryId(F);
        plan.setProductTypeId(SECRET);
        when(plans.findByIdAndFactoryId("plan-1", F)).thenReturn(Optional.of(plan));
        ObjectNode t = tree("{'productionPlanId':'plan-1','items':[{'materialName':'牛肉','inputShareRatio':0.6,"
                + "'requiredQty':30}]}");
        assertThat(scrubber.scrub(t, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "plan-1"),
                "AI tool x", false)).isTrue();
        assertThat(t.at("/items/0/inputShareRatio").isNull()).isTrue();
        assertThat(t.at("/items/0/requiredQty").asInt()).isEqualTo(30);
    }

    @Test
    void deletedPlanCountsAsUnknownProductAndFailsClosed() throws Exception {
        when(plans.findByIdAndFactoryId("gone", F)).thenReturn(Optional.empty());
        ObjectNode t = tree("{'items':[{'inputShareRatio':0.6}]}");
        assertThat(scrubber.scrub(t, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "gone"),
                "GET /x", false)).isTrue();
        assertThat(t.at("/items/0/inputShareRatio").isNull()).isTrue();
    }

    @Test
    void aggregatesNamedLikeConversionRatesAreCaughtBySuffix() throws Exception {
        ObjectNode t = tree("{'avgConversionRate':0.8,'averageWastageRate':4,'totalConversions':12}");
        assertThat(scrubber.scrub(t, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCT, SECRET), "GET /x", false))
                .isTrue();
        assertThat(t.get("avgConversionRate").isNull()).isTrue();
        assertThat(t.get("averageWastageRate").isNull()).isTrue();
        assertThat(t.get("totalConversions").asInt()).isEqualTo(12);
    }

    @Test
    void factoriesWithoutAnyPolicyTakeTheFastPath() throws Exception {
        ObjectNode t = tree("{'productTypeId':'P17','conversionRate':0.82}");
        assertThat(scrubber.scrub(t, new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 5L, "F-B", "operator"),
                "F-B", Set.of(), "GET /x", false)).isFalse();
        assertThat(t.get("conversionRate").asDouble()).isEqualTo(0.82);
    }
}
