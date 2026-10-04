package com.cretas.aims.controller;

import com.cretas.aims.dto.listsummary.ListSummaryRequest;
import com.cretas.aims.dto.listsummary.ListSummaryResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.listsummary.ListSummaryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("F006 list-summary inventory price isolation")
class ListSummaryPriceIsolationContractTest {

    private static final String FACTORY = "F006";
    private static final String PRICE_PERMISSION = PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION;
    private static final String FINANCE_PERMISSION = PriceFieldResponseAdvice.FINANCE_READ_PERMISSION;

    @Mock
    private ListSummaryService listSummaryService;
    @Mock
    private PermissionService permissionService;
    @Mock
    private UserRepository userRepository;
    @InjectMocks
    private PriceFieldResponseAdvice advice;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        ListSummaryController controller = new ListSummaryController(listSummaryService, permissionService, userRepository);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = MockMvcBuilders.standaloneSetup(controller, new PublicSummaryTestController())
                .setControllerAdvice(advice)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @AfterEach
    void clearPriceContext() {
        com.cretas.aims.security.PriceSensitiveContext.clear();
    }

    @Test
    @DisplayName("price denied masks flagged and currency stats, preserves quantities, and does not mutate source")
    void deniedPriceMasksOnlyPriceStatsAndPreservesSource() throws Exception {
        ListSummaryResponse source = fixture();
        when(listSummaryService.computeSummary(eq(FACTORY), eq("inventory"), any(ListSummaryRequest.class)))
                .thenReturn(source);
        User user = stubUser(1001L, false, true);

        JsonNode response = postSummary(user.getId());

        JsonNode stats = response.path("data").path("stats");
        assertEquals(4L, stats.get(0).path("value").asLong());
        assertDecimal("12.50", stats.get(1));
        assertJsonNull(stats.get(2), "value");
        assertJsonNull(stats.get(2), "trendDelta");
        assertJsonNull(stats.get(3), "value");
        assertJsonNull(stats.get(3), "trendDelta");
        assertJsonNull(stats.get(4), "value");
        assertEquals(0, stats.get(5).path("value").decimalValue().compareTo(new BigDecimal("77.00")));

        assertEquals(new BigDecimal("100.00"), source.getStats().get(2).getValue());
        assertEquals(new BigDecimal("-2.00"), source.getStats().get(2).getTrendDelta());
        assertEquals(0, ((BigDecimal) source.getStats().get(3).getValue()).compareTo(new BigDecimal("0.00")));
        assertEquals(new BigDecimal("88.00"), source.getStats().get(4).getValue());
        assertEquals(new BigDecimal("77.00"), source.getStats().get(5).getValue());

        stubUser(1004L, true, true);
        JsonNode reusedByAuthorizedCaller = postSummary(1004L).path("data").path("stats");
        assertDecimal("100.00", reusedByAuthorizedCaller.get(2));
        assertDecimal("88.00", reusedByAuthorizedCaller.get(4));
        verify(listSummaryService, times(2)).computeSummary(eq(FACTORY), eq("inventory"), any(ListSummaryRequest.class));
    }

    @Test
    @DisplayName("price allowed keeps flagged and currency stats even when finance permission is denied")
    void priceAllowedKeepsSummaryPricesWithFinanceDenied() throws Exception {
        ListSummaryResponse source = fixture();
        when(listSummaryService.computeSummary(eq(FACTORY), eq("inventory"), any(ListSummaryRequest.class)))
                .thenReturn(source);
        User user = stubUser(1002L, true, false);

        JsonNode stats = postSummary(user.getId()).path("data").path("stats");

        assertDecimal("100.00", stats.get(2));
        assertEquals(0, stats.get(2).path("trendDelta").decimalValue().compareTo(new BigDecimal("-2.00")));
        assertDecimal("0.00", stats.get(3));
        assertDecimal("88.00", stats.get(4));
        assertDecimal("77.00", stats.get(5));
        verify(listSummaryService).computeSummary(eq(FACTORY), eq("inventory"), any(ListSummaryRequest.class));
    }

    @Test
    @DisplayName("price permission failure fails closed and masks summary prices")
    void pricePermissionFailureMasksSummaryPrices() throws Exception {
        ListSummaryResponse source = fixture();
        when(listSummaryService.computeSummary(eq(FACTORY), eq("inventory"), any(ListSummaryRequest.class)))
                .thenReturn(source);
        User user = stubUser(1003L, false, true);
        when(permissionService.hasPermission(eq(user), eq(PRICE_PERMISSION)))
                .thenThrow(new IllegalStateException("permission service unavailable"));

        JsonNode stats = postSummary(user.getId()).path("data").path("stats");

        assertJsonNull(stats.get(2), "value");
        assertJsonNull(stats.get(3), "value");
        assertJsonNull(stats.get(4), "value");
        assertEquals(new BigDecimal("100.00"), source.getStats().get(2).getValue());
        verify(listSummaryService).computeSummary(eq(FACTORY), eq("inventory"), any(ListSummaryRequest.class));
    }

