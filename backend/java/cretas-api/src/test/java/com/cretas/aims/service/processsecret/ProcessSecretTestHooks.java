package com.cretas.aims.service.processsecret;

import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;

import static org.mockito.Mockito.mock;

/** 测试用入口: 与生产同一个 {@link ProcessSecretReadTracker#record} 打标, 以及按需组装裁剪器。 */
public final class ProcessSecretTestHooks {

    private ProcessSecretTestHooks() {
    }

    /** 在桩里模拟「真实服务读到了这条实体」, 与生产上 Hibernate PostLoad 走同一个入口。 */
    public static void record(Object loadedEntity) {
        ProcessSecretReadTracker.record(loadedEntity);
    }

    /** 领料单 / 对话建厂草稿的解析仓库不涉及时给桩。 */
    public static ProcessSecretPayloadScrubber scrubber(ProcessSecretAccessService access, BomRecipeRepository recipes,
                                                       ProductionWorkflowInstanceRepository instances,
                                                       ProductionPlanRepository plans) {
        return new ProcessSecretPayloadScrubber(access, recipes, instances, plans,
                mock(FactoryMaterialRequisitionRepository.class), mock(BomRecipeDraftRepository.class));
    }
}
