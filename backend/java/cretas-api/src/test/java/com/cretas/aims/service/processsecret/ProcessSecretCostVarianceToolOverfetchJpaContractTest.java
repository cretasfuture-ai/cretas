package com.cretas.aims.service.processsecret;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.impl.report.ReportCostVarianceTool;
import com.cretas.aims.aspect.ProcessSecretToolOutputAspect;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PlanSourceType;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.report.impl.ProductionReportServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清点 A5 (2026-11-03): AI 工具 {@code report_cost_variance} 按产品查时, 原来先取全厂批次 (报表服务) 再在工具里筛产品。
 * 全厂批次里有保密产品的批次 —— 经真实 Hibernate PostLoad 记进 {@link ProcessSecretReadTracker}, 却不在输出里,
 * 出口按「归属不可信」把整份按受限处理: 无授权的本厂账号看<b>不保密</b>产品, 成本也被遮。
 *
 * <p>修法在报表服务层: 按产品查只从库里读这个产品的批次。⛔ 不用 untracked 绕开打标 ——
 * 那样 AI 访问审计记不到保密读数, 与保密产品同计划的兄弟产出也不再被遮。这里两条都钉住:</p>
 * <ul>
 *   <li>负责人看保密产品: AI_ACCESS 记一行 PROTECTED (阳性); 看普通产品记 NONE (阴性)。</li>
 *   <li>兄弟产出 (批次挂在锚定保密产品的计划上的普通产品): 无授权账号看它, 成本仍然被遮。</li>
 * </ul>
 *
 * <p>走真实工具 + 真实报表服务 + 真实工具出口切面 ({@link ProcessSecretToolOutputAspect}) + 真实打标 + 真实裁剪器。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({ProcessSecretAccessService.class, ProcessSecretAuditRecorder.class,
        ProcessSecretReadTrackerConfiguration.class, ProcessSecretAggregateExclusions.class,
        ProcessSecretLlmCostScope.class, ProcessSecretPayloadScrubber.class})
class ProcessSecretCostVarianceToolOverfetchJpaContractTest {

    static final String F = "F-PS-VARIANCE";
    static final String SECRET = "PT-CV-SECRET";    // 线上: QA-S4-1029-成品, 设了保密
    static final String NORMAL = "PT-CV-NORMAL";    // 线上: 【演示L3】卤味三拼 400g, 没设保密
    static final String SIBLING = "PT-CV-SIBLING";  // 没设保密, 但批次挂在锚定保密产品的联产计划上
    static final String JOINT_PLAN = "PP-CV-JOINT";
    static final long OWNER = 9201L;
    static final long COLLEAGUE = 9202L;           // 线上: f006_dept_admin, 无授权

    @Autowired EntityManager entityManager;
    @Autowired ProductionBatchRepository batches;
    @Autowired ProductionPlanRepository plans;
    @Autowired ProductTypeRepository productTypes;
    @Autowired ProcessSecretPolicyRepository policies;
    @Autowired ProcessSecretAccessService accessService;
    @Autowired ProcessSecretLlmCostScope costScope;
    @Autowired ProcessSecretPayloadScrubber scrubber;
    /** 审计写入是独立事务, 在 H2 测试事务里会提交漏出去: 换成桩, 断言打在它收到的那一行上。 */
    @MockBean ProcessSecretAuditRecorder auditRecorder;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ReportCostVarianceTool tool;

