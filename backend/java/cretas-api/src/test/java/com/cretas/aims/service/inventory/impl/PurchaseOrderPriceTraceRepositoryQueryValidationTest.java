package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 采购草稿单价来源/参考价留痕 (G3): 请求字段 → 明细落库 → 读回, 以及 notes 别名. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class PurchaseOrderPriceTraceRepositoryQueryValidationTest {

    @Autowired private PurchaseOrderItemRepository items;
    @Autowired private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void priceTraceRoundTripsThroughRequestEntityAndDatabase() throws Exception {
        String json = "{\"supplierId\":\"S1\",\"orderDate\":\"2026-11-18\",\"notes\":\"草稿备注\",\"items\":[{"
                + "\"materialTypeId\":\"M1\",\"quantity\":10,\"unitPrice\":11,\"priceSource\":\"手改\","
                + "\"referencePrice\":10,\"referencePriceListId\":\"PL1\",\"referencePriceListName\":\"年度采购价\","
                + "\"priceDeviationPct\":10.0,\"notes\":\"行备注\"}]}";
        CreatePurchaseOrderRequest req = mapper.readValue(json, CreatePurchaseOrderRequest.class);
        assertThat(req.getRemark()).isEqualTo("草稿备注");
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO dto = req.getItems().get(0);
        assertThat(dto.getRemark()).isEqualTo("行备注");

        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setPurchaseOrderId("PO-TRACE");
        item.setMaterialTypeId("M1");
        item.setMaterialName("盐");
        item.setQuantity(dto.getQuantity());
        item.setUnit("kg");
        item.setPriceUnit("kg");
        PurchaseServiceImpl.applyPriceTrace(item, dto);
        Long id = items.saveAndFlush(item).getId();
        items.flush();

        PurchaseOrderItem back = items.findById(id).orElseThrow();
        assertThat(back.getPriceSource()).isEqualTo("手改");
        assertThat(back.getReferencePrice()).isEqualByComparingTo("10");
        assertThat(back.getReferencePriceListId()).isEqualTo("PL1");
        assertThat(back.getReferencePriceListName()).isEqualTo("年度采购价");
        assertThat(back.getPriceDeviationPct()).isEqualByComparingTo("10");
    }

    @Test
    void absentTraceStaysNullAndNegativeReferenceRejected() {
        PurchaseOrderItem item = new PurchaseOrderItem();
        PurchaseServiceImpl.applyPriceTrace(item, new CreatePurchaseOrderRequest.PurchaseOrderItemDTO());
        assertThat(item.getPriceSource()).isNull();
        assertThat(item.getReferencePrice()).isNull();
        assertThat(item.getPriceDeviationPct()).isNull();

        CreatePurchaseOrderRequest.PurchaseOrderItemDTO bad = new CreatePurchaseOrderRequest.PurchaseOrderItemDTO();
        bad.setReferencePrice(new BigDecimal("-1"));
        assertThatThrownBy(() -> PurchaseServiceImpl.applyPriceTrace(item, bad)).isInstanceOf(BusinessException.class);
    }
}
