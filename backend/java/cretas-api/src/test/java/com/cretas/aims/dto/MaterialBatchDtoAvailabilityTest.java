package com.cretas.aims.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MaterialBatchDtoAvailabilityTest {

    private static MaterialBatchDTO dto(String current, String unsettled, MaterialBatchStatus status) {
        MaterialBatchDTO d = new MaterialBatchDTO();
        d.setCurrentQuantity(current == null ? null : new BigDecimal(current));
        d.setUnsettledConsumption(unsettled == null ? null : new BigDecimal(unsettled));
        d.setStatus(status);
        return d;
    }

    @Test
    void availableIsCurrentMinusUnsettled() {
        assertThat(dto("100", "30", MaterialBatchStatus.AVAILABLE).getAvailableQuantity())
                .isEqualByComparingTo("70");
    }

    @Test
    void unsettledOverCurrentFloorsAtZero() {
        assertThat(dto("10", "30", MaterialBatchStatus.AVAILABLE).getAvailableQuantity())
                .isEqualByComparingTo("0");
    }

    @Test
    void unknownUnsettledMeansAvailableEqualsCurrent() {
        assertThat(dto("55", null, MaterialBatchStatus.AVAILABLE).getAvailableQuantity())
                .isEqualByComparingTo("55");
    }

    @Test
    void missingCurrentQuantityIsNullNotZero() {
        assertThat(dto(null, "5", MaterialBatchStatus.AVAILABLE).getAvailableQuantity()).isNull();
    }

    @Test
    void qualityStatusDerivedFromStatus() {
        assertThat(dto("1", null, MaterialBatchStatus.INSPECTING).getQualityStatus()).isEqualTo("PENDING");
        assertThat(dto("1", null, MaterialBatchStatus.DEFECTIVE).getQualityStatus()).isEqualTo("REJECTED");
        assertThat(dto("1", null, MaterialBatchStatus.SCRAPPED).getQualityStatus()).isEqualTo("REJECTED");
        assertThat(dto("1", null, MaterialBatchStatus.EXPIRED).getQualityStatus()).isEqualTo("EXPIRED");
        assertThat(dto("1", null, MaterialBatchStatus.AVAILABLE).getQualityStatus()).isEqualTo("NORMAL");
        assertThat(dto("1", null, null).getQualityStatus()).isNull();
    }

    @Test
    void jsonExposesAvailableQualityAndRemaining() throws Exception {
        JsonNode n = new ObjectMapper().registerModule(new JavaTimeModule()).findAndRegisterModules()
                .valueToTree(dto("100", "30", MaterialBatchStatus.INSPECTING));
        assertThat(n.get("availableQuantity").decimalValue()).isEqualByComparingTo("70");
        assertThat(n.get("qualityStatus").asText()).isEqualTo("PENDING");
        assertThat(n.get("remainingQuantity").decimalValue()).isEqualByComparingTo("100");
    }
}
