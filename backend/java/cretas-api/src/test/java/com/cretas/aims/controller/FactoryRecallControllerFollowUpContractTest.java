package com.cretas.aims.controller;

import com.cretas.aims.dto.quality.FactoryRecallDTO;
import com.cretas.aims.service.quality.FactoryRecallFollowUpService;
import com.cretas.aims.service.quality.FactoryRecallService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FactoryRecallControllerFollowUpContractTest {

    @Mock private FactoryRecallService recallService;
    @Mock private FactoryRecallFollowUpService followUpService;
    @InjectMocks private FactoryRecallController controller;

    @Test
    void exposesFactoryScopedReadOnlyFollowUpEndpoint() {
        FactoryRecallDTO.FollowUpView view = FactoryRecallDTO.FollowUpView.builder()
                .recallId(9L).batchId(41L).recallCompleted(true)
                .inventoryResolved(false).receivableResolved(false).completed(false)
                .build();
        when(followUpService.get("F-A", 9L)).thenReturn(view);

        ResponseEntity<?> response = controller.followUp("F-A", 9L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(followUpService).get("F-A", 9L);
    }
}
