package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.workflow.BlendRatioDeviation;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.yield.MaterialBatchRef;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.cretas.aims.service.production.TaskMaterialBatchPickerService;
import com.cretas.aims.service.workflow.BlendRatioCheck.Fed;
import com.cretas.aims.service.workflow.BlendRatioCheck.SharePort;
import com.cretas.aims.service.yield.CostReconcileService;
import com.cretas.aims.service.yield.YieldCalculationService;
import com.cretas.aims.service.yield.impl.YieldCalculationServiceImpl;
import com.cretas.aims.service.yield.impl.YieldReportServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.stereotype.Component;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 06「汇合配比校验」(Steve 2026-10-25: 偏差 &gt; ±2 个百分点必须填原因, 填了就放行, 留痕)。
 *
 * <p>三层: ① 规则本身 ({@link BlendRatioCheck#evaluate}/{@link BlendRatioCheck#enforce});
 * ② 两个真实写入口 (web submitRow / App submitReport) 真的被它拦、真的把快照带进落库对象;
 * ③ 注入 (字段 required=false 会静默为 null, 所以钉 @Component + 两处调用)。</p>
 */
@DisplayName("缺口 06 汇合配比校验")
class BlendRatioCheckContractTest {

    private static final BigDecimal TOL = BlendRatioCheck.DEFAULT_TOLERANCE_PP;

    private static SharePort share(String id, String name, String share, String unit) {
        return new SharePort(id, name, share == null ? null : new BigDecimal(share), unit, null);
    }

    private static Fed fed(String id, String qty, String unit) {
        return new Fed(id, new BigDecimal(qty), unit);
    }

    private static final List<SharePort> BEEF_PORK_FAT = List.of(
            share("p-beef", "牛肉", "30", "kg"),
            share("p-pork", "猪肉", "30", "kg"),
            share("p-fat", "脂肪", "40", "kg"));

    private static BlendRatioDeviation reason(String reason, String note) {
        return BlendRatioDeviation.builder().reason(reason).note(note).build();
    }

    // =====================================================================
    // ① 规则
    // =====================================================================
    @Nested
    @DisplayName("规则")
    class Rule {

        @Test
        @DisplayName("Steve 的例子: 配方 30/30/40, 实际 25/41.7/33.3, 不填原因 → 409 BLEND_RATIO_REASON_REQUIRED, 点名超差的料")
        void steveExampleWithoutReasonIsRejected() {
            BlendRatioDeviation eval = BlendRatioCheck.evaluate(BEEF_PORK_FAT, List.of(
                    fed("p-beef", "25", "kg"), fed("p-pork", "41.7", "kg"), fed("p-fat", "33.3", "kg")), TOL);

            assertThat(eval).isNotNull();
            assertThat(eval.getLines()).extracting(BlendRatioDeviation.Line::isOverTolerance)
                    .containsExactly(true, true, true);
            assertThatThrownBy(() -> BlendRatioCheck.enforce(eval, null))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(409);
                        assertThat(e.getErrorCode()).isEqualTo(BlendRatioCheck.ERROR_CODE);
                        assertThat(e.getMessage()).contains("猪肉").contains("配方 30%").contains("实际 41.7%")
                                .contains("+11.7").contains("±2");
                        assertThat(e.getActionHint()).contains("偏差原因");
                    });
            // 空白原因 = 没原因
            assertThatThrownBy(() -> BlendRatioCheck.enforce(eval, reason("  ", null)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(BlendRatioCheck.ERROR_CODE));
        }

        @Test
        @DisplayName("容差内 (最大偏差 1pp) → 放行, 状态 WITHIN, 不留原因")
        void withinTolerancePasses() {
            BlendRatioDeviation eval = BlendRatioCheck.evaluate(BEEF_PORK_FAT, List.of(
                    fed("p-beef", "31", "kg"), fed("p-pork", "29.5", "kg"), fed("p-fat", "39.5", "kg")), TOL);

            BlendRatioDeviation out = BlendRatioCheck.enforce(eval, reason("称重误差", null));

            assertThat(out.getStatus()).isEqualTo(BlendRatioCheck.STATUS_WITHIN);
            assertThat(out.getReason()).as("容差内不留原因").isNull();
            assertThat(out.getMaxDeviationPp()).isEqualByComparingTo("1.00");
            assertThat(out.getLines()).extracting(BlendRatioDeviation.Line::getRecipeSharePercent)
                    .containsExactly(new BigDecimal("30.00"), new BigDecimal("30.00"), new BigDecimal("40.00"));
        }

        @Test
        @DisplayName("边界: 恰好 2pp 放行; 2.5pp (配方 30 / 实际 32.5) 超差")
        void boundaryIsStrictlyGreaterThanTwo() {
            BlendRatioDeviation exactlyTwo = BlendRatioCheck.evaluate(BEEF_PORK_FAT, List.of(
                    fed("p-beef", "32", "kg"), fed("p-pork", "28", "kg"), fed("p-fat", "40", "kg")), TOL);
            assertThatCode(() -> BlendRatioCheck.enforce(exactlyTwo, null)).doesNotThrowAnyException();
            assertThat(exactlyTwo.getStatus()).isEqualTo(BlendRatioCheck.STATUS_WITHIN);

            BlendRatioDeviation twoAndHalf = BlendRatioCheck.evaluate(BEEF_PORK_FAT, List.of(
                    fed("p-beef", "32.5", "kg"), fed("p-pork", "27.5", "kg"), fed("p-fat", "40", "kg")), TOL);
            assertThat(twoAndHalf.getLines().get(0).getDeviationPp()).isEqualByComparingTo("2.50");
            assertThatThrownBy(() -> BlendRatioCheck.enforce(twoAndHalf, null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(BlendRatioCheck.ERROR_CODE));
        }

        @Test
        @DisplayName("超差 + 标准原因 → 放行, 快照带原因与每种料的配方/实际/偏差")
        void overWithReasonPassesAndCarriesAudit() {
            BlendRatioDeviation eval = BlendRatioCheck.evaluate(BEEF_PORK_FAT, List.of(
                    fed("p-beef", "25", "kg"), fed("p-pork", "41.7", "kg"), fed("p-fat", "33.3", "kg")), TOL);

            BlendRatioDeviation out = BlendRatioCheck.enforce(eval, reason("原料不足", null));

            assertThat(out.getStatus()).isEqualTo(BlendRatioCheck.STATUS_OVER_WITH_REASON);
            assertThat(out.getReason()).isEqualTo("原料不足");
            assertThat(out.getTolerancePp()).isEqualByComparingTo("2");
            assertThat(out.getBasis()).isEqualTo("kg");
            assertThat(out.getLines()).extracting(BlendRatioDeviation.Line::getMaterialName,
                            BlendRatioDeviation.Line::getActualSharePercent, BlendRatioDeviation.Line::getDeviationPp)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("牛肉", new BigDecimal("25.00"), new BigDecimal("-5.00")),
                            org.assertj.core.groups.Tuple.tuple("猪肉", new BigDecimal("41.70"), new BigDecimal("11.70")),
                            org.assertj.core.groups.Tuple.tuple("脂肪", new BigDecimal("33.30"), new BigDecimal("-6.70")));
            Map<String, Object> audit = BlendRatioCheck.toAuditMap(out);
            assertThat(audit).containsEntry("reason", "原料不足").containsEntry("status", "OVER_WITH_REASON");
        }

        @Test
        @DisplayName("原因选「其他」必须写说明; 不在下拉里的原因 → 400")
        void otherNeedsNoteAndUnknownReasonIsInvalid() {
            BlendRatioDeviation eval = BlendRatioCheck.evaluate(BEEF_PORK_FAT, List.of(
                    fed("p-beef", "25", "kg"), fed("p-pork", "41.7", "kg"), fed("p-fat", "33.3", "kg")), TOL);
            assertThatThrownBy(() -> BlendRatioCheck.enforce(eval, reason("其他", " ")))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(BlendRatioCheck.ERROR_CODE));
            assertThatThrownBy(() -> BlendRatioCheck.enforce(eval, reason("随便", null)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(400);
                        assertThat(e.getErrorCode()).isEqualTo(BlendRatioCheck.INVALID_REASON_CODE);
                    });
            BlendRatioDeviation eval2 = BlendRatioCheck.evaluate(BEEF_PORK_FAT, List.of(
                    fed("p-beef", "25", "kg"), fed("p-pork", "41.7", "kg"), fed("p-fat", "33.3", "kg")), TOL);
            BlendRatioDeviation ok = BlendRatioCheck.enforce(eval2, reason("其他", "客户临时要求加瘦肉"));
            assertThat(ok.getNote()).isEqualTo("客户临时要求加瘦肉");
        }

        @Test
        @DisplayName("不带份额的工序不动: 无份额 / 只投了一种带份额的料 / 份额 0 → 不适用 (null)")
        void nonShareStepsAreUntouched() {
            List<SharePort> noShares = List.of(share("a", "A", null, "kg"), share("b", "B", null, "kg"));
            assertThat(BlendRatioCheck.evaluate(noShares, List.of(fed("a", "10", "kg"), fed("b", "90", "kg")), TOL))
                    .isNull();
            assertThat(BlendRatioCheck.evaluate(BEEF_PORK_FAT, List.of(fed("p-beef", "100", "kg")), TOL))
                    .as("替代料只选了一种, 不是汇合").isNull();
            List<SharePort> zero = List.of(share("a", "A", "30", "kg"), share("b", "B", "0", "kg"));
            assertThat(BlendRatioCheck.evaluate(zero, List.of(fed("a", "10", "kg"), fed("b", "90", "kg")), TOL))
                    .as("份额 0 = 不参与 (与 InputShareRatios.derive 同口径)").isNull();
            // 带份额 + 不带份额(调料) 混投: 调料不进分母
            List<SharePort> withSeasoning = List.of(share("a", "A", "30", "kg"), share("b", "B", "70", "kg"),
                    share("salt", "盐", null, "kg"));
            BlendRatioDeviation eval = BlendRatioCheck.evaluate(withSeasoning, List.of(
                    fed("a", "30", "kg"), fed("b", "70", "kg"), fed("salt", "50", "kg")), TOL);
            assertThat(eval.getStatus()).isEqualTo(BlendRatioCheck.STATUS_WITHIN);
            assertThat(eval.getLines()).hasSize(2);
        }

        @Test
        @DisplayName("没投的替代端口不进分母: 配方 A30/A'30(替代)/B70, 只投 A+B 按 30:70 归一")
        void unfedAlternativeIsNotInDenominator() {
            List<SharePort> ports = List.of(share("a", "A", "30", "kg"), share("a2", "A替代", "30", "kg"),
                    share("b", "B", "70", "kg"));
            BlendRatioDeviation eval = BlendRatioCheck.evaluate(ports, List.of(
                    fed("a", "30", "kg"), fed("b", "70", "kg")), TOL);
            assertThat(eval.getStatus()).isEqualTo(BlendRatioCheck.STATUS_WITHIN);
            assertThat(eval.getLines().get(0).getRecipeSharePercent()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("非 kg: 全是「个」按个数比 (与预填同口径); 30/70 配方投 40 个/60 个 → 超差")
        void countUnitSameBasis() {
            List<SharePort> ports = List.of(share("egg", "蛋", "30", "个"), share("bun", "饼", "70", "个"));
            BlendRatioDeviation within = BlendRatioCheck.evaluate(ports, List.of(
                    fed("egg", "30", "个"), fed("bun", "70", "个")), TOL);
            assertThat(within.getStatus()).isEqualTo(BlendRatioCheck.STATUS_WITHIN);
            assertThat(within.getBasis()).isEqualTo("个");

            BlendRatioDeviation over = BlendRatioCheck.evaluate(ports, List.of(
                    fed("egg", "40", "个"), fed("bun", "60", "个")), TOL);
            assertThatThrownBy(() -> BlendRatioCheck.enforce(over, null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(BlendRatioCheck.ERROR_CODE));
        }

        @Test
        @DisplayName("混合质量单位: 0.3 kg + 700 g 折成克比 (30:70 正好), 不是 0.3:700")
        void mixedMassUnitsAreConvertedToGrams() {
            List<SharePort> ports = List.of(share("a", "A", "30", "kg"), share("b", "B", "70", "g"));
            BlendRatioDeviation eval = BlendRatioCheck.evaluate(ports, List.of(
                    fed("a", "0.3", "kg"), fed("b", "700", "g")), TOL);
            assertThat(eval.getBasis()).isEqualTo("g");
            assertThat(eval.getStatus()).isEqualTo(BlendRatioCheck.STATUS_WITHIN);
            assertThat(eval.getLines().get(0).getActualSharePercent()).isEqualByComparingTo("30");
        }

        @Test
        @DisplayName("个 + kg: 有冻结克重就折算; 没克重 → SKIPPED_UNITS 不拦报工")
        void countWithGramsConvertsOtherwiseSkips() {
            List<SharePort> withGrams = List.of(new SharePort("egg", "蛋", new BigDecimal("30"), "个", new BigDecimal("50")),
                    share("meat", "肉", "70", "kg"));
            // 600 个 × 50 g = 30 kg; 肉 70 kg → 30:70
            BlendRatioDeviation eval = BlendRatioCheck.evaluate(withGrams, List.of(
                    fed("egg", "600", "个"), fed("meat", "70", "kg")), TOL);
            assertThat(eval.getStatus()).isEqualTo(BlendRatioCheck.STATUS_WITHIN);
            assertThat(eval.getBasis()).isEqualTo("g");

            List<SharePort> noGrams = List.of(share("egg", "蛋", "30", "个"), share("meat", "肉", "70", "kg"));
            BlendRatioDeviation skipped = BlendRatioCheck.evaluate(noGrams, List.of(
                    fed("egg", "999", "个"), fed("meat", "1", "kg")), TOL);
            assertThat(skipped.getStatus()).isEqualTo(BlendRatioCheck.STATUS_SKIPPED_UNITS);
            assertThat(skipped.getSkipReason()).contains("蛋");
            assertThatCode(() -> BlendRatioCheck.enforce(skipped, null)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("容差只有一个查找点, 默认全厂 2pp")
        void toleranceSeam() {
            assertThat(new BlendRatioCheck().tolerancePp("F006", "ANY")).isEqualByComparingTo("2");
        }
    }

    // =====================================================================
    // ② web 逐道录入 (真实 ProcessSheetServiceImpl.submitRow / saveDraft)
    // =====================================================================
    @Nested
    @DisplayName("web 逐道录入")
    class WebWritePath {

        private static final String F = "F006";
        private static final String PLAN = "P-BLEND";
        /** 端口校验全部通过后, 下一步就是查重 —— 在那里截停, 证明请求「过了配比闸」。 */
        private static final IllegalStateException PASSED_SELECTION_GATES =
                new IllegalStateException("passed workflow selection gates");

        private ProcessSheetRowRepository rowRepo;
        private ProcessSheetServiceImpl svc;
        /** 逐道录入配置里的产品; 多产出计划时是原料锚点 (id 就是原料 id)。 */
        private String configProductTypeId = "SEMI-MIX";

        private WorkflowClerkSheetConfigDTO.PortDescriptor input(String id, String sku, String name, String share) {
            WorkflowClerkSheetConfigDTO.PortDescriptor p = new WorkflowClerkSheetConfigDTO.PortDescriptor();
            p.setWorkflowPortId(id);
            p.setSkuId(sku);
            p.setMaterialName(name);
            p.setMaterialKind("RAW_MATERIAL");
            p.setUnit("kg");
            p.setRequired(false);
            p.setInputShareRatio(new BigDecimal(share));
            return p;
        }

        private void build(ObjectMapper mapper) {
            ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
            when(planRepo.findByIdAndFactoryId(PLAN, F)).thenReturn(Optional.of(new ProductionPlan()));
            rowRepo = mock(ProcessSheetRowRepository.class);
            when(rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(eq(F), eq(PLAN), anyString(), anyString()))
                    .thenThrow(PASSED_SELECTION_GATES);
            WorkflowClerkSheetConfigDTO.PortDescriptor out = new WorkflowClerkSheetConfigDTO.PortDescriptor();
            out.setWorkflowPortId("out-mix");
            out.setSkuId("SEMI-MIX");
            out.setMaterialKind("SEMI_FINISHED");
            out.setUnit("kg");
            WorkflowClerkSheetConfigDTO.ProcessDescriptor d = new WorkflowClerkSheetConfigDTO.ProcessDescriptor();
            d.setProcessOrder(1);
            d.setWorkflowNodeId("node-mix");
            d.setInputs(List.of(input("p-beef", "RM-BEEF", "牛肉", "30"), input("p-pork", "RM-PORK", "猪肉", "30"),
                    input("p-fat", "RM-FAT", "脂肪", "40")));
            d.setOutput(out);
            d.setOutputs(List.of(out));
            WorkflowClerkSheetConfigDTO cfg = new WorkflowClerkSheetConfigDTO();
            cfg.setProductTypeId(configProductTypeId);
            cfg.setProcesses(List.of(d));
            WorkflowClerkSheetService sheet = mock(WorkflowClerkSheetService.class);
            when(sheet.getWorkflowSheetConfig(F, PLAN)).thenReturn(cfg);

            Map<Class<?>, Object> provided = new HashMap<>();
            provided.put(ProductionPlanRepository.class, planRepo);
            provided.put(ProcessSheetRowRepository.class, rowRepo);
            if (mapper != null) provided.put(ObjectMapper.class, mapper);
            svc = construct(ProcessSheetServiceImpl.class, provided);
            ReflectionTestUtils.setField(svc, "workflowClerkSheetService", sheet);
            ReflectionTestUtils.setField(svc, "blendRatioCheck", new BlendRatioCheck());
        }

        private ProcessSheetRowRequest.MaterialInputTotal total(String sku, String port, String qty) {
            ProcessSheetRowRequest.MaterialInputTotal t = new ProcessSheetRowRequest.MaterialInputTotal();
            t.setMaterialTypeId(sku);
            t.setWorkflowPortId(port);
            t.setQuantity(new BigDecimal(qty));
            t.setUnit("kg");
            return t;
        }

        private ProcessSheetRowRequest steveExampleRow() {
            ProcessSheetRowRequest req = new ProcessSheetRowRequest();
            req.setClientRowId("r-blend");
            req.setProcessCode("MIX");
            req.setProcessOrder(1);
            req.setProductTypeId("SEMI-MIX");
            req.setProcessDate(LocalDate.of(2026, 10, 26));
            req.setOutputQuantity(new BigDecimal("95"));
            req.setMaterialInputTotals(new ArrayList<>(List.of(
                    total("RM-BEEF", "p-beef", "25"), total("RM-PORK", "p-pork", "41.7"),
                    total("RM-FAT", "p-fat", "33.3"))));
            return req;
        }

        @Test
        @DisplayName("超差无原因 → 409 来自配比闸 (摘掉闸同一请求就过了端口校验), 没写工序行")
        void overWithoutReasonIsRejectedByThisCheck() {
            build(null);
            ProcessSheetRowRequest req = steveExampleRow();

            assertThatThrownBy(() -> svc.submitRow(F, PLAN, req, 7L))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(BlendRatioCheck.ERROR_CODE);
                        assertThat(e.getMessage()).contains("猪肉");
                    });
            verify(rowRepo, never()).save(any());

            // 归因对照: 同一请求, 摘掉配比闸 → 不再是 409 BLEND, 而是走到了下一步 (截停点)
            ReflectionTestUtils.setField(svc, "blendRatioCheck", null);
            assertThatThrownBy(() -> svc.submitRow(F, PLAN, steveExampleRow(), 7L)).isSameAs(PASSED_SELECTION_GATES);
        }

        /** 工艺保密: SEMI-MIX 受保护, 负责人 10 号; 判定服务是真的。 */
        private BlendRatioCheck secretAwareCheck() {
            // 单产出计划: 计划本身没有别的产品, 只看配置里的产品
            ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
            when(plans.findByIdAndFactoryId(PLAN, F)).thenReturn(Optional.of(new ProductionPlan()));
            return secretAwareCheck("SEMI-MIX", plans);
        }

        private BlendRatioCheck secretAwareCheck(String protectedProduct, ProductionPlanRepository planRepo) {
            com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository policies =
                    mock(com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository.class);
            com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository grants =
                    mock(com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository.class);
            when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
            com.cretas.aims.entity.processsecret.ProcessSecretPolicy policy =
                    com.cretas.aims.entity.processsecret.ProcessSecretPolicy.builder().id(1L).factoryId(F)
                            .productTypeId(protectedProduct)
                            .storageMode(com.cretas.aims.entity.processsecret.ProcessSecretStorageMode.CLOUD_RESTRICTED)
                            .ownerUserId(10L).createdBy(10L).build();
            when(policies.findByFactoryIdAndProductTypeId(F, protectedProduct)).thenReturn(Optional.of(policy));
            when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
            com.cretas.aims.service.processsecret.ProcessSecretAccessService access =
                    new com.cretas.aims.service.processsecret.ProcessSecretAccessService(policies, grants,
                            mock(com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder.class));
            BlendRatioCheck check = new BlendRatioCheck();
            ReflectionTestUtils.setField(check, "processSecretGuard",
                    new com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard(
                            mock(com.cretas.aims.repository.bom.BomRecipeRepository.class), access));
            if (planRepo != null) ReflectionTestUtils.setField(check, "planRepository", planRepo);
            return check;
        }

        private String rejectionMessageFor(long userId) {
            return rejectionMessageFor(userId, secretAwareCheck());
        }

        private String rejectionMessageFor(long userId, BlendRatioCheck check) {
            ReflectionTestUtils.setField(svc, "blendRatioCheck", check);
            com.cretas.aims.security.AuthenticatedActor actor = new com.cretas.aims.security.AuthenticatedActor(
                    com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, userId, F, "operator");
            try (com.cretas.aims.security.AuthenticatedActor.ActorScope ignored =
                         com.cretas.aims.security.AuthenticatedActor.trustedAsyncScope(actor)) {
                svc.submitRow(F, PLAN, steveExampleRow(), userId);
            } catch (BusinessException e) {
                assertThat(e.getErrorCode()).isEqualTo(BlendRatioCheck.ERROR_CODE);
                return e.getMessage();
            }
            throw new AssertionError("超差无原因应被配比闸拦下");
        }

        @Test
        @DisplayName("工艺保密: 无授权的人超差提示不给配方份额 (只给料名、实际份额、偏高/偏低), 负责人照常看到配方")
        void deviationMessageHidesRecipeSharesFromCallersWithoutFullView() {
            build(null);
            String operatorMessage = rejectionMessageFor(5L);
            assertThat(operatorMessage).contains("猪肉").contains("实际 41.7%").contains("偏高")
                    .doesNotContain("配方").doesNotContain("30%").doesNotContain("11.7");
            verify(rowRepo, never()).save(any());

            String ownerMessage = rejectionMessageFor(10L);
            assertThat(ownerMessage).contains("配方 30%").contains("+11.7");
        }

        @Test
        @DisplayName("工艺保密: 多产出计划的产品是原料锚点 (id = 原料 id) —— 按计划牵涉的成品判, 无权的人照样看不到配方份额")
        void multiOutputAnchorIsResolvedThroughThePlan() {
            configProductTypeId = "RM-ANCHOR";
            build(null);
            ProductionPlan plan = new ProductionPlan();
            plan.setProductTypeId("RM-ANCHOR");
            plan.setTargetFinishedGoodIds(List.of("FG-OPEN", "FG-SECRET"));
            ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
            when(plans.findByIdAndFactoryId(PLAN, F)).thenReturn(Optional.of(plan));

            String operatorMessage = rejectionMessageFor(5L, secretAwareCheck("FG-SECRET", plans));
            assertThat(operatorMessage).contains("猪肉").contains("实际 41.7%").contains("偏高")
                    .doesNotContain("配方").doesNotContain("30%").doesNotContain("11.7");

            String ownerMessage = rejectionMessageFor(10L, secretAwareCheck("FG-SECRET", plans));
            assertThat(ownerMessage).contains("配方 30%").contains("+11.7");

            // 计划查不到 (已删 / 他厂) 时, 本厂有保密产品就不给份额
            ProductionPlanRepository missing = mock(ProductionPlanRepository.class);
            when(missing.findByIdAndFactoryId(PLAN, F)).thenReturn(Optional.empty());
            BlendRatioCheck check = secretAwareCheck("FG-SECRET", missing);
            BlendRatioDeviation over = BlendRatioDeviation.builder().status("OVER").build();
            assertThat(check.revealRecipeShares(F, PLAN, "RM-ANCHOR", over)).isFalse();
        }

        @Test
        @DisplayName("工艺保密: App 报工按任务 → 批次 → 计划找产品, 多产出锚点不会被当成不受保护")
        void appReportResolvesThePlanThroughTheBatch() {
            ProductionPlan plan = new ProductionPlan();
            plan.setProductTypeId("RM-ANCHOR");
            plan.setTargetFinishedGoodIds(List.of("FG-OPEN", "FG-SECRET"));
            ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
            when(plans.findByIdAndFactoryId(PLAN, F)).thenReturn(Optional.of(plan));
            com.cretas.aims.entity.ProductionBatch batch = new com.cretas.aims.entity.ProductionBatch();
            batch.setProductionPlanId(PLAN);
            com.cretas.aims.repository.ProductionBatchRepository batches =
                    mock(com.cretas.aims.repository.ProductionBatchRepository.class);
            when(batches.findByIdAndFactoryId(77L, F)).thenReturn(Optional.of(batch));
            BlendRatioCheck check = secretAwareCheck("FG-SECRET", plans);
            ReflectionTestUtils.setField(check, "batchRepository", batches);
            WorkProcessTask task = new WorkProcessTask();
            task.setProductionBatchId(77L);
            task.setProductTypeId("RM-ANCHOR");
            BlendRatioDeviation over = BlendRatioDeviation.builder().status("OVER").build();

            assertThat(check.planIdOf(F, task)).isEqualTo(PLAN);
            com.cretas.aims.security.AuthenticatedActor operator = new com.cretas.aims.security.AuthenticatedActor(
                    com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, 5L, F, "operator");
            try (com.cretas.aims.security.AuthenticatedActor.ActorScope ignored =
                         com.cretas.aims.security.AuthenticatedActor.trustedAsyncScope(operator)) {
                assertThat(check.revealRecipeShares(F, check.planIdOf(F, task), task.getProductTypeId(), over))
                        .isFalse();
            }
            com.cretas.aims.security.AuthenticatedActor owner = new com.cretas.aims.security.AuthenticatedActor(
                    com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, 10L, F, "owner");
            try (com.cretas.aims.security.AuthenticatedActor.ActorScope ignored =
                         com.cretas.aims.security.AuthenticatedActor.trustedAsyncScope(owner)) {
                assertThat(check.revealRecipeShares(F, check.planIdOf(F, task), task.getProductTypeId(), over))
                        .isTrue();
            }
        }

        @Test
        @DisplayName("超差带原因 → 过闸; 快照挂在请求上, 经 row_payload 序列化往返仍在 (落库留痕)")
        void overWithReasonPassesAndSnapshotRoundTripsThroughRowPayload() {
            build(new ObjectMapper().findAndRegisterModules());
            ProcessSheetRowRequest req = steveExampleRow();
            req.setBlendRatio(reason("按主管口头调整", null));

            assertThatThrownBy(() -> svc.submitRow(F, PLAN, req, 7L)).isSameAs(PASSED_SELECTION_GATES);

            assertThat(req.getBlendRatio().getStatus()).isEqualTo(BlendRatioCheck.STATUS_OVER_WITH_REASON);
            assertThat(req.getBlendRatio().getReason()).isEqualTo("按主管口头调整");
            assertThat(req.getBlendRatio().getMaxDeviationPp()).isEqualByComparingTo("11.70");

            String payload = ReflectionTestUtils.invokeMethod(svc, "serializePayload", req);
            assertThat(payload).contains("\"blendRatio\"").contains("按主管口头调整").contains("OVER_WITH_REASON");
            ProcessSheetRowRequest back = ReflectionTestUtils.invokeMethod(svc, "tryDeserialize", payload);
            assertThat(back.getBlendRatio().getLines()).hasSize(3);
            assertThat(back.getBlendRatio().getLines().get(1).getDeviationPp()).isEqualByComparingTo("11.70");
        }

        @Test
        @DisplayName("容差内: 客户端伪造的快照被服务端覆盖")
        void clientSnapshotIsOverwritten() {
            build(null);
            ProcessSheetRowRequest req = steveExampleRow();
            req.setMaterialInputTotals(new ArrayList<>(List.of(total("RM-BEEF", "p-beef", "30"),
                    total("RM-PORK", "p-pork", "30"), total("RM-FAT", "p-fat", "40"))));
            req.setBlendRatio(BlendRatioDeviation.builder().status("OVER_WITH_REASON").reason("称重误差")
                    .maxDeviationPp(new BigDecimal("99")).build());

            assertThatThrownBy(() -> svc.submitRow(F, PLAN, req, 7L)).isSameAs(PASSED_SELECTION_GATES);
            assertThat(req.getBlendRatio().getStatus()).isEqualTo(BlendRatioCheck.STATUS_WITHIN);
            assertThat(req.getBlendRatio().getMaxDeviationPp()).isEqualByComparingTo("0");
            assertThat(req.getBlendRatio().getReason()).isNull();
        }

        @Test
        @DisplayName("草稿不拦: saveDraft 不调配比闸, 也不会抛 BLEND_RATIO_REASON_REQUIRED")
        void draftIsNotChecked() {
            build(new ObjectMapper().findAndRegisterModules());
            BlendRatioCheck spy = Mockito.spy(new BlendRatioCheck());
            ReflectionTestUtils.setField(svc, "blendRatioCheck", spy);
            when(rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(eq(F), eq(PLAN), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            try {
                svc.saveDraft(F, PLAN, steveExampleRow(), 7L);
            } catch (BusinessException e) {
                assertThat(e.getErrorCode()).isNotEqualTo(BlendRatioCheck.ERROR_CODE);
            } catch (RuntimeException ignored) {
                // 草稿持久化后面还有别的依赖没装配; 这里只关心它没被配比闸拦
            }
            // 「一定会发生」的读数 (硬约束 9): 草稿确实跑过了校验段、走到了查已有行 —— 否则 never() 无意义
            verify(rowRepo, Mockito.atLeastOnce())
                    .findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(eq(F), eq(PLAN), anyString(), anyString());
            verify(spy, never()).enforceProcessSheet(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("多产出: 每个产出行都带同一份配比快照")
        void multiOutputRowsCarrySnapshot() {
            build(null);
            ProcessSheetRowRequest base = steveExampleRow();
            base.setBlendRatio(BlendRatioDeviation.builder().status("OVER_WITH_REASON").reason("原料不足").build());
            ProcessSheetRowRequest.OutputLine o = new ProcessSheetRowRequest.OutputLine();
            o.setProductTypeId("SEMI-MIX");
            o.setQuantity(BigDecimal.TEN);
            ProcessSheetRowRequest one = ReflectionTestUtils.invokeMethod(svc, "synthesizeOutputRequest",
                    base, o, 1, false);
            assertThat(one.getBlendRatio()).isSameAs(base.getBlendRatio());
        }
    }

    // =====================================================================
    // ② App 报工 (真实 YieldReportServiceImpl.submitReport)
    // =====================================================================
    @Nested
    @DisplayName("App 报工")
    class AppWritePath {

        private static final String F = "F006";
        private ProductionReportRepository reportRepo;
        private YieldReportServiceImpl svc;
        private BlendRatioCheck check;
        /** 任务上的产品; 多产出计划时是原料锚点 (id 就是原料 id)。 */
        private String taskProductTypeId = "SEMI-MIX";

        private WorkflowTaskPort port(String portId, String sku, String share) {
            WorkflowTaskPort p = new WorkflowTaskPort();
            p.setTaskId(34L);
            p.setWorkflowPortId(portId);
            p.setDirection(WorkflowTaskPort.Direction.INPUT);
            p.setMaterialKind("RAW_MATERIAL");
            p.setSkuId(sku);
            p.setUnit("kg");
            p.setMaterialPrimaryUnitCode("kg");
            p.setPortToPrimaryFactorSnapshot(BigDecimal.ONE);
            p.setConversionFromUnitCode("kg");
            p.setConversionToUnitCode("kg");
            p.setRequired(false);
            p.setInputShareRatio(share == null ? null : new BigDecimal(share));
            return p;
        }

        private MaterialBatch batch(String id, String typeId, String name) {
            MaterialBatch b = new MaterialBatch();
            b.setId(id);
            b.setFactoryId(F);
            b.setMaterialTypeId(typeId);
            b.setQuantityUnit("kg");
            RawMaterialType type = new RawMaterialType();
            type.setName(name);
            b.setMaterialType(type);
            return b;
        }

        private void build(String beefShare) {
            WorkProcessTask task = new WorkProcessTask();
            task.setId(34L);
            task.setFactoryId(F);
            task.setProductionBatchId(1L);
            task.setProcessOrder(1);
            task.setWorkProcessId("WP-MIX");
            task.setProductTypeId(taskProductTypeId);
            task.setWorkflowInstanceId(96L);
            task.setStatus(WorkProcessTask.Status.IN_PROGRESS);
            WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
            when(taskRepo.findByFactoryIdAndId(F, 34L)).thenReturn(Optional.of(task));
            WorkflowTaskPortRepository portRepo = mock(WorkflowTaskPortRepository.class);
            when(portRepo.findByFactoryIdAndWorkflowInstanceId(F, 96L)).thenReturn(List.of(
                    port("p-beef", "RM-BEEF", beefShare), port("p-pork", "RM-PORK", beefShare == null ? null : "30"),
                    port("p-fat", "RM-FAT", beefShare == null ? null : "40")));
            MaterialBatchRepository batchRepo = mock(MaterialBatchRepository.class);
            when(batchRepo.findByIdAndFactoryId("MB-BEEF", F)).thenReturn(Optional.of(batch("MB-BEEF", "RM-BEEF", "牛肉")));
            when(batchRepo.findByIdAndFactoryId("MB-PORK", F)).thenReturn(Optional.of(batch("MB-PORK", "RM-PORK", "猪肉")));
            when(batchRepo.findByIdAndFactoryId("MB-FAT", F)).thenReturn(Optional.of(batch("MB-FAT", "RM-FAT", "脂肪")));
            reportRepo = mock(ProductionReportRepository.class);
            when(reportRepo.save(any(ProductionReport.class))).thenAnswer(inv -> {
                ProductionReport r = inv.getArgument(0);
                r.setId(900L);
                return r;
            });

            Map<Class<?>, Object> provided = new HashMap<>();
            provided.put(ProductionReportRepository.class, reportRepo);
            provided.put(WorkProcessTaskRepository.class, taskRepo);
            provided.put(MaterialBatchRepository.class, batchRepo);
            provided.put(ObjectMapper.class, new ObjectMapper());
            provided.put(YieldCalculationService.class, new YieldCalculationServiceImpl());
            provided.put(CostReconcileService.class, new CostReconcileService());
            svc = construct(YieldReportServiceImpl.class, provided);
            ReflectionTestUtils.setField(svc, "workflowTaskPortRepository", portRepo);
            TaskMaterialBatchPickerService validator = mock(TaskMaterialBatchPickerService.class);
            when(validator.validateMaterialBatchRefs(eq(F), eq(1L), eq(34L), anyList())).thenReturn(new BigDecimal("100"));
            ReflectionTestUtils.setField(svc, "taskMaterialBatchPickerService", validator);
            ReflectionTestUtils.setField(svc, "workshopReservationService",
                    mock(com.cretas.aims.service.production.WorkshopReportReservationService.class));
            check = new BlendRatioCheck();
            ReflectionTestUtils.setField(check, "taskPortRepository", portRepo);
            ReflectionTestUtils.setField(check, "materialBatchRepository", batchRepo);
            ReflectionTestUtils.setField(svc, "blendRatioCheck", check);
        }

        private YieldReportRequest steveExampleInput() {
            YieldReportRequest req = new YieldReportRequest();
            req.setWorkProcessTaskId(34L);
            req.setReportKind("INPUT");
            req.setInputQuantity(new BigDecimal("100"));
            req.setInputUnit("kg");
            req.setMaterialBatchRefs(List.of(new MaterialBatchRef("MB-BEEF", new BigDecimal("25"), "kg"),
                    new MaterialBatchRef("MB-PORK", new BigDecimal("41.7"), "kg"),
                    new MaterialBatchRef("MB-FAT", new BigDecimal("33.3"), "kg")));
            return req;
        }

        @Test
        @DisplayName("超差无原因 → 409 BLEND_RATIO_REASON_REQUIRED, 不落报工单")
        void overWithoutReasonIsRejected() {
            build("30");
            assertThatThrownBy(() -> svc.submitReport(F, 1L, 5L, steveExampleInput()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(BlendRatioCheck.ERROR_CODE));
            verify(reportRepo, never()).save(any());
        }

        @Test
        @DisplayName("超差带原因 → 落库, custom_fields.blendRatio 记原因与偏差; 原有 customFields 不丢")
        void overWithReasonIsPersistedOnTheReport() {
            build("30");
            YieldReportRequest req = steveExampleInput();
            req.setBlendRatio(reason("原料规格差异", null));

            svc.submitReport(F, 1L, 5L, req);

            ArgumentCaptor<ProductionReport> saved = ArgumentCaptor.forClass(ProductionReport.class);
            verify(reportRepo).save(saved.capture());
            Map<String, Object> fields = saved.getValue().getCustomFields();
            assertThat(fields).containsEntry("reportStack", "YIELD");
            @SuppressWarnings("unchecked")
            Map<String, Object> blend = (Map<String, Object>) fields.get("blendRatio");
            assertThat(blend).containsEntry("reason", "原料规格差异").containsEntry("status", "OVER_WITH_REASON");
            assertThat((BigDecimal) blend.get("maxDeviationPp")).isEqualByComparingTo("11.70");
        }

        @Test
        @DisplayName("工艺保密: 多产出计划的 App 报工 —— 任务挂在原料锚点上, 经任务 → 批次 → 计划判, 无权的人看不到配方份额")
        void multiOutputAppReportResolvesThePlanThroughTheBatch() {
            taskProductTypeId = "RM-ANCHOR";
            build("30");
            com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository policies =
                    mock(com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository.class);
            com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository grants =
                    mock(com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository.class);
            when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
            com.cretas.aims.entity.processsecret.ProcessSecretPolicy policy =
                    com.cretas.aims.entity.processsecret.ProcessSecretPolicy.builder().id(1L).factoryId(F)
                            .productTypeId("FG-SECRET")
                            .storageMode(com.cretas.aims.entity.processsecret.ProcessSecretStorageMode.CLOUD_RESTRICTED)
                            .ownerUserId(10L).createdBy(10L).build();
            when(policies.findByFactoryIdAndProductTypeId(F, "FG-SECRET")).thenReturn(Optional.of(policy));
            when(policies.findByFactoryIdOrderByProductTypeIdAsc(F)).thenReturn(List.of(policy));
            ReflectionTestUtils.setField(check, "processSecretGuard",
                    new com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard(
                            mock(com.cretas.aims.repository.bom.BomRecipeRepository.class),
                            new com.cretas.aims.service.processsecret.ProcessSecretAccessService(policies, grants,
                                    mock(com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder.class))));
            ProductionPlan plan = new ProductionPlan();
            plan.setProductTypeId("RM-ANCHOR");
            plan.setTargetFinishedGoodIds(List.of("FG-OPEN", "FG-SECRET"));
            ProductionPlanRepository plans = mock(ProductionPlanRepository.class);
            when(plans.findByIdAndFactoryId("PLAN-M", F)).thenReturn(Optional.of(plan));
            com.cretas.aims.entity.ProductionBatch batch = new com.cretas.aims.entity.ProductionBatch();
            batch.setProductionPlanId("PLAN-M");
            com.cretas.aims.repository.ProductionBatchRepository batches =
                    mock(com.cretas.aims.repository.ProductionBatchRepository.class);
            when(batches.findByIdAndFactoryId(1L, F)).thenReturn(Optional.of(batch));
            ReflectionTestUtils.setField(check, "planRepository", plans);
            ReflectionTestUtils.setField(check, "batchRepository", batches);

            String operatorMessage = rejectionMessageFor(5L);
            assertThat(operatorMessage).contains("猪肉").contains("实际 41.7%").contains("偏高")
                    .doesNotContain("配方").doesNotContain("30%").doesNotContain("11.7");
            verify(reportRepo, never()).save(any());

            String ownerMessage = rejectionMessageFor(10L);
            assertThat(ownerMessage).contains("配方 30%").contains("+11.7");
        }

        private String rejectionMessageFor(long userId) {
            com.cretas.aims.security.AuthenticatedActor actor = new com.cretas.aims.security.AuthenticatedActor(
                    com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, userId, F, "operator");
            try (com.cretas.aims.security.AuthenticatedActor.ActorScope ignored =
                         com.cretas.aims.security.AuthenticatedActor.trustedAsyncScope(actor)) {
                svc.submitReport(F, 1L, userId, steveExampleInput());
            } catch (BusinessException e) {
                assertThat(e.getErrorCode()).isEqualTo(BlendRatioCheck.ERROR_CODE);
                return e.getMessage();
            }
            throw new AssertionError("超差无原因应被配比闸拦下");
        }

        @Test
        @DisplayName("端口不带份额 → 不校验, 照常落库且不写 blendRatio")
        void nonShareTaskIsUntouched() {
            build(null);
            svc.submitReport(F, 1L, 5L, steveExampleInput());
            ArgumentCaptor<ProductionReport> saved = ArgumentCaptor.forClass(ProductionReport.class);
            verify(reportRepo).save(saved.capture());
            assertThat(saved.getValue().getCustomFields()).doesNotContainKey("blendRatio");
        }
    }

    // =====================================================================
    // ③ 注入
    // =====================================================================
    @Test
    @DisplayName("注入: BlendRatioCheck 是 @Component; 两个写入口声明字段并在落库前调用")
    void wiredIntoBothWritePaths() throws IOException {
        assertThat(BlendRatioCheck.class.isAnnotationPresent(Component.class))
                .as("没有 @Component, required=false 的字段会静默注入 null").isTrue();
        Path root = Paths.get("src/main/java/com/cretas/aims/service");
        String web = Files.readString(root.resolve("processentry/impl/ProcessSheetServiceImpl.java"), StandardCharsets.UTF_8);
        assertThat(web).contains("BlendRatioCheck blendRatioCheck;").contains("blendRatioCheck.enforceProcessSheet(")
                .contains("one.setBlendRatio(base.getBlendRatio())");
        String app = Files.readString(root.resolve("yield/impl/YieldReportServiceImpl.java"), StandardCharsets.UTF_8);
        assertThat(app).contains("BlendRatioCheck blendRatioCheck;");
        int call = app.indexOf("blendRatioCheck.enforceYieldReport(");
        int save = app.indexOf("ProductionReport saved = reportRepo.save(r);");
        assertThat(call).as("App 报工要在 submitReport 落库前调用").isPositive().isLessThan(save);
    }

    /** 用最多参数的构造器建服务, 没给的依赖一律 mock (与 WorkflowStepUnlockWiringContractTest 同法)。 */
    static <T> T construct(Class<T> type, Map<Class<?>, Object> provided) {
        try {
            Constructor<?> ctor = Arrays.stream(type.getDeclaredConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount))
                    .orElseThrow();
            Class<?>[] params = ctor.getParameterTypes();
            Object[] args = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                args[i] = provided.containsKey(params[i]) ? provided.get(params[i]) : Mockito.mock(params[i]);
            }
            ctor.setAccessible(true);
            return type.cast(ctor.newInstance(args));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot construct " + type.getSimpleName(), e);
        }
    }
}
