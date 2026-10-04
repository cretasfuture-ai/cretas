package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.Customer;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.inventory.SalesDeliveryItem;
import com.cretas.aims.entity.inventory.SalesDeliveryRecord;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.BusinessLinkRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Sales delivery price isolation across all response controllers")
class SalesDeliveryPriceIsolationContractTest {

    @Mock private SalesService salesService;
    @Mock private MobileService mobileService;
    @Mock private PermissionService permissionService;
    @Mock private UserRepository userRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private BusinessLinkRepository businessLinkRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private GrossMarginRedlineService grossMarginRedlineService;
    @Mock private EstimatePriceCheckService estimatePriceCheckService;
    @Mock private SalesPriceAdjustmentService priceAdjustmentService;
    @Mock private ProductPackagingSpecService productPackagingSpecService;

    @InjectMocks private PriceFieldResponseAdvice advice;

    private MockMvc salesMvc;
    private MockMvc warehouseMvc;
    private MockMvc shipmentMvc;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        SalesController salesController = new SalesController(
                salesService, mobileService, permissionService, userRepository,
                salesOrderRepository, businessLinkRepository, productTypeRepository,
                finishedGoodsBatchRepository, grossMarginRedlineService,
                estimatePriceCheckService, priceAdjustmentService, productPackagingSpecService);
        salesMvc = MockMvcBuilders.standaloneSetup(salesController)
                .setControllerAdvice(advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        warehouseMvc = MockMvcBuilders.standaloneSetup(
                        new com.cretas.aims.controller.warehouse.WarehouseDeliveryController(
                                salesService, mobileService))
                .setControllerAdvice(advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        shipmentMvc = MockMvcBuilders.standaloneSetup(
                        new com.cretas.aims.controller.sales.SalesDeliveryShipmentController(
                                salesService, mobileService))
                .setControllerAdvice(advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        lenient().when(mobileService.getUserFromToken("token"))
                .thenReturn(UserDTO.builder().id(99L).build());
    }

    @Test
    @DisplayName("viewer: all 12 delivery record responses hide prices and preserve source graph")
    void viewerAllDeliveryRoutesHidePricesWithoutMutatingSource() throws Exception {
        User viewer = asUser(10L, false);
        SalesDeliveryRecord source = fixture(new BigDecimal("50.00"), new BigDecimal("125.00"));
        stubAllRoutes(source);

        List<ResultActions> actions = allRoutes(10L);
        for (ResultActions action : actions) {
            expectMasked(action);
        }
        assertPageContracts(actions);

        org.junit.jupiter.api.Assertions.assertSame(viewer, userRepository.findById(10L).orElseThrow());
        assertSourceUnchanged(source, new BigDecimal("50.00"), new BigDecimal("125.00"));
    }

    @Test
    @DisplayName("price-view role: all 12 routes preserve non-zero prices")
    void allowedAllDeliveryRoutesKeepNonZeroPrices() throws Exception {
        asUser(11L, true);
        SalesDeliveryRecord source = fixture(new BigDecimal("50.00"), new BigDecimal("125.00"));
        stubAllRoutes(source);

        List<ResultActions> actions = allRoutes(11L);
        for (ResultActions action : actions) {
            expectPrice(action, new BigDecimal("50.00"), new BigDecimal("125.00"));
        }
        assertPageContracts(actions);
        assertSourceUnchanged(source, new BigDecimal("50.00"), new BigDecimal("125.00"));
    }

    @Test
    @DisplayName("price-view role: all 12 routes preserve explicit zero prices")
    void allowedAllDeliveryRoutesKeepZeroPrices() throws Exception {
        asUser(12L, true);
        SalesDeliveryRecord source = fixture(BigDecimal.ZERO, BigDecimal.ZERO);
        stubAllRoutes(source);

        List<ResultActions> actions = allRoutes(12L);
        for (ResultActions action : actions) {
            expectPrice(action, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        assertPageContracts(actions);
        assertSourceUnchanged(source, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("null and empty delivery responses remain null and empty after copying")
    void nullAndEmptyResponsesPreserveSemantics() throws Exception {
        asUser(13L, true);
        lenient().when(salesService.getDeliveryRecordById("F006", "DLV-NULL")).thenReturn(null);
        lenient().when(salesService.getDeliveryRecordsByOrder("F006", "SO-EMPTY"))
                .thenReturn(List.of());
        lenient().when(salesService.getDeliveryRecords("F006", 1, 20))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0L));

        salesMvc.perform(get("/api/mobile/F006/sales/deliveries/DLV-NULL").requestAttr("userId", 13L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", nullValue()));
        salesMvc.perform(get("/api/mobile/F006/sales/deliveries/by-order/SO-EMPTY")
                        .requestAttr("userId", 13L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", empty()));
        salesMvc.perform(get("/api/mobile/F006/sales/deliveries")
                        .requestAttr("userId", 13L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", empty()))
                .andExpect(jsonPath("$.data.totalElements", equalTo(0)))
                .andExpect(jsonPath("$.data.totalPages", equalTo(0)));
    }

    private User asUser(Long id, boolean canViewPrices) {
        User user = new User();
        user.setId(id);
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(permissionService.hasPermission(eq(user), eq(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)))
                .thenReturn(canViewPrices);
        when(permissionService.hasPermission(eq(user), eq(PriceFieldResponseAdvice.FINANCE_READ_PERMISSION)))
                .thenReturn(canViewPrices);
        return user;
    }

    private SalesDeliveryRecord fixture(BigDecimal unitPrice, BigDecimal totalAmount) {
        SalesDeliveryRecord record = new SalesDeliveryRecord();
        record.setId("DLV-1");
        record.setFactoryId("F006");
        record.setDeliveryNumber("D-2026-001");
        record.setCustomerId("C-1");
        record.setDeliveryDate(LocalDate.of(2026, 10, 4));
        record.setShippedBy(99L);
        record.setTotalAmount(totalAmount);
        record.setDeliveryAddress("warehouse address");
        record.setSignedByName("签收人");
        record.setItems(new ArrayList<>());

        SalesDeliveryItem item = new SalesDeliveryItem();
        item.setId(1L);
        item.setDeliveryRecordId("DLV-1");
        item.setProductTypeId("PT-1");
        item.setProductName("SKU-1");
        item.setDeliveredQuantity(new BigDecimal("20.0000"));
        item.setUnit("kg");
        item.setUnitPrice(unitPrice);
        item.setSourceWarehouseCode("WH-LOG");
        item.setDeliveryRecord(record);
        ProductType productType = new ProductType();
        Factory factory = new Factory();
        Customer customer = new Customer();
        SalesOrder salesOrder = new SalesOrder();
        User shippedBy = new User();
        item.setProductType(productType);
        item.setFinishedGoodsBatch(new FinishedGoodsBatch());
        record.setFactory(factory);
        record.setSalesOrder(salesOrder);
        record.setCustomer(customer);
        record.setShippedByUser(shippedBy);
        record.setItems(List.of(item));

        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("unitPrice", unitPrice);
        nested.put("label", "nested");
        record.setAiInvocationMetadata(new LinkedHashMap<>(Map.of(
                "source", "contract",
                "pricing", nested,
                "rows", List.of(new LinkedHashMap<>(Map.of("unitPrice", unitPrice))))));
        return record;
    }

    private void stubAllRoutes(SalesDeliveryRecord record) {
        PageResponse<SalesDeliveryRecord> page = PageResponse.of(List.of(record), 1, 20, 1L);
        lenient().when(salesService.createDeliveryRecord(eq("F006"), any(), eq(99L))).thenReturn(record);
        lenient().when(salesService.getDeliveryRecords("F006", 1, 20)).thenReturn(page);
        lenient().when(salesService.getDeliveryRecordById("F006", "DLV-1")).thenReturn(record);
        lenient().when(salesService.confirmDelivered("F006", "DLV-1")).thenReturn(record);
        lenient().when(salesService.uploadDeliverySignature(eq("F006"), eq("DLV-1"), any(), any(), any()))
                .thenReturn(record);
        lenient().when(salesService.getDeliveryRecordsByOrder("F006", "SO-1")).thenReturn(List.of(record));
        lenient().when(salesService.getPendingWarehouseDeliveries("F006", 1, 20)).thenReturn(page);
        lenient().when(salesService.getWarehouseDeliveryHistory("F006", 1, 20)).thenReturn(page);
        lenient().when(salesService.warehouseConfirmDelivery(eq("F006"), eq("DLV-1"), any(), eq(99L)))
                .thenReturn(record);
        lenient().when(salesService.getDeliveryShipments("F006", "DLV-1")).thenReturn(List.of(record));
        lenient().when(salesService.createDeliveryShipment(eq("F006"), eq("DLV-1"), any(), eq(99L)))
                .thenReturn(record);
        lenient().when(salesService.cancelDeliveryShipment("F006", "DLV-1", "SHP-1", 99L))
                .thenReturn(record);
    }

    private List<ResultActions> allRoutes(Long userId) throws Exception {
        String attr = "userId";
        return List.of(
                salesMvc.perform(post("/api/mobile/F006/sales/deliveries")
                        .requestAttr(attr, userId).header("Authorization", "Bearer token")
                        .contentType("application/json").content("{\"customerId\":\"C-1\",\"deliveryDate\":\"2026-10-04\",\"items\":[{\"productTypeId\":\"PT-1\",\"deliveredQuantity\":20,\"unit\":\"kg\"}]}")),
                salesMvc.perform(get("/api/mobile/F006/sales/deliveries").requestAttr(attr, userId)),
                salesMvc.perform(get("/api/mobile/F006/sales/deliveries/DLV-1").requestAttr(attr, userId)),
                salesMvc.perform(post("/api/mobile/F006/sales/deliveries/DLV-1/delivered").requestAttr(attr, userId)),
                salesMvc.perform(post("/api/mobile/F006/sales/deliveries/DLV-1/signature")
                        .requestAttr(attr, userId).contentType("application/json")
                        .content("{\"photoUrls\":[],\"signedByName\":\"签收人\",\"remark\":\"ok\"}")),
                salesMvc.perform(get("/api/mobile/F006/sales/deliveries/by-order/SO-1").requestAttr(attr, userId)),
                warehouseMvc.perform(get("/api/mobile/F006/warehouse/deliveries/pending").requestAttr(attr, userId)),
                warehouseMvc.perform(get("/api/mobile/F006/warehouse/deliveries/history").requestAttr(attr, userId)),
                warehouseMvc.perform(post("/api/mobile/F006/warehouse/deliveries/DLV-1/confirm")
                        .requestAttr(attr, userId).header("Authorization", "Bearer token")
                        .contentType("application/json").content("{}")),
                shipmentMvc.perform(get("/api/mobile/F006/sales/deliveries/DLV-1/shipments").requestAttr(attr, userId)),
                shipmentMvc.perform(post("/api/mobile/F006/sales/deliveries/DLV-1/shipments")
                        .requestAttr(attr, userId).header("Authorization", "Bearer token")
                        .contentType("application/json").content("{\"plannedShipmentDate\":\"2026-10-04\",\"deliveryMode\":\"FULL\",\"items\":[{\"parentDeliveryItemId\":1,\"quantity\":20}]}")),
                shipmentMvc.perform(post("/api/mobile/F006/sales/deliveries/DLV-1/shipments/SHP-1/cancel")
                        .requestAttr(attr, userId).header("Authorization", "Bearer token"))
        );
    }

    private ResultActions expectMasked(ResultActions action) throws Exception {
        return assertDeliveryFields(action.andExpect(status().isOk()))
                .andExpect(jsonPath("$..items[0].unitPrice", contains(nullValue())))
                .andExpect(jsonPath("$..unitPrice", everyItem(nullValue())))
                .andExpect(jsonPath("$..totalAmount", contains(nullValue())));
    }

    private ResultActions expectPrice(ResultActions action, BigDecimal unitPrice, BigDecimal totalAmount)
            throws Exception {
        return assertDeliveryFields(action.andExpect(status().isOk()))
                .andExpect(jsonPath("$..items[0].unitPrice", contains(unitMatcher(unitPrice))))
                .andExpect(jsonPath("$..unitPrice", everyItem(unitMatcher(unitPrice))))
                .andExpect(jsonPath("$..totalAmount", contains(unitMatcher(totalAmount))));
    }

    private ResultActions assertDeliveryFields(ResultActions action) throws Exception {
        return action
                .andExpect(jsonPath("$..items[0].productName", contains("SKU-1")))
                .andExpect(jsonPath("$..items[0].deliveredQuantity", contains(comparesEqualTo(20.0))))
                .andExpect(jsonPath("$..items[0].unit", contains("kg")))
                .andExpect(jsonPath("$..items[0].sourceWarehouseCode", contains("WH-LOG")))
                .andExpect(jsonPath("$..signedByName", contains("签收人")))
                .andExpect(jsonPath("$..deliveryAddress", contains("warehouse address")))
                .andExpect(jsonPath("$..aiInvocationMetadata.source", contains("contract")))
                .andExpect(jsonPath("$..aiInvocationMetadata.pricing.label", contains("nested")));
    }

    private void assertPageContracts(List<ResultActions> actions) throws Exception {
        for (int index : List.of(1, 6, 7)) {
            actions.get(index)
                    .andExpect(jsonPath("$.data.page", equalTo(1)))
                    .andExpect(jsonPath("$.data.currentPage", equalTo(1)))
                    .andExpect(jsonPath("$.data.size", equalTo(20)))
                    .andExpect(jsonPath("$.data.totalElements", equalTo(1)))
                    .andExpect(jsonPath("$.data.totalPages", equalTo(1)))
                    .andExpect(jsonPath("$.data.first", equalTo(true)))
                    .andExpect(jsonPath("$.data.last", equalTo(true)));
        }
    }

    @SuppressWarnings("unchecked")
    private org.hamcrest.Matcher<Object> unitMatcher(BigDecimal value) {
        if (value.signum() == 0) {
            return (org.hamcrest.Matcher<Object>) (org.hamcrest.Matcher<?>) equalTo(0);
        }
        return (org.hamcrest.Matcher<Object>) (org.hamcrest.Matcher<?>) comparesEqualTo(value.doubleValue());
    }

    private void assertSourceUnchanged(SalesDeliveryRecord source, BigDecimal unitPrice, BigDecimal totalAmount) {
        org.junit.jupiter.api.Assertions.assertEquals(totalAmount, source.getTotalAmount());
        org.junit.jupiter.api.Assertions.assertEquals(unitPrice, source.getItems().get(0).getUnitPrice());
        org.junit.jupiter.api.Assertions.assertSame(source, source.getItems().get(0).getDeliveryRecord());
        org.junit.jupiter.api.Assertions.assertNotNull(source.getFactory());
        org.junit.jupiter.api.Assertions.assertNotNull(source.getSalesOrder());
        org.junit.jupiter.api.Assertions.assertNotNull(source.getCustomer());
        org.junit.jupiter.api.Assertions.assertNotNull(source.getShippedByUser());
        Map<String, Object> pricing = (Map<String, Object>) source.getAiInvocationMetadata().get("pricing");
        org.junit.jupiter.api.Assertions.assertEquals(unitPrice, pricing.get("unitPrice"));
        List<?> rows = (List<?>) source.getAiInvocationMetadata().get("rows");
        org.junit.jupiter.api.Assertions.assertEquals(unitPrice, ((Map<?, ?>) rows.get(0)).get("unitPrice"));
    }
}
