package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.testutil.MaterialBatchInventoryScopeFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.testutil.PermissionRepositoryFixtures;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class MaterialBatchSelectionScopeContractTest {
    private DataScopeResolver resolver;
    private MaterialBatchRepository repository;
    private BomRecipeItemRepository bomRepository;
    private MaterialBatchService service;
    private UserRepository userRepository;
    private PermissionServiceImpl permissionService;
    private final Map<String, String> moduleLevels = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(MaterialBatchRepository.class);
        MaterialBatch a = MaterialBatchInventoryScopeFixture.batch("A", "F006", 101L,
                MaterialBatchStatus.AVAILABLE, LocalDate.now().plusDays(1));
        MaterialBatch b = MaterialBatchInventoryScopeFixture.batch("B", "F006", 202L,
                MaterialBatchStatus.AVAILABLE, LocalDate.now().plusDays(5));
        MaterialBatch foreign = MaterialBatchInventoryScopeFixture.batch("FOREIGN", "F007", 202L,
                MaterialBatchStatus.AVAILABLE, LocalDate.now().plusDays(1));
        MaterialBatch expiredB = MaterialBatchInventoryScopeFixture.batch("B-EXPIRED", "F006", 202L,
                MaterialBatchStatus.EXPIRED, LocalDate.now().minusDays(1));
        MaterialBatch wipB = MaterialBatchInventoryScopeFixture.batch("B-WIP", "F006", 202L,
                MaterialBatchStatus.PRODUCING_RESERVED, LocalDate.now().plusDays(5));
        MaterialBatchInventoryScopeFixture.stubQueries(repository, List.of(a, b, foreign, expiredB, wipB));
        bomRepository = mock(BomRecipeItemRepository.class);
        User actor = new User();
        actor.setId(101L);
        actor.setFactoryId("F006");
        actor.setRoleCode("warehouse_worker");
        actor.setIsActive(true);
        userRepository = mock(UserRepository.class);
        when(userRepository.findById(101L)).thenReturn(Optional.of(actor));
        com.cretas.aims.repository.RoleDefinitionRepository roles =
                mock(com.cretas.aims.repository.RoleDefinitionRepository.class);
        com.cretas.aims.entity.config.RoleDefinition definition = new com.cretas.aims.entity.config.RoleDefinition();
        definition.setRoleCode("warehouse_worker"); definition.setDataScope("SELF");
        when(roles.findByRoleCode("warehouse_worker")).thenReturn(Optional.of(definition));
        resolver = new DataScopeResolver(userRepository, roles);
        MaterialBatchServiceImpl target = MaterialBatchInventoryScopeFixture.service(repository, resolver);
        ReflectionTestUtils.setField(target, "bomRecipeItemRepository", bomRepository);
        PlatformRolePermissionRepository platformRepository = mock(PlatformRolePermissionRepository.class);
        FactoryModuleConfigRepository factoryRepository = mock(FactoryModuleConfigRepository.class);
        PermissionRepositoryFixtures.successfulEmpty(platformRepository, factoryRepository);
        when(platformRepository.findByRoleCodeAndModuleCodeAndDeletedAtIsNull("warehouse_worker", "production"))
                .thenAnswer(invocation -> Optional.of(permission("production", moduleLevels.getOrDefault("production", "-"))));
        when(platformRepository.findByRoleCodeAndModuleCodeAndDeletedAtIsNull("warehouse_worker", "inventory"))
                .thenAnswer(invocation -> Optional.of(permission("inventory", moduleLevels.getOrDefault("inventory", "-"))));
        when(platformRepository.findByRoleCodeAndModuleCodeAndDeletedAtIsNull("warehouse_worker", "warehouse"))
                .thenAnswer(invocation -> Optional.of(permission("warehouse", moduleLevels.getOrDefault("warehouse", "-"))));
        permissionService = new PermissionServiceImpl();
        ReflectionTestUtils.setField(permissionService, "platformRepo", platformRepository);
        ReflectionTestUtils.setField(permissionService, "factoryConfigRepo", factoryRepository);
        ReflectionTestUtils.setField(target, "userRepository", userRepository);
        ReflectionTestUtils.setField(target, "permissionService", permissionService);
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new com.cretas.aims.aspect.DataScopeAspect(resolver));
        service = (MaterialBatchService) proxyFactory.getProxy();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", "101");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        moduleLevels.put("inventory", "r");
        moduleLevels.put("production", "r");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        while (DataScopeContext.current() != null) {
            DataScopeContext.pop();
        }
    }

    @Test
    void validCurrentBomSelectionMayCrossReceiverWithinFactory() {
        moduleLevels.put("inventory", "-"); // production-only SELF actor must still select across receivers
        BomRecipeItem item = new BomRecipeItem();
        item.setMaterialTypeId("RM-1");
        when(bomRepository.findCurrentByProduct("F006", "PT-1")).thenReturn(List.of(item));

        assertThat(service.getMaterialBatchesByStatus("F006", MaterialBatchStatus.AVAILABLE,
                "PT-1", "WH-RAW")).extracting("id").containsExactly("A", "B");
    }

    @Test
    void missingCurrentBomNeverFallsBackToAllReceivers() {
        when(bomRepository.findCurrentByProduct("F006", "PT-MISSING")).thenReturn(List.of());

        assertThat(service.getMaterialBatchesByStatus("F006", MaterialBatchStatus.AVAILABLE,
                "PT-MISSING", "WH-RAW")).isEmpty();
    }

    @Test
    void blankProductUsesOrdinaryCreatorScope() {
        assertThat(service.getMaterialBatchesByStatus("F006", MaterialBatchStatus.AVAILABLE,
                " ", "WH-RAW")).extracting("id").containsExactly("A");
    }

    @Test
    void productionOnlyActorCannotUseOrdinaryInventoryQuery() {
        moduleLevels.put("inventory", "-");
        moduleLevels.put("production", "r");

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> service.getMaterialBatchesByStatus("F006", MaterialBatchStatus.AVAILABLE));
        verifyNoInteractions(repository);
    }

    @Test
    void warehouseOnlyActorCannotForgeProductionSelection() {
        moduleLevels.put("inventory", "r");
        moduleLevels.put("production", "-");
        BomRecipeItem item = new BomRecipeItem();
        item.setMaterialTypeId("RM-1");
        when(bomRepository.findCurrentByProduct("F006", "PT-1")).thenReturn(List.of(item));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> service.getMaterialBatchesByStatus("F006", MaterialBatchStatus.AVAILABLE,
                        "PT-1", "WH-RAW"));
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @EnumSource(value = MaterialBatchStatus.class, names = {"EXPIRED", "PRODUCING_RESERVED"})
    void productionSelectionCannotReadNonAvailableCrossReceiverBatches(MaterialBatchStatus status) {
        moduleLevels.put("inventory", "-");
        moduleLevels.put("production", "r");
        BomRecipeItem item = new BomRecipeItem();
        item.setMaterialTypeId("RM-1");
        when(bomRepository.findCurrentByProduct("F006", "PT-1")).thenReturn(List.of(item));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> service.getMaterialBatchesByStatus("F006", status, "PT-1", "WH-RAW"));
        verifyNoInteractions(repository, bomRepository);
    }

    @Test
    void actualHttpProductionOnlyValidBomReturnsBothReceivers() throws Exception {
        moduleLevels.put("inventory", "-");
        BomRecipeItem item = new BomRecipeItem(); item.setMaterialTypeId("RM-1");
        when(bomRepository.findCurrentByProduct("F006", "PT-1")).thenReturn(List.of(item));
        http().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/mobile/F006/material-batches/status/AVAILABLE").requestAttr("userId", 101L)
                .param("productTypeId", "PT-1").param("warehouseId", "WH-RAW"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].id").value("A"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[1].id").value("B"));
    }

    @Test
    void actualHttpProductionOnlyWithoutBomCannotUseOrdinaryInventory() throws Exception {
        moduleLevels.put("inventory", "-");
        http().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/mobile/F006/material-batches/status/AVAILABLE").requestAttr("userId", 101L))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        verifyNoInteractions(repository, bomRepository);
    }

    @Test
    void actualHttpWarehouseOnlyCannotForgeProductionBomParameter() throws Exception {
        moduleLevels.put("production", "-");
        http().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/mobile/F006/material-batches/status/AVAILABLE").requestAttr("userId", 101L)
                .param("productTypeId", "PT-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        verifyNoInteractions(repository, bomRepository);
    }

    private org.springframework.test.web.servlet.MockMvc http() {
        com.cretas.aims.service.MobileService mobile = mock(com.cretas.aims.service.MobileService.class);
        com.cretas.aims.controller.MaterialBatchController controller = new com.cretas.aims.controller.MaterialBatchController(
                service, mobile, new com.cretas.aims.security.PriceMaskResolver(mobile, userRepository, permissionService),
                mock(com.cretas.aims.service.inventory.OpeningInventoryService.class));
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new com.cretas.aims.config.PermissionInterceptor(permissionService, userRepository,
                        new com.fasterxml.jackson.databind.ObjectMapper()))
                .setControllerAdvice(new com.cretas.aims.exception.GlobalExceptionHandler()).build();
    }

    private static PlatformRolePermission permission(String module, String level) {
        PlatformRolePermission row = new PlatformRolePermission();
        row.setRoleCode("warehouse_worker");
        row.setModuleCode(module);
        row.setPermissionLevel(level);
        return row;
    }
}
