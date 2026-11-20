package com.cretas.aims.service.impl;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.SchedulingController;
import com.cretas.aims.dto.scheduling.ChainImpactResult;
import com.cretas.aims.dto.scheduling.ProductionLineDTO;
import com.cretas.aims.dto.scheduling.ProductionLineStaffingRequest;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.repository.InsertSlotRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.ImpactAnalysisService.FactoryCapacityConfig;
import com.cretas.aims.service.NotificationService;
import com.cretas.aims.service.PushNotificationService;
import com.cretas.aims.service.scheduling.SchedulingResourceWriteLock;
import com.cretas.aims.service.scheduling.core.SchedulingAIService;
import com.cretas.aims.service.scheduling.core.WorkerAssignmentService;
import com.cretas.aims.service.scheduling.impl.SchedulingPlanCrudServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产线人数配置 (网页「产线产能与设备」→ 人数配置) 的合约: 真 JPA (独立 H2 库) 跑真实保存入口
 * {@link SchedulingPlanCrudServiceImpl#updateProductionLineStaffing}, 再用同一个库喂给插单时段生成
 * {@link UrgentInsertServiceImpl#generateInsertSlots} (真实 {@code findActiveLinesByFactory} 查询),
 * 证明插单比较用的是这里保存的「最少人数」; 不配 = 不比较 (默认行为不变)。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:line_staffing_contract;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS VARCHAR",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims")
@Import({SchedulingPlanCrudServiceImpl.class, SchedulingResourceWriteLock.class})
class ProductionLineStaffingJpaContractTest {

    private static final String F = "F-STAFF";
    private static final String OTHER = "F-STAFF-OTHER";

    @MockBean private NotificationService notificationService;
    @MockBean private PushNotificationService pushNotificationService;
    @MockBean private WorkerAssignmentService workerAssignmentService;
    @MockBean private SchedulingAIService schedulingAIService;

    @Autowired private SchedulingPlanCrudServiceImpl crud;
    @Autowired private ProductionLineRepository lineRepository;
    @Autowired private EntityManager em;

    @BeforeEach
    void factories() {
        for (String id : List.of(F, OTHER)) {
            Factory factory = new Factory();
            factory.setId(id);
            factory.setName(id);
            em.persist(factory);
        }
        em.flush();
    }

    // ---------------------------------------------------------------- 保存 / 读取

    @Test
    void savedStaffingReadsBackFromTheDatabaseAndThroughTheListEndpoint() {
        String id = line(F, "卤制一线").getId();

        ProductionLineDTO saved = crud.updateProductionLineStaffing(F, id, new ProductionLineStaffingRequest(4, 8, 3));
        em.flush();
        em.clear();

        ProductionLine row = lineRepository.findById(id).orElseThrow();
        assertThat(row.getStandardWorkers()).isEqualTo(4);
        assertThat(row.getMaxWorkers()).isEqualTo(8);
        assertThat(row.getRequiredSkillLevel()).isEqualTo(3);
        // min_workers 列不动: 仍是建表默认 1 (APS 特征在读它)
        assertThat(row.getMinWorkers()).isEqualTo(1);
        assertThat(saved.getStandardWorkers()).isEqualTo(4);

        ProductionLineDTO listed = crud.getProductionLines(F, null).stream()
                .filter(l -> l.getId().equals(id)).findFirst().orElseThrow();
        assertThat(listed.getStandardWorkers()).isEqualTo(4);
        assertThat(listed.getMaxWorkers()).isEqualTo(8);
        assertThat(listed.getRequiredSkillLevel()).isEqualTo(3);
    }

    @Test
    void savingEmptyClearsAllThreeAndZeroMeansNotCompared() {
        String id = line(F, "卤制二线").getId();
        crud.updateProductionLineStaffing(F, id, new ProductionLineStaffingRequest(4, 8, 3));

        crud.updateProductionLineStaffing(F, id, new ProductionLineStaffingRequest(null, null, null));
        em.flush();
        em.clear();
        ProductionLine cleared = lineRepository.findById(id).orElseThrow();
        assertThat(cleared.getStandardWorkers()).isNull();
        assertThat(cleared.getMaxWorkers()).isNull();
        assertThat(cleared.getRequiredSkillLevel()).isNull();

        crud.updateProductionLineStaffing(F, id, new ProductionLineStaffingRequest(0, 5, null));
        em.flush();
        em.clear();
        assertThat(lineRepository.findById(id).orElseThrow().getStandardWorkers()).isNull();
    }

    // ---------------------------------------------------------------- 校验

    @Test
    void minAboveMaxIsRejectedAndNothingIsWritten() {
        String id = line(F, "包装线").getId();
        crud.updateProductionLineStaffing(F, id, new ProductionLineStaffingRequest(2, 6, 1));

        assertThatThrownBy(() -> crud.updateProductionLineStaffing(F, id, new ProductionLineStaffingRequest(7, 6, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最少人数 7 人不能多于最多人数 6 人");
        // 等于是允许的 (阴性对照: 校验不是「一律拒」)
        crud.updateProductionLineStaffing(F, id, new ProductionLineStaffingRequest(6, 6, 1));
        em.flush();
        em.clear();
        assertThat(lineRepository.findById(id).orElseThrow().getStandardWorkers()).isEqualTo(6);
    }

    @Test
    void negativeCountsAndSkillOutOfRangeAreRejected() {
        String id = line(F, "腌制线").getId();
        assertThatThrownBy(() -> crud.updateProductionLineStaffing(F, id, new ProductionLineStaffingRequest(-1, null, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("负数");
        assertThatThrownBy(() -> crud.updateProductionLineStaffing(F, id, new ProductionLineStaffingRequest(null, -2, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("负数");
        assertThatThrownBy(() -> crud.updateProductionLineStaffing(F, id, new ProductionLineStaffingRequest(null, null, 6)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("1 到 5");
        assertThatThrownBy(() -> crud.updateProductionLineStaffing(F, id, new ProductionLineStaffingRequest(null, null, 0)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("1 到 5");
        em.flush();
        em.clear();
        assertThat(lineRepository.findById(id).orElseThrow().getStandardWorkers()).isNull();
    }

    // ---------------------------------------------------------------- 跨厂隔离

    @Test
    void anotherFactoryCannotWriteThisFactorysLineAndTheRowStaysUntouched() {
        String id = line(F, "卤制一线").getId();
        crud.updateProductionLineStaffing(F, id, new ProductionLineStaffingRequest(3, 5, 2));

        assertThatThrownBy(() -> crud.updateProductionLineStaffing(OTHER, id, new ProductionLineStaffingRequest(9, 9, 5)))
                .isInstanceOf(EntityNotFoundException.class);
        em.flush();
        em.clear();
        ProductionLine row = lineRepository.findById(id).orElseThrow();
        assertThat(row.getStandardWorkers()).isEqualTo(3);
        assertThat(row.getMaxWorkers()).isEqualTo(5);
        assertThat(row.getRequiredSkillLevel()).isEqualTo(2);
        assertThat(crud.getProductionLines(OTHER, null)).isEmpty();
    }

    // ---------------------------------------------------------------- 插单真的用上配置

    @Test
    void urgentInsertSlotsCompareAgainstTheSavedMinimumAndUnconfiguredLinesAreNotCompared() {
        String configured = line(F, "卤制一线").getId();
        String untouched = line(F, "卤制二线").getId();
        // 阴性对照: 另一条线 min_workers 列很大, 但没在人数配置里填最少人数 => 插单仍不比较
        ProductionLine legacy = lineRepository.findById(untouched).orElseThrow();
        legacy.setMinWorkers(9);
        lineRepository.saveAndFlush(legacy);
        crud.updateProductionLineStaffing(F, configured, new ProductionLineStaffingRequest(5, 8, 2));
        em.flush();
        em.clear();

        List<InsertSlot> slots = generateSlots();

        assertThat(slots).isNotEmpty();
        assertThat(slots).filteredOn(s -> s.getProductionLineId().equals(configured))
                .isNotEmpty().allMatch(s -> Integer.valueOf(5).equals(s.getRequiredWorkers()));
        assertThat(slots).filteredOn(s -> s.getProductionLineId().equals(untouched))
                .isNotEmpty().allMatch(s -> s.getRequiredWorkers() == null);
    }

    @Test
    void staffingEndpointUsesTheSamePermissionAsLineEditing() throws Exception {
        Method staffing = Arrays.stream(SchedulingController.class.getMethods())
                .filter(m -> m.getName().equals("updateProductionLineStaffing")).findFirst().orElseThrow();
        Method edit = Arrays.stream(SchedulingController.class.getMethods())
                .filter(m -> m.getName().equals("updateProductionLine")).findFirst().orElseThrow();
        assertThat(staffing.getAnnotation(RequirePermission.class)).isNotNull();
        assertThat(staffing.getAnnotation(RequirePermission.class).value())
                .containsExactly(edit.getAnnotation(RequirePermission.class).value());
        assertThat(staffing.getAnnotation(PutMapping.class).value())
                .containsExactly("/production-lines/{lineId}/staffing");
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private List<InsertSlot> generateSlots() {
        InsertSlotRepository slotRepo = mock(InsertSlotRepository.class);
        when(slotRepo.saveAll(any())).thenAnswer(i -> i.getArgument(0));
        ImpactAnalysisService impact = mock(ImpactAnalysisService.class);
        FactoryCapacityConfig capacity = new FactoryCapacityConfig();
        capacity.setSwitchCostMinutes(0);
        when(impact.getFactoryCapacityConfig(F)).thenReturn(capacity);
        when(impact.calculateChainImpact(anyString(), any(), any(), isNull(), isNull()))
                .thenReturn(ChainImpactResult.builder().impactLevel("none").build());
        when(impact.calculateRecommendScore(anyString(), any(), any(), isNull(), isNull(), isNull()))
                .thenReturn(new ImpactAnalysisService.RecommendScoreResult());
        UrgentInsertServiceImpl insert = UrgentInsertTestSupport.construct(UrgentInsertServiceImpl.class, Map.of(
                InsertSlotRepository.class, slotRepo,
                ProductionLineRepository.class, lineRepository,
                ImpactAnalysisService.class, impact,
                SchedulingResourceWriteLock.class, mock(SchedulingResourceWriteLock.class),
                ObjectMapper.class, new ObjectMapper()));

        insert.generateInsertSlots(F, 4);

        ArgumentCaptor<Iterable<InsertSlot>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(slotRepo).saveAll(saved.capture());
        return ((List<?>) saved.getValue()).stream().map(InsertSlot.class::cast).toList();
    }

    private ProductionLine line(String factoryId, String name) {
        ProductionLine line = new ProductionLine();
        line.setFactoryId(factoryId);
        line.setName(name);
        line.setStatus(ProductionLine.LineStatus.active);
        line.setHourlyCapacity(new BigDecimal("100"));
        return lineRepository.saveAndFlush(line);
    }
}
