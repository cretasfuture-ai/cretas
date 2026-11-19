package com.cretas.aims.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.cretas.aims.config.EnumCoercionJacksonConfig;
import com.cretas.aims.config.PriceSensitiveJacksonConfig;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 可用量与库存汇总同口径(仅 AVAILABLE 且自有批次有值), 质量状态由批次状态推导。
 *
 * <p>#1844 终审 L-5: JSON 断言用<b>生产注入的</b> ObjectMapper(Boot Jackson 自动配置 +
 * spring.jackson.* + 本仓 Jackson 配置类), ⛔ 不 new。
 */
@SpringBootTest(classes = {JacksonAutoConfiguration.class, EnumCoercionJacksonConfig.class,
        PriceSensitiveJacksonConfig.class}, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MaterialBatchDtoAvailabilityContractTest {

    @Autowired ObjectMapper objectMapper;

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
    void unqueriedUnsettledMeansNullNotBookQuantity() {
        // 没查预扣的端点: 不退成账面数冒充可用
        assertThat(dto("55", null, MaterialBatchStatus.AVAILABLE).getAvailableQuantity()).isNull();
    }

    @Test
    void missingCurrentQuantityIsNullNotZero() {
        assertThat(dto(null, "5", MaterialBatchStatus.AVAILABLE).getAvailableQuantity()).isNull();
    }

    @Test
    void nonAvailableStatusIsZeroWithReason() {
        for (MaterialBatchStatus s : new MaterialBatchStatus[] {MaterialBatchStatus.INSPECTING,
                MaterialBatchStatus.DEFECTIVE, MaterialBatchStatus.EXPIRED, MaterialBatchStatus.SCRAPPED}) {
            MaterialBatchDTO d = dto("100", "0", s);
            assertThat(d.getAvailableQuantity()).as(s.name()).isEqualByComparingTo("0");
            assertThat(d.getAvailableQuantityReason()).as(s.name()).isEqualTo("STATUS_NOT_AVAILABLE");
        }
    }

    @Test
    void customerOwnedIsZeroWithReasonAndCompanyOwnedIsNot() {
        MaterialBatchDTO customer = dto("100", "0", MaterialBatchStatus.AVAILABLE);
        customer.setOwnership(InventoryOwnership.CUSTOMER_OWNED);
        assertThat(customer.getAvailableQuantity()).isEqualByComparingTo("0");
        assertThat(customer.getAvailableQuantityReason()).isEqualTo("CUSTOMER_OWNED");

        MaterialBatchDTO own = dto("100", "0", MaterialBatchStatus.AVAILABLE);
        own.setOwnership(InventoryOwnership.COMPANY_OWNED);
        assertThat(own.getAvailableQuantity()).isEqualByComparingTo("100");
        assertThat(own.getAvailableQuantityReason()).isNull();
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
    void injectedMapperIsTheProductionOne() throws Exception {
        // 仪器自检: 注入的 mapper 带本仓 EnumCoercionJacksonConfig 定制(空串枚举 → null);
        // new ObjectMapper() 在这里会抛 InvalidFormatException。
        assertThat(objectMapper.readValue("\"\"", MaterialBatchStatus.class)).isNull();
    }

    @Test
    void jsonExposesAvailableReasonQualityAndRemaining() throws Exception {
        JsonNode n = objectMapper.valueToTree(dto("100", "30", MaterialBatchStatus.AVAILABLE));
        assertThat(n.get("availableQuantity").decimalValue()).isEqualByComparingTo("70");
        assertThat(n.get("qualityStatus").asText()).isEqualTo("NORMAL");
        assertThat(n.get("remainingQuantity").decimalValue()).isEqualByComparingTo("100");
        assertThat(n.get("availableQuantityReason").isNull()).isTrue();
    }
}
