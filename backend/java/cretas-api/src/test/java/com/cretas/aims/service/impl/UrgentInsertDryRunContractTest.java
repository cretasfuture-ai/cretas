package com.cretas.aims.service.impl;

import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview;
import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview.Day;
import com.cretas.aims.dto.scheduling.ConstrainedSchedulePreview.PlanResult;
import com.cretas.aims.entity.InsertSlot;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.enums.ProductionPlanType;
import com.cretas.aims.repository.LineScheduleRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.SchedulingPlanRepository;
import com.cretas.aims.repository.scheduling.ApsConstraintQueryRepository;
import com.cretas.aims.service.scheduling.UrgentInsertScheduleService;
import com.cretas.aims.service.scheduling.UrgentInsertScheduleService.Proposal;
import com.cretas.aims.service.scheduling.daily.DailyCapacityScheduleGenerator;
import com.cretas.aims.service.shortage.DeliveryRiskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实 JPA 事务下的排程 dry-run (缺口 17 第 2 项): 独立事务里建的临时计划, 引擎能读到它,
 * 结束后不留任何行 (成功、引擎抛错两种都一样)。引擎本身 (缺口 18) 是桩。
 *
 * <p>production_plans 带到 factories / product_types / users 的外键, 这里只证明事务行为, 不造这些主数据,
 * 所以先关掉 H2 的引用完整性检查; 临时计划 created_by 必须是真实用户由
 * {@code UrgentInsertMaterialPlanContractTest} 守。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UrgentInsertDryRunContractTest {

    private static final String F = "F-DRYRUN-JPA";

    @Autowired ProductionPlanRepository plans;
    @Autowired LineScheduleRepository lineSchedules;
    @Autowired SchedulingPlanRepository schedulingPlans;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM production_plans WHERE factory_id = ?", F);
    }

    private ProductionPlan temp() {
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        ProductionPlan p = new ProductionPlan();
        p.setId("PLAN-DRY-" + System.nanoTime());
        p.setFactoryId(F);
        p.setPlanNumber("URGENT-DRY-" + System.nanoTime());
        p.setProductTypeId("PT-X");
        p.setPlannedQuantity(new BigDecimal("50"));
        p.setPlannedUnit("kg");
        p.setStatus(ProductionPlanStatus.PENDING);
        p.setPlanType(ProductionPlanType.FROM_INVENTORY);
        p.setCreatedBy(1L);
        p.setPriority(9);
        return p;
    }

    private UrgentInsertScheduleService service(DailyCapacityScheduleGenerator generator) {
        ApsConstraintQueryRepository constraints = mock(ApsConstraintQueryRepository.class);
        when(constraints.findLiveEndingFrom(anyString(), any())).thenReturn(List.of());
        return UrgentInsertTestSupport.construct(UrgentInsertScheduleService.class, Map.of(
                DailyCapacityScheduleGenerator.class, generator,
                ApsConstraintQueryRepository.class, constraints,
                ProductionPlanRepository.class, plans,
                LineScheduleRepository.class, lineSchedules,
                SchedulingPlanRepository.class, schedulingPlans,
                DeliveryRiskService.class, mock(DeliveryRiskService.class),
                PlatformTransactionManager.class, transactionManager));
    }

    private static InsertSlot slot() {
        return InsertSlot.builder().id("S1").factoryId(F).productionLineId("L1")
                .startTime(LocalDateTime.now().plusDays(1).withHour(9)).endTime(LocalDateTime.now().plusDays(1).withHour(11))
                .status("available").build();
    }

    private long rowsOf(String planId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM production_plans WHERE id = ?", Long.class, planId);
        return n == null ? 0 : n;
    }

    @Test
    void theEngineSeesTheTemporaryPlanAndNothingRemainsAfterwards() {
        ProductionPlan temp = temp();
        AtomicBoolean visibleToEngine = new AtomicBoolean(false);
        DailyCapacityScheduleGenerator generator = mock(DailyCapacityScheduleGenerator.class);
        when(generator.preview(anyString(), any())).thenAnswer(inv -> {
            visibleToEngine.set(plans.findByIdAndFactoryId(temp.getId(), F).isPresent());
            LocalDate day = LocalDate.now().plusDays(1);
            return new ConstrainedSchedulePreview("SCHEDULED", "L1", day, List.of(), List.of(
                    new PlanResult(temp.getId(), "T", "SCHEDULED",
                            List.of(new Day(day, new BigDecimal("50"), BigDecimal.ONE)), List.of(), List.of(), Map.of())),
                    List.of());
        });

        Proposal p = service(generator).dryRun(F, slot(), temp);

        assertThat(visibleToEngine.get()).as("引擎在同一事务里读得到临时计划").isTrue();
        assertThat(p.ok()).isTrue();
        assertThat(rowsOf(temp.getId())).as("回滚后一行不留").isZero();
    }

    @Test
    void nothingRemainsWhenTheEngineThrows() {
        ProductionPlan temp = temp();
        DailyCapacityScheduleGenerator generator = mock(DailyCapacityScheduleGenerator.class);
        when(generator.preview(anyString(), any())).thenThrow(new IllegalStateException("engine down"));

        Proposal p = service(generator).dryRun(F, slot(), temp);

        assertThat(p.status()).isEqualTo(UrgentInsertScheduleService.NOT_EVALUATED);
        assertThat(rowsOf(temp.getId())).isZero();
    }
}