    @Test
    @DisplayName("authenticated userId with missing user fails closed without returning sensitive body")
    void unresolvedUserOptionalEmptyFailsClosed() throws Exception {
        ListSummaryResponse source = fixture();
        when(listSummaryService.computeSummary(eq(FACTORY), eq("inventory"), any(ListSummaryRequest.class)))
                .thenReturn(source);
        User controllerUser = stubUser(1005L, false, true);
        when(userRepository.findById(1005L)).thenReturn(Optional.of(controllerUser), Optional.empty());

        JsonNode response = postSummaryWithUnresolvedUser(1005L);

        assertFailClosed(response);
        verify(listSummaryService).computeSummary(eq(FACTORY), eq("inventory"), any(ListSummaryRequest.class));
        assertEquals(new BigDecimal("100.00"), source.getStats().get(2).getValue());
        assertEquals(new BigDecimal("88.00"), source.getStats().get(4).getValue());
    }

    @Test
    @DisplayName("authenticated userId repository failure fails closed without returning sensitive body")
    void unresolvedUserRepositoryExceptionFailsClosed() throws Exception {
        ListSummaryResponse source = fixture();
        when(listSummaryService.computeSummary(eq(FACTORY), eq("inventory"), any(ListSummaryRequest.class)))
                .thenReturn(source);
        User controllerUser = stubUser(1006L, false, true);
        when(userRepository.findById(1006L))
                .thenReturn(Optional.of(controllerUser))
                .thenThrow(new IllegalStateException("user database unavailable"));

        JsonNode response = postSummaryWithUnresolvedUser(1006L);

        assertFailClosed(response);
        verify(listSummaryService).computeSummary(eq(FACTORY), eq("inventory"), any(ListSummaryRequest.class));
        assertEquals(new BigDecimal("100.00"), source.getStats().get(2).getValue());
        assertEquals(new BigDecimal("88.00"), source.getStats().get(4).getValue());
    }

    @Test
    @DisplayName("public request without userId remains pass-through")
    void publicRequestWithoutUserIdRemainsUnchanged() throws Exception {
        JsonNode response = postSummaryWithoutUserId();

        assertTrue(response.path("success").asBoolean());
        assertDecimal("100.00", response.path("data").path("stats").get(2));
        verifyNoInteractions(userRepository, permissionService);
    }

    private JsonNode postSummary(Long userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/mobile/{factoryId}/list-summary/{entityType}", FACTORY, "inventory")
                        .requestAttr("userId", userId)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postSummaryWithoutUserId() throws Exception {
        MvcResult result = mockMvc.perform(post("/test-public-summary")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postSummaryWithUnresolvedUser(Long userId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/mobile/{factoryId}/list-summary/{entityType}", FACTORY, "inventory")
                        .requestAttr("userId", userId)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertFailClosed(JsonNode response) {
        assertEquals(200, response.path("code").asInt());
        assertTrue(response.path("success").asBoolean());
        assertEquals("inventory", response.path("data").path("entityType").asText());
        JsonNode stats = response.path("data").path("stats");
        assertEquals(4L, stats.get(0).path("value").asLong());
        assertDecimal("12.50", stats.get(1));
        assertJsonNull(stats.get(2), "value");
        assertJsonNull(stats.get(4), "value");
        assertDecimal("77.00", stats.get(5));
        assertFalse(response.toString().contains("100.00"));
        assertFalse(response.toString().contains("88.00"));
    }

    private User stubUser(Long userId, boolean canViewPrice, boolean canViewFinance) {
        User user = new User();
        user.setId(userId);
        user.setFactoryId(FACTORY);
        lenient().when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        lenient().when(permissionService.hasAnyPermission(eq(user), any(String[].class))).thenReturn(true);
        lenient().when(permissionService.hasPermission(eq(user), eq(PRICE_PERMISSION))).thenReturn(canViewPrice);
        lenient().when(permissionService.hasPermission(eq(user), eq(FINANCE_PERMISSION))).thenReturn(canViewFinance);
        return user;
    }

    private ListSummaryResponse fixture() {
        return ListSummaryResponse.builder()
                .entityType("inventory")
                .stats(List.of(
                        stat("共", 4L, "number", "批", false, null, null),
                        stat("可用数量", new BigDecimal("12.50"), "number", "", null, null, null),
                        stat("总价值", new BigDecimal("100.00"), "currency", "¥", true, "down", new BigDecimal("-2.00")),
                        stat("零价值", BigDecimal.ZERO, "currency", "¥", true, "flat", BigDecimal.ZERO),
                        stat("历史价值", new BigDecimal("88.00"), "currency", "¥", null, null, null),
                        stat("普通数值", new BigDecimal("77.00"), "number", "", null, null, null)))
                .build();
    }

    private ListSummaryResponse.SummaryStat stat(String label, Object value, String format, String unit,
                                                  Boolean canViewPrice, String trend, Number trendDelta) {
        return ListSummaryResponse.SummaryStat.builder()
                .label(label)
                .value(value)
                .format(format)
                .unit(unit)
                .canViewPrice(canViewPrice)
                .trend(trend)
                .trendDelta(trendDelta)
                .build();
    }

    private void assertDecimal(String expected, JsonNode stat) {
        assertEquals(0, stat.path("value").decimalValue().compareTo(new BigDecimal(expected)));
    }

    private void assertJsonNull(JsonNode stat, String field) {
        assertTrue(stat.has(field) && stat.get(field).isNull(), field + " must be JSON null");
    }

    @RestController
    private class PublicSummaryTestController {
        @PostMapping("/test-public-summary")
        ApiResponse<ListSummaryResponse> summary() {
            return ApiResponse.success("查询成功", fixture());
        }
    }
}
