package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ProposalComparisonService;
import com.cretas.aims.utils.FactoryAccessValidator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProposalComparisonRoleGateMvcTest {
    private static final String URL = "/api/mobile/F006/proposal-comparisons/evaluate";
    static final String BODY = """
        {"comparisonBasisId":"batch-1","currency":"CNY","costUnit":"PER_BATCH","resourceUnit":"PERSON_HOUR",
         "windowStart":"2027-01-01T08:00:00Z","windowEnd":"2027-01-01T20:00:00Z",
         "requiredBy":"2027-01-01T18:00:00Z","availableResource":10,
         "candidates":[
          {"id":"a","comparisonBasisId":"batch-1","currency":"CNY","costUnit":"PER_BATCH","resourceUnit":"PERSON_HOUR","cost":10,"completionTime":"2027-01-01T10:00:00Z","requiredResource":1},
          {"id":"b","comparisonBasisId":"batch-1","currency":"CNY","costUnit":"PER_BATCH","resourceUnit":"PERSON_HOUR","cost":20,"completionTime":"2027-01-01T11:00:00Z","requiredResource":2}]}
        """;
    private static ValidatorFactory validators;
    private final UserRepository users = mock(UserRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private FactoryAccessValidator access;
    private ProposalComparisonService comparison;
    private MockMvc mvc;
    @BeforeAll static void validator() { validators = Validation.buildDefaultValidatorFactory(); }
    @AfterAll static void close() { validators.close(); }
    @BeforeEach void setup() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        access = spy(new FactoryAccessValidator(users));
        comparison = spy(new ProposalComparisonService(validators.getValidator()));
        mvc = MockMvcBuilders.standaloneSetup(new ProposalComparisonController(access, comparison, mapper))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new PermissionInterceptor(permissions, users, mapper), new RequireRoleInterceptor(mapper)).build();
    }

    @Test void authorizedAdminRunsRealComparatorAndCannotElevateSuppliedFacts() throws Exception {
        allowRead();
        String spoofed = BODY.replace("{\"comparisonBasisId\"", "{\"factsVerified\":true,\"resourcesReserved\":true,\"factoryId\":\"OTHER\",\"comparisonBasisId\"");
        mvc.perform(post(URL).requestAttr("role", "factory_super_admin").requestAttr("userId", 1L)
                        .contentType(MediaType.APPLICATION_JSON).content(spoofed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUPPLIED_FACTS_COMPARABLE"))
                .andExpect(jsonPath("$.data.nonDominatedCandidateIds[0]").value("a"))
                .andExpect(jsonPath("$.data.factsVerified").value(false))
                .andExpect(jsonPath("$.data.resourcesReserved").value(false))
                .andExpect(jsonPath("$.data.businessExecutionPerformed").value(false));
        var order = inOrder(access, comparison);
        order.verify(access).validateAccess("F006"); order.verify(comparison).compare(any());
        verify(permissions).hasAnyPermission(any(User.class), eq(new String[]{"system:read"}));
    }
    @Test void permissionAdminWithDynamicReadAlsoPasses() throws Exception {
        allowRead();
        mvc.perform(post(URL).requestAttr("role", "permission_admin").requestAttr("userId", 1L)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        verify(comparison).compare(any());
    }
    @Test void unapprovedRoleCannotRunComparisonEvenWhenModuleReadIsGranted() throws Exception {
        allowRead();
        mvc.perform(post(URL).requestAttr("role", "operator").requestAttr("userId", 1L)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(access, comparison);
    }
    @Test void missingIdentityIsRejectedBeforeReadingOrCalculating() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(users, access, comparison);
    }
    @Test void adminWithExplicitPermissionDeniedCannotCalculate() throws Exception {
        when(users.findById(1L)).thenReturn(Optional.of(user()));
        when(permissions.hasAnyPermission(any(User.class), any(String[].class))).thenReturn(false);
        mvc.perform(post(URL).requestAttr("role", "factory_super_admin").requestAttr("userId", 1L)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(access, comparison);
    }
    @Test void realFactoryGuardRejectsAnotherFactoryBeforeCalculation() throws Exception {
        allowRead();
        mvc.perform(post(URL.replace("F006", "OTHER")).requestAttr("role", "factory_super_admin").requestAttr("userId", 1L)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verify(access).validateAccess("OTHER"); verifyNoInteractions(comparison);
    }
    @Test void malformedFactDoesNotTurnIntoASuccessfulComparison() throws Exception {
        allowRead();
        mvc.perform(post(URL).requestAttr("role", "factory_super_admin").requestAttr("userId", 1L)
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"cost\":10", "\"cost\":-1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }
    private void allowRead() {
        when(users.findById(1L)).thenReturn(Optional.of(user()));
        when(permissions.hasAnyPermission(any(User.class), any(String[].class))).thenReturn(true);
    }
    private static User user() { User user = new User(); user.setId(1L); user.setFactoryId("F006"); return user; }
}
