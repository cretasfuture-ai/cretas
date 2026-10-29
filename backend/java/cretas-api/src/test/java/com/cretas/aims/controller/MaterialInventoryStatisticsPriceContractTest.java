package com.cretas.aims.controller;

import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.inventory.OpeningInventoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inventory statistics must keep operational quantities visible while masking only
 * the aggregate price field for callers without procurement:price:view.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MaterialBatchController inventory statistics price boundary")
class MaterialInventoryStatisticsPriceContractTest {

    private static final String URL = "/api/mobile/F006/material-batches/inventory/statistics";
    private static final String PRICE_PERMISSION = PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION;

    @Mock private MaterialBatchService materialBatchService;
    @Mock private MobileService mobileService;
    @Mock private UserRepository userRepository;
    @Mock private PermissionService permissionService;
    @Mock private OpeningInventoryService openingInventoryService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        PriceMaskResolver resolver = new PriceMaskResolver(
                mobileService, userRepository, permissionService);
        MaterialBatchController controller = new MaterialBatchController(
                materialBatchService, mobileService, resolver, openingInventoryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("denied caller receives no totalValue and immutable service input is unchanged")
    void deniedCallerMasksOnlyTotalValueWithoutMutatingInput() throws Exception {
        Map<String, Object> source = immutableStatistics();
        when(materialBatchService.getInventoryStatistics("F006")).thenReturn(source);
        stubCaller("masked-token", 80L, false);

        JsonNode data = perform("masked-token");

        assertFalse(data.has("totalValue"));
        assertCommonStatistics(data);
        assertBigDecimal("12.50", data.get("totalQuantity"));
        assertBigDecimal("34.00", data.get("totalWeight"));
        assertInventoryByType(data);
        assertTrue(source.containsKey("totalValue"));
        assertEquals(new BigDecimal("100.00"), source.get("totalValue"));
        verify(materialBatchService).getInventoryStatistics("F006");
    }

    @Test
    @DisplayName("authorized caller keeps totalValue, counts and quantities unchanged")
    void authorizedCallerKeepsAllStatisticsValues() throws Exception {
        Map<String, Object> source = immutableStatistics();
        when(materialBatchService.getInventoryStatistics("F006")).thenReturn(source);
        stubCaller("authorized-token", 81L, true);

        JsonNode data = perform("authorized-token");

        assertEquals(source.size(), data.size());
        assertCommonStatistics(data);
        assertBigDecimal("100.00", data.get("totalValue"));
        assertBigDecimal("12.50", data.get("totalQuantity"));
        assertBigDecimal("34.00", data.get("totalWeight"));
        assertInventoryByType(data);
        verify(permissionService).hasConfiguredPermission(any(User.class), eq("procurement:read"), any());
    }

    @Test
    @DisplayName("authorized caller keeps an explicit zero totalValue")
    void authorizedZeroTotalValueRemainsPresent() throws Exception {
        when(materialBatchService.getInventoryStatistics("F006"))
                .thenReturn(statistics(BigDecimal.ZERO));
        stubCaller("authorized-zero-token", 84L, true);

        JsonNode data = perform("authorized-zero-token");

        assertTrue(data.has("totalValue"));
        assertBigDecimal("0", data.get("totalValue"));
        assertCommonStatistics(data);
        assertInventoryByType(data);
    }

    @Test
    @DisplayName("authorized caller keeps a null totalValue field as JSON null")
    void authorizedNullTotalValueRemainsPresentAsJsonNull() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>(statistics(BigDecimal.ZERO));
        source.put("totalValue", null);
        when(materialBatchService.getInventoryStatistics("F006")).thenReturn(source);
        stubCaller("authorized-null-token", 85L, true);

        JsonNode data = perform("authorized-null-token");

        assertTrue(data.has("totalValue"));
        assertTrue(data.get("totalValue").isNull());
        assertCommonStatistics(data);
        assertBigDecimal("12.50", data.get("totalQuantity"));
        assertInventoryByType(data);
    }

    @Test
    @DisplayName("service exception propagates through MockMvc with the same root cause")
    void serviceErrorPropagatesWithoutControllerFallback() {
        IllegalStateException sentinel = new IllegalStateException("synthetic inventory statistics failure");
        when(materialBatchService.getInventoryStatistics("F006")).thenThrow(sentinel);

        ServletException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                ServletException.class,
                () -> mockMvc.perform(get(URL).header("Authorization", "Bearer service-error-token")));

