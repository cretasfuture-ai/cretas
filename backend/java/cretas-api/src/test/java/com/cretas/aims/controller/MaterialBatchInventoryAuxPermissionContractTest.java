package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.permission.PlatformRolePermission;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.permission.PlatformRolePermissionRepository;
import com.cretas.aims.repository.config.FactoryModuleConfigRepository;
import com.cretas.aims.testutil.PermissionRepositoryFixtures;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.inventory.OpeningInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual controller/interceptor/canonical L1 resolver; fixture grants reflect the observed
 * F006 module domain split, not a production role allowlist. JWT tenant protection is unchanged
 * and separately checked by real runtime negative controls. */
class MaterialBatchInventoryAuxPermissionContractTest {
    private static final List<String> ROUTES=List.of("inventory/statistics","low-stock","inventory/alerts","inventory/expiry-warnings",
            "status/AVAILABLE", "wip", "expiring", "expired");
    private static final String BASE="/api/mobile/F006/material-batches/";
    private final MaterialBatchService service=mock(MaterialBatchService.class);
    private final UserRepository users=mock(UserRepository.class);
    private final MobileService mobile=mock(MobileService.class);
    private final PlatformRolePermissionRepository platform=mock(PlatformRolePermissionRepository.class);
    private final PermissionServiceImpl policy=new PermissionServiceImpl();
    private final Map<String,Object> source=Map.of("totalBatches",7L,"totalQuantity",new BigDecimal("25"),"totalValue",new BigDecimal("99"));
    private User actor;

    private MockMvc mvc(String role,Map<String,String> levels,boolean actorPresent) {
        actor=new User();actor.setId(99101L);actor.setFactoryId("F006");actor.setRoleCode(role);actor.setIsActive(true);
        when(users.findById(actor.getId())).thenReturn(actorPresent?Optional.of(actor):Optional.empty());
        ReflectionTestUtils.setField(policy,"platformRepo",platform);
        FactoryModuleConfigRepository factory=mock(FactoryModuleConfigRepository.class);
        PermissionRepositoryFixtures.successfulEmpty(platform,factory);
        ReflectionTestUtils.setField(policy,"factoryConfigRepo",factory);
        when(platform.findByRoleCodeAndModuleCodeAndDeletedAtIsNull(anyString(),anyString())).thenAnswer(call->{
            PlatformRolePermission row=new PlatformRolePermission();row.setRoleCode(call.getArgument(0));row.setModuleCode(call.getArgument(1));
            row.setPermissionLevel(levels.getOrDefault(call.getArgument(1),"-"));return Optional.of(row);
        });
        UserDTO dto=mock(UserDTO.class);when(dto.getId()).thenReturn(actor.getId());
        when(mobile.getUserFromToken("fixture-token")).thenReturn(dto);
        when(service.getInventoryStatistics("F006")).thenReturn(source);
        when(service.getLowStockWarnings("F006")).thenReturn(List.of(Map.of("materialTypeName","fixture","currentQuantity",5,"minQuantity",10)));
        when(service.getExpiringBatches(eq("F006"),anyInt())).thenReturn(List.of());
        when(service.getExpiredBatches("F006")).thenReturn(List.of());
        when(service.getWipBatches("F006")).thenReturn(List.of());
        when(service.getMaterialBatchesByStatus(eq("F006"), eq(MaterialBatchStatus.AVAILABLE),
                nullable(String.class), nullable(String.class))).thenReturn(List.of());
        return MockMvcBuilders.standaloneSetup(new MaterialBatchController(service,mobile,new PriceMaskResolver(mobile,users,policy),mock(OpeningInventoryService.class)))
                .addInterceptors(new PermissionInterceptor(policy,users,new ObjectMapper())).build();
    }

