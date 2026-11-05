package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.shortage.ProcessParamDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.shortage.ProcessParamDeviationCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessParamDeviationCaseContractTest {

    private final ShortageCaseRepository cases = mock(ShortageCaseRepository.class);
    private final ProcessParamDeviationCaseDetailRepository details = mock(ProcessParamDeviationCaseDetailRepository.class);
    private final ShortageCaseEventRepository events = mock(ShortageCaseEventRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final ProcessSecretAggregateExclusions exclusions = mock(ProcessSecretAggregateExclusions.class);
    private final PermissionService permissions = mock(PermissionService.class);

    private ProcessParamDeviationCaseService service(boolean enabled) {
        when(exclusions.forCurrentCaller(anyString(), anyString())).thenReturn(ProcessSecretAggregateExclusions.Scope.EMPTY);
        return new ProcessParamDeviationCaseService(cases, details, events, users,
                mock(ProductionBatchRepository.class), exclusions, permissions, enabled);
    }

    private static ProcessParamDeviationCaseService.Fact fact(String status, String value) {
        return new ProcessParamDeviationCaseService.Fact("F1", ProcessParamDeviationCaseDetail.SourceKind.YIELD_REPORT,
                "yield-report:11:temp", 11L, 7L, 8L, null, null, "腌制", "B-7", "PT1", "temp", "温度", "℃",
                new BigDecimal(value), new BigDecimal("150"), new BigDecimal("140"), new BigDecimal("160"),
                status, LocalDateTime.of(2026, 11, 6, 10, 0));
    }

    @Test
    void disabledWriterDoesNotCreateRowsOrEnumFacts() {
        assertThat(service(false).apply(fact("HIGH", "170"), null))
                .isEqualTo(ProcessParamDeviationCaseService.Applied.DISABLED);
        verify(cases, never()).saveAndFlush(any());
        verify(details, never()).saveAndFlush(any());
    }

    @Test
    void sameFactIsIdempotentChangedFactRefreshesAndOkNeverCloses() {
        ProcessParamDeviationCaseService writer = service(true);
        when(details.findByFactoryIdAndSourceKey(eq("F1"), eq("yield-report:11:temp")))
                .thenReturn(Optional.empty())
                .thenAnswer(invocation -> Optional.of(savedDetail));
        when(cases.saveAndFlush(any(ShortageCase.class))).thenAnswer(invocation -> {
            ShortageCase c = invocation.getArgument(0);
            c.setId("CASE-1");
            return c;
        });
        when(details.saveAndFlush(any(ProcessParamDeviationCaseDetail.class))).thenAnswer(invocation -> {
            savedDetail = invocation.getArgument(0);
            return savedDetail;
        });

        assertThat(writer.apply(fact("HIGH", "170"), null)).isEqualTo(ProcessParamDeviationCaseService.Applied.CREATED);
        ShortageCase open = new ShortageCase();
        open.setId("CASE-1");
        open.setFactoryId("F1");
        open.setSourceType(ShortageCase.SourceType.PROCESS_PARAM_DEVIATION);
        open.setSourceId("yield-report:11:temp");
        open.setStage(ShortageCase.Stage.DETECTED);
        when(cases.findByIdAndFactoryIdAndSourceTypeIn(eq("CASE-1"), eq("F1"), anyCollection()))
                .thenReturn(Optional.of(open));
        when(cases.findByFactoryIdAndSourceTypeAndSourceIdForUpdate(eq("F1"),
                eq(ShortageCase.SourceType.PROCESS_PARAM_DEVIATION), eq("yield-report:11:temp")))
                .thenReturn(Optional.of(open));
        assertThat(writer.apply(fact("HIGH", "170"), null)).isEqualTo(ProcessParamDeviationCaseService.Applied.UNCHANGED);
        assertThat(writer.apply(fact("HIGH", "175"), null)).isEqualTo(ProcessParamDeviationCaseService.Applied.REFRESHED);
        assertThat(open.getInputFingerprint()).isNotBlank();

        ProcessParamDeviationCaseDetail closed = savedDetail;
        open.setClosedAt(LocalDateTime.now());
        when(details.findByFactoryIdAndSourceKey(eq("F1"), eq("yield-report:11:temp")))
                .thenReturn(Optional.of(closed));
        assertThat(writer.apply(fact("HIGH", "175"), null)).isEqualTo(ProcessParamDeviationCaseService.Applied.ALREADY_RESOLVED);
        assertThat(writer.apply(fact("OK", "150"), null)).isEqualTo(ProcessParamDeviationCaseService.Applied.ALREADY_RESOLVED);
        assertThat(open.getStage()).isEqualTo(ShortageCase.Stage.DETECTED);
    }

    private ProcessParamDeviationCaseDetail savedDetail;

    @Test
    void clerkFanoutReportsShareOneStableSourceFact() {
        ProcessParamDeviationCaseService writer = service(true);
        Map<String, ProcessParamDeviationCaseDetail> byKey = new LinkedHashMap<>();
        when(details.findByFactoryIdAndSourceKey(anyString(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(byKey.get(invocation.getArgument(1))));
        when(cases.saveAndFlush(any(ShortageCase.class))).thenAnswer(invocation -> {
            ShortageCase c = invocation.getArgument(0);
            c.setId("CASE-FANOUT");
            return c;
        });
        when(details.saveAndFlush(any(ProcessParamDeviationCaseDetail.class))).thenAnswer(invocation -> {
            ProcessParamDeviationCaseDetail d = invocation.getArgument(0);
            byKey.put(d.getSourceKey(), d);
            return d;
        });
        ShortageCase persisted = new ShortageCase();
        persisted.setId("CASE-FANOUT");
        persisted.setFactoryId("F1");
        persisted.setSourceType(ShortageCase.SourceType.PROCESS_PARAM_DEVIATION);
        persisted.setSourceId("process-sheet:7:3|炒制|加工:temp");
        persisted.setStage(ShortageCase.Stage.DETECTED);
        when(cases.findByFactoryIdAndSourceTypeAndSourceIdForUpdate(eq("F1"),
                eq(ShortageCase.SourceType.PROCESS_PARAM_DEVIATION), eq("process-sheet:7:3|炒制|加工:temp")))
                .thenAnswer(invocation -> byKey.isEmpty() ? Optional.empty() : Optional.of(persisted));
        when(cases.findByIdAndFactoryIdAndSourceTypeIn(eq("CASE-FANOUT"), eq("F1"), anyCollection()))
                .thenReturn(Optional.of(persisted));

        for (int i = 0; i < 3; i++) {
            ProductionReport report = ProductionReport.builder().id((long) (100 + i)).factoryId("F1")
                    .batchId(7L).productTypeId("PT1").reportType("YIELD")
                    .customFields(Map.of("processEntryStepKey", "3|炒制|加工",
                            "paramChecks", List.of(Map.of("key", "temp", "label", "温度", "unit", "℃",
                                    "value", new BigDecimal("170"), "standardValue", new BigDecimal("150"),
                                    "min", new BigDecimal("140"), "max", new BigDecimal("160"), "status", "HIGH"))))
                    .build();
            writer.recordReport(report);
        }
        verify(cases).saveAndFlush(any(ShortageCase.class));
        assertThat(byKey).containsOnlyKeys("process-sheet:7:3|炒制|加工:temp");
    }

    @Test
    void appResolvedProcessNameWinsWhileClerkKeepsCustomFieldName() {
        ProcessParamDeviationCaseService writer = service(true);
        List<ProcessParamDeviationCaseDetail> saved = new ArrayList<>();
        when(details.findByFactoryIdAndSourceKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(cases.saveAndFlush(any(ShortageCase.class))).thenAnswer(invocation -> {
            ShortageCase c = invocation.getArgument(0);
            c.setId("CASE-NAME-" + (saved.size() + 1));
            return c;
        });
        when(details.saveAndFlush(any(ProcessParamDeviationCaseDetail.class))).thenAnswer(invocation -> {
            ProcessParamDeviationCaseDetail d = invocation.getArgument(0);
            saved.add(d);
            return d;
        });

        ProductionReport appReport = ProductionReport.builder().id(91L).factoryId("F1")
                .batchId(7L).productTypeId("PT1").reportType("YIELD")
                .customFields(Map.of("paramChecks", List.of(
                        Map.of("key", "temp", "label", "温度", "unit", "℃", "value", new BigDecimal("170"),
                                "standardValue", new BigDecimal("150"), "min", new BigDecimal("140"),
                                "max", new BigDecimal("160"), "status", "HIGH"))))
                .build();
        writer.recordReport(appReport, "权威工序");
        assertThat(saved).hasSize(1);
        assertThat(saved.getFirst().getProcessName()).isEqualTo("权威工序");

        ProductionReport clerkReport = ProductionReport.builder().id(92L).factoryId("F1")
                .batchId(7L).productTypeId("PT1").reportType("YIELD")
                .customFields(Map.of("processEntryProcessName", "Clerk工序", "paramChecks", List.of(
                        Map.of("key", "temp", "label", "温度", "unit", "℃", "value", new BigDecimal("170"),
                                "standardValue", new BigDecimal("150"), "min", new BigDecimal("140"),
                                "max", new BigDecimal("160"), "status", "HIGH"))))
                .build();
        writer.recordReport(clerkReport);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(1).getProcessName()).isEqualTo("Clerk工序");
    }

    @Test
    void hiddenProductCasesAreFailClosedOnList() {
        ProcessParamDeviationCaseService writer = service(true);
        ShortageCase c = new ShortageCase();
        c.setId("CASE-HIDDEN");
        c.setFactoryId("F1");
        c.setSourceType(ShortageCase.SourceType.PROCESS_PARAM_DEVIATION);
        c.setSourceId("yield-report:22:temp");
        c.setStage(ShortageCase.Stage.DETECTED);
        c.setOwnerRole("quality_manager");
        c.setInputFingerprint("fp");
        c.setLastRefreshedAt(LocalDateTime.now());
        c.setLastRefreshReason("TEST");
        ProcessParamDeviationCaseDetail d = new ProcessParamDeviationCaseDetail();
        d.setCaseId(c.getId());
        d.setFactoryId("F1");
        d.setSourceKey(c.getSourceId());
        d.setSourceKind(ProcessParamDeviationCaseDetail.SourceKind.YIELD_REPORT);
        d.setParamKey("temp");
        d.setStatus("HIGH");
        d.setFactFingerprint("fp");
        d.setObservedAt(LocalDateTime.now());
        d.setProductTypeId("SECRET-PT");
        when(cases.findForFactoryBySources(eq("F1"), eq(true), anyCollection(), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(c)));
        when(details.findByCaseIdIn(anyCollection())).thenReturn(List.of(d));
        when(exclusions.forCurrentCaller(eq("F1"), anyString()))
                .thenReturn(new ProcessSecretAggregateExclusions.Scope(List.of("SECRET-PT"), List.of()));

        assertThat(writer.list("F1", true, 0, 50)).isEmpty();

        // A non-empty hidden scope must not turn an unresolvable historical row into a visible row.
        d.setProductTypeId(null);
        assertThat(writer.list("F1", true, 0, 50)).isEmpty();

        // A factory-resolved product is still hidden when its resolved production plan is protected.
        d.setProductTypeId("VISIBLE-PT");
        d.setProductionPlanId("SECRET-PLAN");
        when(exclusions.forCurrentCaller(eq("F1"), anyString()))
                .thenReturn(new ProcessSecretAggregateExclusions.Scope(List.of("SECRET-PT"), List.of("SECRET-PLAN")));
        assertThat(writer.list("F1", true, 0, 50)).isEmpty();
    }

    @Test
    void assigneesAreFactoryScopedActiveUsersWithSafeDisplayFields() {
        User user = new User();
        user.setId(41L);
        user.setFactoryId("F1");
        user.setUsername("qc-41");
        user.setFullName("质检甲");
        user.setRoleCode("QUALITY_MANAGER");
        user.setIsActive(true);
        User production = new User();
        production.setId(42L);
        production.setFactoryId("F1");
        production.setUsername("prod-42");
        production.setFullName("生产乙");
        production.setRoleCode("PRODUCTION_MANAGER");
        production.setIsActive(true);
        User warehouse = new User();
        warehouse.setId(43L);
        warehouse.setFactoryId("F1");
        warehouse.setUsername("warehouse-43");
        warehouse.setFullName("仓库丙");
        warehouse.setRoleCode("WAREHOUSE_WORKER");
        warehouse.setIsActive(true);
        when(users.findByFactoryIdAndIsActive("F1", true)).thenReturn(List.of(user, production, warehouse));
        when(permissions.hasAnyPermission(eq(user), eq("quality:read_write"), eq("production:read_write")))
                .thenReturn(true);
        when(permissions.hasAnyPermission(eq(production), eq("quality:read_write"), eq("production:read_write")))
                .thenReturn(true);
        when(permissions.hasAnyPermission(eq(warehouse), eq("quality:read_write"), eq("production:read_write")))
                .thenReturn(false);

        assertThat(service(true).assignees("F1"))
                .containsExactly(new ProcessParamDeviationCaseService.Assignee(41L, "质检甲", "QUALITY_MANAGER"),
                        new ProcessParamDeviationCaseService.Assignee(42L, "生产乙", "PRODUCTION_MANAGER"));
        verify(users).findByFactoryIdAndIsActive("F1", true);
    }

    @Test
    void assignRechecksProcessingPermissionForActiveWarehouseUser() {
        ShortageCase c = new ShortageCase();
        c.setId("CASE-ASSIGN");
        c.setFactoryId("F1");
        c.setSourceType(ShortageCase.SourceType.PROCESS_PARAM_DEVIATION);
        c.setSourceId("yield-report:41:temp");
        c.setStage(ShortageCase.Stage.DETECTED);
        ProcessParamDeviationCaseDetail d = new ProcessParamDeviationCaseDetail();
        d.setCaseId(c.getId());
        d.setFactoryId("F1");
        d.setSourceKey(c.getSourceId());
        d.setSourceKind(ProcessParamDeviationCaseDetail.SourceKind.YIELD_REPORT);
        d.setParamKey("temp");
        d.setStatus("HIGH");
        d.setFactFingerprint("fp");
        d.setObservedAt(LocalDateTime.now());
        User warehouse = new User();
        warehouse.setId(43L);
        warehouse.setFactoryId("F1");
        warehouse.setIsActive(true);
        warehouse.setRoleCode("WAREHOUSE_WORKER");
        when(cases.findForUpdateByIdAndFactoryIdAndSourceTypeIn(eq(c.getId()), eq("F1"), anyCollection()))
                .thenReturn(Optional.of(c));
        when(details.findByCaseIdAndFactoryIdForUpdate(c.getId(), "F1")).thenReturn(Optional.of(d));
        when(users.findByIdAndFactoryId(43L, "F1")).thenReturn(Optional.of(warehouse));
        when(permissions.hasAnyPermission(eq(warehouse), eq("quality:read_write"), eq("production:read_write")))
                .thenReturn(false);

        assertThatThrownBy(() -> service(true).assign("F1", c.getId(), 43L, 0L, null))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("没有工艺参数异常处理权限");
        verify(cases, never()).saveAndFlush(any(ShortageCase.class));
    }
}
