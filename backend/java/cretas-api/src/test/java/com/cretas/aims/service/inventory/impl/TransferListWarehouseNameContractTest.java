package com.cretas.aims.service.inventory.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.enums.TransferStatus;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.InternalTransfer;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.InternalTransferRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

class TransferListWarehouseNameContractTest {

    private static FactoryWarehouse wh(String id, String name) {
        FactoryWarehouse w = new FactoryWarehouse();
        w.setId(id);
        w.setName(name);
        return w;
    }

    @Test
    void listFillsWarehouseNamesAndStatusLabel() throws Exception {
        InternalTransferRepository repo = mock(InternalTransferRepository.class);
        TransferServiceImpl svc = new TransferServiceImpl(repo, null, null, null, null, null, null);
        FactoryWarehouseRepository whRepo = mock(FactoryWarehouseRepository.class);
        when(whRepo.findByFactoryIdAndDeletedAtIsNullOrderByCodeAsc("F1"))
                .thenReturn(List.of(wh("W1", "主仓"), wh("W2", "生产仓")));
        ReflectionTestUtils.setField(svc, "factoryWarehouseRepository", whRepo);

        InternalTransfer t = new InternalTransfer();
        t.setSourceWarehouseId("W1");
        t.setTargetWarehouseId("W-GONE");
        t.setStatus(TransferStatus.SHIPPED);
        when(repo.findByFactoryId(eq("F1"), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(t)));

        PageResponse<InternalTransfer> page = svc.getTransfers("F1", 1, 20);
        InternalTransfer out = page.getContent().get(0);

        assertThat(out.getSourceWarehouseName()).isEqualTo("主仓");
        assertThat(out.getTargetWarehouseName()).isNull();
        var json = new ObjectMapper().registerModule(new JavaTimeModule()).valueToTree(out);
        assertThat(json.get("statusLabel").asText()).isEqualTo("已发货");
    }
}
