package com.cretas.aims.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.security.DataScopeContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

/** 守住 getMaterialBatchList 真实入口把「未结单预扣」填进 DTO(删掉填充循环或换 factoryId 必须红)。 */
class MaterialBatchListUnsettledContractTest {

    @Test
    void listFillsUnsettledSoAvailableIsCurrentMinusHold() {
        MaterialBatchRepository repo = mock(MaterialBatchRepository.class);
        MaterialConsumptionRepository consumption = mock(MaterialConsumptionRepository.class);
        MaterialBatchMapper mapper = mock(MaterialBatchMapper.class);
        MaterialBatch held = new MaterialBatch();
        held.setId("B-HELD");
        MaterialBatch free = new MaterialBatch();
        free.setId("B-FREE");
        when(repo.findByFactoryId(anyString(), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(held, free)));
        when(mapper.toDTO(any(MaterialBatch.class))).thenAnswer(i -> {
            MaterialBatch b = i.getArgument(0);
            return MaterialBatchDTO.builder().id(b.getId()).status(MaterialBatchStatus.AVAILABLE)
                    .currentQuantity(new BigDecimal("100")).build();
        });
        when(consumption.sumUnsettledConsumptionGroupedByBatch(org.mockito.ArgumentMatchers.eq("F1"), anyList()))
                .thenReturn(Collections.singletonList(new Object[] {"B-HELD", new BigDecimal("30")}));

        MaterialBatchServiceImpl service = new MaterialBatchServiceImpl(
                repo, null, null, mapper, consumption, null, null, null);
        PageRequest pr = new PageRequest();
        pr.setPage(1);
        pr.setSize(20);
        DataScopeContext.push(DataScopeContext.builder().userId(1L).factoryId("F1")
                .roleCode("factory_super_admin").scope(com.cretas.aims.entity.enums.DataScope.ALL).column("created_by").build());
        PageResponse<MaterialBatchDTO> out;
        try {
            out = service.getMaterialBatchList("F1", pr);
        } finally {
            DataScopeContext.pop();
        }

        MaterialBatchDTO h = out.getContent().get(0);
        MaterialBatchDTO f = out.getContent().get(1);
        assertThat(h.getUnsettledConsumption()).isEqualByComparingTo("30");
        assertThat(h.getAvailableQuantity()).isEqualByComparingTo("70");
        // 已查询但没有预扣的批次: 预扣是 0(不是未知), 可用 == 账面
        assertThat(f.getUnsettledConsumption()).isEqualByComparingTo("0");
        assertThat(f.getAvailableQuantity()).isEqualByComparingTo("100");
    }
}
