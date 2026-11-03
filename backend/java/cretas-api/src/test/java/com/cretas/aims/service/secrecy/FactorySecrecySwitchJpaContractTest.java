package com.cretas.aims.service.secrecy;

import com.cretas.aims.dto.ai.CostAIContext;
import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.processsecret.ProcessSecretGrant;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.impl.AIContextServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.service.processsecret.ProcessSecretCostMask;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.processsecret.ProcessSecretPayloadScrubber;
import com.cretas.aims.service.processsecret.ProcessSecretReadTracker;
import com.cretas.aims.service.processsecret.ProcessSecretReadTrackerConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工厂级保密配置 a / b / e (+ d 的全厂并集) 在真实入口上的开 / 关对照: 真实 Hibernate 打标
 * ({@link ProcessSecretReadTracker}) + 真实出口裁剪 ({@link ProcessSecretPayloadScrubber}, 与 ResponseAdvice 同一调用),
 * 同一个请求只改配置行。模式照 ProcessSecretCostContextOverfetchJpaContractTest。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({ProcessSecretAccessService.class, ProcessSecretAuditRecorder.class,
        ProcessSecretReadTrackerConfiguration.class, ProcessSecretAggregateExclusions.class,
        ProcessSecretLlmCostScope.class, ProcessSecretPayloadScrubber.class, ProcessSecretCostMask.class,
        ProcessSecretSampleFilter.class, FactorySecrecySwitchJpaContractTest.Config.class})
class FactorySecrecySwitchJpaContractTest {

    static final String F = "F-SW-A";
    static final String G = "F-SW-B";
    static final String SECRET = "PT-SW-SECRET";
    static final String HELD = "PT-SW-HELD";
    static final String G_SECRET = "PT-SW-G-SECRET";
    static final long OWNER = 9301L;
    static final long COLLEAGUE = 9302L;
    static final long GRANTEE = 9303L;

    /** 切片里读配置不另开事务 (生产是 REQUIRES_NEW 只读事务; 这里配置行写在测试事务里, 另开事务看不到)。 */
    @TestConfiguration
    static class Config {
        @Bean
        FactorySecrecyConfig factorySecrecyConfig(FactorySecrecySettingRepository repository) {
            return new FactorySecrecyConfig(repository, (org.springframework.transaction.PlatformTransactionManager) null);
        }
    }

    @Autowired EntityManager entityManager;
    @Autowired ProductionBatchRepository batches;
    @Autowired ProductTypeRepository productTypes;
    @Autowired ProcessSecretPolicyRepository policies;
    @Autowired ProcessSecretGrantRepository grants;
    @Autowired FactorySecrecySettingRepository settings;
    @Autowired FactorySecrecyConfig secrecy;
    @Autowired ProcessSecretAccessService accessService;
    @Autowired ProcessSecretLlmCostScope costScope;
    @Autowired ProcessSecretPayloadScrubber scrubber;
    @Autowired ProcessSecretCostMask costMask;
    @Autowired ProcessSecretSampleFilter sampleFilter;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private AIContextServiceImpl contexts;

