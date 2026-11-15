package com.cretas.aims.controller;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgExcelUpload;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgExcelUploadRepository;
import com.cretas.aims.service.smartbi.DynamicAnalysisService;
import com.cretas.aims.service.smartbi.ExcelDynamicParserService;
import com.cretas.aims.service.smartbi.SmartBIUploadFlowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 上传记录的 fields/data 读取必须校验归属工厂（此前只按 uploadId 读，跨厂可读到字段定义）。 */
@ExtendWith(MockitoExtension.class)
class SmartBIUploadControllerTenantContractTest {
    @Mock ExcelDynamicParserService parser;
    @Mock SmartBIUploadFlowService flow;
    @Mock PythonSmartBIClient client;
    @Mock PythonSmartBIConfig config;
    @Mock ObjectMapper mapper;
    @Mock DynamicAnalysisService analysis;
    @Mock SmartBiPgExcelUploadRepository uploads;

    private SmartBIUploadController controller() {
        return new SmartBIUploadController(parser, flow, client, config, mapper, analysis, uploads);
    }

    @Test
    void fieldsAndDataOfAnotherFactoryUploadAreNotFoundAndNeverRead() {
        when(uploads.findById(9L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().id(9L).factoryId("OTHER").build()));

        assertEquals(404, controller().getUploadFields("F006", 9L).getStatusCode().value());
        assertEquals(404, controller().getUploadData("F006", 9L, 0, 50).getStatusCode().value());
        verifyNoInteractions(analysis);
    }

    @Test
    void fieldsOfOwnUploadAreReturned() {
        when(uploads.findById(9L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().id(9L).factoryId("F006").build()));
        when(analysis.getFieldDefinitions(9L)).thenReturn(java.util.List.of());

        assertEquals(200, controller().getUploadFields("F006", 9L).getStatusCode().value());
        verify(analysis).getFieldDefinitions(9L);
    }
}
