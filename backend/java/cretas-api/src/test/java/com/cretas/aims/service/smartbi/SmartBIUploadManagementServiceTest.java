package com.cretas.aims.service.smartbi;

import com.cretas.aims.entity.smartbi.postgres.SmartBiPgExcelUpload;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.smartbi.postgres.SmartBiDynamicDataRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgAnalysisResultRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgExcelUploadRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgFieldDefinitionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmartBIUploadManagementServiceTest {
    @Mock SmartBiPgExcelUploadRepository uploads;
    @Mock SmartBiDynamicDataRepository data;
    @Mock SmartBiPgFieldDefinitionRepository fields;
    @Mock SmartBiPgAnalysisResultRepository results;
    SmartBIUploadManagementService service;

    @BeforeEach
    void setUp() {
        service = new SmartBIUploadManagementService(uploads, data, fields, results);
    }

    private SmartBiPgExcelUpload upload(String factory) {
        return SmartBiPgExcelUpload.builder().id(7L).factoryId(factory).fileName("old.xlsx").build();
    }

    @Test
    void deleteIsPhysicalAndRemovesDependentRowsBeforeTheUpload() {
        when(uploads.findById(7L)).thenReturn(Optional.of(upload("F006")));

        service.delete("F006", 7L);

        InOrder order = inOrder(data, fields, results, uploads);
        order.verify(data).deleteByFactoryIdAndUploadId("F006", 7L);
        order.verify(fields).deleteByUploadId(7L);
        order.verify(results).deleteByUploadId(7L);
        order.verify(uploads).deleteById(7L);
    }

    @Test
    void crossFactoryDeleteAndRenameAreNotFoundAndTouchNothing() {
        when(uploads.findById(7L)).thenReturn(Optional.of(upload("OTHER")));

        assertThatThrownBy(() -> service.delete("F006", 7L)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.rename("F006", 7L, "new.xlsx")).isInstanceOf(BusinessException.class);

        verifyNoInteractions(data, fields, results);
        verify(uploads, never()).deleteById(any());
        verify(uploads, never()).save(any());
    }

    @Test
    void renameTrimsAndPersistsAndRejectsBlankOrOverlongNames() {
        when(uploads.findById(7L)).thenReturn(Optional.of(upload("F006")));
        when(uploads.save(any(SmartBiPgExcelUpload.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(service.rename("F006", 7L, "  新名称.xlsx ").getFileName()).isEqualTo("新名称.xlsx");
        assertThatThrownBy(() -> service.rename("F006", 7L, "   ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.rename("F006", 7L, "x".repeat(256))).isInstanceOf(BusinessException.class);
    }
}
