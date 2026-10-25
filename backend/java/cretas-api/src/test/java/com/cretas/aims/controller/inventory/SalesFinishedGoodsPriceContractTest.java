package com.cretas.aims.controller.inventory;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.entity.product.ProductPackagingSpec;
import com.cretas.aims.repository.BusinessLinkRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.product.ProductPackagingSpecRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.SalesPriceAdjustmentService;
import com.cretas.aims.service.inventory.SalesService;
import com.cretas.aims.service.pricing.EstimatePriceCheckService;
import com.cretas.aims.service.pricing.GrossMarginRedlineService;
import com.cretas.aims.service.product.ProductPackagingSpecService;
import com.cretas.aims.service.product.impl.ProductPackagingSpecServiceImpl;
import com.cretas.aims.service.unit.TestUnitContractFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Sales finished-goods price isolation")
class SalesFinishedGoodsPriceContractTest {

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
    @Mock private ProductPackagingSpecRepository productPackagingSpecRepository;
    @Mock private Factory factory;
    @Mock private ProductType productType;

    @InjectMocks private PriceFieldResponseAdvice advice;

    private FinishedGoodsBatch source;
    private SalesController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new SalesController(
                salesService,
                mobileService,
                permissionService,
                userRepository,
                salesOrderRepository,
                businessLinkRepository,
                productTypeRepository,
                finishedGoodsBatchRepository,
                grossMarginRedlineService,
                estimatePriceCheckService,
                priceAdjustmentService,
                productPackagingSpecService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().registerModule(new JavaTimeModule())))
                .build();

        source = org.mockito.Mockito.spy(sampleBatch());
        source.setFactory(factory);
        source.setProductType(productType);
    }

    @Test
    @DisplayName("viewer sees null prices on both finished-goods read routes without mutating service source")
    void viewerPricesAreNullAndSourceIsUnchanged() throws Exception {
        User viewer = new User();
        viewer.setId(10L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(viewer));
        when(permissionService.hasPermission(eq(viewer), eq(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)))
                .thenReturn(false);
        when(permissionService.hasPermission(eq(viewer), eq(PriceFieldResponseAdvice.FINANCE_READ_PERMISSION)))
                .thenReturn(false);

        when(salesService.getFinishedGoodsBatches("F006", 1, 20))
                .thenReturn(PageResponse.of(List.of(source), 1, 20, 1L));
        when(salesService.getAvailableBatches("F006", "PT-1", null))
                .thenReturn(List.of(source));
        when(salesService.getFinishedGoodsBatchById("F006", "FGB-1"))
                .thenReturn(source);

        mockMvc.perform(get("/api/mobile/F006/sales/finished-goods").requestAttr("userId", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].unitPrice").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].unitCost").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].producedQuantity").value(37.0))
                .andExpect(jsonPath("$.data.content[0].storageLocation").value("WH-LOG"));

        mockMvc.perform(get("/api/mobile/F006/sales/finished-goods/available")
                        .param("productTypeId", "PT-1")
                        .requestAttr("userId", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].unitPrice").value(nullValue()))
                .andExpect(jsonPath("$.data[0].unitCost").value(nullValue()))
                .andExpect(jsonPath("$.data[0].availableQuantity").value(31.0));

        mockMvc.perform(get("/api/mobile/F006/sales/finished-goods/FGB-1")
                        .requestAttr("userId", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unitPrice").value(nullValue()))
                .andExpect(jsonPath("$.data.unitCost").value(nullValue()))
                .andExpect(jsonPath("$.data.producedQuantity").value(37.0));

        verify(source, never()).getFactory();
        verify(source, never()).getProductType();
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("12.34"), source.getUnitPrice());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("8.76"), source.getUnitCost());
        org.junit.jupiter.api.Assertions.assertSame(factory, source.getFactory());
        org.junit.jupiter.api.Assertions.assertSame(productType, source.getProductType());
    }

    @Test
    @DisplayName("price-view role keeps non-zero prices on both routes")
    void priceViewRoleKeepsNonZeroPrices() throws Exception {
        User allowed = new User();
        allowed.setId(11L);
        when(userRepository.findById(11L)).thenReturn(Optional.of(allowed));
        when(permissionService.hasPermission(eq(allowed), eq(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)))
                .thenReturn(true);
        when(permissionService.hasPermission(eq(allowed), eq(PriceFieldResponseAdvice.FINANCE_READ_PERMISSION)))
                .thenReturn(true);

        FinishedGoodsBatch priced = sampleBatch();
        when(salesService.getFinishedGoodsBatches("F006", 1, 20))
                .thenReturn(PageResponse.of(List.of(priced), 1, 20, 1L));
        when(salesService.getAvailableBatches("F006", "PT-1", "WH-LOG"))
                .thenReturn(List.of(priced));
        when(salesService.getFinishedGoodsBatchById("F006", "FGB-1"))
                .thenReturn(priced);

        mockMvc.perform(get("/api/mobile/F006/sales/finished-goods")
                        .requestAttr("userId", 11L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].unitPrice").value(12.34))
                .andExpect(jsonPath("$.data.content[0].unitCost").value(8.76));

        mockMvc.perform(get("/api/mobile/F006/sales/finished-goods/available")
                        .param("productTypeId", "PT-1")
                        .param("sourceWarehouseCode", "WH-LOG")
                        .requestAttr("userId", 11L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].unitPrice").value(12.34))
                .andExpect(jsonPath("$.data[0].unitCost").value(8.76));

        mockMvc.perform(get("/api/mobile/F006/sales/finished-goods/FGB-1")
                        .requestAttr("userId", 11L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unitPrice").value(12.34))
                .andExpect(jsonPath("$.data.unitCost").value(8.76));
    }

    @Test
    @DisplayName("price-view role keeps zero prices on both routes")
    void priceViewRoleKeepsZeroPrices() throws Exception {
        User allowed = new User();
        allowed.setId(12L);
        when(userRepository.findById(12L)).thenReturn(Optional.of(allowed));
        when(permissionService.hasPermission(eq(allowed), eq(PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)))
                .thenReturn(true);
        when(permissionService.hasPermission(eq(allowed), eq(PriceFieldResponseAdvice.FINANCE_READ_PERMISSION)))
                .thenReturn(true);

        FinishedGoodsBatch zero = sampleBatch();
        zero.setUnitPrice(BigDecimal.ZERO);
        zero.setUnitCost(BigDecimal.ZERO);
        when(salesService.getFinishedGoodsBatches("F006", 1, 20))
                .thenReturn(PageResponse.of(List.of(zero), 1, 20, 1L));
        when(salesService.getAvailableBatches("F006", "PT-1", null))
                .thenReturn(List.of(zero));
        when(salesService.getFinishedGoodsBatchById("F006", "FGB-1"))
                .thenReturn(zero);

        mockMvc.perform(get("/api/mobile/F006/sales/finished-goods")
                        .requestAttr("userId", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].unitPrice").value(0))
                .andExpect(jsonPath("$.data.content[0].unitCost").value(0));

        mockMvc.perform(get("/api/mobile/F006/sales/finished-goods/available")
                        .param("productTypeId", "PT-1")
                        .requestAttr("userId", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].unitPrice").value(0))
                .andExpect(jsonPath("$.data[0].unitCost").value(0));

        mockMvc.perform(get("/api/mobile/F006/sales/finished-goods/FGB-1")
                        .requestAttr("userId", 12L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unitPrice").value(0))
                .andExpect(jsonPath("$.data.unitCost").value(0));
    }

    @Test
    @DisplayName("all write responses also use detached copies")
    void writeRoutesDoNotExposeOrMutateServiceEntity() {
        when(mobileService.getUserFromToken("token"))
                .thenReturn(UserDTO.builder().id(99L).build());
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber("F006", "OPEN-1"))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findByIdAndFactoryId("PT-1", "F006"))
                .thenReturn(Optional.of(productType));
        when(productPackagingSpecService.resolveOpeningSelection(any(), any(), any(), any()))
                .thenReturn(ProductPackagingSpecService.PackagingSelection.none());
        when(salesService.createOpeningFinishedGoodsBatch(eq("F006"), any(FinishedGoodsBatch.class), eq(99L)))
                .thenReturn(source);
        when(salesService.editFinishedGoodsBatch(eq("F006"), eq("FGB-1"), any(), eq(99L)))
                .thenReturn(source);
        when(salesService.adjustFinishedGoodsQuantity(eq("F006"), eq("FGB-1"), any(), eq(99L)))
                .thenReturn(source);

        ApiResponse<FinishedGoodsBatch> opening = controller.createOpeningFinishedGoods(
                "F006",
                "Bearer token",
                new SalesController.OpeningFinishedGoodsRequest(
                        "PT-1", "OPEN-1", new BigDecimal("37"), "kg",
                        null, java.time.LocalDate.of(2026, 10, 4), null,
                        null, null, null));
        verify(productPackagingSpecService).resolveOpeningSelection("F006", "PT-1", "kg", null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 99L);
        ApiResponse<FinishedGoodsBatch> edit = controller.editFinishedGoods(
                "F006", "FGB-1", "Bearer token",
                new com.cretas.aims.dto.inventory.EditFinishedGoodsRequest(
                        "edited", "WH-LOG", null, new BigDecimal("12.34")), request);
        ApiResponse<FinishedGoodsBatch> adjust = controller.adjustFinishedGoods(
                "F006", "FGB-1", "Bearer token",
                new com.cretas.aims.dto.inventory.AdjustFinishedGoodsRequest(
                        new BigDecimal("1"), "test", 
                        com.cretas.aims.dto.inventory.AdjustFinishedGoodsRequest.ReferenceType.OTHER), request);

        for (ApiResponse<FinishedGoodsBatch> response : List.of(opening, edit, adjust)) {
            org.junit.jupiter.api.Assertions.assertNotSame(source, response.getData());
            org.junit.jupiter.api.Assertions.assertNull(response.getData().getFactory());
            org.junit.jupiter.api.Assertions.assertNull(response.getData().getProductType());
            org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("12.34"), response.getData().getUnitPrice());
            org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("8.76"), response.getData().getUnitCost());
        }
        org.junit.jupiter.api.Assertions.assertSame(factory, source.getFactory());
        org.junit.jupiter.api.Assertions.assertSame(productType, source.getProductType());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("12.34"), source.getUnitPrice());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("8.76"), source.getUnitCost());
    }

    @Test
    @DisplayName("opening controller resolves base-unit packaging and preserves quantity plus snapshot")
    void openingUsesRealResolverForBaseUnitSnapshot() {
        ProductPackagingSpec spec = new ProductPackagingSpec();
        spec.setId("SPEC-8");
        spec.setFactoryId("F006");
        spec.setProductTypeId("PT-1");
        spec.setName("8盒/箱");
        spec.setPackageUnit("箱");
        spec.setBaseUnit("盒");
        spec.setConversionFactor(new BigDecimal("8"));
        spec.setActive(true);
        spec.setSortOrder(0);
        when(productPackagingSpecRepository.findByFactoryIdAndProductTypeIdAndActiveTrueOrderBySortOrderAscCreatedAtAsc(
                "F006", "PT-1")).thenReturn(List.of(spec));
        when(productPackagingSpecRepository.findByIdAndFactoryIdAndProductTypeIdAndActiveTrue(
                "SPEC-8", "F006", "PT-1")).thenReturn(Optional.of(spec));
        when(mobileService.getUserFromToken("token"))
                .thenReturn(UserDTO.builder().id(99L).build());
        when(finishedGoodsBatchRepository.findByFactoryIdAndBatchNumber("F006", "OPEN-16"))
                .thenReturn(Optional.empty());
        when(productTypeRepository.findByIdAndFactoryId("PT-1", "F006"))
                .thenReturn(Optional.of(productType));
        when(salesService.createOpeningFinishedGoodsBatch(eq("F006"), any(FinishedGoodsBatch.class), eq(99L)))
                .thenAnswer(invocation -> invocation.getArgument(1));

        ProductPackagingSpecService realResolver = new ProductPackagingSpecServiceImpl(
                productPackagingSpecRepository,
                productTypeRepository,
                TestUnitContractFactory.contract());
        SalesController realResolverController = new SalesController(
                salesService, mobileService, permissionService, userRepository, salesOrderRepository,
                businessLinkRepository, productTypeRepository, finishedGoodsBatchRepository,
                grossMarginRedlineService, estimatePriceCheckService, priceAdjustmentService, realResolver);

        realResolverController.createOpeningFinishedGoods(
                "F006", "Bearer token",
                new SalesController.OpeningFinishedGoodsRequest(
                        "PT-1", "OPEN-16", new BigDecimal("16"), "盒", "SPEC-8",
                        java.time.LocalDate.of(2026, 10, 24), null, null, null, null));

        org.mockito.ArgumentCaptor<FinishedGoodsBatch> captured =
                org.mockito.ArgumentCaptor.forClass(FinishedGoodsBatch.class);
        verify(salesService).createOpeningFinishedGoodsBatch(eq("F006"), captured.capture(), eq(99L));
        FinishedGoodsBatch batch = captured.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("16"), batch.getProducedQuantity());
        org.junit.jupiter.api.Assertions.assertEquals("盒", batch.getUnit());
        org.junit.jupiter.api.Assertions.assertEquals("SPEC-8", batch.getPackagingSpecId());
        org.junit.jupiter.api.Assertions.assertEquals("8盒/箱", batch.getPackagingSpecName());
        org.junit.jupiter.api.Assertions.assertEquals("箱", batch.getPackagingUnit());
        org.junit.jupiter.api.Assertions.assertEquals("盒", batch.getPackagingBaseUnit());
        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("8"), batch.getPackagingFactor());
    }

    private FinishedGoodsBatch sampleBatch() {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("FGB-1");
        batch.setFactoryId("F006");
        batch.setBatchNumber("FG-2026-001");
        batch.setProductTypeId("PT-1");
        batch.setProductName("finished");
        batch.setProducedQuantity(new BigDecimal("37.0000"));
        batch.setShippedQuantity(new BigDecimal("2.0000"));
        batch.setReservedQuantity(new BigDecimal("4.0000"));
        batch.setUnit("kg");
        batch.setUnitPrice(new BigDecimal("12.34"));
        batch.setUnitCost(new BigDecimal("8.76"));
        batch.setStorageLocation("WH-LOG");
        return batch;
    }
}