    static Stream<Arguments> roles(){return Stream.of(
            Arguments.of("cashier", false, false),
            Arguments.of("department_admin", true, true),
            Arguments.of("dispatcher", true, true),
            Arguments.of("equipment_admin", false, false),
            Arguments.of("factory_super_admin", true, true),
            Arguments.of("finance_manager", false, false),
            Arguments.of("hr_admin", false, false),
            Arguments.of("operator", false, true),
            Arguments.of("procurement_manager", false, false),
            Arguments.of("production_manager", true, true),
            Arguments.of("quality_inspector", false, true),
            Arguments.of("quality_manager", false, true),
            Arguments.of("sales_manager", false, false),
            Arguments.of("viewer", true, true),
            Arguments.of("warehouse_manager", true, false),
            Arguments.of("warehouse_worker", true, false),
            Arguments.of("workshop_supervisor", true, true),
            Arguments.of("yield_operator", false, true)
    );}
    static Stream<Arguments> roleRoutes(){return roles().flatMap(a->ROUTES.stream().map(route->Arguments.of(a.get()[0],a.get()[1],a.get()[2],route)));}
    @ParameterizedTest(name="{0} {3}") @MethodSource("roleRoutes")
    void actualRoleDomainsDenyBeforeBusinessAndPreserveProductionStatistics(String role,boolean inventoryRead,boolean productionRead,String route)throws Exception{
        Map<String,String> levels=Map.of("inventory",inventoryRead?"r":"-","warehouse","-","production",productionRead?"r":"-");
        assertDomain(mvc(role,levels,true),route,
                inventoryRead || (route.equals("inventory/statistics") && productionRead)
                        || (route.equals("status/AVAILABLE") && productionRead));
    }
    static Stream<Arguments> aliases(){return Stream.of("warehouse","inventory","production").flatMap(module->Stream.of("r","rw").flatMap(level->ROUTES.stream().map(route->Arguments.of(module,level,route))));}
    @ParameterizedTest(name="{0}:{1} {2}") @MethodSource("aliases")
    void eachReadOrReadWriteAliasWorksWithoutOtherModuleGrants(String module,String level,String route)throws Exception{
        assertDomain(mvc("hr_admin",Map.of(module,level),true),route,
                !module.equals("production")
                        || route.equals("inventory/statistics")
                        || route.equals("status/AVAILABLE"));
    }
    static Stream<Arguments> unavailableActors(){return ROUTES.stream().flatMap(route->Stream.of(Arguments.of(route,false),Arguments.of(route,true)));}
    @ParameterizedTest @MethodSource("unavailableActors")
    void missingOrUnknownActorIsRejectedBeforeAnyBusinessCall(String route,boolean unknown)throws Exception{
        MockMvc mvc=mvc("warehouse_worker",Map.of("inventory","r"),false);
        var request=get(BASE+route);if(unknown)request.requestAttr("userId",99101L);
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(service);
    }
    private void assertDomain(MockMvc mvc,String route,boolean allowed)throws Exception{
        var result=mvc.perform(get(BASE+route).requestAttr("userId",99101L).header("Authorization","Bearer fixture-token"));
        if(!allowed){result.andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));verifyNoInteractions(service);return;}
        result.andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        switch(route){
            case "inventory/statistics" -> {
                result.andExpect(jsonPath("$.data.totalBatches").value(7)).andExpect(jsonPath("$.data.totalQuantity").value(25));
                if(policy.hasPermission(actor,"procurement:price:view"))result.andExpect(jsonPath("$.data.totalValue").value(99));
                else result.andExpect(jsonPath("$.data.totalValue").doesNotExist());
                assertThat(source.get("totalValue")).isEqualTo(new BigDecimal("99"));verify(service).getInventoryStatistics("F006");
            }
            case "low-stock" -> {result.andExpect(jsonPath("$.data[0].currentQuantity").value(5));verify(service).getLowStockWarnings("F006");}
            case "inventory/alerts" -> {result.andExpect(jsonPath("$.data[0].alertType").value("LOW_STOCK"));verify(service).getLowStockWarnings("F006");}
            case "inventory/expiry-warnings" -> {result.andExpect(jsonPath("$.data.summary.urgent").value(0));verify(service).getExpiringBatches("F006",90);}
            case "status/AVAILABLE" -> verify(service).getMaterialBatchesByStatus(
                    eq("F006"), eq(MaterialBatchStatus.AVAILABLE), nullable(String.class), nullable(String.class));
            case "wip" -> verify(service).getWipBatches("F006");
            case "expiring" -> verify(service).getExpiringBatches(eq("F006"), anyInt());
            case "expired" -> verify(service).getExpiredBatches("F006");
            default -> throw new AssertionError("Unknown fixture route");
        }
    }
}
