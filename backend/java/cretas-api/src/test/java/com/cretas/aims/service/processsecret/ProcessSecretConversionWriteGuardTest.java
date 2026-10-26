package com.cretas.aims.service.processsecret;

import com.cretas.aims.dto.ConversionDTO;
import com.cretas.aims.entity.MaterialProductConversion;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ConversionChangeHistoryRepository;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.impl.ConversionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 转换率就是配方。页面上对无权的人显示「已保密」(值为空), 所以服务层必须挡住他的写入 ——
 * 否则一次「编辑 → 保存」就把空值 / 0 写回保密产品的转换率。判定服务是真的, 审计写入是桩。
 */
class ProcessSecretConversionWriteGuardTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final long OWNER = 10L;
    static final long OPERATOR = 5L;

    ConversionRepository conversions;
    ProcessSecretAuditRecorder recorder;
    ConversionServiceImpl service;

    @BeforeEach
    void setUp() {
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        ProcessSecretGrantRepository grants = mock(ProcessSecretGrantRepository.class);
        recorder = mock(ProcessSecretAuditRecorder.class);
        when(grants.findActive(anyString(), anyString(), any(), any(), any())).thenReturn(List.of());
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(SECRET)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).createdBy(OWNER).build();
        when(policies.findByFactoryIdAndProductTypeId(F, SECRET)).thenReturn(Optional.of(policy));
        ProcessSecretAccessService access = new ProcessSecretAccessService(policies, grants, recorder);

        conversions = mock(ConversionRepository.class);
        MaterialProductConversion existing = new MaterialProductConversion();
        existing.setId("c1");
        existing.setFactoryId(F);
        existing.setMaterialTypeId("M1");
        existing.setProductTypeId(SECRET);
        existing.setConversionRate(new BigDecimal("0.82"));
        when(conversions.findById("c1")).thenReturn(Optional.of(existing));
        when(conversions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service = new ConversionServiceImpl(conversions, mock(RawMaterialTypeRepository.class),
                mock(ProductTypeRepository.class), mock(ProductionBatchRepository.class),
                mock(MaterialConsumptionRepository.class), mock(MaterialBatchRepository.class),
                mock(ConversionChangeHistoryRepository.class), mock(UserRepository.class),
                new ProcessSecretRecipeGuard(mock(BomRecipeRepository.class), access));
    }

    static AuthenticatedActor actor(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    static ConversionDTO blankedEdit() {
        // 无权的人在页面上看到的是空值; 这就是他保存时会发出的内容
        return ConversionDTO.builder().materialTypeId("M1").productTypeId(SECRET).conversionRate(null).build();
    }

    @Test
    void anOperatorCannotOverwriteAProtectedConversionWithTheBlankedValue() {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OPERATOR))) {
            assertThatThrownBy(() -> service.updateConversion(F, "c1", blankedEdit()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
            assertThatThrownBy(() -> service.deleteConversion(F, "c1")).isInstanceOf(BusinessException.class);
        }
        verify(conversions, never()).save(any());
        verify(conversions, never()).delete(any());
        verify(recorder).record(eq(F), eq(SECRET), eq(actor(OPERATOR)), eq(ProcessSecretAction.EDIT), eq("DENY"),
                anyString(), eq("conversion update c1"));
    }

    @Test
    void theOwnerCanStillEditTheConversion() {
        ConversionDTO edit = ConversionDTO.builder().materialTypeId("M1").productTypeId(SECRET)
                .conversionRate(new BigDecimal("0.85")).build();
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor(OWNER))) {
            service.updateConversion(F, "c1", edit);
        }
        verify(conversions).save(any());
    }
}
