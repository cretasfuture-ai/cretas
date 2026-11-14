package com.cretas.aims.service.bom.impl;

import com.cretas.aims.entity.bom.BomVersion;
import com.cretas.aims.entity.bom.BomVersion.VersionStatus;
import com.cretas.aims.entity.bom.EngineeringChangeNotice;
import com.cretas.aims.entity.bom.EngineeringChangeNotice.EcnStatus;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.bom.EngineeringChangeNoticeRepository;
import com.cretas.aims.service.bom.BomVersionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 红线 3 后续: ECN 审批级联审批多个 BOM 版本, 每个版本审批都会锁它的配方头 (markCurrentVersion)。
 * 必须按配方 id 升序逐个审批 (与族锁同向), 排序交给 PG —— 用按配方 id 排好序的查询, 不用无序的 findByEcnId。
 */
@ExtendWith(MockitoExtension.class)
class ECNServiceImplApproveOrderContractTest {

    private static final String FACTORY = "F006";

    @Mock EngineeringChangeNoticeRepository ecnRepo;
    @Mock BomVersionRepository versionRepo;
    @Mock BomVersionService versionService;
    @InjectMocks ECNServiceImpl service;

    @Test
    void cascadedVersionsAreApprovedInRecipeIdOrderFromTheOrderedQuery() {
        EngineeringChangeNotice ecn = EngineeringChangeNotice.builder()
                .id("ecn-1").factoryId(FACTORY).ecnNumber("ECN-QA-1").bomRecipeId("recipe-a")
                .status(EcnStatus.SUBMITTED).build();
        when(ecnRepo.findById("ecn-1")).thenReturn(Optional.of(ecn));
        when(ecnRepo.save(any(EngineeringChangeNotice.class))).thenAnswer(inv -> inv.getArgument(0));
        BomVersion onA = version("v-9", "recipe-a");
        BomVersion onB = version("v-1", "recipe-b");
        when(versionRepo.findByEcnIdOrderByBomRecipeIdAscIdAsc("ecn-1")).thenReturn(List.of(onA, onB));

        service.approve(FACTORY, "ecn-1", 7L);

        InOrder order = inOrder(versionService);
        order.verify(versionService).approve(FACTORY, "v-9", 7L);
        order.verify(versionService).approve(FACTORY, "v-1", 7L);
        verify(versionRepo, never()).findByEcnId(anyString());
    }

    private static BomVersion version(String id, String recipeId) {
        return BomVersion.builder().id(id).factoryId(FACTORY).bomRecipeId(recipeId)
                .versionNumber(1).status(VersionStatus.PENDING_APPROVAL).build();
    }
}