    @BeforeEach
    void seed() {
        Factory factory = new Factory();
        factory.setId(F);
        factory.setName("Process Secret Cost Variance Factory");
        entityManager.persist(factory);
        User user = new User();
        user.setFactoryId(F);
        user.setUsername("ps-variance-user");
        user.setPasswordHash("test");
        user.setIsActive(true);
        entityManager.persist(user);
        entityManager.flush();
        productTypes.saveAndFlush(product(SECRET, "QA-S4-SECRET", user.getId()));
        productTypes.saveAndFlush(product(NORMAL, "L3-NORMAL", user.getId()));
        productTypes.saveAndFlush(product(SIBLING, "SIBLING", user.getId()));
        plans.saveAndFlush(plan(JOINT_PLAN, SECRET, user.getId()));

        batches.saveAndFlush(batch("B-CV-SECRET", SECRET, null, "7777.77", "100"));
        batches.saveAndFlush(batch("B-CV-NORMAL", NORMAL, null, "3442.39", "100"));
        batches.saveAndFlush(batch("B-CV-SIBLING", SIBLING, JOINT_PLAN, "5000.00", "100"));
        // H2 把 jsonb 存成带引号的字符串, 读回失败; 这些列与本测试无关, 置空即可
        entityManager.createNativeQuery("UPDATE production_batches SET selected_bom_recipe_ids_by_product = NULL, "
                + "selected_bom_versions_by_product = NULL, workflow_output_units_by_product = NULL, "
                + "target_finished_good_ids = NULL, custom_fields = NULL, ai_invocation_metadata = NULL").executeUpdate();
        entityManager.createNativeQuery("UPDATE production_plans SET selected_bom_recipe_ids_by_product = NULL, "
                + "selected_bom_versions_by_product = NULL, workflow_output_units_by_product = NULL, "
                + "target_finished_good_ids = NULL, source_order_ids = NULL, related_orders = NULL").executeUpdate();

        policies.saveAndFlush(ProcessSecretPolicy.builder().factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build());
        accessService.invalidate(F);

        // 真实报表服务: 只有批次仓库参与成本差异报表
        ProductionReportServiceImpl production = new ProductionReportServiceImpl(null, null, null, null, null, null,
                null, null, batches, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(production, "llmCostScope", costScope);
        ReportService reports = mock(ReportService.class);
        when(reports.getCostVarianceReport(eq(F), any(), any())).thenAnswer(inv ->
                production.getCostVarianceReport(F, inv.getArgument(1), inv.getArgument(2)));
        when(reports.getCostVarianceReport(eq(F), any(), any(), anyString())).thenAnswer(inv ->
                production.getCostVarianceReport(F, inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));

        ReportCostVarianceTool target = new ReportCostVarianceTool();
        ReflectionTestUtils.setField(target, "reportService", reports);
        ReflectionTestUtils.setField(target, "llmCostScope", costScope);
        ReflectionTestUtils.setField(target, "objectMapper", mapper);
        ProcessSecretToolOutputAspect aspect = new ProcessSecretToolOutputAspect(scrubber, mapper);
        ReflectionTestUtils.setField(aspect, "aiAccessAudit", new ProcessSecretAiAccessAudit(accessService, costScope));
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAspect(aspect);
        tool = proxyFactory.getProxy();

        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    void resetRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** 与线上一致: 调用者挂在 HTTP 请求上, 工具经出口切面执行, 请求结束时写 AI 访问审计。 */
    private Response ask(long actorId, String productTypeId) throws Exception {
        AuthenticatedActor actor = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, actorId, F, "operator");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        ServletRequestAttributes attributes = new ServletRequestAttributes(request);
        RequestContextHolder.setRequestAttributes(attributes);
        entityManager.clear();
        try {
            String args = "{\"productTypeId\":\"" + productTypeId + "\",\"startDate\":\"" + LocalDate.now().minusDays(7)
                    + "\",\"endDate\":\"" + LocalDate.now() + "\"}";
            String raw = tool.execute(ToolCall.of("1", "report_cost_variance", args),
                    Map.of("factoryId", F, "userId", actorId, "userRole", "operator"));
            attributes.requestCompleted();   // 请求结束: AI 访问审计在这里写
            return new Response(mapper.readTree(raw), raw);
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    record Response(JsonNode tree, String raw) {
        JsonNode data() {
            return tree.path("data").path("data");
        }

        JsonNode row(String productTypeId) {
            for (JsonNode row : data().path("productVariances")) {
                if (productTypeId.equals(row.path("productTypeId").asText())) return row;
            }
            throw new AssertionError("no row for " + productTypeId + " in " + raw);
        }

        int markers() {
            return tree.findValues(ProcessSecretPayloadScrubber.MARKER).size();
        }

        String message() {
            return tree.path("data").path("message").asText();
        }
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    /** 审计写入器收到的 AI 访问记录的结论 (PROTECTED / NONE)。 */
    private List<String> aiAccessDecisions() {
        ArgumentCaptor<String> decisions = ArgumentCaptor.forClass(String.class);
        verify(auditRecorder, atLeast(0)).record(eq(F), isNull(), any(), eq(ProcessSecretAction.AI_ACCESS),
                decisions.capture(), anyString(), anyString());
        return new ArrayList<>(decisions.getAllValues());
    }

    @Test
    void aColleagueWithoutAGrantSeesTheFullCostVarianceOfTheProductThatIsNotSecret() throws Exception {
        Response r = ask(COLLEAGUE, NORMAL);

        // 一定发生: 本产品的行确实出来了, 数量和批次数是真读数
        assertThat(r.row(NORMAL).path("quantity").decimalValue()).isEqualByComparingTo("100");
        assertThat(r.data().path("batchCount").asInt()).isEqualTo(1);
        // 没被遮: 单位成本、合计都在, 没有保密标记, 文案写的是本产品自己的合计
        assertThat(r.markers()).isZero();
        assertThat(r.row(NORMAL).path("actualUnitCost").decimalValue()).isEqualByComparingTo("34.42");
        assertThat(r.data().path("totalActualCost").decimalValue()).isEqualByComparingTo("3442.39");
        assertThat(absent(r.data().path("processSecretNote"))).isTrue();
        assertThat(r.message()).contains("实际总成本 ¥3442.39").doesNotContain("保密");
    }

    @Test
    void theSecretProductStaysHiddenFromTheSameColleague() throws Exception {
        Response r = ask(COLLEAGUE, SECRET);

        // 一定发生: 保密产品的行照常出来 (AI 回答按汇总视图, 无权时连单批数量也不给 —— 裁剪器既有口径)
        assertThat(r.row(SECRET).path("productName").asText()).isEqualTo(SECRET);
        assertThat(absent(r.row(SECRET).path("actualUnitCost"))).isTrue();
        assertThat(r.raw()).doesNotContain("7777.77").doesNotContain("77.78");
        assertThat(r.data().path("processSecretNote").asText()).contains("保密");
    }

    /** 兄弟产出: 普通产品的批次挂在锚定保密产品的计划上 —— 无授权账号看它, 成本照旧遮 (按计划, 不是按产品)。 */
    @Test
    void aSiblingOutputOfTheSecretProductsPlanStaysHiddenFromTheColleague() throws Exception {
        Response r = ask(COLLEAGUE, SIBLING);

        assertThat(r.row(SIBLING).path("productName").asText()).isEqualTo(SIBLING);   // 一定发生: 行照常出来
        assertThat(absent(r.row(SIBLING).path("actualUnitCost"))).isTrue();
        assertThat(r.raw()).doesNotContain("5000").doesNotContain("50.00");
        assertThat(r.data().path("processSecretNote").asText()).contains("保密");
        // 负责人看同一个兄弟产出: 完整
        Response owner = ask(OWNER, SIBLING);
        assertThat(owner.row(SIBLING).path("actualUnitCost").decimalValue()).isEqualByComparingTo("50.00");
    }

    @Test
    void theOwnerSeesBothProductsInFull() throws Exception {
        Response secret = ask(OWNER, SECRET);
        assertThat(secret.row(SECRET).path("actualUnitCost").decimalValue()).isEqualByComparingTo("77.78");
        assertThat(secret.markers()).isZero();
        Response normal = ask(OWNER, NORMAL);
        assertThat(normal.row(NORMAL).path("actualUnitCost").decimalValue()).isEqualByComparingTo("34.42");
    }

    /** AI 访问审计: 负责人看保密产品, 读数照常打标 ⇒ 记 PROTECTED; 看普通产品 ⇒ 记 NONE (阴性对照)。 */
    @Test
    void theOwnersReadOfTheSecretProductIsAuditedAsProtected() throws Exception {
        ask(OWNER, NORMAL);
        assertThat(aiAccessDecisions()).containsExactly("NONE");

        ask(OWNER, SECRET);
        assertThat(aiAccessDecisions()).containsExactly("NONE", "PROTECTED");
    }

    /**
     * 机制对照: 同一次工具调用里真读到了保密产品的批次 (例如有人又改回「先取全厂再筛」), 出口仍把整份遮掉 ——
     * 兜底规则没被放宽, 修的是「别读不输出的东西」。
     */
    @Test
    void readingTheSecretProductsBatchesInTheSameCallStillMasksTheWholeOutput() throws Exception {
        ReportService fullFactoryRead = mock(ReportService.class);
        ReportService real = (ReportService) ReflectionTestUtils.getField(
                ((org.springframework.aop.framework.Advised) tool).getTargetSource().getTarget(), "reportService");
        when(fullFactoryRead.getCostVarianceReport(eq(F), any(), any(), anyString())).thenAnswer(inv -> {
            batches.findByFactoryIdAndCreatedAtBetween(F, LocalDateTime.now().minusDays(7), LocalDateTime.now().plusDays(1));
            return real.getCostVarianceReport(F, inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
        });
        ReflectionTestUtils.setField(((org.springframework.aop.framework.Advised) tool).getTargetSource().getTarget(),
                "reportService", fullFactoryRead);

        Response r = ask(COLLEAGUE, NORMAL);

        assertThat(r.markers()).isPositive();
        assertThat(absent(r.row(NORMAL).path("actualUnitCost"))).isTrue();
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

    static ProductionPlan plan(String id, String anchorProductTypeId, Long createdBy) {
        ProductionPlan p = new ProductionPlan();
        p.setId(id);
        p.setFactoryId(F);
        p.setPlanNumber(id);
        p.setProductTypeId(anchorProductTypeId);
        p.setPlannedQuantity(new BigDecimal("300"));
        p.setPlannedUnit("kg");
        p.setStatus(ProductionPlanStatus.PENDING);
        p.setSourceType(PlanSourceType.MANUAL);
        p.setCreatedBy(createdBy);
        p.setIsLocked(false);
        p.setSkipProcessReporting(false);
        return p;
    }

    static ProductionBatch batch(String number, String productTypeId, String planId, String totalCost, String quantity) {
        ProductionBatch b = new ProductionBatch();
        b.setFactoryId(F);
        b.setBatchNumber(number);
        b.setProductTypeId(productTypeId);
        b.setProductName(productTypeId);
        b.setProductionPlanId(planId);
        b.setQuantity(new BigDecimal(quantity));
        b.setActualQuantity(new BigDecimal(quantity));
        b.setUnit("kg");
        b.setStatus(ProductionBatchStatus.COMPLETED);
        b.setTotalCost(new BigDecimal(totalCost));
        return b;
    }
}
