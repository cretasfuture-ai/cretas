package com.cretas.aims.service.processsecret;

import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.TimeClockRecordRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.ImpactAnalysisService;
import com.cretas.aims.service.impl.ImpactAnalysisServiceImpl;
import com.cretas.aims.service.orchestration.BomExpansionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 独立审查 #3: 插单影响分析的数量由调用方随意填, 缺料提示「X 需要 N」÷ 数量 = 单位用量。
 * 无完整查看权的人只看到哪些料不足; 判定服务是真的, 拒绝要留审计。
 */
class ProcessSecretUrgentInsertMessageTest {

    static final String F = "F-A";
    static final String SECRET = "P17";

    ProcessSecretAuditRecorder recorder;
    ImpactAnalysisServiceImpl service;

    @BeforeEach
    void setUp() {
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        recorder = mock(ProcessSecretAuditRecorder.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(10L).createdBy(10L).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        ProcessSecretAccessService access = new ProcessSecretAccessService(policies, grants, recorder);

        BomExpansionService bom = mock(BomExpansionService.class);
        MaterialRequirement req = new MaterialRequirement();
        req.setMaterialTypeName("秘方香料");
        req.setRequiredQuantity(new BigDecimal("3250"));
        when(bom.expandBOM(eq(F), eq(SECRET), any())).thenReturn(List.of(req));
        MaterialShortfall shortfall = new MaterialShortfall();
        shortfall.setMaterialTypeName("秘方香料");
        shortfall.setRequiredQuantity(new BigDecimal("3250"));
        shortfall.setAvailableQuantity(new BigDecimal("10"));
        MaterialCheckResult check = new MaterialCheckResult();
        check.setAllSatisfied(false);
        check.setShortfalls(List.of(shortfall));
        when(bom.checkMaterialAvailability(eq(F), any())).thenReturn(check);

        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new ImpactAnalysisServiceImpl(mock(ProductionPlanRepository.class), mock(MaterialBatchRepository.class),
                mock(EquipmentRepository.class), mock(TimeClockRecordRepository.class), mock(ProductTypeRepository.class),
                bom, new ProcessSecretRecipeGuard(mock(BomRecipeRepository.class), access), tx);
    }

    String messageFor(long userId) {
        AuthenticatedActor actor = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, F, "dispatcher");
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor)) {
            ImpactAnalysisService.ResourceCheckResult result =
                    service.checkMaterialAvailability(F, SECRET, new BigDecimal("1000"));
            assertThat(result.isAvailable()).isFalse();
            return result.getMessage();
        }
    }

    @Test
    void aDispatcherWithoutGrantLearnsWhichMaterialsAreShortButNotHowMuchIsNeeded() {
        String message = messageFor(5L);
        assertThat(message).contains("秘方香料").contains("不足").doesNotContain("3250");
        verify(recorder).record(eq(F), eq(SECRET), any(), eq(ProcessSecretAction.VIEW_FULL), eq("DENY"), anyString(),
                eq("urgent-insert material check"));
    }

    @Test
    void theOwnerStillGetsTheQuantities() {
        assertThat(messageFor(10L)).contains("需要 3250").contains("可用 10");
    }
}
