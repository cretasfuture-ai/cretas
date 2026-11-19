package com.cretas.aims.service.factory.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cretas.aims.dto.factory.StocktakeDTO;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.repository.factory.FactoryStocktakeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

class StocktakeListWarehouseNameContractTest {

    @Test
    void listCarriesWarehouseNameAndLeavesUnknownNull() {
        FactoryStocktakeRepository repo = mock(FactoryStocktakeRepository.class);
        FactoryStocktakeServiceImpl svc = new FactoryStocktakeServiceImpl(
                repo, null, null, null, null, null, null, null, null, null, null);
        FactoryWarehouseRepository whRepo = mock(FactoryWarehouseRepository.class);
        FactoryWarehouse w = new FactoryWarehouse();
        w.setId("W1");
        w.setName("主仓");
        when(whRepo.findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc("F1")).thenReturn(List.of(w));
        ReflectionTestUtils.setField(svc, "factoryWarehouseRepository", whRepo);

        FactoryStocktake a = new FactoryStocktake();
        a.setId("a");
        a.setFactoryId("F1");
        a.setWarehouseId("W1");
        FactoryStocktake b = new FactoryStocktake();
        b.setId("b");
        b.setFactoryId("F1");
        b.setWarehouseId("GONE");
        when(repo.findByFactoryIdAndOptionalStatus(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(a, b)));

        List<StocktakeDTO> out = svc.list("F1", null, PageRequest.of(0, 20)).getContent();

        assertThat(out.get(0).getWarehouseName()).isEqualTo("主仓");
        assertThat(out.get(1).getWarehouseName()).isNull();
        // #1844 L-1: 整页一次查本厂仓库, 不逐行按 id 查 (N+1)。
        verify(whRepo, times(1)).findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc("F1");
        verify(whRepo, never()).findByIdAndFactoryIdAndDeletedAtIsNull(any(), any());
    }
}
