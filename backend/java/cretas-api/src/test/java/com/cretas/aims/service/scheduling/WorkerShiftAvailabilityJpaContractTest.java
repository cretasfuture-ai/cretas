package com.cretas.aims.service.scheduling;

import com.cretas.aims.entity.*;
import com.cretas.aims.entity.hr.*;
import com.cretas.aims.entity.hr.enums.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual Hibernate query execution: default H2, same assertions on an isolated local PostgreSQL. */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan("com.cretas.aims.entity")
@EnableJpaRepositories("com.cretas.aims.repository")
@Import(WorkerShiftAvailabilityCheck.class)
class WorkerShiftAvailabilityJpaContractTest {
    private static final String F = "APS-ROSTER-A", OTHER = "APS-ROSTER-B";
    private static final LocalDateTime START = LocalDateTime.of(2027, 1, 5, 9, 0);
    @Autowired TestEntityManager em;
    @Autowired WorkerShiftAvailabilityCheck check;
    private User worker;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("CRETAS_WORKFLOW_PG_URL");
        if (url != null && !url.isBlank()) {
            if (!url.matches("jdbc:postgresql://(127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z0-9_]*test(?:\\?.*)?")) {
                throw new IllegalArgumentException("Roster contract accepts only an isolated loopback *_test database");
            }
            registry.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "stringtype=unspecified");
            registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
            registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
            registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
            registry.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        }
    }

    @BeforeEach
    void fixture() {
        for (String id : new String[]{F, OTHER}) {
            Factory factory = new Factory();factory.setId(id);factory.setName(id);em.persist(factory);
        }
        worker = worker(F);
        roster(F, worker.getId());
        User other = worker(OTHER);roster(OTHER, other.getId());
        em.flush();em.clear();
    }

    private User worker(String factory) {
        User user = new User();user.setFactoryId(factory);user.setUsername("aps-" + UUID.randomUUID());
        user.setPasswordHash("test-fixture-unused");user.setRoleCode("operator");user.setIsActive(true);
        return em.persist(user);
    }

    private void roster(String factory, Long id) {
        AttendanceShift shift = AttendanceShift.builder().factoryId(factory)
                .shiftCode("DAY").shiftName("Day").startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(18, 0)).isActive(true).isOvernight(false).build();
        em.persist(shift);
        em.persist(EmployeeShiftAssignment.builder().factoryId(factory).userId(id)
                .workDate(START.toLocalDate()).shiftId(shift.getId()).status(ShiftAssignmentStatus.SCHEDULED).build());
    }

    @Test
    void ownShiftAndUserQueriesDoNotCountAnotherFactory() {
        var result = check.check(F, START, START.plusHours(2), 1);
        assertThat(result.verified()).isTrue();assertThat(result.available()).isTrue();
        assertThat(result.availableWorkerIds()).containsExactly(worker.getId());
        assertThat(check.check(F, START, START.plusHours(2), 2).available()).isFalse();
    }

    @Test
    void approvedLeaveQueryExcludesWorkerAndSoftDeletedLeaveDoesNot() {
        LeaveRequest leave = LeaveRequest.builder().factoryId(F).userId(worker.getId())
                .leaveType(LeaveType.PERSONAL).startDate(START.toLocalDate()).endDate(START.toLocalDate())
                .durationHours(BigDecimal.ONE).status(HrRequestStatus.APPROVED).build();
        em.persistAndFlush(leave);em.clear();
        var excluded = check.check(F, START, START.plusHours(2), 1);
        assertThat(excluded.verified()).isTrue();assertThat(excluded.availableWorkers()).isZero();
        em.getEntityManager().createNativeQuery("update leave_requests set deleted_at = CURRENT_TIMESTAMP where factory_id = :factory and id = :id")
                .setParameter("factory", F).setParameter("id", leave.getId()).executeUpdate();
        em.clear();
        assertThat(check.check(F, START, START.plusHours(2), 1).available()).isTrue();
    }

    @Test
    void realScheduledOverlapBlocksAndCancelledLineReleasesIt() {
        LineSchedule line = occupy(F, worker.getId());
        em.flush();em.clear();
        assertThat(check.check(F, START, START.plusHours(2), 1).availableWorkers()).isZero();
        LineSchedule persisted = em.find(LineSchedule.class, line.getId());persisted.setStatus(LineSchedule.ScheduleStatus.cancelled);
        em.flush();em.clear();
        assertThat(check.check(F, START, START.plusHours(2), 1).available()).isTrue();
    }

    @Test
    void softDeletedRosterOrShiftCannotSupplyAWorker() {
        int removed = em.getEntityManager().createNativeQuery(
                        "update employee_shift_assignments set deleted_at = CURRENT_TIMESTAMP where factory_id = :factory and user_id = :user")
                .setParameter("factory", F).setParameter("user", worker.getId()).executeUpdate();
        assertThat(removed).isEqualTo(1);em.clear();
        assertThat(check.check(F, START, START.plusHours(2), 1).available()).isFalse();
        em.getEntityManager().createNativeQuery(
                        "update employee_shift_assignments set deleted_at = NULL where factory_id = :factory and user_id = :user")
                .setParameter("factory", F).setParameter("user", worker.getId()).executeUpdate();
        int retired = em.getEntityManager().createNativeQuery(
                        "update attendance_shifts set deleted_at = CURRENT_TIMESTAMP where factory_id = :factory")
                .setParameter("factory", F).executeUpdate();
        assertThat(retired).isEqualTo(1);em.clear();
        var result = check.check(F, START, START.plusHours(2), 1);
        assertThat(result.verified()).isFalse();assertThat(result.availableWorkerIds()).isEmpty();
    }

    @Test
    void anotherFactoryAssignmentUsingTheSameGlobalUserIdDoesNotAffectOwnRoster() {
        occupy(OTHER, worker.getId());em.flush();em.clear();
        assertThat(check.check(F, START, START.plusHours(2), 1).availableWorkerIds()).containsExactly(worker.getId());
    }

    @Test
    void softDeletedSchedulingParentDoesNotRemainAnActiveOccupancy() {
        LineSchedule line = occupy(F, worker.getId());
        em.flush();
        line.getPlan().setDeletedAt(START.minusDays(1));em.flush();em.clear();
        assertThat(check.check(F, START, START.plusHours(2), 1).available()).isTrue();
    }

    private LineSchedule occupy(String factory, Long userId) {
        SchedulingPlan parent = new SchedulingPlan();parent.setId(UUID.randomUUID().toString());parent.setFactoryId(factory);
        parent.setPlanDate(START.toLocalDate());parent.setStatus(SchedulingPlan.PlanStatus.confirmed);em.persist(parent);
        LineSchedule line = new LineSchedule();line.setId(UUID.randomUUID().toString());line.setPlan(parent);
        line.setProductionLineId("TEST-LINE");line.setPlannedStartTime(START.plusMinutes(30));
        line.setPlannedEndTime(START.plusHours(3));line.setStatus(LineSchedule.ScheduleStatus.pending);em.persist(line);
        WorkerAssignment assignment = new WorkerAssignment();assignment.setId(UUID.randomUUID().toString());
        assignment.setSchedule(line);assignment.setUserId(userId);assignment.setStatus(WorkerAssignment.AssignmentStatus.assigned);
        em.persist(assignment);
        return line;
    }
}
