package com.cretas.aims.controller;

import com.cretas.aims.entity.smartbi.postgres.SmartBiPgAnalysisResult;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgExcelUpload;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgAnalysisResultRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgExcelUploadRepository;
import com.cretas.aims.service.smartbi.*;
import com.cretas.aims.service.smartbi.chart.AdaptiveChartGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SmartBIDashboardControllerTenantContractTest {
    private SmartBIDashboardController controller(
            DynamicAnalysisService dynamic,
            SmartBiPgExcelUploadRepository uploads,
            SmartBiPgAnalysisResultRepository cache) {
        SmartBIDashboardController controller = new SmartBIDashboardController(
                mock(SalesAnalysisService.class), mock(DepartmentAnalysisService.class),
                mock(RegionAnalysisService.class), mock(FinanceAnalysisService.class),
                mock(InventoryHealthAnalysisService.class), mock(ProcurementAnalysisService.class),
                mock(RecommendationService.class), mock(SmartBIService.class),
                mock(AdaptiveChartGenerator.class), dynamic);
        ReflectionTestUtils.setField(controller, "pgUploadRepository", uploads);
        ReflectionTestUtils.setField(controller, "analysisResultRepository", cache);
        ReflectionTestUtils.setField(controller, "cacheObjectMapper", new ObjectMapper());
        return controller;
    }

    @Test
    void foreignUploadIsRejectedBeforeCacheLookupOrDynamicRead() {
        DynamicAnalysisService dynamic = mock(DynamicAnalysisService.class);
        SmartBiPgExcelUploadRepository uploads = mock(SmartBiPgExcelUploadRepository.class);
        SmartBiPgAnalysisResultRepository cache = mock(SmartBiPgAnalysisResultRepository.class);
        when(uploads.findById(9L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().id(9L).factoryId("F007").build()));

        var response = controller(dynamic, uploads, cache)
                .analyzeDynamicData("F006", 9L, "auto", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getSuccess()).isFalse();
        verifyNoInteractions(cache, dynamic);
    }

    @Test
    void sameFactoryCacheUsesFactoryScopedLookupAndSkipsLiveRead() {
        DynamicAnalysisService dynamic = mock(DynamicAnalysisService.class);
        SmartBiPgExcelUploadRepository uploads = mock(SmartBiPgExcelUploadRepository.class);
        SmartBiPgAnalysisResultRepository cache = mock(SmartBiPgAnalysisResultRepository.class);
        when(uploads.findById(9L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().id(9L).factoryId("F006").build()));
        SmartBiPgAnalysisResult row = SmartBiPgAnalysisResult.builder()
                .uploadId(9L).factoryId("F006").analysisType("dashboard_auto")
                .analysisResult(Map.of("uploadId", 9L)).createdAt(LocalDateTime.now()).build();
        when(cache.findByFactoryIdAndUploadIdAndAnalysisType("F006", 9L, "dashboard_auto"))
                .thenReturn(Optional.of(row));

        var response = controller(dynamic, uploads, cache)
                .analyzeDynamicData("F006", 9L, "auto", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getSuccess()).isTrue();
        verify(cache).findByFactoryIdAndUploadIdAndAnalysisType("F006", 9L, "dashboard_auto");
        verify(dynamic, never()).analyzeDynamic(any(), any(), any());
    }
}
