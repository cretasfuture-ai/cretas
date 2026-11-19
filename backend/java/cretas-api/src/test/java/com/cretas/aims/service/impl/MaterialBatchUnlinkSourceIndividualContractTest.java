package com.cretas.aims.service.impl;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.datacenter.OperationLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.datacenter.OperationLogRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * #1844 终审 L-d: 「解除批次的来源个体挂载」这条独立入口的契约。
 *
 * <p>守的是: 原因必填且先于任何读写校验; 只能解除本厂批次; 没挂个体时 409 而不是假装成功;
 * 解除时同一事务先写操作日志(old/new 值 + 原因)再清空批次上的个体 id;
 * 日志仓储不在时拒绝(不静默不留痕)。
 */
class MaterialBatchUnlinkSourceIndividualContractTest {

    private MaterialBatchRepository repo;
    private OperationLogRepository logRepo;
    private MaterialBatchMapper mapper;
    private MaterialConsumptionRepository consumptionRepo;
    private ThresholdResolverService thresholds;
    private MaterialBatchServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(MaterialBatchRepository.class);
        logRepo = mock(OperationLogRepository.class);
        mapper = mock(MaterialBatchMapper.class);
        when(repo.save(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));
        when(mapper.toDTO(any(MaterialBatch.class))).thenAnswer(i -> {
            MaterialBatch b = i.getArgument(0);
            return MaterialBatchDTO.builder().id(b.getId()).sourceIndividualId(b.getSourceIndividualId()).build();
        });
        consumptionRepo = mock(MaterialConsumptionRepository.class);
        noConsumptionRecords();
        service = new MaterialBatchServiceImpl(repo, null, null, mapper, consumptionRepo, null, null, null);
        ReflectionTestUtils.setField(service, "operationLogRepository", logRepo);
        // 真 PermissionServiceImpl + 可配置名单 (阈值打桩): 守的是「按权限码 + 配置判」这条真链, 不是一个桩的返回值。
        thresholds = mock(ThresholdResolverService.class);
        PermissionServiceImpl permissions = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissions, "thresholdResolver", thresholds);
        ReflectionTestUtils.setField(service, "permissionService", permissions);
    }

    private void noConsumptionRecords() {
        Page<MaterialConsumption> empty = new PageImpl<>(List.of());
        when(consumptionRepo.findByFactoryIdAndBatchIdPaged(eq("F1"), eq("B-1"), any(Pageable.class))).thenReturn(empty);
    }

    private MaterialBatch batch(String factoryId, String individualId) {
        MaterialBatch b = new MaterialBatch();
        b.setId("B-1");
        b.setFactoryId(factoryId);
        b.setBatchNumber("MB-0001");
        b.setCreatedBy(7L);
        b.setSourceIndividualId(individualId);
        when(repo.findById("B-1")).thenReturn(Optional.of(b));
        return b;
    }

    @Test
    @DisplayName("本厂已挂个体: 先写操作日志(旧值/原因/操作人)再清空批次上的个体 id")
    void unlinkClearsTheLinkAndWritesTheAuditRowFirst() {
        MaterialBatch b = batch("F1", "SI-OLD");

        MaterialBatchDTO out = service.unlinkSourceIndividual("F1", "B-1", "  挂错了个体  ", 42L, "warehouse_manager");

        assertThat(out.getSourceIndividualId()).isNull();
        assertThat(b.getSourceIndividualId()).isNull();
        ArgumentCaptor<OperationLog> log = ArgumentCaptor.forClass(OperationLog.class);
        InOrder order = inOrder(logRepo, repo);
        order.verify(logRepo).save(log.capture());
        order.verify(repo).save(b);
        OperationLog row = log.getValue();
        assertThat(row.getFactoryId()).isEqualTo("F1");
        assertThat(row.getUserId()).isEqualTo(42L);
        assertThat(row.getModule()).isEqualTo("MATERIAL_BATCH");
        assertThat(row.getAction()).isEqualTo("UNLINK_SOURCE_INDIVIDUAL");
        assertThat(row.getEntityId()).isEqualTo("B-1");
        assertThat(row.getOldValue()).containsEntry("sourceIndividualId", "SI-OLD");
        assertThat(row.getNewValue()).containsEntry("sourceIndividualId", null);
        assertThat(row.getSummary()).contains("MB-0001").contains("SI-OLD").contains("挂错了个体");
    }

    @Test
    @DisplayName("原因空白 → 400, 读写都没发生")
    void blankReasonIsRejectedBeforeAnyRead() {
        BusinessException e = catchThrowableOfType(
                () -> service.unlinkSourceIndividual("F1", "B-1", "   ", 42L, "warehouse_manager"),
                BusinessException.class);

        assertThat(e).isNotNull();
        assertThat(e.getCode()).isEqualTo(400);
        verifyNoInteractions(repo, logRepo);
    }

    @Test
    @DisplayName("原因超过 255 字 → 400 (不截断)")
    void overlongReasonIsRejected() {
        BusinessException e = catchThrowableOfType(
                () -> service.unlinkSourceIndividual("F1", "B-1", "x".repeat(256), 42L, "warehouse_manager"),
                BusinessException.class);

        assertThat(e.getCode()).isEqualTo(400);
        verifyNoInteractions(repo, logRepo);
    }

    @Test
    @DisplayName("别厂批次 → 403, 个体挂载不动, 不写日志")
    void otherFactoryBatchIsRejected() {
        MaterialBatch b = batch("F-OTHER", "SI-OLD");

        BusinessException e = catchThrowableOfType(
                () -> service.unlinkSourceIndividual("F1", "B-1", "挂错了", 42L, "warehouse_manager"),
                BusinessException.class);

        assertThat(e.getCode()).isEqualTo(403);
        assertThat(b.getSourceIndividualId()).isEqualTo("SI-OLD");
        verify(repo, never()).save(any());
        verifyNoInteractions(logRepo);
    }

    @Test
    @DisplayName("批次没挂个体 → 409 SOURCE_INDIVIDUAL_NOT_LINKED, 不写日志")
    void notLinkedIsAConflictNotASilentSuccess() {
        batch("F1", null);

        BusinessException e = catchThrowableOfType(
                () -> service.unlinkSourceIndividual("F1", "B-1", "挂错了", 42L, "warehouse_manager"),
                BusinessException.class);

        assertThat(e.getCode()).isEqualTo(409);
        assertThat(e.getErrorCode()).isEqualTo("SOURCE_INDIVIDUAL_NOT_LINKED");
        verify(repo, never()).save(any());
        verifyNoInteractions(logRepo);
    }

    @Test
    @DisplayName("操作日志仓储不在 → 拒绝, 批次挂载不动")
    void missingAuditRepositoryFailsClosed() {
        MaterialBatch b = batch("F1", "SI-OLD");
        ReflectionTestUtils.setField(service, "operationLogRepository", null);

        BusinessException e = catchThrowableOfType(
                () -> service.unlinkSourceIndividual("F1", "B-1", "挂错了", 42L, "warehouse_manager"),
                BusinessException.class);

        assertThat(e.getCode()).isEqualTo(500);
        assertThat(b.getSourceIndividualId()).isEqualTo("SI-OLD");
        verify(repo, never()).save(any());
    }

    // ── #1968 M-2 口径 a: 已有消耗的批次解除需要「解除已消耗批次的来源个体」权限 ──

    private void configureUnlinkConsumedRoles(String roles) {
        when(thresholds.getString("F1", ThresholdKeys.INVENTORY_PERMISSION_SOURCE_INDIVIDUAL_UNLINK_CONSUMED, null))
                .thenReturn(roles);
    }

    @Test
    @DisplayName("已领用的批次 + 仓库员(默认名单外) → 409 SOURCE_INDIVIDUAL_LINK_CONSUMED, 不写日志不改批次")
    void consumedBatchNeedsTheExtraPermission() {
        MaterialBatch b = batch("F1", "SI-OLD");
        b.setUsedQuantity(new BigDecimal("5"));
        b.setQuantityUnit("kg");

        BusinessException e = catchThrowableOfType(
                () -> service.unlinkSourceIndividual("F1", "B-1", "挂错了", 42L, "warehouse_worker"),
                BusinessException.class);

        assertThat(e).isNotNull();
        assertThat(e.getCode()).isEqualTo(409);
        assertThat(e.getErrorCode()).isEqualTo("SOURCE_INDIVIDUAL_LINK_CONSUMED");
        assertThat(e.getMessage()).contains("已领用 5 kg").contains("解除已消耗批次挂载");
        assertThat(e.getMessage() + e.getActionHint()).doesNotContain("质量经理").doesNotContain("仓储主管");
        assertThat(b.getSourceIndividualId()).isEqualTo("SI-OLD");
        verify(repo, never()).save(any());
        verifyNoInteractions(logRepo);
    }

    @Test
    @DisplayName("已领用的批次 + 默认名单里的仓储主管 → 能解除 (阳性对照: 不是一律拦)")
    void consumedBatchUnlinksForTheDefaultPermittedRole() {
        MaterialBatch b = batch("F1", "SI-OLD");
        b.setUsedQuantity(new BigDecimal("5"));

        service.unlinkSourceIndividual("F1", "B-1", "挂错了", 42L, "warehouse_manager");

        assertThat(b.getSourceIndividualId()).isNull();
        verify(logRepo).save(any(OperationLog.class));
    }

    @Test
    @DisplayName("权限按工厂配置走: 名单配成 warehouse_worker ⇒ 仓库员能解除, 仓储主管反而不能")
    void permissionFollowsFactoryConfiguration() {
        configureUnlinkConsumedRoles("warehouse_worker");
        MaterialBatch b = batch("F1", "SI-OLD");
        b.setStatus(MaterialBatchStatus.USED_UP);

        BusinessException denied = catchThrowableOfType(
                () -> service.unlinkSourceIndividual("F1", "B-1", "挂错了", 42L, "warehouse_manager"),
                BusinessException.class);
        assertThat(denied.getErrorCode()).isEqualTo("SOURCE_INDIVIDUAL_LINK_CONSUMED");
        assertThat(b.getSourceIndividualId()).isEqualTo("SI-OLD");

        service.unlinkSourceIndividual("F1", "B-1", "挂错了", 42L, "warehouse_worker");
        assertThat(b.getSourceIndividualId()).isNull();
    }

    @Test
    @DisplayName("used_quantity 为 0 但有消耗记录 (adjust 扣减不写 used_quantity) → 同样要权限")
    void consumptionRecordsCountAsConsumed() {
        MaterialBatch b = batch("F1", "SI-OLD");
        Page<MaterialConsumption> one = new PageImpl<>(List.of(new MaterialConsumption()), Pageable.ofSize(1), 3);
        when(consumptionRepo.findByFactoryIdAndBatchIdPaged(eq("F1"), eq("B-1"), any(Pageable.class))).thenReturn(one);

        BusinessException e = catchThrowableOfType(
                () -> service.unlinkSourceIndividual("F1", "B-1", "挂错了", 42L, "warehouse_worker"),
                BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo("SOURCE_INDIVIDUAL_LINK_CONSUMED");
        assertThat(e.getMessage()).contains("3 条消耗记录");
        assertThat(b.getSourceIndividualId()).isEqualTo("SI-OLD");
    }

    @Test
    @DisplayName("已消耗批次 + 权限服务缺失 → 拒绝 (fail closed)")
    void missingPermissionServiceFailsClosedForConsumedBatch() {
        MaterialBatch b = batch("F1", "SI-OLD");
        b.setStatus(MaterialBatchStatus.DEPLETED);
        ReflectionTestUtils.setField(service, "permissionService", null);

        BusinessException e = catchThrowableOfType(
                () -> service.unlinkSourceIndividual("F1", "B-1", "挂错了", 42L, "factory_super_admin"),
                BusinessException.class);

        assertThat(e.getErrorCode()).isEqualTo("SOURCE_INDIVIDUAL_LINK_CONSUMED");
        assertThat(b.getSourceIndividualId()).isEqualTo("SI-OLD");
    }
}
