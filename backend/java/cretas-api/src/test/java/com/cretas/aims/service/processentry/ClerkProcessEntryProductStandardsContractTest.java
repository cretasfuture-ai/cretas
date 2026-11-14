package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessChainEntryRequest;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.BatchEntry;
import com.cretas.aims.dto.processentry.ProcessChainEntryRequest.StepEntry;
import com.cretas.aims.entity.ProductProcessWorkflowRevision;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.repository.ProcessEntryIdempotencyRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.service.processentry.impl.ClerkProcessEntryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 链式录入(Clerk)真实入口 {@link ClerkProcessEntryServiceImpl#recordChain}: 两个产品共用同一道工序「炒制」,
 * 画布里配了不同的油温标准, 各自按「本计划锁定的产品工艺版本」判; 没锁定/没配的走工序 schema 的旧标准。
 * (判定发生在物化写入之前, 结果写在请求的 StepEntry.paramChecks 上; 之后的物化是否成功与本断言无关。)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Clerk 链式录入: 工艺参数按计划锁定的产品工艺版本判")
class ClerkProcessEntryProductStandardsContractTest {

    private static final String FACTORY = "F006";
    private static final String WP = "WP-FRY";

    @Mock private ProductionBatchRepository batchRepo;
    @Mock private ProcessEntryIdempotencyRepository idempotencyRepo;
    @Mock private ProductionPlanRepository planRepository;
    @Mock private ProductWorkProcessRepository productWorkProcessRepository;
    @Mock private WorkProcessRepository workProcessRepository;

    @InjectMocks
    private ClerkProcessEntryServiceImpl service;

    private final ProductProcessWorkflowRevisionRepository revisionRepository =
            mock(ProductProcessWorkflowRevisionRepository.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "workflowRevisionRepository", revisionRepository);
        Map<String, Object> oil = new LinkedHashMap<>();
        oil.put("key", "oilTemp");
        oil.put("label", "油温");
        oil.put("type", "number");
        oil.put("enabled", true);
        oil.put("unit", "℃");
        oil.put("min", 100);   // 工序上原有的宽松旧标准
        oil.put("max", 200);
        WorkProcess wp = WorkProcess.builder().id(WP).factoryId(FACTORY).processName("炒制")
                .customFieldSchema(List.of(oil)).build();
        when(workProcessRepository.findById(WP)).thenReturn(Optional.of(wp));
        for (String pt : List.of("PT-A", "PT-B", "PT-C")) {
            when(productWorkProcessRepository.findByFactoryIdAndProductTypeIdOrderByProcessOrderAsc(FACTORY, pt))
                    .thenReturn(List.of(ProductWorkProcess.builder().factoryId(FACTORY).productTypeId(pt)
                            .workProcessId(WP).processOrder(1).build()));
        }
        plan("PLAN-A", "PT-A", 11L, nodes(150, 160));  // 产品 A: 150-160
        plan("PLAN-B", "PT-B", 12L, nodes(100, 120));  // 产品 B: 100-120
        plan("PLAN-C", "PT-C", null, null);            // 产品 C: 没锁定画布版本
    }

    private static String nodes(int min, int max) {
        return "[{\"id\":\"process:1\",\"kind\":\"PROCESS\",\"data\":{\"workProcessId\":\"" + WP
                + "\",\"processParams\":[{\"label\":\"油温\",\"unit\":\"℃\",\"min\":" + min + ",\"max\":" + max + "}]}}]";
    }

    private void plan(String planId, String productTypeId, Long revisionId, String nodesJson) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(planId);
        plan.setFactoryId(FACTORY);
        plan.setProductTypeId(productTypeId);
        plan.setSelectedWorkflowRevisionId(revisionId);
        when(planRepository.findByIdAndFactoryId(planId, FACTORY)).thenReturn(Optional.of(plan));
        when(idempotencyRepo.findByFactoryIdAndPlanIdAndIdempotencyKey(FACTORY, planId, "K-" + planId))
                .thenReturn(Optional.empty());
        if (revisionId != null) {
            ProductProcessWorkflowRevision rev = new ProductProcessWorkflowRevision();
            rev.setNodesJson(nodesJson);
            when(revisionRepository.findByIdAndFactoryId(revisionId, FACTORY)).thenReturn(Optional.of(rev));
        }
    }

    private String statusOf(String planId, String productTypeId, int oilTemp) {
        StepEntry step = new StepEntry();
        step.setProcessOrder(1);
        step.setProcessName("炒制");
        step.setInputQuantity(new BigDecimal("100"));
        step.setOutputQuantity(new BigDecimal("80"));
        step.setCustomFields(Map.of("oilTemp", oilTemp));
        BatchEntry batch = new BatchEntry();
        batch.setClientBatchKey("b1");
        batch.setProductTypeId(productTypeId);
        batch.setFinished(true);
        batch.setSteps(List.of(step));
        ProcessChainEntryRequest req = new ProcessChainEntryRequest();
        req.setIdempotencyKey("K-" + planId);
        req.setBatches(List.of(batch));
        try {
            service.recordChain(FACTORY, planId, req, 7L);
        } catch (RuntimeException ignoredAfterValidation) {
            // 判定在物化之前完成; 物化需要的其它依赖这里不装配, 与本断言无关
        }
        assertThat(step.getParamChecks()).as("判定一定发生 (阳性读数)").hasSize(1);
        return String.valueOf(step.getParamChecks().get(0).get("status"));
    }

    @Test
    @DisplayName("同一道工序, 同样 155℃: A(150-160) 合格, B(100-120) 偏高, 没锁定画布的 C 按工序旧标准(100-200)合格")
    void eachProductIsJudgedByItsOwnLockedVersion() {
        assertThat(statusOf("PLAN-A", "PT-A", 155)).isEqualTo("OK");
        assertThat(statusOf("PLAN-B", "PT-B", 155)).isEqualTo("HIGH");
        assertThat(statusOf("PLAN-C", "PT-C", 155)).isEqualTo("OK");
    }

    @Test
    @DisplayName("165℃: A 偏高, B 偏高, C 仍按旧标准合格")
    void aHotterReadingFlipsOnlyTheProductsWhoseCanvasStandardIsExceeded() {
        assertThat(statusOf("PLAN-A", "PT-A", 165)).isEqualTo("HIGH");
        assertThat(statusOf("PLAN-B", "PT-B", 165)).isEqualTo("HIGH");
        assertThat(statusOf("PLAN-C", "PT-C", 165)).isEqualTo("OK");
    }
}
