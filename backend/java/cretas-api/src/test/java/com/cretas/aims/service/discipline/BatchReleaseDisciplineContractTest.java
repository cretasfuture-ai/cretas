package com.cretas.aims.service.discipline;

import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.impl.dataop.BatchUpdateTool;
import com.cretas.aims.ai.tool.impl.workdesk.ReleaseDecisionTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.material.MaterialBatchRelease;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.material.MaterialBatchReleaseRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.impl.MaterialBatchServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纪律排查 A3: 批次改「可用」(放行) 按工厂配置的放行规则走, 默认只能质检放行; 不论怎么配, 放行都留痕。
 *
 * <p>测的是产品真实入口 {@link MaterialBatchServiceImpl#updateBatchStatus} (仓库页 PUT 与 AI batch_update 都汇到这里),
 * 以及 AI 质量主管放行 {@code release_decision}。
 */
@DisplayName("A3 批次放行: 工厂配置的放行规则 + 放行台账")
class BatchReleaseDisciplineContractTest {

    private static final String F = "F001";

    private final MaterialBatchRepository batches = mock(MaterialBatchRepository.class);
    private final MaterialBatchReleaseRepository releases = mock(MaterialBatchReleaseRepository.class);
    private final MaterialBatchServiceImpl service = new MaterialBatchServiceImpl(
            batches, null, null, mock(MaterialBatchMapper.class), null, null, null, null);

    private MaterialBatch batch(MaterialBatchStatus status, Map<String, String> config) {
        MaterialBatch b = new MaterialBatch();
        b.setId("MB-1");
        b.setFactoryId(F);
        b.setBatchNumber("B-1");
        b.setMaterialTypeId("M1");
        b.setStatus(status);
        when(batches.findById("MB-1")).thenReturn(Optional.of(b));
        when(batches.save(any())).thenAnswer(i -> i.getArgument(0));
        ReflectionTestUtils.setField(service, "materialBatchReleaseGuard",
                new MaterialBatchReleaseGuard(DisciplineFixtures.policy(config), releases));
        // D-S411-04: 质检判过不合格的原料不能改回可用 —— 这里没有质检放行记录
        ReflectionTestUtils.setField(service, "qcReleaseRecordRepository",
                mock(com.cretas.aims.repository.quality.QcReleaseRecordRepository.class));
        return b;
    }

    private static MaterialBatchReleaseGuard.ManualChange warehouse(String reason, Long userId, String role) {
        return new MaterialBatchReleaseGuard.ManualChange(MaterialBatchRelease.Channel.WAREHOUSE_STATUS_CHANGE,
                reason, userId, role);
    }

    private static final Map<String, String> MANUAL = Map.of(
            ThresholdKeys.QUALITY_BATCH_RELEASE_MODE, "QC_OR_MANUAL");

    // ── 默认: 只能质检放行 ───────────────────────────────────────────────

    @Test
    @DisplayName("默认 QC_ONLY: 仓库把质检中改成可用 → 409 且状态不变、不留放行记录 (带了原因也不行)")
    void defaultBlocksWarehouseRelease() {
        MaterialBatch b = batch(MaterialBatchStatus.INSPECTING, Map.of());
        assertThatThrownBy(() -> service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.AVAILABLE,
                warehouse("我看过了", 7L, "factory_super_admin")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(MaterialBatchReleaseGuard.CODE_QC_ONLY));
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        verify(batches, never()).save(any());
        verify(releases, never()).save(any());
    }

    @Test
    @DisplayName("默认 QC_ONLY: 过期改成鲜品也算放行 (能领去生产), 同样拦")
    void expiredToFreshIsAlsoARelease() {
        batch(MaterialBatchStatus.EXPIRED, Map.of());
        assertThatThrownBy(() -> service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.FRESH,
                warehouse("x", 7L, "warehouse_manager")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("阴性对照: 不构成放行的改状态 (可用→冻品、质检中→报废) 默认照常, 不要原因, 不写放行台账")
    void nonReleaseChangesAreUntouched() {
        MaterialBatch b = batch(MaterialBatchStatus.AVAILABLE, Map.of());
        assertThatCode(() -> service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.FROZEN,
                warehouse(null, 7L, "warehouse_manager"))).doesNotThrowAnyException();
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.FROZEN);

        MaterialBatch c = batch(MaterialBatchStatus.INSPECTING, Map.of());
        service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.SCRAPPED, warehouse(null, 7L, "warehouse_manager"));
        assertThat(c.getStatus()).isEqualTo(MaterialBatchStatus.SCRAPPED);
        verify(releases, never()).save(any());
    }

    @Test
    @DisplayName("配置值写错 → 按最严 (只能质检放行), 文案说出配置值")
    void unknownModeFallsBackToStrictest() {
        batch(MaterialBatchStatus.INSPECTING, Map.of(ThresholdKeys.QUALITY_BATCH_RELEASE_MODE, "MANUAL_OK"));
        assertThatThrownBy(() -> service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.AVAILABLE,
                warehouse("x", 7L, "warehouse_manager")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("MANUAL_OK");
    }

    // ── 非默认: 允许手动放行 ─────────────────────────────────────────────

    @Test
    @DisplayName("QC_OR_MANUAL + 原因必填 (默认): 不填原因 → 409; 填了 → 放行且台账记下谁、从哪个状态、什么原因")
    void manualReleaseNeedsReasonAndIsRecorded() {
        MaterialBatch b = batch(MaterialBatchStatus.INSPECTING, MANUAL);
        assertThatThrownBy(() -> service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.AVAILABLE,
                warehouse("  ", 7L, "warehouse_manager")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(MaterialBatchReleaseGuard.CODE_REASON_REQUIRED));
        verify(releases, never()).save(any());

        service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.AVAILABLE,
                warehouse("已线下复检合格 QC-0811", 7L, "warehouse_manager"));
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
        ArgumentCaptor<MaterialBatchRelease> row = ArgumentCaptor.forClass(MaterialBatchRelease.class);
        verify(releases).save(row.capture());
        assertThat(row.getValue().getReleasedBy()).isEqualTo(7L);
        assertThat(row.getValue().getReleasedByRole()).isEqualTo("warehouse_manager");
        assertThat(row.getValue().getFromStatus()).isEqualTo("INSPECTING");
        assertThat(row.getValue().getToStatus()).isEqualTo("AVAILABLE");
        assertThat(row.getValue().getChannel()).isEqualTo("WAREHOUSE_STATUS_CHANGE");
        assertThat(row.getValue().getReason()).isEqualTo("已线下复检合格 QC-0811");
        assertThat(row.getValue().getFactoryId()).isEqualTo(F);
    }

    @Test
    @DisplayName("QC_OR_MANUAL + 配了角色: 名单外的角色 403, 名单内的放行")
    void manualRolesAreConfigurable() {
        Map<String, String> cfg = Map.of(
                ThresholdKeys.QUALITY_BATCH_RELEASE_MODE, "QC_OR_MANUAL",
                ThresholdKeys.QUALITY_BATCH_RELEASE_MANUAL_ROLES, "quality_manager, factory_super_admin");
        MaterialBatch b = batch(MaterialBatchStatus.INSPECTING, cfg);
        assertThatThrownBy(() -> service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.AVAILABLE,
                warehouse("复检合格", 7L, "warehouse_manager")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getCode()).isEqualTo(403);
                    assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(MaterialBatchReleaseGuard.CODE_ROLE_NOT_ALLOWED);
                });
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);

        service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.AVAILABLE, warehouse("复检合格", 8L, "quality_manager"));
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
    }

    @Test
    @DisplayName("QC_OR_MANUAL + 原因可不填: 不填照样放行, 但台账仍记下放行人")
    void reasonOptionalStillRecordsWho() {
        MaterialBatch b = batch(MaterialBatchStatus.INSPECTING, Map.of(
                ThresholdKeys.QUALITY_BATCH_RELEASE_MODE, "QC_OR_MANUAL",
                ThresholdKeys.QUALITY_BATCH_RELEASE_REQUIRE_REASON, "0"));
        service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.AVAILABLE, warehouse(null, 9L, "warehouse_manager"));
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
        ArgumentCaptor<MaterialBatchRelease> row = ArgumentCaptor.forClass(MaterialBatchRelease.class);
        verify(releases).save(row.capture());
        assertThat(row.getValue().getReleasedBy()).isEqualTo(9L);
        assertThat(row.getValue().getReason()).isNull();
    }

    @Test
    @DisplayName("不论怎么配: 拿不到放行人 → 拒绝, 不允许无记录的放行")
    void noOperatorNoRelease() {
        batch(MaterialBatchStatus.INSPECTING, Map.of(
                ThresholdKeys.QUALITY_BATCH_RELEASE_MODE, "QC_OR_MANUAL",
                ThresholdKeys.QUALITY_BATCH_RELEASE_REQUIRE_REASON, "0"));
        assertThatThrownBy(() -> service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.AVAILABLE,
                warehouse("x", null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(MaterialBatchReleaseGuard.CODE_OPERATOR_REQUIRED));
        verify(releases, never()).save(any());
    }

    @Test
    @DisplayName("放行闸未装配 → 放行一律拒绝 (fail-closed), 不构成放行的照常")
    void missingGuardFailsClosed() {
        MaterialBatch b = batch(MaterialBatchStatus.INSPECTING, MANUAL);
        ReflectionTestUtils.setField(service, "materialBatchReleaseGuard", null);
        assertThatThrownBy(() -> service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.AVAILABLE,
                warehouse("x", 7L, "quality_manager"))).isInstanceOf(IllegalStateException.class);
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        service.updateBatchStatus(F, "MB-1", MaterialBatchStatus.SCRAPPED, warehouse(null, 7L, "quality_manager"));
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.SCRAPPED);
    }

    // ── AI 入口 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AI batch_update 改状态带上 AI 通道、会话里的人和角色、对话里的原因")
    void aiBatchUpdatePassesWhoAndWhy() throws Exception {
        MaterialBatchService svc = mock(MaterialBatchService.class);
        BatchUpdateTool tool = new BatchUpdateTool();
        ReflectionTestUtils.setField(tool, "materialBatchService", svc);
        MaterialBatchDTO dto = new MaterialBatchDTO();
        dto.setId("MB-1");
        when(svc.getMaterialBatchById(F, "MB-1")).thenReturn(dto);
        Map<String, Object> params = new HashMap<>(Map.of("batchId", "MB-1", "status", "AVAILABLE", "reason", "复检合格"));
        Map<String, Object> ctx = new HashMap<>(Map.of("userId", 12L, "userRole", "warehouse_manager"));
        Method m = BatchUpdateTool.class.getDeclaredMethod("doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        m.invoke(tool, F, params, ctx);

        ArgumentCaptor<MaterialBatchReleaseGuard.ManualChange> ch =
                ArgumentCaptor.forClass(MaterialBatchReleaseGuard.ManualChange.class);
        verify(svc).updateBatchStatus(eq(F), eq("MB-1"), eq(MaterialBatchStatus.AVAILABLE), ch.capture());
        assertThat(ch.getValue().channel()).isEqualTo(MaterialBatchRelease.Channel.AI_BATCH_UPDATE);
        assertThat(ch.getValue().operatorId()).isEqualTo(12L);
        assertThat(ch.getValue().operatorRole()).isEqualTo("warehouse_manager");
        assertThat(ch.getValue().reason()).isEqualTo("复检合格");
    }

    private ReleaseDecisionTool releaseTool(boolean hasQcPermission) {
        ReleaseDecisionTool tool = new ReleaseDecisionTool();
        ReflectionTestUtils.setField(tool, "materialBatchRepository", batches);
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        ToolRbacGuard rbac = mock(ToolRbacGuard.class);
        when(rbac.hasAnyPermission(any(), any(String[].class))).thenReturn(hasQcPermission);
        when(rbac.denyMessage(any(), any(), any(String[].class))).thenReturn("权限不足");
        ReflectionTestUtils.setField(tool, "rbacGuard", rbac);
        ReflectionTestUtils.setField(tool, "releaseGuard",
                new MaterialBatchReleaseGuard(DisciplineFixtures.defaults(), releases));
        ReflectionTestUtils.setField(tool, "qcReleaseRecordRepository",
                mock(com.cretas.aims.repository.quality.QcReleaseRecordRepository.class));
        return tool;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runRelease(ReleaseDecisionTool tool) throws Exception {
        Method m = ReleaseDecisionTool.class.getDeclaredMethod("doExecute", String.class, Map.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(tool, F,
                Map.of("batchNumber", "B-1", "decision", "RELEASED", "notes", "感官+微生物合格"),
                new HashMap<>(Map.of("userId", 21L, "userRole", "quality_manager")));
    }

    @Test
    @DisplayName("AI 质检放行 release_decision: 没有质检权限 (如仓管) → 拒绝且状态不变 —— 阴性对照: 有权限的放行并留痕")
    void aiReleaseDecisionNeedsQcPermissionAndRecords() throws Exception {
        MaterialBatch b = new MaterialBatch();
        b.setId("MB-1");
        b.setFactoryId(F);
        b.setBatchNumber("B-1");
        b.setStatus(MaterialBatchStatus.INSPECTING);
        when(batches.findByFactoryIdAndBatchNumber(F, "B-1")).thenReturn(Optional.of(b));
        when(batches.save(any())).thenAnswer(i -> i.getArgument(0));

        Map<String, Object> denied = runRelease(releaseTool(false));
        assertThat(denied).containsEntry("denied", true);
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        verify(releases, never()).save(any());

        runRelease(releaseTool(true));
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.AVAILABLE);
        ArgumentCaptor<MaterialBatchRelease> row = ArgumentCaptor.forClass(MaterialBatchRelease.class);
        verify(releases).save(row.capture());
        assertThat(row.getValue().getChannel()).isEqualTo("QC_RELEASE_DECISION");
        assertThat(row.getValue().getReleasedBy()).isEqualTo(21L);
        assertThat(row.getValue().getReason()).isEqualTo("感官+微生物合格");
    }
}
