package com.cretas.aims.controller;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.impl.ProcessingServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exact factory-scoped batch-number lookup used by quality scan/manual entry. */
@ExtendWith(MockitoExtension.class)
class ProcessingQualityBatchLookupContractTest {

    @Mock
    private ProductionBatchRepository batchRepository;

    @InjectMocks
    private ProcessingServiceImpl processingService;

    @Test
    void serviceTrimsExactNumberUsesFactoryScopedRepositoryAndDoesNotWrite() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(10806L);
        batch.setFactoryId("F006");
        batch.setBatchNumber("PB-REAL-10806");
        when(batchRepository.findByFactoryIdAndBatchNumber("F006", "PB-REAL-10806"))
                .thenReturn(Optional.of(batch));

        ProductionBatch result = processingService.getBatchByNumber("F006", "  PB-REAL-10806 ");

        org.junit.jupiter.api.Assertions.assertSame(batch, result);
        verify(batchRepository).findByFactoryIdAndBatchNumber(eq("F006"), eq("PB-REAL-10806"));
    }

    @Test
    void controllerExposesQualityReadWriteGetLookupWithPersistedIdentity() throws Exception {
        ProcessingService service = org.mockito.Mockito.mock(ProcessingService.class);
        ProductionBatch batch = new ProductionBatch();
        batch.setId(10806L);
        batch.setFactoryId("F006");
        batch.setBatchNumber("PB-REAL-10806");
        when(service.getBatchByNumber("F006", "PB-REAL-10806")).thenReturn(batch);

        ProcessingController controller = new ProcessingController(
                service,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/mobile/F006/processing/batches/lookup")
                        .param("batchNumber", "PB-REAL-10806"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(10806))
                .andExpect(jsonPath("$.data.factoryId").value("F006"))
                .andExpect(jsonPath("$.data.batchNumber").value("PB-REAL-10806"));
        verify(service).getBatchByNumber("F006", "PB-REAL-10806");
    }
}
