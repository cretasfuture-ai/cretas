package com.cretas.aims.controller;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.FactoryService;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.utils.FactoryAccessValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class WarehouseFinishedGoodsPriceContractTest {
    @Mock private FactoryAccessValidator factoryAccessValidator;
    @Mock private FactoryService factoryService;
    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepository;
    @Mock private FactoryWarehouseRepository factoryWarehouseRepository;
    @Mock private MaterialBatchMapper materialBatchMapper;
    @Mock private MaterialBatchService materialBatchService;
    @Mock private UserRepository userRepository;
    @Mock private PermissionService permissionService;
    @InjectMocks private PriceFieldResponseAdvice advice;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        WarehouseInventoryController controller = new WarehouseInventoryController(
                factoryAccessValidator, factoryService, materialBatchRepository,
                finishedGoodsBatchRepository, factoryWarehouseRepository,
                materialBatchMapper, materialBatchService);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        new ObjectMapper().registerModule(new JavaTimeModule()))).build();
    }

    @ParameterizedTest
    @CsvSource({"false,12.34,8.76", "true,12.34,8.76", "true,0,0"})
    void warehouseResponseFiltersOnlyCopyAndPreservesInventory(
            boolean canViewPrice, String price, String cost) throws Exception {
        User actor = new User();
        actor.setId(10L);
        when(userRepository.findById(10L)).thenReturn(Optional.of(actor));
        when(permissionService.hasPermission(actor, PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION))
                .thenReturn(canViewPrice);
        when(permissionService.hasPermission(actor, PriceFieldResponseAdvice.FINANCE_READ_PERMISSION))
                .thenReturn(canViewPrice);
        FactoryWarehouse warehouse = new FactoryWarehouse();
        warehouse.setId("W-1");
        warehouse.setFactoryId("F006");
        warehouse.setCode("WH-LOG");
        warehouse.setName("Logistics");
        when(factoryWarehouseRepository.findByIdAndFactoryIdAndDeletedAtIsNull("W-1", "F006"))
                .thenReturn(Optional.of(warehouse));
        when(materialBatchRepository.findByFactoryIdAndWarehouseId("F006", "W-1"))
                .thenReturn(List.of());
        FinishedGoodsBatch source = spy(new FinishedGoodsBatch());
        source.setId("FG-1");
        source.setFactoryId("F006");
        source.setBatchNumber("FG-2026-001");
        source.setWarehouseId("W-1");
        source.setStorageLocation("Shelf-A");
        source.setProducedQuantity(new BigDecimal("37"));
        source.setShippedQuantity(new BigDecimal("2"));
        source.setReservedQuantity(new BigDecimal("4"));
        source.setUnitPrice(new BigDecimal(price));
        source.setUnitCost(new BigDecimal(cost));
        Factory factory = mock(Factory.class);
        ProductType product = mock(ProductType.class);
        source.setFactory(factory);
        source.setProductType(product);
        when(finishedGoodsBatchRepository.findByFactoryIdAndWarehouseId("F006", "W-1"))
                .thenReturn(List.of(source));
        var result = mvc.perform(get("/api/mobile/F006/inventory/by-warehouse")
                        .param("warehouseId", "W-1").requestAttr("userId", 10L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.factoryId").value("F006"))
                .andExpect(jsonPath("$.data.productCount").value(1))
                .andExpect(jsonPath("$.data.products[0].batchNumber").value("FG-2026-001"))
                .andExpect(jsonPath("$.data.products[0].storageLocation").value("Shelf-A"))
                .andExpect(jsonPath("$.data.products[0].availableQuantity").value(31));
        if (canViewPrice) {
            result.andExpect(jsonPath("$.data.products[0].unitPrice").value(Double.parseDouble(price)))
                    .andExpect(jsonPath("$.data.products[0].unitCost").value(Double.parseDouble(cost)));
        } else {
            result.andExpect(jsonPath("$.data.products[0].unitPrice").value(nullValue()))
                    .andExpect(jsonPath("$.data.products[0].unitCost").value(nullValue()));
        }
        assertEquals(new BigDecimal(price), source.getUnitPrice());
        assertEquals(new BigDecimal(cost), source.getUnitCost());
        verify(source, never()).getFactory();
        verify(source, never()).getProductType();
        assertSame(factory, source.getFactory());
        assertSame(product, source.getProductType());
        verifyNoInteractions(factory, product);
    }
}
