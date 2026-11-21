package com.cretas.aims.service.inventory.impl;

import com.cretas.aims.service.inventory.TradeJpaQueryTestSupport;

import com.cretas.aims.controller.inventory.PurchaseController;
import com.cretas.aims.dto.inventory.CreatePurchaseOrderRequest;
import com.cretas.aims.dto.inventory.UpdatePurchaseOrderRequest;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.SupplierMaterial;
import com.cretas.aims.entity.enums.PurchaseOrderStatus;
import com.cretas.aims.entity.inventory.PriceList;
import com.cretas.aims.entity.inventory.PriceListItem;
import com.cretas.aims.entity.inventory.PurchaseOrder;
import com.cretas.aims.entity.inventory.PurchaseOrderItem;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.SupplierMaterialRepository;
import com.cretas.aims.repository.SupplierRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderItemRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.inventory.TradeJpaFixtures;
import com.cretas.aims.service.inventory.TradeCaliberSettings;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 采购草稿正常保存与数据库回读，以服务端价目为准，兼容 notes 别名。 */
@DataJpaTest(showSql = false, properties = {"spring.flyway.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class PurchaseOrderPriceTraceRepositoryQueryValidationTest extends TradeJpaQueryTestSupport {

    private static final String FACTORY = "IT-PO-TRACE";
    private static final String OTHER = "IT-PO-OTHER";

    @Autowired private PurchaseOrderItemRepository items;
    @Autowired private PurchaseOrderRepository orders;
    @Autowired private SupplierRepository suppliers;
    @Autowired private SupplierMaterialRepository supplierMaterials;
    @Autowired private RawMaterialTypeRepository materials;
    @Autowired private FactorySettingsRepository settings;
    @Autowired private UserRepository users;
    @PersistenceContext private EntityManager em;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private PurchaseServiceImpl service;
    private Long userId;

    @BeforeEach
    void setUp() {
        userId = TradeJpaFixtures.createFactoryUser(em, FACTORY);
        TradeJpaFixtures.createSupplier(em, FACTORY, userId, "S1", "供货商");
        TradeJpaFixtures.createMaterial(em, FACTORY, "M1");
        TradeJpaFixtures.createMaterial(em, FACTORY, "M2");
        TradeJpaFixtures.createFactoryUser(em, OTHER);
        relation("M1");
        relation("M2");
        priceList(FACTORY, "PL1", "kg", "10", LocalDate.of(2026, 11, 1), null, true, "M1");
        priceList(FACTORY, "OLD", "kg", "8", LocalDate.of(2026, 10, 1), null, true, "M1");
        priceList(FACTORY, "FUTURE", "kg", "4", LocalDate.of(2026, 11, 20), null, true, "M1");
        priceList(FACTORY, "DISABLED", "kg", "3", LocalDate.of(2026, 11, 17), null, false, "M1");
        priceList(FACTORY, "EXPIRED", "kg", "2", LocalDate.of(2026, 11, 17),
                LocalDate.of(2026, 11, 17), true, "M1");
        priceList(FACTORY, "OTHER-UNIT", "箱", "1", LocalDate.of(2026, 11, 17), null, true, "M1");
        priceList(OTHER, "OTHER-FACTORY", "kg", "1", LocalDate.of(2026, 11, 17), null, true, "M2");
        service = new PurchaseServiceImpl(orders, items, null, suppliers, materials, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "supplierMaterialRepository", supplierMaterials);
        ReflectionTestUtils.setField(service, "tradeCaliberSettings", new TradeCaliberSettings(settings));
    }

    private void relation(String materialId) {
        SupplierMaterial relation = new SupplierMaterial();
        relation.setFactoryId(FACTORY);
        relation.setSupplierId("S1");
        relation.setMaterialTypeId(materialId);
        relation.setPurchaseUnit("kg");
        em.persist(relation);
    }

    private void priceList(String factoryId, String id, String unit, String price, LocalDate from, LocalDate to,
                           boolean active, String materialId) {
        var header = new PriceList();
        header.setId(id);
        header.setFactoryId(factoryId);
        header.setName(id + "采购价");
        header.setPriceType("PURCHASE_PRICE");
        header.setEffectiveFrom(from);
        header.setEffectiveTo(to);
        header.setIsActive(active);
        em.persist(header);
        var line = new PriceListItem();
        line.setPriceListId(id);
        line.setMaterialTypeId(materialId);
        line.setUnit(unit);
        line.setStandardPrice(new BigDecimal(price));
        em.persist(line);
    }

    @ParameterizedTest
    @CsvSource({"M1, 10.0000, 价目表, 0", "M1, 11, 手改, 10", "M2, 11, 手填,"})
    void createDraftReplacesForgedPriceTrace(String material, String price, String source, String deviation)
            throws Exception {
        var req = mapper.readValue(createJson(lineJson(material, price)), CreatePurchaseOrderRequest.class);
        assertThat(req.getRemark()).isEqualTo("草稿备注");
        assertThat(req.getItems().getFirst().getPriceSource()).isEqualTo("伪造来源");

        var created = service.createPurchaseOrder(FACTORY, req, userId);
        assertThat(created.getPurchasePriceSourceMode()).isEqualTo("SERVER_DETERMINED");
        String orderId = created.getId();
        assertTrace(reloadDraftLine(orderId, "草稿备注"), source, deviation);
    }

    @ParameterizedTest
    @CsvSource({"M1, 10, 价目表, 0", "M1, 9, 手改, -10", "M2, 11, 手填,"})
    void updateDraftReplacesForgedPriceTrace(String material, String price, String source, String deviation)
            throws Exception {
        var create = mapper.readValue(createJson(lineJson("M1", "12")), CreatePurchaseOrderRequest.class);
        create.getItems().getFirst().setPriceSource("手改");
        String orderId = service.createPurchaseOrder(FACTORY, create, userId).getId();
        assertTrace(reloadDraftLine(orderId, "草稿备注"), "手改", "20");

        var update = mapper.readValue("{\"remark\":\"更新草稿备注\",\"items\":[" + lineJson(material, price) + "]}",
                UpdatePurchaseOrderRequest.class);
        assertThat(service.updateDraftOrder(FACTORY, orderId, update).getPurchasePriceSourceMode())
                .isEqualTo("SERVER_DETERMINED");
        assertTrace(reloadDraftLine(orderId, "更新草稿备注"), source, deviation);
    }

    @Test
    void factoryPolicySwitchPreservesOnlyTheClientLabelInCreateAndUpdate() throws Exception {
        var mobile = mock(MobileService.class);
        var tokenUser = new UserDTO();
        tokenUser.setId(userId);
        when(mobile.getUserFromToken("trade-test")).thenReturn(tokenUser);
        var permissions = mock(PermissionService.class);
        when(permissions.hasPermission(any(), anyString())).thenReturn(true);
        when(permissions.hasConfiguredPermission(any(), anyString(), anySet())).thenReturn(true);
        var advice = new PriceFieldResponseAdvice();
        ReflectionTestUtils.setField(advice, "permissionService", permissions);
        ReflectionTestUtils.setField(advice, "userRepository", users);
        var mvc = MockMvcBuilders.standaloneSetup(new PurchaseController(service, null, mobile, permissions, users, null, null))
                .setControllerAdvice(advice, new GlobalExceptionHandler()).build();

        String line = lineJson("M1", "11");
        String path = "/api/mobile/" + FACTORY + "/purchase/orders";
        String body = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(createJson(line))
                        .header("Authorization", "Bearer trade-test").requestAttr("userId", userId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.purchasePriceSourceMode").value("SERVER_DETERMINED"))
                .andReturn().getResponse().getContentAsString();
        String orderId = mapper.readTree(body).required("data").required("id").asText();
        String update = "{\"remark\":\"更新草稿备注\",\"items\":[" + line + "]}";
        mvc.perform(put(path + "/" + orderId).contentType(MediaType.APPLICATION_JSON).content(update)
                        .requestAttr("userId", userId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.purchasePriceSourceMode").value("SERVER_DETERMINED"));
        assertTrace(reloadDraftLine(orderId, "更新草稿备注"), "手改", "10");

        TradeJpaFixtures.createProductionSettings(em, FACTORY, "{\"purchasePriceSourceMode\":\"CLIENT_PRESERVED\"}");
        mvc.perform(put(path + "/" + orderId).contentType(MediaType.APPLICATION_JSON).content(update)
                        .requestAttr("userId", userId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.purchasePriceSourceMode").value("CLIENT_PRESERVED"));
        assertTrace(reloadDraftLine(orderId, "更新草稿备注"), "伪造来源", "10");

        var forNewOrder = mapper.readValue(createJson(line.replace("\"quantity\":10", "\"quantity\":20")),
                CreatePurchaseOrderRequest.class);
        var created = service.createPurchaseOrder(FACTORY, forNewOrder, userId);
        assertThat(created.getPurchasePriceSourceMode()).isEqualTo("CLIENT_PRESERVED");
        assertTrace(reloadDraftLine(created.getId(), "草稿备注"), "伪造来源", "10");
    }

    @Test
    void orderDateOnlyUpdateRefreshesTheReferenceAndSupportsLargeDeviation() throws Exception {
        priceList(FACTORY, "FINE", "kg", "0.0001", LocalDate.of(2026, 11, 22), null, true, "M1");
        var create = mapper.readValue(createJson(lineJson("M1", "10")), CreatePurchaseOrderRequest.class);
        String orderId = service.createPurchaseOrder(FACTORY, create, userId).getId();
        assertTrace(reloadDraftLine(orderId, "草稿备注"), "价目表", "0");

        var update = mapper.readValue("{\"orderDate\":\"2026-11-22\"}", UpdatePurchaseOrderRequest.class);
        service.updateDraftOrder(FACTORY, orderId, update);
        var back = reloadDraftLine(orderId, "草稿备注");
        assertThat(back.getPriceSource()).isEqualTo("手改");
        assertThat(back.getReferencePrice()).isEqualByComparingTo("0.0001");
        assertThat(back.getReferencePriceListId()).isEqualTo("FINE");
        assertThat(back.getPriceDeviationPct()).isEqualByComparingTo("9999900");
    }

    @Test
    void missingActualPriceAndZeroReferenceDoNotFabricateDeviation() throws Exception {
        var create = mapper.readValue(createJson(lineJson("M1", "null")), CreatePurchaseOrderRequest.class);
        var first = reloadDraftLine(service.createPurchaseOrder(FACTORY, create, userId).getId(), "草稿备注");
        assertThat(first.getUnitPrice()).isNull();
        assertThat(first.getPriceSource()).isEqualTo("手填");
        assertThat(first.getReferencePrice()).isEqualByComparingTo("10");
        assertThat(first.getPriceDeviationPct()).isNull();

        priceList(FACTORY, "FREE", "kg", "0", LocalDate.of(2026, 11, 17), null, true, "M1");
        var update = mapper.readValue("{\"items\":[" + lineJson("M1", "10") + "]}", UpdatePurchaseOrderRequest.class);
        service.updateDraftOrder(FACTORY, first.getPurchaseOrderId(), update);
        var back = reloadDraftLine(first.getPurchaseOrderId(), "草稿备注");
        assertThat(back.getPriceSource()).isEqualTo("手改");
        assertThat(back.getReferencePrice()).isEqualByComparingTo("0");
        assertThat(back.getPriceDeviationPct()).isNull();
    }

    private static String createJson(String lineJson) {
        return "{\"supplierId\":\"S1\",\"orderDate\":\"2026-11-18\",\"notes\":\"草稿备注\",\"items\":[" + lineJson + "]}";
    }

    private static String lineJson(String material, String price) {
        return """
                {"materialTypeId":"%s","materialName":"盐","quantity":10,"unit":"kg","unitPrice":%s,"taxRate":13,
                 "priceSource":"伪造来源","referencePrice":-999,"referencePriceListId":"FORGED",
                 "referencePriceListName":"伪造价目表","priceDeviationPct":888,"notes":"行备注"}
                """.formatted(material, price);
    }

    private static void assertTrace(PurchaseOrderItem back, String source, String deviation) {
        assertThat(back.getPriceSource()).isEqualTo(source);
        assertThat(back.getRemark()).isEqualTo("行备注");
        if (deviation == null) {
            assertThat(back.getReferencePrice()).isNull();
            assertThat(back.getReferencePriceListId()).isNull();
            assertThat(back.getReferencePriceListName()).isNull();
            assertThat(back.getPriceDeviationPct()).isNull();
        } else {
            assertThat(back.getReferencePrice()).isEqualByComparingTo("10");
            assertThat(back.getReferencePriceListId()).isEqualTo("PL1");
            assertThat(back.getReferencePriceListName()).isEqualTo("PL1采购价");
            assertThat(back.getPriceDeviationPct()).isEqualByComparingTo(deviation);
        }
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

}
