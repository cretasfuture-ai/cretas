package com.cretas.aims.controller;

import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.production.PlanConcurrentReadinessDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.AuthorizationException;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.production.PlanConcurrentReadinessService;
import com.cretas.aims.utils.FactoryAccessValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlanConcurrentReadinessRoleGateMvcContractTest {
    private static final String URL="/api/mobile/F006/production-plans/own/concurrent-readiness";
    private final FactoryAccessValidator access=mock(FactoryAccessValidator.class);
    private final ProductionPlanRepository plans=mock(ProductionPlanRepository.class);
    private final BusinessRowScope rows=mock(BusinessRowScope.class);
    private final PlanConcurrentReadinessService reader=mock(PlanConcurrentReadinessService.class);
    private final PermissionService permissions=mock(PermissionService.class);
    private final UserRepository users=mock(UserRepository.class);
    private final FactoryConfigService config=mock(FactoryConfigService.class);
    private final UserModuleAccessService modules=mock(UserModuleAccessService.class);
    private final PlanConcurrentReadinessController controller=new PlanConcurrentReadinessController(access,plans,rows,reader);
    private MockMvc mvc;
    @BeforeEach void setup(){
        ObjectMapper mapper=new ObjectMapper();
        mvc=MockMvcBuilders.standaloneSetup(controller).addInterceptors(
                new PermissionInterceptor(permissions,users,mapper),new ModuleEnabledInterceptor(config,modules,users,mapper)).build();
        User user=new User();user.setId(1L);user.setFactoryId("F006");user.setRoleCode("production_manager");
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(permissions.hasAnyPermission(any(User.class),any(String[].class))).thenReturn(true);
        when(config.isModuleEnabled("F006","production_plan")).thenReturn(true);
        when(modules.canAccessModule(any(User.class),eq("production_plan"))).thenReturn(true);
        ProductionPlan plan=new ProductionPlan();plan.setId("own");plan.setFactoryId("F006");plan.setCreatedBy(1L);
        when(plans.findByIdAndFactoryId("own","F006")).thenReturn(Optional.of(plan));
        when(rows.creatorIds("F006")).thenReturn(null);
    }
    @Test void missingIdentityCannotReachReader() throws Exception{
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());verifyNoInteractions(access,reader,plans);
    }
    @Test void dynamicPermissionDenialCannotReachReader() throws Exception{
        when(permissions.hasAnyPermission(any(User.class),any(String[].class))).thenReturn(false);
        mvc.perform(get(URL).requestAttr("userId",1L).requestAttr("role","production_manager")).andExpect(status().isForbidden());
        verifyNoInteractions(access,reader,plans);
    }
    @Test void moduleAndExplicitAccountDenyCannotReachReader() throws Exception{
        when(config.isModuleEnabled("F006","production_plan")).thenReturn(false);
        mvc.perform(get(URL).requestAttr("userId",1L).requestAttr("role","production_manager")).andExpect(status().isBadRequest());
        verifyNoInteractions(access,reader,plans);
        when(config.isModuleEnabled("F006","production_plan")).thenReturn(true);
        when(modules.canAccessModule(any(User.class),eq("production_plan"))).thenReturn(false);
        mvc.perform(get(URL).requestAttr("userId",1L).requestAttr("role","production_manager")).andExpect(status().isForbidden());
        verifyNoInteractions(access,reader,plans);
    }
    @Test void foreignFactoryFailsBeforePlanOrSnapshotRead(){
        doThrow(new AuthorizationException("无权访问该工厂数据")).when(access).validateAccess("F001");
        assertThatThrownBy(()->controller.read("F001","own")).isInstanceOf(AuthorizationException.class);
        verifyNoInteractions(plans,reader);
    }
    @Test void rowScopeDeniesOtherCreatorsBeforeReader(){
        when(rows.creatorIds("F006")).thenReturn(List.of(2L));
        assertThatThrownBy(()->controller.read("F006","own")).isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        verifyNoInteractions(reader);
    }
    @Test void restrictedOwnerCannotInferHiddenCompetingPlans(){
        when(rows.creatorIds("F006")).thenReturn(List.of(1L));
        assertThatThrownBy(()->controller.read("F006","own")).isInstanceOf(com.cretas.aims.exception.BusinessException.class);
        verifyNoInteractions(plans,reader);
    }
    @Test void authorizedReadPreservesFactoryThenRowThenReaderOrder() throws Exception{
        mvc.perform(get(URL).requestAttr("userId",1L).requestAttr("role","production_manager"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        var order=inOrder(access,plans,rows,reader);order.verify(access).validateAccess("F006");
        order.verify(rows).creatorIds("F006");order.verify(plans).findByIdAndFactoryId("own","F006");order.verify(reader).read("F006","own");
    }
    @Test void noWriteRouteIsExposed() throws Exception{
        mvc.perform(post(URL).requestAttr("userId",1L)).andExpect(status().isMethodNotAllowed());verifyNoInteractions(reader,plans);
    }
    @Test void targetPlanUsesTheExistingFullProcessSecretGate() throws Exception{
        var gate=PlanConcurrentReadinessController.class.getMethod("read",String.class,String.class)
                .getAnnotation(com.cretas.aims.annotation.ProcessSecretGuard.class);
        org.assertj.core.api.Assertions.assertThat(gate).isNotNull();
        org.assertj.core.api.Assertions.assertThat(gate.action()).isEqualTo(com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL);
        org.assertj.core.api.Assertions.assertThat(gate.from()).isEqualTo(com.cretas.aims.annotation.ProcessSecretGuard.Source.PLAN_PATH);
        org.assertj.core.api.Assertions.assertThat(gate.name()).isEqualTo("planId");
    }
    @Test void unknownQuantityFieldsRemainExplicitNullEvenWithGlobalNonNullPolicy() throws Exception{
        var data=new PlanConcurrentReadinessDTO("own","OWN","PENDING",null,null,"DUE_THROUGH_PLAN_DATE_AND_STARTED",0,1,List.of(),
                new PlanConcurrentReadinessDTO.MaterialCoverage("UNVERIFIED","UNKNOWN_COVERAGE",null,null,List.of()),
                new PlanConcurrentReadinessDTO.Progress("UNVERIFIED","FINISHED_GOODS_RECEIVED",null,null,null,null,null,"单位待核对"),true);
        ObjectMapper mapper=new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var json=mapper.readTree(mapper.writeValueAsBytes(data));
        org.assertj.core.api.Assertions.assertThat(json.path("material").has("lowerPercent")).isTrue();
        org.assertj.core.api.Assertions.assertThat(json.path("material").get("lowerPercent").isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(json.path("progress").has("receivedQuantity")).isTrue();
        org.assertj.core.api.Assertions.assertThat(json.path("progress").get("receivedQuantity").isNull()).isTrue();
    }
}
