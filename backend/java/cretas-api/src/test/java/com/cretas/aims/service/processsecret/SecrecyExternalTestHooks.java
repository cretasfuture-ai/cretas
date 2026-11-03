package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretExternalPackageRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretExternalResultRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.service.unit.UnitContractService;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 给别的包里的保密配置测试用: 包内可见的 requirePolicy 只能在本包里打桩。 */
public final class SecrecyExternalTestHooks {

    private SecrecyExternalTestHooks() {
    }

    /** 一个只认 {@code policy} 的客户侧工艺包服务; 单位一律认可 (走到公钥校验)。 */
    public static ProcessSecretExternalService external(ProcessSecretPolicy policy) {
        ProcessSecretPolicyService policyService = mock(ProcessSecretPolicyService.class);
        when(policyService.requirePolicy(policy.getFactoryId(), policy.getProductTypeId())).thenReturn(policy);
        ProcessSecretPolicyRepository policies = mock(ProcessSecretPolicyRepository.class);
        when(policies.findByFactoryIdAndProductTypeId(policy.getFactoryId(), policy.getProductTypeId()))
                .thenReturn(Optional.of(policy));
        UnitContractService units = mock(UnitContractService.class);
        when(units.areEquivalent(anyString(), anyString(), anyString())).thenReturn(true);
        return new ProcessSecretExternalService(policies, mock(ProcessSecretExternalPackageRepository.class),
                mock(ProcessSecretExternalResultRepository.class), mock(ProductionBatchRepository.class),
                mock(BomRecipeRepository.class), policyService, mock(ProcessSecretAccessService.class), units);
    }
}
