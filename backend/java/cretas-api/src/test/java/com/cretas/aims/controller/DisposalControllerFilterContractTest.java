package com.cretas.aims.controller;

import com.cretas.aims.entity.DisposalRecord;
import com.cretas.aims.service.DisposalRecordService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisposalControllerFilterContractTest {

    @Mock private DisposalRecordService service;
    @InjectMocks private DisposalController controller;

    @Test
    void forwardsStatusKeywordAndFinishedGoodsBatchFiltersToServerSideService() {
        DisposalRecord record = new DisposalRecord();
        when(service.getByFactoryIdWithFilters("F-A", "product", "APPROVED",
                "needle", "FG-A", 0, 1))
                .thenReturn(new PageImpl<>(List.of(record), PageRequest.of(0, 1), 1));

        ResponseEntity<?> response = controller.getDisposalRecords(
                "F-A", 0, 1, "product", "APPROVED", "needle", "FG-A");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(service).getByFactoryIdWithFilters(eq("F-A"), eq("product"), eq("APPROVED"),
                eq("needle"), eq("FG-A"), eq(0), eq(1));
    }
}
