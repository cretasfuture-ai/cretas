package com.cretas.aims.controller.vision;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.client.PythonSmartBIClient.VisionTrainingAction;
import com.cretas.aims.config.JwtAuthInterceptor;
import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Optional;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Actual JWT/tenant interceptor, never client-supplied identity headers. */
class FactoryVisionTrainingContractTest {
    private final PythonSmartBIClient python = mock(PythonSmartBIClient.class);
    private final UserRepository users = mock(UserRepository.class);
    private final JwtUtil jwt = new JwtUtil();
    private MockMvc mvc;
    private User member;
    private static final String PATH = "/api/mobile/FA/vision-training";

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(BusinessException.class)
        ResponseEntity<String> business(BusinessException error) { return ResponseEntity.status(error.getCode()).body(error.getMessage()); }
    }
    @BeforeEach void setup() throws Exception {
        ReflectionTestUtils.setField(jwt, "secret", UUID.randomUUID().toString() + UUID.randomUUID());
        ReflectionTestUtils.setField(jwt, "expiration", 60_000L);
        member = new User(); member.setId(62L); member.setFactoryId("FA"); member.setRoleCode("quality_manager"); member.setIsActive(true);
        when(users.findById(62L)).thenReturn(Optional.of(member));
        var factories = mock(FactoryRepository.class);
        var factory = new Factory(); factory.setId("FA"); factory.setIsActive(true);
        when(factories.findById("FA")).thenReturn(Optional.of(factory));
        var interceptor = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtUtil", jwt);
        ReflectionTestUtils.setField(interceptor, "userRepository", users);
        ReflectionTestUtils.setField(interceptor, "factoryRepository", factories);
        ReflectionTestUtils.setField(interceptor, "tokenBlacklistService", mock(TokenBlacklistService.class));
        when(python.callFactoryVisionTraining(eq("FA"), eq(62L), eq("quality_manager"), any(), any(), any()))
                .thenReturn(new ObjectMapper().readTree("{\"templates\":[],\"recipes\":[]}"));
        mvc = MockMvcBuilders.standaloneSetup(new FactoryVisionTrainingController(python, users))
                .setControllerAdvice(new Errors()).addInterceptors(interceptor, new RequireRoleInterceptor(new ObjectMapper())).build();
    }
    private String token(String factory, String role) { return "Bearer " + jwt.generateToken(62L, factory, "quality-test", role); }

    @Test void realFactoryIdentityReachesDailyRouteWithoutAssemblyContext() throws Exception {
        mvc.perform(get(PATH+"/templates").header("Authorization", token("FA", "quality_manager")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(python).callFactoryVisionTraining("FA",62L,"quality_manager",VisionTrainingAction.TEMPLATES,null,null);
    }
    @Test void anonymousAndForgedHeadersCannotCallTheService() throws Exception {
        mvc.perform(get(PATH+"/templates")).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH+"/templates").header("X-User-Id","62").header("X-Factory-Id","FA")
                .header("X-User-Role","quality_manager")).andExpect(status().isUnauthorized());
        verifyNoInteractions(python, users);
    }
    @Test void platformIdNeverFallsBackToSameNumericFactoryMember() throws Exception {
        mvc.perform(get(PATH+"/templates").header("Authorization","Bearer "+jwt.generatePlatformToken(62L,"platform","super_admin")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users, python);
    }
    @Test void otherFactoryAndOrdinaryRoleCannotReachPython() throws Exception {
        mvc.perform(get("/api/mobile/FB/vision-training/templates").header("Authorization",token("FA","quality_manager")))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH+"/templates").header("Authorization",token("FA","operator")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(python);
    }
    @Test void revokedRoleAndDisabledMemberAreRejectedBeforeDispatch() throws Exception {
        member.setRoleCode("operator");
        mvc.perform(get(PATH+"/templates").header("Authorization",token("FA","quality_manager"))).andExpect(status().isForbidden());
        member.setIsActive(false);
        mvc.perform(get(PATH+"/templates").header("Authorization",token("FA","quality_manager"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(python);
    }
    @Test void registeredStageBodyIsForwardedWithTheVerifiedMember() throws Exception {
        mvc.perform(post(PATH+"/drafts/project-a/jobs").header("Authorization",token("FA","quality_manager"))
                        .contentType("application/json").content("{\"stage\":\"collect\",\"recipe_id\":\"recipe-a\"}"))
                .andExpect(status().isOk());
        verify(python).callFactoryVisionTraining(eq("FA"),eq(62L),eq("quality_manager"),eq(VisionTrainingAction.ENQUEUE_JOB),eq("project-a"),
                eq(new ObjectMapper().readTree("{\"stage\":\"collect\",\"recipe_id\":\"recipe-a\"}")));
    }
    @Test void preparationOperationsReuseTheSameLiveFactoryMembershipGuard() throws Exception {
        mvc.perform(get(PATH+"/drafts/project-a/preparation").header("Authorization",token("FA","quality_manager"))).andExpect(status().isOk());
        verify(python).callFactoryVisionTraining("FA",62L,"quality_manager",VisionTrainingAction.PREPARATION,"project-a",null);
        for(String operation:new String[]{"photos","image","review","standard","submit","export"}) {
            mvc.perform(post(PATH+"/drafts/project-a/preparation/"+operation).header("Authorization",token("FA","quality_manager"))
                .contentType("application/json").content("{}" )).andExpect(status().isOk());
            mvc.perform(post("/api/mobile/FB/vision-training/drafts/project-a/preparation/"+operation).header("Authorization",token("FA","quality_manager"))
                .contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        }
        mvc.perform(post(PATH+"/drafts/project-a/preparation/arbitrary").header("Authorization",token("FA","quality_manager"))
                .contentType("application/json").content("{}" )).andExpect(status().isNotFound());
        mvc.perform(post(PATH+"/drafts/project-a/preparation/submit").header("Authorization","Bearer "+jwt.generatePlatformToken(62L,"platform","super_admin"))
                .contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        verify(python,times(7)).callFactoryVisionTraining(eq("FA"),eq(62L),eq("quality_manager"),any(),eq("project-a"),any());
    }
}