        assertSame(sentinel, thrown.getCause());
    }

    @Test
    @DisplayName("missing Authorization header is closed by default")
    void missingAuthorizationMasksPrice() throws Exception {
        when(materialBatchService.getInventoryStatistics("F006")).thenReturn(immutableStatistics());

        JsonNode data = performWithoutAuthorization();

        assertFalse(data.has("totalValue"));
    }

    @Test
    @DisplayName("unresolvable caller is closed by default")
    void missingCallerMasksPrice() throws Exception {
        when(materialBatchService.getInventoryStatistics("F006")).thenReturn(immutableStatistics());
        UserDTO caller = org.mockito.Mockito.mock(UserDTO.class);
        when(mobileService.getUserFromToken("unknown-token")).thenReturn(caller);
        when(caller.getId()).thenReturn(82L);
        when(userRepository.findById(82L)).thenReturn(Optional.empty());

        JsonNode data = perform("unknown-token");

        assertFalse(data.has("totalValue"));
    }

    @Test
    @DisplayName("resolver errors are closed by default")
    void resolverErrorMasksPrice() throws Exception {
        when(materialBatchService.getInventoryStatistics("F006")).thenReturn(immutableStatistics());
        when(mobileService.getUserFromToken("error-token"))
                .thenThrow(new IllegalStateException("synthetic resolver failure"));

        JsonNode data = perform("error-token");

        assertFalse(data.has("totalValue"));
    }

    @Test
    @DisplayName("zero quantity remains zero and only totalValue is removed")
    void zeroQuantityRemainsVisibleWhenMasked() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("totalBatches", 0L);
        source.put("availableBatches", 0L);
        source.put("expiredBatches", 0L);
        source.put("totalQuantity", BigDecimal.ZERO);
        source.put("totalWeight", BigDecimal.ZERO);
        source.put("totalValue", BigDecimal.ZERO);
        source.put("inventoryByType", Map.of("RAW", BigDecimal.ZERO));
        source.put("lowStockCount", 0);
        source.put("expiringBatchesCount", 0);
        source.put("expiringCount", 0);
        when(materialBatchService.getInventoryStatistics("F006")).thenReturn(source);

        stubCaller("zero-token", 83L, false);
        JsonNode data = perform("zero-token");

        assertFalse(data.has("totalValue"));
        assertEquals(0, data.get("totalBatches").intValue());
        assertEquals(0, data.get("availableBatches").intValue());
        assertEquals(0, data.get("expiredBatches").intValue());
        assertBigDecimal("0", data.get("totalQuantity"));
        assertBigDecimal("0", data.get("totalWeight"));
        assertBigDecimal("0", data.get("inventoryByType").get("RAW"));
        assertTrue(source.containsKey("totalValue"));
    }

    private JsonNode perform(String token) throws Exception {
        String body = mockMvc.perform(get(URL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private JsonNode performWithoutAuthorization() throws Exception {
        String body = mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data");
    }

    private void stubCaller(String token, long userId, boolean canViewPrice) {
        UserDTO caller = org.mockito.Mockito.mock(UserDTO.class);
        when(caller.getId()).thenReturn(userId);
        when(mobileService.getUserFromToken(token)).thenReturn(caller);
        User user = user(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        // S5-12: 库存总价值按「采购读 或 财务读」(配置) 判, 不再按 procurement:price:view 名单
        when(permissionService.hasConfiguredPermission(
                org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.eq("procurement:read"),
                org.mockito.ArgumentMatchers.any())).thenReturn(canViewPrice);
    }

    private static Map<String, Object> immutableStatistics() {
        return statistics(new BigDecimal("100.00"));
    }

    private static Map<String, Object> statistics(BigDecimal totalValue) {
        return Map.of(
                "totalBatches", 2L,
                "availableBatches", 2L,
                "expiredBatches", 0L,
                "totalValue", totalValue,
                "inventoryByType", Map.of(
                        "RAW", new BigDecimal("12.50"),
                        "PACKAGING", new BigDecimal("0.00")),
                "totalQuantity", new BigDecimal("12.50"),
                "totalWeight", new BigDecimal("34.00"),
                "lowStockCount", 0,
                "expiringBatchesCount", 0,
                "expiringCount", 0);
    }

    private static void assertCommonStatistics(JsonNode data) {
        assertEquals(2, data.get("totalBatches").intValue());
        assertEquals(2, data.get("availableBatches").intValue());
        assertEquals(0, data.get("expiredBatches").intValue());
        assertEquals(0, data.get("lowStockCount").intValue());
        assertEquals(0, data.get("expiringBatchesCount").intValue());
        assertEquals(0, data.get("expiringCount").intValue());
    }

    private static void assertInventoryByType(JsonNode data) {
        assertBigDecimal("12.50", data.get("inventoryByType").get("RAW"));
        assertBigDecimal("0.00", data.get("inventoryByType").get("PACKAGING"));
    }

    private static User user(long id) {
        User user = new User();
        user.setId(id);
        user.setFactoryId("F006");
        return user;
    }

    private static void assertBigDecimal(String expected, JsonNode node) {
        assertEquals(0, new BigDecimal(expected).compareTo(node.decimalValue()));
    }
}
