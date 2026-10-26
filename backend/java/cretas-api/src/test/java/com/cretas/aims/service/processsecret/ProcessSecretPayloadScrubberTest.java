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
        assertThat(scrubber.scrub(t, user(5), F, Set.of(), "GET /x")).isTrue();
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
        assertThat(scrubber.scrub(t, user(OWNER), F, Set.of(), "GET /x")).isFalse();
        assertThat(t.toString()).contains("0.82").contains("0.7").doesNotContain("processSecret");
        verify(recorder).record(eq(F), eq(SECRET), eq(user(OWNER)), eq(ProcessSecretAction.VIEW_FULL), eq("ALLOW"),
                anyString(), eq("GET /x"));
    }

    @Test
    void ownerIsDowngradedWhenTheAllowAuditCannotBeWritten() throws Exception {
        doThrow(new IllegalStateException("db down")).when(recorder).record(any(), any(), any(), any(),
                eq("ALLOW"), any(), any());
        ObjectNode t = tree("{'productTypeId':'P17','conversionRate':0.82}");
        assertThat(scrubber.scrub(t, user(OWNER), F, Set.of(), "GET /x")).isTrue();
        assertThat(t.get("conversionRate").isNull()).isTrue();
    }

    @Test
    void platformStaffLoseTotalsToo() throws Exception {
        ObjectNode t = tree("{'productTypeId':'P17','dosagePerKgG':12.5,'requiredQuantity':120,'materialName':'盐'}");
        assertThat(scrubber.scrub(t, platform(), F, Set.of(), "GET /x")).isTrue();
        assertThat(t.get("dosagePerKgG").isNull()).isTrue();
        assertThat(t.get("requiredQuantity").isNull()).isTrue();
        assertThat(t.get("processSecret").get("visibility").asText()).isEqualTo("RESTRICTED");
        verify(recorder).record(eq(F), eq(SECRET), eq(platform()), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"),
                contains("无权"), eq("GET /x"));
    }

    @Test
    void unprotectedProductsAreNotTouchedAndNotAudited() throws Exception {
        ObjectNode t = tree("{'productTypeId':'PLAIN','conversionRate':0.9,'standardQuantity':3}");
        assertThat(scrubber.scrub(t, user(5), F, Set.of(), "GET /x")).isFalse();
        assertThat(t.get("conversionRate").asDouble()).isEqualTo(0.9);
        verify(recorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unattributedRatiosAreHiddenOnlyWhenRestrictedRecipeContentWasReadInThisScope() throws Exception {
        String body = "{'items':[{'materialName':'秘方香料','dosagePerKgG':12.5,'rawInputKg':40}]}";
        ObjectNode untainted = tree(body);
        assertThat(scrubber.scrub(untainted, user(5), F, Set.of(), "GET /x")).isFalse();

        ObjectNode tainted = tree(body);
        assertThat(scrubber.scrub(tainted, user(5), F, reads(ProcessSecretReadTracker.Kind.RECIPE, "r-secret"),
                "GET /x")).isTrue();
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
        assertThat(scrubber.scrub(t, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCT, SECRET), "GET /x"))
                .isTrue();
        assertThat(t.at("/processes/0/inputs/0/inputShareRatio").isNull()).isTrue();
        assertThat(t.at("/processes/0/inputs/0/materialName").asText()).isEqualTo("牛肉");
    }

    @Test
    void restrictedReadThatNeverShowsUpAsAnAttributionMakesTheWholeOutputRestricted() throws Exception {
        // 内容挂在一个不受保护的产品名下, 但本次读过的是受保护产品的配方: 宁可多遮
        ObjectNode t = tree("{'productTypeId':'PLAIN','conversionRate':0.9}");
        assertThat(scrubber.scrub(t, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCT, SECRET), "GET /x"))
                .isTrue();
        assertThat(t.get("conversionRate").isNull()).isTrue();

        // 对照: 受保护产品自己也出现在输出里时, 不受保护产品照常
        ObjectNode both = tree("{'rows':[{'productTypeId':'PLAIN','conversionRate':0.9},"
                + "{'productTypeId':'P17','conversionRate':0.8}]}");
        scrubber.scrub(both, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCT, SECRET), "GET /x");
        assertThat(both.at("/rows/0/conversionRate").asDouble()).isEqualTo(0.9);
        assertThat(both.at("/rows/1/conversionRate").isNull()).isTrue();
    }

    @Test
    void yieldRateIsTreatedAsStandardOnlyOnRecipeLines() throws Exception {
        ObjectNode actual = tree("{'productTypeId':'P17','yieldRate':0.91}");
        assertThat(scrubber.scrub(actual, user(5), F, Set.of(), "GET /x")).isFalse();
        assertThat(actual.get("yieldRate").asDouble()).isEqualTo(0.91);

        ObjectNode bomLine = tree("{'productTypeId':'P17','standardQuantity':1.2,'yieldRate':0.91}");
        assertThat(scrubber.scrub(bomLine, user(5), F, Set.of(), "GET /x")).isTrue();
        assertThat(bomLine.get("yieldRate").isNull()).isTrue();
        assertThat(bomLine.get("standardQuantity").isNull()).isTrue();
    }

    @Test
    void copyCandidatesAreAttributedBySourceProductAndRecipeIds() throws Exception {
        ObjectNode t = tree("{'candidates':[{'sourceProductTypeId':'P17','bomItems':[{'standardQuantity':3.25}]},"
                + "{'sourceRecipeId':'r-secret','seasoningItems':[{'dosagePerKgG':8}]},"
                + "{'sourceRecipeId':'r-plain','seasoningItems':[{'dosagePerKgG':9}]}]}");
        assertThat(scrubber.scrub(t, user(5), F, Set.of(), "GET /x")).isTrue();
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
                "AI tool x")).isTrue();
        assertThat(t.at("/items/0/inputShareRatio").isNull()).isTrue();
        assertThat(t.at("/items/0/requiredQty").asInt()).isEqualTo(30);
    }

    @Test
    void deletedPlanCountsAsUnknownProductAndFailsClosed() throws Exception {
        when(plans.findByIdAndFactoryId("gone", F)).thenReturn(Optional.empty());
        ObjectNode t = tree("{'items':[{'inputShareRatio':0.6}]}");
        assertThat(scrubber.scrub(t, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCTION_PLAN, "gone"),
                "GET /x")).isTrue();
        assertThat(t.at("/items/0/inputShareRatio").isNull()).isTrue();
    }

    @Test
    void aggregatesNamedLikeConversionRatesAreCaughtBySuffix() throws Exception {
        ObjectNode t = tree("{'avgConversionRate':0.8,'averageWastageRate':4,'totalConversions':12}");
        assertThat(scrubber.scrub(t, user(5), F, reads(ProcessSecretReadTracker.Kind.PRODUCT, SECRET), "GET /x"))
                .isTrue();
        assertThat(t.get("avgConversionRate").isNull()).isTrue();
        assertThat(t.get("averageWastageRate").isNull()).isTrue();
        assertThat(t.get("totalConversions").asInt()).isEqualTo(12);
    }

    @Test
    void factoriesWithoutAnyPolicyTakeTheFastPath() throws Exception {
        ObjectNode t = tree("{'productTypeId':'P17','conversionRate':0.82}");
        assertThat(scrubber.scrub(t, new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 5L, "F-B", "operator"),
                "F-B", Set.of(), "GET /x")).isFalse();
        assertThat(t.get("conversionRate").asDouble()).isEqualTo(0.82);
    }
}
