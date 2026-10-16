package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.*;
import com.cretas.aims.entity.enums.DataScope;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RoleDataScopeControllerContractTest {
    private RoleDataScopeService service;
    private MockMvc mvc;
    private final String route="/api/mobile/F1/roles/warehouse_manager/data-scope";
    @BeforeEach void setUp() {
        service=mock(RoleDataScopeService.class);mvc=standaloneSetup(new RoleDataScopeController(service)).setControllerAdvice(new Errors()).build();
    }
    private AuthenticatedActor actor(String factory,String role) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,56L,factory,role);
    }
    private void fresh(AuthenticatedActor actor,String role) {
        when(service.requireFactoryActor(actor,"F1")).thenReturn(DataScopeContext.builder().userId(56L).factoryId("F1").roleCode(role).scope(DataScope.ALL).build());
    }
    @Test void factoryAdminSavesForExactFactoryAndTypedActorId() throws Exception {
        var actor=actor("F1","factory_super_admin");fresh(actor,"factory_super_admin");
        var update=new RoleDataScopeService.Update(DataScope.SELF,3);
        when(service.update("F1","warehouse_manager",update,56L)).thenReturn(new RoleDataScopeService.View("warehouse_manager",DataScope.SELF,true,4,true));
        mvc.perform(put(route).contentType("application/json").content("{\"scope\":\"SELF\",\"version\":3}").requestAttr("userId",88L).requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,actor))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.scope").value("SELF")).andExpect(jsonPath("$.data.version").value(4));
        verify(service).update("F1","warehouse_manager",update,56L);
    }
    @Test void revokedAdministratorRoleCannotUseOldTokenRoleToSave() throws Exception {
        var actor=actor("F1","factory_super_admin");fresh(actor,"warehouse_manager");
        mvc.perform(put(route).contentType("application/json").content("{\"scope\":\"ALL\",\"version\":0}").requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,actor))
            .andExpect(status().isForbidden());verify(service,never()).update(anyString(),anyString(),any(),anyLong());
    }
    @Test void roleReadUsesCurrentServerRoleToReportReadOnly() throws Exception {
        var actor=actor("F1","warehouse_manager");fresh(actor,"warehouse_manager");
        when(service.read("F1","warehouse_manager",false)).thenReturn(new RoleDataScopeService.View("warehouse_manager",DataScope.SELF,true,2,false));
        mvc.perform(get(route).requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,actor)).andExpect(status().isOk()).andExpect(jsonPath("$.data.editable").value(false));
        verify(service).read("F1","warehouse_manager",false);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void platformIdCollisionIsRejectedBeforeService(boolean write) throws Exception {
        denied(write,new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN,56L,"F1","super_admin"),403);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void otherFactoryIsRejectedBeforeService(boolean write) throws Exception {denied(write,actor("F2","factory_super_admin"),403);}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void missingTypedActorCannotBorrowLegacyId(boolean write) throws Exception {denied(write,null,401);}
    private void denied(boolean write,AuthenticatedActor actor,int statusCode) throws Exception {
        var request=write?put(route).contentType("application/json").content("{\"scope\":\"ALL\",\"version\":0}"):get(route);
        request.requestAttr("userId",56L);if(actor!=null)request.requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,actor);
        mvc.perform(request).andExpect(status().is(statusCode));verifyNoInteractions(service);
    }
    @Test void readAndWriteKeepSystemPermissionInterceptors() throws Exception {
        assertThat(RoleDataScopeController.class.getMethod("read",String.class,String.class,HttpServletRequest.class).getAnnotation(RequirePermission.class).value()).contains("system:read","system:read_write");
        assertThat(RoleDataScopeController.class.getMethod("update",String.class,String.class,HttpServletRequest.class,RoleDataScopeService.Update.class).getAnnotation(RequirePermission.class).value()).containsExactly("system:read_write");
    }
    @RestControllerAdvice static class Errors {
        @ExceptionHandler(BusinessException.class) ResponseEntity<Map<String,Object>> handle(BusinessException e) {
            return ResponseEntity.status(e.getCode()).body(Map.of("success",false,"message",e.getMessage()));
        }
    }
}