    @BeforeEach
    void seed() {
        for (String id : new String[]{F, G}) {
            Factory factory = new Factory();
            factory.setId(id);
            factory.setName("Secrecy Switch " + id);
            entityManager.persist(factory);
        }
        User user = new User();
        user.setFactoryId(F);
        user.setUsername("secrecy-switch-user");
        user.setPasswordHash("test");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        productTypes.saveAndFlush(product(F, SECRET, "SW保密卤牛肉", user.getId()));
        productTypes.saveAndFlush(product(F, HELD, "SW客户侧酱鸭", user.getId()));
        productTypes.saveAndFlush(product(G, G_SECRET, "SW乙厂秘制酱", user.getId()));
        batches.saveAndFlush(batch(F, "B-SW-SECRET", SECRET, "7777.77", "100"));
        batches.saveAndFlush(batch(F, "B-SW-HELD", HELD, "5555.55", "100"));
        entityManager.createNativeQuery("UPDATE production_batches SET selected_bom_recipe_ids_by_product = NULL, "
                + "selected_bom_versions_by_product = NULL, workflow_output_units_by_product = NULL, "
                + "target_finished_good_ids = NULL, custom_fields = NULL, ai_invocation_metadata = NULL").executeUpdate();
        policies.saveAndFlush(policy(F, SECRET, ProcessSecretStorageMode.CLOUD_RESTRICTED));
        policies.saveAndFlush(policy(F, HELD, ProcessSecretStorageMode.CUSTOMER_HELD));
        policies.saveAndFlush(policy(G, G_SECRET, ProcessSecretStorageMode.CLOUD_RESTRICTED));
        grants.saveAndFlush(ProcessSecretGrant.builder().factoryId(F).productTypeId(SECRET)
                .granteeType(ProcessSecretGranteeType.FACTORY_USER).granteeId(GRANTEE)
                .canViewFull(true).canExport(false).canEdit(false).purpose("对账")
                .expiresAt(LocalDateTime.now().plusDays(3)).grantedBy(OWNER).createdAt(LocalDateTime.now()).build());

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

    // ------------------------------------------------------------------ a

    @Test
    void processSecretOffShowsTheColleagueTheFullCostAndTurningItBackOnRestoresThePolicyAndGrants() {
        set(F, SecrecyItem.PROCESS_SECRET, true);
        JsonNode on = costContext(actor(COLLEAGUE), SECRET);
        assertThat(on.path("avgActualUnitCost").isNull() || on.path("avgActualUnitCost").isMissingNode()).isTrue();
        assertThat(on.path("processSecretNote").asText()).contains("保密");
        assertThat(costContext(actor(GRANTEE), SECRET).path("avgActualUnitCost").decimalValue())
                .as("有授权的人照常看").isEqualByComparingTo("77.7777");

        set(F, SecrecyItem.PROCESS_SECRET, false);
        JsonNode off = costContext(actor(COLLEAGUE), SECRET);
        assertThat(off.path("avgActualUnitCost").decimalValue()).isEqualByComparingTo("77.7777");
        assertThat(off.findValues(ProcessSecretPayloadScrubber.MARKER)).isEmpty();
        assertThat(accessService.protectedProductTypeIds(F)).doesNotContain(SECRET);
        // 策略与授权都还在库里, 只是不生效
        assertThat(policies.findByFactoryIdAndProductTypeId(F, SECRET)).isPresent();
        assertThat(grants.findAll()).hasSize(1);

        set(F, SecrecyItem.PROCESS_SECRET, true);
        JsonNode again = costContext(actor(COLLEAGUE), SECRET);
        assertThat(again.path("avgActualUnitCost").isNull() || again.path("avgActualUnitCost").isMissingNode())
                .as("重新开启: 原策略恢复生效").isTrue();
        assertThat(costContext(actor(GRANTEE), SECRET).path("avgActualUnitCost").decimalValue())
                .as("重新开启: 原授权恢复生效").isEqualByComparingTo("77.7777");
    }

    @Test
    void theRowFactoryDecidesNotTheRequestFactory() {
        // F 开着、G 关着; 一份平台输出里两家的行都有
        set(F, SecrecyItem.PROCESS_SECRET, true);
        set(F, SecrecyItem.PLATFORM_RECIPE_BLOCK, true);
        set(G, SecrecyItem.PROCESS_SECRET, false);
        AuthenticatedActor platform = platform();
        // 前提 (一定成立的读数): F 的保密产品生效、平台对它无权; G 没有生效的保密产品
        assertThat(accessService.protectedProductTypeIds(F)).contains(SECRET);
        assertThat(accessService.protectedProductTypeIds(G)).isEmpty();
        assertThat(accessService.decide(platform, F, SECRET,
                com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL).permitted()).isFalse();

        ArrayNode viaG = rows();
        boolean changedViaG = scrubber.scrub(viaG, platform, G, Set.of(), "GET /test", false);
        assertThat(changedViaG).as("请求工厂 G 关着, F 的行照样裁").isTrue();
        assertThat(viaG.get(0).path("conversionRate").isNull()).as("F 行的配比被去掉").isTrue();
        assertThat(viaG.get(0).has(ProcessSecretPayloadScrubber.MARKER)).isTrue();
        assertThat(viaG.get(1).path("conversionRate").asDouble()).as("G 行照常").isEqualTo(0.7);
        assertThat(viaG.get(1).has(ProcessSecretPayloadScrubber.MARKER)).isFalse();

        // 反过来: 请求工厂 F 开着, G 的行也不因此被裁
        ArrayNode viaF = rows();
        scrubber.scrub(viaF, platform, F, Set.of(), "GET /test", false);
        assertThat(viaF.get(0).path("conversionRate").isNull()).isTrue();
        assertThat(viaF.get(1).path("conversionRate").asDouble()).isEqualTo(0.7);
    }

    // ------------------------------------------------------------------ b

    @Test
    void platformRecipeBlockOffLetsThePlatformSeeTheFactorysRecipeOnlyWhileProcessSecretIsOn() {
        set(F, SecrecyItem.PROCESS_SECRET, true);
        set(F, SecrecyItem.PLATFORM_RECIPE_BLOCK, true);
        JsonNode on = costContext(platform(), SECRET);
        assertThat(on.path("avgActualUnitCost").isNull() || on.path("avgActualUnitCost").isMissingNode()).isTrue();
        assertThat(quantitiesHiddenFor(platform())).as("b 开: 平台连数量也看不到").isTrue();

        set(F, SecrecyItem.PLATFORM_RECIPE_BLOCK, false);
        JsonNode off = costContext(platform(), SECRET);
        assertThat(off.path("avgActualUnitCost").decimalValue()).isEqualByComparingTo("77.7777");
        assertThat(quantitiesHiddenFor(platform())).as("b 关: 平台看得到").isFalse();
        // 本厂无权账号照旧被遮
        JsonNode colleague = costContext(actor(COLLEAGUE), SECRET);
        assertThat(colleague.path("avgActualUnitCost").isNull() || colleague.path("avgActualUnitCost").isMissingNode()).isTrue();
        // B 档 (e 开) 平台照样看不到
        set(F, SecrecyItem.CUSTOMER_HELD, true);
        assertThat(accessService.decide(platform(), F, HELD,
                com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL).permitted()).isFalse();
    }

    // ------------------------------------------------------------------ e

    @Test
    void customerHeldIsIndependentOfProcessSecret() {
        set(F, SecrecyItem.PROCESS_SECRET, false);
        set(F, SecrecyItem.CUSTOMER_HELD, true);
        JsonNode on = costContext(actor(OWNER), HELD);
        assertThat(on.path("avgActualUnitCost").isNull() || on.path("avgActualUnitCost").isMissingNode())
                .as("a 关、e 开: B 档照旧, 连负责人都不给").isTrue();
        assertThat(accessService.protectedProductTypeIds(F)).containsExactly(HELD);

        set(F, SecrecyItem.CUSTOMER_HELD, false);
        JsonNode off = costContext(actor(COLLEAGUE), HELD);
        assertThat(off.path("avgActualUnitCost").decimalValue()).as("a、e 都关: 不保护").isEqualByComparingTo("55.5555");

        set(F, SecrecyItem.PROCESS_SECRET, true);
        JsonNode aOnly = costContext(actor(OWNER), HELD);
        assertThat(aOnly.path("avgActualUnitCost").decimalValue())
                .as("e 关、a 开: B 档退回 A 档, 负责人看得到").isEqualByComparingTo("55.5555");
    }

    // ------------------------------------------------------------------ d (全厂并集)

    @Test
    void theAllFactoriesUnionIsAlwaysComputedAndDropsOnlyFactoriesWhoseTrainingFilterIsOff() {
        set(F, SecrecyItem.PROCESS_SECRET, true);
        set(G, SecrecyItem.PROCESS_SECRET, true);
        set(F, SecrecyItem.TRAINING_FILTER, false);
        set(G, SecrecyItem.TRAINING_FILTER, true);
        // 写入方是 F (d 关): 并集照算, G 的保密名在里面
        assertThat(sampleFilter.allFactoriesProtectedNames()).contains("SW乙厂秘制酱").doesNotContain("SW保密卤牛肉");
        assertThat(sampleFilter.mentionsAnyFactoryProtectedProduct("给我看看SW乙厂秘制酱")).isTrue();
        // 本厂 (d 关) 不按保密名剔训练样本; G (d 开) 照剔
        assertThat(sampleFilter.mentionsProtectedProduct(F, "SW保密卤牛肉卖了多少")).isFalse();
        assertThat(sampleFilter.mentionsProtectedProduct(G, "SW乙厂秘制酱卖了多少")).isTrue();

        set(F, SecrecyItem.TRAINING_FILTER, true);
        assertThat(sampleFilter.allFactoriesProtectedNames()).contains("SW乙厂秘制酱", "SW保密卤牛肉");
        assertThat(sampleFilter.mentionsProtectedProduct(F, "SW保密卤牛肉卖了多少")).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private void set(String factoryId, SecrecyItem item, Boolean enabled) {
        settings.saveAndFlush(FactorySecrecySetting.builder().factoryId(factoryId).itemCode(item.name())
                .enabled(enabled).updatedAt(LocalDateTime.now()).updatedBy(1L).build());
        secrecy.invalidate(factoryId);
        accessService.invalidate(factoryId);
    }

    private JsonNode costContext(AuthenticatedActor actor, String productTypeId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        entityManager.clear();
        try {
            CostAIContext context = contexts.buildCostContextForCaller(F, productTypeId, 10);
            JsonNode tree = mapper.valueToTree(context);
            scrubber.scrub(tree, actor, F, ProcessSecretReadTracker.reads(),
                    "GET /api/mobile/" + F + "/ai/context/cost-analysis", false);
            return tree;
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private boolean quantitiesHiddenFor(AuthenticatedActor actor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            return costMask.quantitiesHidden(F);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private ArrayNode rows() {
        ArrayNode rows = mapper.createArrayNode();
        ObjectNode f = rows.addObject();
        f.put("factoryId", F);
        f.put("productTypeId", SECRET);
        f.put("conversionRate", 0.8);
        ObjectNode g = rows.addObject();
        g.put("factoryId", G);
        g.put("productTypeId", G_SECRET);
        g.put("conversionRate", 0.7);
        return rows;
    }

    static AuthenticatedActor actor(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    static AuthenticatedActor platform() {
        return new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "super_admin");
    }

    static ProcessSecretPolicy policy(String factoryId, String productTypeId, ProcessSecretStorageMode mode) {
        return ProcessSecretPolicy.builder().factoryId(factoryId).productTypeId(productTypeId).storageMode(mode)
                .ownerUserId(OWNER).createdBy(OWNER).build();
    }

    static ProductType product(String factoryId, String id, String name, Long createdBy) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(factoryId);
        p.setCode(id);
        p.setName(name);
        p.setCategory("finished");
        p.setUnit("kg");
        p.setIsActive(true);
        p.setCreatedBy(createdBy);
        return p;
    }

    static ProductionBatch batch(String factoryId, String number, String productTypeId, String totalCost, String quantity) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(factoryId);
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
