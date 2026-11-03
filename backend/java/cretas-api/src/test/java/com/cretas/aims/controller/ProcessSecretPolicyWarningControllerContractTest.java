package com.cretas.aims.controller;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretAccessEventRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretExternalPackageRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretExternalService;
import com.cretas.aims.service.processsecret.ProcessSecretPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Steve 2026-11-03 选 B: 保密产品的全名本身是通用品类词 (产品就叫「牛腱」) 时, 训练过滤不按名字拦。
 * 负责人保存策略 (PUT /policies/{productTypeId}) 时响应里带 {@code warnings} 提示 —— 是提示不是拒绝 (仍 200);
 * 而品类词别名仍然 400 (登记了也不会生效)。走真的控制器 + 真的 service + 全局异常处理。
 */
class ProcessSecretPolicyWarningControllerContractTest {

    static final String F = "F-W";
    static final long OWNER = 10L;
    static final String WARNING = "该名称为通用品类词，训练过滤不按名字拦截，仅按编码/工艺数值保护";

    MockMvc mvc;
    ProcessSecretPolicyRepository policies;
    ProductTypeRepository products;

    @BeforeEach
    void setUp() {
        policies = mock(ProcessSecretPolicyRepository.class);
        UserRepository users = mock(UserRepository.class);
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        when(access.audit(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
        User owner = new User();
        owner.setId(OWNER);
        owner.setFactoryId(F);
        owner.setRoleCode("factory_super_admin");
        owner.setIsActive(true);
        when(users.findByIdAndFactoryId(OWNER, F)).thenReturn(Optional.of(owner));
        when(policies.save(any(ProcessSecretPolicy.class))).thenAnswer(i -> i.getArgument(0));
        products = mock(ProductTypeRepository.class);
        product("P-NJ", "牛腱");
        product("P-SX", "五香虎皮猪肘");
        ProcessSecretPolicyService service = new ProcessSecretPolicyService(policies,
                mock(ProcessSecretGrantRepository.class), mock(ProcessSecretAccessEventRepository.class), users, access,
                mock(ProcessSecretExternalPackageRepository.class));
        service.setProductTypeRepository(products);
        mvc = MockMvcBuilders.standaloneSetup(new ProcessSecretController(service, mock(ProcessSecretExternalService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    void product(String id, String name) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setFactoryId(F);
        p.setName(name);
        when(products.findByIdAndFactoryId(id, F)).thenReturn(Optional.of(p));
        ProcessSecretPolicy policy = ProcessSecretPolicy.builder().id(1L).factoryId(F).productTypeId(id)
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(OWNER).policyVersion(3L)
                .createdBy(OWNER).build();
        when(policies.findForUpdate(F, id)).thenReturn(Optional.of(policy));
    }

    MockHttpServletRequestBuilder save(String productTypeId, String aliasesJson) {
        String body = "{\"storageMode\":\"CLOUD_RESTRICTED\",\"ownerUserId\":" + OWNER + ",\"expectedPolicyVersion\":3"
                + (aliasesJson == null ? "" : ",\"aliases\":" + aliasesJson) + "}";
        return put("/api/mobile/" + F + "/process-secrets/policies/" + productTypeId)
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE,
                        new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, OWNER, F, "factory_super_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    void savingAProductWhoseFullNameIsACategoryWordSucceedsWithAWarning() throws Exception {
        mvc.perform(save("P-NJ", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.policyVersion").value(4))
                .andExpect(jsonPath("$.data.warnings", contains(WARNING)));
    }

    @Test
    void aProductWithAnOwnNameGetsNoWarning() throws Exception {
        mvc.perform(save("P-SX", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings", empty()));
    }

    @Test
    void aCategoryWordAliasIsRejectedWith400AndNothingIsWritten() throws Exception {
        mvc.perform(save("P-SX", "[\"五香肘\", \"鸭脖\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("是品类词")));
        mvc.perform(save("P-SX", "[\"20251103\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("纯数字")));
        verify(policies, never()).save(any(ProcessSecretPolicy.class));
    }
}
