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
        ProcessSecretAccessService access = new ProcessSecretAccessService(policies, grants, recorder);

        BomRecipeRepository recipes = mock(BomRecipeRepository.class);
        when(recipes.findById("r-secret")).thenReturn(Optional.of(recipe("r-secret", SECRET)));
        when(recipes.findById("r-plain")).thenReturn(Optional.of(recipe("r-plain", PLAIN)));
        plans = mock(ProductionPlanRepository.class);
        scrubber = com.cretas.aims.service.processsecret.ProcessSecretTestHooks.scrubber(access, recipes,
                mock(ProductionWorkflowInstanceRepository.class), plans);
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
        ObjectNode t = tree("{'productTypeId':'__RAW_WORKFLOW_OWNER__x','processes':[{'inputs':"
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
