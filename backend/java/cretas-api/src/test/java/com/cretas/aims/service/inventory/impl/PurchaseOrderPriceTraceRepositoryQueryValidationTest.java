package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.service.inventory.TradeJpaQueryTestSupport;

import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.UpdatePurchaseOrderRequest;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.TradeJpaFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 采购草稿单价来源/参考价留痕 (G3): 请求字段 → 明细落库 → 读回, 以及 notes 别名. */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class PurchaseOrderPriceTraceRepositoryQueryValidationTest extends TradeJpaQueryTestSupport {

    private static final String FACTORY = "IT-PO-TRACE";

    @Autowired private PurchaseOrderItemRepository items;
    @Autowired private PurchaseOrderRepository orders;
    @Autowired private SupplierRepository suppliers;
    @Autowired private SupplierMaterialRepository supplierMaterials;
    @Autowired private RawMaterialTypeRepository materials;
    @PersistenceContext private EntityManager em;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private PurchaseServiceImpl service;
    private Long userId;

    @BeforeEach
    void setUp() {
        userId = TradeJpaFixtures.createFactoryUser(em, FACTORY);
        TradeJpaFixtures.createSupplier(em, FACTORY, userId, "S1", "供货商");
        TradeJpaFixtures.createMaterial(em, FACTORY, "M1");
        SupplierMaterial relation = new SupplierMaterial();
        relation.setFactoryId(FACTORY);
        relation.setSupplierId("S1");
        relation.setMaterialTypeId("M1");
        relation.setPurchaseUnit("kg");
        em.persist(relation);
        service = new PurchaseServiceImpl(orders, items, null, suppliers, materials, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "supplierMaterialRepository", supplierMaterials);
    }

    @Test
    void createDraftPersistsAllPriceTraceFieldsAndNotes() throws Exception {
        String json = "{\"supplierId\":\"S1\",\"orderDate\":\"2026-11-18\",\"notes\":\"草稿备注\",\"items\":[{"
                + "\"materialTypeId\":\"M1\",\"quantity\":10,\"unit\":\"kg\",\"unitPrice\":11,\"taxRate\":13,\"priceSource\":\"手改\","
                + "\"referencePrice\":10,\"referencePriceListId\":\"PL1\",\"referencePriceListName\":\"年度采购价\","
                + "\"priceDeviationPct\":10.0,\"notes\":\"行备注\"}]}";
        CreatePurchaseOrderRequest req = mapper.readValue(json, CreatePurchaseOrderRequest.class);
        assertThat(req.getRemark()).isEqualTo("草稿备注");
        CreatePurchaseOrderRequest.PurchaseOrderItemDTO dto = req.getItems().get(0);
        assertThat(dto.getRemark()).isEqualTo("行备注");

        String orderId = service.createPurchaseOrder(FACTORY, req, userId).getId();
        PurchaseOrderItem back = reloadDraftLine(orderId, "草稿备注");
        assertThat(back.getPriceSource()).isEqualTo("手改");
        assertThat(back.getReferencePrice()).isEqualByComparingTo("10");
        assertThat(back.getReferencePriceListId()).isEqualTo("PL1");
        assertThat(back.getReferencePriceListName()).isEqualTo("年度采购价");
        assertThat(back.getPriceDeviationPct()).isEqualByComparingTo("10");
        assertThat(back.getRemark()).isEqualTo("行备注");
    }

    @Test
    void updateDraftPersistsReplacementPriceTraceAndNotes() throws Exception {
        var create = mapper.readValue("""
                {"supplierId":"S1","orderDate":"2026-11-18","items":[
                  {"materialTypeId":"M1","materialName":"盐","quantity":10,"unit":"kg","unitPrice":11,"taxRate":13}
                ]}
                """, CreatePurchaseOrderRequest.class);
        String orderId = service.createPurchaseOrder(FACTORY, create, userId).getId();
        assertThat(reloadDraftLine(orderId, null).getPriceSource()).isNull();

        var update = mapper.readValue("""
                {"remark":"更新草稿备注","items":[
                  {"materialTypeId":"M1","materialName":"盐","quantity":12,"unit":"kg","unitPrice":12,"taxRate":13,
                   "priceSource":"价目表","referencePrice":15,"referencePriceListId":"PL2",
                   "referencePriceListName":"新价目表","priceDeviationPct":-20,"notes":"更新行备注"}
                ]}
                """, UpdatePurchaseOrderRequest.class);
        service.updateDraftOrder(FACTORY, orderId, update);

        PurchaseOrderItem back = reloadDraftLine(orderId, "更新草稿备注");
        assertThat(back.getPriceSource()).isEqualTo("价目表");
        assertThat(back.getReferencePrice()).isEqualByComparingTo("15");
        assertThat(back.getReferencePriceListId()).isEqualTo("PL2");
        assertThat(back.getReferencePriceListName()).isEqualTo("新价目表");
        assertThat(back.getPriceDeviationPct()).isEqualByComparingTo("-20");
        assertThat(back.getRemark()).isEqualTo("更新行备注");
    }

    private PurchaseOrderItem reloadDraftLine(String orderId, String remark) {
        em.flush();
        em.clear();
        PurchaseOrder order = orders.findByIdAndFactoryId(orderId, FACTORY).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(order.getRemark()).isEqualTo(remark);
        List<PurchaseOrderItem> lines = items.findByPurchaseOrderId(orderId);
        assertThat(lines).hasSize(1);
        return lines.get(0);
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
