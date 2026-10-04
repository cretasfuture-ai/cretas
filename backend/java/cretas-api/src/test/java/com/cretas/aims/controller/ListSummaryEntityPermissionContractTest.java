package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.listsummary.ListSummaryRequest;
import com.cretas.aims.dto.listsummary.ListSummaryResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.listsummary.ListSummaryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("F006 list-summary entity permission contract")
class ListSummaryEntityPermissionContractTest {

    private static final String FACTORY = "F006";

    @Mock
    private ListSummaryService listSummaryService;
    @Mock
    private PermissionService permissionService;
    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        lenient().when(userRepository.findById(7001L)).thenReturn(Optional.of(user(7001L)));
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ListSummaryController(listSummaryService, permissionService, userRepository))
                .addInterceptors(new PermissionInterceptor(permissionService, userRepository, objectMapper))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @ParameterizedTest(name = "{0} accepts its backing read permission")
    @MethodSource("entityReadPermissions")
    void eachSupportedEntityUsesItsOwnReadPermission(String entityType, String permission) throws Exception {
        User user = user(7001L);
        allowOnly(user, permission);
        when(listSummaryService.computeSummary(eq(FACTORY), eq(entityType), any(ListSummaryRequest.class)))
                .thenReturn(summary(entityType));

        JsonNode response = postSummary(entityType, user.getId());

        assertEquals(200, response.path("code").asInt());
        assertEquals(entityType, response.path("data").path("entityType").asText());
        verify(listSummaryService).computeSummary(eq(FACTORY), eq(entityType), any(ListSummaryRequest.class));
    }

    @Test
    @DisplayName("quality_manager cannot use quality permission to read inventory summary")
    void qualityPermissionCannotReadInventory() throws Exception {
        User user = user(7001L);
        allowOnly(user, "quality:read");

        JsonNode response = postSummaryExpectingStatus("inventory", user.getId(), 403);

        assertFalse(response.path("success").asBoolean());
        verify(listSummaryService, never()).computeSummary(any(), any(), any());
    }

    @Test
    @DisplayName("hr_admin cannot use hr permission to read inventory summary")
    void hrPermissionCannotReadInventory() throws Exception {
        User user = user(7001L);
        allowOnly(user, "hr:read");

        JsonNode response = postSummaryExpectingStatus("inventory", user.getId(), 403);

        assertFalse(response.path("success").asBoolean());
        verify(listSummaryService, never()).computeSummary(any(), any(), any());
    }

    @Test
    @DisplayName("unknown entity type fails closed without business execution")
    void unknownEntityTypeFailsClosed() throws Exception {
        User user = user(7001L);

        JsonNode response = postSummaryExpectingStatus("unknownEntity", user.getId(), 403);

        assertFalse(response.path("success").asBoolean());
        verify(listSummaryService, never()).computeSummary(any(), any(), any());
    }

    @Test
    @DisplayName("missing actor is rejected before business execution")
    void missingActorIsRejectedBeforeBusinessExecution() throws Exception {
        JsonNode response = postSummaryExpectingStatusWithoutActor("inventory", 401);

        assertFalse(response.path("success").asBoolean());
        verify(listSummaryService, never()).computeSummary(any(), any(), any());
    }

    @Test
    @DisplayName("empty actor lookup is rejected before business execution")
    void emptyActorLookupIsRejectedBeforeBusinessExecution() throws Exception {
        when(userRepository.findById(7002L)).thenReturn(Optional.empty());

        JsonNode response = postSummaryWithoutInterceptor("inventory", 7002L, 401);

        assertFalse(response.path("success").asBoolean());
        verify(listSummaryService, never()).computeSummary(any(), any(), any());
    }

    @Test
    @DisplayName("actor lookup failure is rejected before business execution")
    void actorLookupFailureIsRejectedBeforeBusinessExecution() throws Exception {
        when(userRepository.findById(7003L)).thenThrow(new IllegalStateException("user repository unavailable"));

        JsonNode response = postSummaryWithoutInterceptor("inventory", 7003L, 401);

        assertFalse(response.path("success").asBoolean());
        verify(listSummaryService, never()).computeSummary(any(), any(), any());
    }

    @Test
    @DisplayName("restaurant_manager can read wastage through the real permission service")
    void restaurantManagerCanReadWastageWithRealPermissionService() throws Exception {
        User restaurantManager = user(7004L);
        restaurantManager.setRoleCode(FactoryUserRole.restaurant_manager.name());
        when(userRepository.findById(7004L)).thenReturn(Optional.of(restaurantManager));
        when(listSummaryService.computeSummary(eq(FACTORY), eq("wastage"), any(ListSummaryRequest.class)))
                .thenReturn(summary("wastage"));

        JsonNode response = postWithRealPermissionService("wastage", restaurantManager.getId(), 200);

        assertEquals(200, response.path("code").asInt());
        verify(listSummaryService).computeSummary(eq(FACTORY), eq("wastage"), any(ListSummaryRequest.class));
    }

    @Test
    @DisplayName("restaurant_manager cannot read unrelated inventory summary")
    void restaurantManagerCannotReadInventoryWithRealPermissionService() throws Exception {
        User restaurantManager = user(7005L);
        restaurantManager.setRoleCode(FactoryUserRole.restaurant_manager.name());
        when(userRepository.findById(7005L)).thenReturn(Optional.of(restaurantManager));

        JsonNode response = postWithRealPermissionService("inventory", restaurantManager.getId(), 403);

        assertFalse(response.path("success").asBoolean());
        verify(listSummaryService, never()).computeSummary(any(), any(), any());
    }

    static Stream<Arguments> entityReadPermissions() {
        return Stream.of(
                Arguments.of("salesOrder", "sales:read"),
                Arguments.of("purchaseOrder", "procurement:read"),
                Arguments.of("inventory", "warehouse:read"),
                Arguments.of("wastage", "inventory:read"),
                Arguments.of("attendance", "hr:read"),
                Arguments.of("returnOrder", "sales:read"),
                Arguments.of("internalTransfer", "inventory:read"),
                Arguments.of("qualityInspection", "quality:read"),
                Arguments.of("productionPlan", "production:read"),
                Arguments.of("shipment", "warehouse:read"));
    }

    private JsonNode postSummary(String entityType, Long userId) throws Exception {
        return postSummaryExpectingStatus(entityType, userId, 200);
    }

    private JsonNode postSummaryExpectingStatus(String entityType, Long userId, int status) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/mobile/{factoryId}/list-summary/{entityType}", FACTORY, entityType)
                        .requestAttr("userId", userId)
                        .requestAttr("factoryId", FACTORY)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().is(status))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postSummaryExpectingStatusWithoutActor(String entityType, int status) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/mobile/{factoryId}/list-summary/{entityType}", FACTORY, entityType)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().is(status))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postSummaryWithoutInterceptor(String entityType, Long userId, int status) throws Exception {
        MockMvc controllerOnly = MockMvcBuilders.standaloneSetup(
                        new ListSummaryController(listSummaryService, permissionService, userRepository))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        MvcResult result = controllerOnly.perform(post("/api/mobile/{factoryId}/list-summary/{entityType}", FACTORY, entityType)
                        .requestAttr("userId", userId)
                        .requestAttr("factoryId", FACTORY)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().is(status))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode postWithRealPermissionService(String entityType, Long userId, int status) throws Exception {
        PermissionService realPermissionService = new PermissionServiceImpl();
        MockMvc realMockMvc = MockMvcBuilders.standaloneSetup(
                        new ListSummaryController(listSummaryService, realPermissionService, userRepository))
                .addInterceptors(new PermissionInterceptor(realPermissionService, userRepository, objectMapper))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        MvcResult result = realMockMvc.perform(post("/api/mobile/{factoryId}/list-summary/{entityType}", FACTORY, entityType)
                        .requestAttr("userId", userId)
                        .requestAttr("factoryId", FACTORY)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().is(status))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void allowOnly(User user, String permission) {
        Set<String> allowed = new HashSet<>(Set.of(permission));
        doAnswer(invocation -> Arrays.stream(invocation.getArguments())
                        .skip(1)
                        .map(String.class::cast)
                        .anyMatch(allowed::contains))
                .when(permissionService).hasAnyPermission(any(User.class), any(String[].class));
    }

    private User user(Long userId) {
        User user = new User();
        user.setId(userId);
        user.setFactoryId(FACTORY);
        return user;
    }

    private ListSummaryResponse summary(String entityType) {
        return ListSummaryResponse.builder()
                .entityType(entityType)
                .stats(List.of())
                .build();
    }
}
