package com.cretas.aims.service.gate;

import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.material.GateDiagnoseBatchTool;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GateDiagnosisLookupContractTest {
    private final MaterialBatchRepository material = mock(MaterialBatchRepository.class);
    private final SemiFinishedInventoryRepository semi = mock(SemiFinishedInventoryRepository.class);
    private final FinishedGoodsBatchRepository finished = mock(FinishedGoodsBatchRepository.class);
    private final GateDiagnosisService service = new GateDiagnosisService(material, semi, finished);

    @Test
    void absentOrForeignBatchCannotBeReportedAsUnblocked() {
        var foreign = new MaterialBatch();
        foreign.setFactoryId("B");
        when(material.findByFactoryIdAndBatchNumber("B", "FOREIGN")).thenReturn(Optional.of(foreign));
        for (String batch : new String[]{"MISSING", "FOREIGN"}) {
            assertThatThrownBy(() -> service.diagnoseBatch("A", batch))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> {
                        assertThat(ex.getCode()).isEqualTo(404);
                        assertThat(ex.getErrorCode()).isEqualTo("BATCH_NOT_FOUND");
                    });
            verify(material).findByFactoryIdAndBatchNumber("A", batch);
            verify(semi).findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull("A", batch);
            verify(finished).findByFactoryIdAndBatchNumber("A", batch);
        }
    }

    @Test
    void realAvailableBatchCanReturnUnblockedWithoutWriteConfirmation() {
        var batch = new MaterialBatch();
        batch.setFactoryId("A");
        batch.setBatchNumber("OK");
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        when(material.findByFactoryIdAndBatchNumber("A", "OK")).thenReturn(Optional.of(batch));
        var tool = new GateDiagnoseBatchTool(service);
        Map<String, Object> result = ReflectionTestUtils.invokeMethod(tool, "doExecute", "A",
                Map.of("batchNumber", "OK"), Map.of());
        assertThat(result).containsEntry("blocked", false);
        assertThat(new WriteGuardService().isWriteTool(tool)).isFalse();
    }
}
