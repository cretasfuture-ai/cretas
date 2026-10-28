package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.entity.smartbi.postgres.SmartBiDynamicData;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgExcelUpload;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgFieldDefinition;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.smartbi.postgres.SmartBiDynamicDataRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgExcelUploadRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgFieldDefinitionRepository;
import com.cretas.aims.service.smartbi.DynamicAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamicAnalysisServiceTenantContractTest {
    private final SmartBiDynamicDataRepository rows = mock(SmartBiDynamicDataRepository.class);
    private final SmartBiPgExcelUploadRepository uploads = mock(SmartBiPgExcelUploadRepository.class);
    private final SmartBiPgFieldDefinitionRepository fields = mock(SmartBiPgFieldDefinitionRepository.class);

    @Test
    void truncatedPageWithNullRowCountIsNotMarkedComplete() {
        SmartBiPgExcelUpload upload = SmartBiPgExcelUpload.builder().factoryId("F006").build();
        when(uploads.findById(9L)).thenReturn(Optional.of(upload));
        SmartBiDynamicData row = SmartBiDynamicData.builder().rowData(Map.of("单位", "kg")).build();
        when(rows.findByFactoryIdAndUploadIdOrderByRowIndex(eq("F006"), eq(9L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 1000), 2000));
        when(fields.findByUploadIdOrderByDisplayOrder(9L)).thenReturn(List.of(
                SmartBiPgFieldDefinition.builder().originalName("单位").standardName("product_unit").build()));

        var response = new DynamicAnalysisServiceImpl(rows, uploads, fields)
                .analyzeDynamic("F006", 9L, "auto");

        assertThat(response.getAnalysisFacts()).containsEntry("unitComplete", false);
        assertThat(response.getAnalysisFacts()).containsEntry("unitValues", List.of("kg"));
        assertThat(response.getAnalysisFacts()).containsEntry("sourceRows", 2000L);
    }

    @Test
    void pageTotalIsAuthoritativeWhenUploadMetadataDrifts() {
        SmartBiPgExcelUpload upload = SmartBiPgExcelUpload.builder()
                .factoryId("F006").rowCount(1).build();
        when(uploads.findById(10L)).thenReturn(Optional.of(upload));
        SmartBiDynamicData row = SmartBiDynamicData.builder()
                .rowData(Map.of("单位", "kg")).build();
        when(rows.findByFactoryIdAndUploadIdOrderByRowIndex(eq("F006"), eq(10L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 1000), 2000));
        when(fields.findByUploadIdOrderByDisplayOrder(10L)).thenReturn(List.of(
                SmartBiPgFieldDefinition.builder().originalName("单位").standardName("product_unit").build()));

        var response = new DynamicAnalysisServiceImpl(rows, uploads, fields)
                .analyzeDynamic("F006", 10L, "auto");

        assertThat(response.getAnalysisFacts()).containsEntry("sourceRows", 2000L)
                .containsEntry("unitComplete", false);
    }

    @Test
    void lowPageTotalCanProveCompleteDespiteStaleHighUploadMetadata() {
        SmartBiPgExcelUpload upload = SmartBiPgExcelUpload.builder()
                .factoryId("F006").rowCount(5000).build();
        when(uploads.findById(11L)).thenReturn(Optional.of(upload));
        SmartBiDynamicData row = SmartBiDynamicData.builder()
                .rowData(Map.of("单位", "kg")).build();
        when(rows.findByFactoryIdAndUploadIdOrderByRowIndex(eq("F006"), eq(11L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 1000), 1));
        when(fields.findByUploadIdOrderByDisplayOrder(11L)).thenReturn(List.of(
                SmartBiPgFieldDefinition.builder().originalName("单位").standardName("product_unit").build()));

        var response = new DynamicAnalysisServiceImpl(rows, uploads, fields)
                .analyzeDynamic("F006", 11L, "auto");

        assertThat(response.getAnalysisFacts()).containsEntry("sourceRows", 1L)
                .containsEntry("unitComplete", true);
    }

    @Test
    void explicitWasteCurrencyRequiresCompleteSingleCurrencyProof() {
        Map<String, Object> cny = factsForCurrency(20L,
                List.of(SmartBiDynamicData.builder()
                        .rowData(Map.of("损耗金额(元)", 100)).build()),
                List.of(SmartBiPgFieldDefinition.builder()
                        .originalName("损耗金额(元)").standardName("waste_amount").build()));
        assertThat(cny).containsEntry("explicitWasteCurrency", "CNY");

        Map<String, Object> usd = factsForCurrency(21L,
                List.of(SmartBiDynamicData.builder()
                        .rowData(Map.of("waste_cost_usd", 100)).build()),
                List.of(SmartBiPgFieldDefinition.builder()
                        .originalName("waste_cost_usd").standardName("waste_cost").build()));
        assertThat(usd).containsEntry("explicitWasteCurrency", "USD");

        Map<String, Object> chineseUsd = factsForCurrency(24L,
                List.of(SmartBiDynamicData.builder()
                        .rowData(Map.of("损耗金额(美元)", 100)).build()),
                List.of(SmartBiPgFieldDefinition.builder()
                        .originalName("损耗金额(美元)").standardName("waste_amount").build()));
        assertThat(chineseUsd).containsEntry("explicitWasteCurrency", "USD");

        Map<String, Object> missing = factsForCurrency(22L,
                List.of(SmartBiDynamicData.builder()
                        .rowData(Map.of("损耗金额", 100)).build()),
                List.of(SmartBiPgFieldDefinition.builder()
                        .originalName("损耗金额").standardName("waste_amount").build()));
        assertThat(missing).doesNotContainKey("explicitWasteCurrency");

        Map<String, Object> euro = factsForCurrency(25L,
                List.of(SmartBiDynamicData.builder()
                        .rowData(Map.of("损耗金额(欧元)", 100)).build()),
                List.of(SmartBiPgFieldDefinition.builder()
                        .originalName("损耗金额(欧元)").standardName("waste_amount").build()));
        assertThat(euro).doesNotContainKey("explicitWasteCurrency");

        Map<String, Object> yen = factsForCurrency(26L,
                List.of(SmartBiDynamicData.builder()
                        .rowData(Map.of("损耗金额(日元)", 100)).build()),
                List.of(SmartBiPgFieldDefinition.builder()
                        .originalName("损耗金额(日元)").standardName("waste_amount").build()));
        assertThat(yen).doesNotContainKey("explicitWasteCurrency");

        Map<String, Object> hkd = factsForCurrency(27L,
                List.of(SmartBiDynamicData.builder()
                        .rowData(Map.of("损耗金额(港元)", 100)).build()),
                List.of(SmartBiPgFieldDefinition.builder()
                        .originalName("损耗金额(港元)").standardName("waste_amount").build()));
        assertThat(hkd).doesNotContainKey("explicitWasteCurrency");

        Map<String, Object> mixed = factsForCurrency(23L,
                List.of(
                        SmartBiDynamicData.builder()
                                .rowData(Map.of("损耗金额", 100, "币种", "CNY")).build(),
                        SmartBiDynamicData.builder()
                                .rowData(Map.of("损耗金额", 200, "币种", "USD")).build()),
                List.of(
                        SmartBiPgFieldDefinition.builder()
                                .originalName("损耗金额").standardName("waste_amount").build(),
                        SmartBiPgFieldDefinition.builder()
                                .originalName("币种").standardName("currency").build()));
        assertThat(mixed).doesNotContainKey("explicitWasteCurrency");
    }

    private Map<String, Object> factsForCurrency(
            Long uploadId, List<SmartBiDynamicData> data, List<SmartBiPgFieldDefinition> definitions) {
        when(uploads.findById(uploadId)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().factoryId("F006").build()));
        when(rows.findByFactoryIdAndUploadIdOrderByRowIndex(eq("F006"), eq(uploadId), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(data, PageRequest.of(0, 1000), data.size()));
        when(fields.findByUploadIdOrderByDisplayOrder(uploadId)).thenReturn(definitions);
        return new DynamicAnalysisServiceImpl(rows, uploads, fields)
                .analyzeDynamic("F006", uploadId, "auto").getAnalysisFacts();
    }

    @Test
    void crossFactoryAnalysisFailsBeforeReadingFieldDefinitionsOrRows() {
        when(uploads.findById(9L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().factoryId("F007").build()));

        assertThatThrownBy(() -> new DynamicAnalysisServiceImpl(rows, uploads, fields)
                .analyzeDynamic("F006", 9L, "auto"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前工厂");
        verifyNoInteractions(fields, rows);
    }

    @Test
    void internalAnalysisFactsAreIgnoredByDashboardJson() throws Exception {
        DynamicAnalysisService.DashboardResponse response = new DynamicAnalysisService.DashboardResponse();
        response.setAnalysisFacts(Map.of("explicitWasteAmounts", List.of(2500D)));

        assertThat(new ObjectMapper().writeValueAsString(response)).doesNotContain("analysisFacts", "2500");
    }
}
