package com.cretas.aims.controller;

import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class SmartBICostProductAccessControllerContractTest {

    @Test
    void jacksonAndBeanValidationKeepTheFixedRequestShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SmartBICostProductAccessController.AccessRequest request = mapper.readValue(
                "{\"productIds\":[\"p-1\",\"p-2\"]}",
                SmartBICostProductAccessController.AccessRequest.class);
        var violations = Validation.buildDefaultValidatorFactory().getValidator().validate(request);
        assertThat(request.productIds()).containsExactly("p-1", "p-2");
        assertThat(violations).isEmpty();
    }

    @Test
    void filtersRequestedProductsAndAllowsUnscopedOnlyWhenAllProtectedProductsPass() {
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        var actor = actor("F001", 7L);
        when(access.protectedProductTypeIds("F001")).thenReturn(Set.of("p-secret", "p-other"));
        when(access.filterPermitted(any(), eq("F001"), any(), eq(ProcessSecretAction.VIEW_FULL), eq(false),
                eq("smartbi:cost-product-access"))).thenReturn(List.of("p-free", "p-secret", "p-other"));

        var response = new SmartBICostProductAccessController(access).authorize(
                "F001", new SmartBICostProductAccessController.AccessRequest(List.of("p-free", "p-secret")),
                request(actor));
        assertThat(response.getData().allowedProductIds()).containsExactly("p-free", "p-secret");
        assertThat(response.getData().unscopedCostAllowed()).isTrue();
    }

    @Test
    void crossFactoryAndClientActorInputsCannotAuthorize() {
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        var controller = new SmartBICostProductAccessController(access);
        assertThatThrownBy(() -> controller.authorize("F001",
                new SmartBICostProductAccessController.AccessRequest(List.of("p1")), request(actor("F002", 7L))))
                .hasMessageContaining("tenant");
        assertThatThrownBy(() -> controller.authorize("F001",
                new SmartBICostProductAccessController.AccessRequest(List.of("p1", "p1")), request(actor("F001", 7L))))
                .hasMessageContaining("unique");
    }

    @Test
    void auditDeniedProductsAreNotReturnedAndUnscopedIsFalse() {
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        when(access.protectedProductTypeIds("F001")).thenReturn(Set.of("p-secret"));
        when(access.filterPermitted(any(), eq("F001"), any(), eq(ProcessSecretAction.VIEW_FULL), eq(false),
                eq("smartbi:cost-product-access"))).thenReturn(List.of("p-free"));
        var response = new SmartBICostProductAccessController(access).authorize("F001",
                new SmartBICostProductAccessController.AccessRequest(List.of("p-free", "p-secret")),
                request(actor("F001", 7L)));
        assertThat(response.getData().allowedProductIds()).containsExactly("p-free");
        assertThat(response.getData().unscopedCostAllowed()).isFalse();
    }

    @Test
    void invalidInputIsRejectedBeforeAuthorityRead() {
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        var controller = new SmartBICostProductAccessController(access);
        List<String> tooMany = IntStream.range(0, 513).mapToObj(i -> "p" + i).toList();
        assertThatThrownBy(() -> controller.authorize("F001",
                new SmartBICostProductAccessController.AccessRequest(tooMany), request(actor("F001", 7L))))
                .hasMessageContaining("512");
        assertThatThrownBy(() -> controller.authorize("F001",
                new SmartBICostProductAccessController.AccessRequest(List.of("p", "p")), request(actor("F001", 7L))))
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> controller.authorize("F001",
                new SmartBICostProductAccessController.AccessRequest(List.of("p\u0000")), request(actor("F001", 7L))))
                .hasMessageContaining("identifiers");
        assertThatThrownBy(() -> controller.authorize("F001",
                new SmartBICostProductAccessController.AccessRequest(java.util.Arrays.asList((String) null)), request(actor("F001", 7L))))
                .hasMessageContaining("identifiers");
        verifyNoInteractions(access);
    }

    @Test
    void emptyRequestIsValidAndOversizedProtectedSetDisablesUnscopedAccess() {
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        Set<String> oversized = IntStream.range(0, 513).mapToObj(i -> "受保护-" + i).collect(java.util.stream.Collectors.toSet());
        when(access.protectedProductTypeIds("F001")).thenReturn(oversized);
        when(access.filterPermitted(any(), eq("F001"), any(), eq(ProcessSecretAction.VIEW_FULL), eq(false),
                eq("smartbi:cost-product-access"))).thenReturn(List.of("自由产品"));
        var response = new SmartBICostProductAccessController(access).authorize("F001",
                new SmartBICostProductAccessController.AccessRequest(new ArrayList<>()), request(actor("F001", 7L)));
        assertThat(response.getData().allowedProductIds()).isEmpty();
        assertThat(response.getData().unscopedCostAllowed()).isFalse();
    }

    @Test
    void jacksonRejectsClientActorFieldsAndResponseUsesTrustedActor() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertThatThrownBy(() -> mapper.readValue(
                "{\"productIds\":[\"中文SKU\"],\"actorId\":999,\"role\":\"super_admin\"}",
                SmartBICostProductAccessController.AccessRequest.class)).isInstanceOf(Exception.class);
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        when(access.protectedProductTypeIds("F001")).thenReturn(Set.of());
        when(access.filterPermitted(any(), eq("F001"), any(), eq(ProcessSecretAction.VIEW_FULL), eq(false),
                eq("smartbi:cost-product-access"))).thenReturn(List.of("中文SKU"));
        var response = new SmartBICostProductAccessController(access).authorize("F001",
                new SmartBICostProductAccessController.AccessRequest(List.of("中文SKU")), request(actor("F001", 7L)));
        assertThat(response.getData().userId()).isEqualTo(7L);
        assertThat(response.getData().allowedProductIds()).containsExactly("中文SKU");
    }

    private static AuthenticatedActor actor(String factory, long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, factory, "operator");
    }

    private static HttpServletRequest request(AuthenticatedActor actor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE)).thenReturn(actor);
        return request;
    }
}
