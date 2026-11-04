package com.cretas.aims.security;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService.Decision;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService.Outcome;
import com.cretas.aims.service.processsecret.ProcessSecretPayloadScrubber;
import com.cretas.aims.service.processsecret.ProcessSecretTestHooks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审查 #4 / #7: HTTP 出口 (ResponseAdvice) 也按每一行的工厂判 —— 路径工厂、身份工厂、读数都没有生效保密时,
 * 输出里别家工厂的行照样裁 (工厂账号与平台账号两条分支都测); 库里任何工厂都没有保密策略时才整份跳过。
 */
class ProcessSecretResponseAdviceRowFactoryContractTest {

    static final String PROTECTED = "F-ROW-A";
    static final String PLAIN = "F-ROW-B";
    static final String SECRET = "P-ROW";

    ProcessSecretAccessService access;
    ProcessSecretResponseAdvice advice;
    final ObjectMapper json = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @BeforeEach
    void setUp() {
        access = mock(ProcessSecretAccessService.class);
        BomRecipeRepository recipes = mock(BomRecipeRepository.class);
        advice = new ProcessSecretResponseAdvice(access, recipes, mock(ProductionPlanRepository.class),
                ProcessSecretTestHooks.scrubber(access, recipes, mock(ProductionWorkflowInstanceRepository.class),
                        mock(ProductionPlanRepository.class)), json);
        when(access.protectedProductTypeIds(PROTECTED)).thenReturn(Set.of(SECRET));
        when(access.decide(any(), eq(PROTECTED), eq(SECRET), eq(ProcessSecretAction.VIEW_FULL)))
                .thenReturn(new Decision(Outcome.DENY, ProcessSecretStorageMode.CLOUD_RESTRICTED, "x"));
        when(access.audit(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
    }

    static ApiResponse<List<Map<String, Object>>> rows() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("factoryId", PROTECTED);
        a.put("productTypeId", SECRET);
        a.put("conversionRate", 0.8);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("factoryId", PLAIN);
        b.put("productTypeId", "P-PLAIN");
        b.put("conversionRate", 0.7);
        return ApiResponse.success(List.of(a, b));
    }

    Object write(Object body, AuthenticatedActor actor) {
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/api/mobile/" + PLAIN + "/rows");
        raw.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        raw.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("factoryId", PLAIN));
        raw.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/mobile/{factoryId}/rows");
        return advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, new ServletServerHttpRequest(raw), null);
    }

    @Test
    void aFactoryAccountsOutputIsScrubbedPerRowFactoryEvenWhenItsOwnFactoryIsUnprotected() {
        JsonNode data = json.valueToTree(write(rows(), new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 5L,
                PLAIN, "operator"))).get("data");
        assertThat(data.get(0).get("conversionRate").isNull()).as("受保护工厂那一行被裁").isTrue();
        assertThat(data.get(0).has("processSecret")).isTrue();
        assertThat(data.get(1).get("conversionRate").asDouble()).as("本厂那一行照常").isEqualTo(0.7);
    }

    @Test
    void thePlatformBranchIsScrubbedPerRowFactory() {
        JsonNode data = json.valueToTree(write(rows(), new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L,
                "PLATFORM", "super_admin"))).get("data");
        assertThat(data.get(0).get("conversionRate").isNull()).isTrue();
        assertThat(data.get(1).get("conversionRate").asDouble()).isEqualTo(0.7);
    }

    /**
     * 审查跟进 2b: 原来只断言「返回同一个对象」, 而 scrubber 什么都没裁时也返回同一个对象 —— 快路径删掉也绿。
     * 现在用 mock scrubber 断言快路径上 scrubber 根本没被调用; 对照组 (库里有策略) 必须调用。
     */
    @Test
    void theWholeOutputIsSkippedOnlyWhenNoFactoryHasAnyPolicy() {
        ProcessSecretPayloadScrubber scrubber = mock(ProcessSecretPayloadScrubber.class);
        advice = new ProcessSecretResponseAdvice(access, mock(BomRecipeRepository.class),
                mock(ProductionPlanRepository.class), scrubber, json);
        when(access.protectedProductTypeIds(PROTECTED)).thenReturn(Set.of());
        AuthenticatedActor operator = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 5L, PLAIN, "operator");

        when(access.noProcessSecretPolicyAnywhere()).thenReturn(true);
        ApiResponse<List<Map<String, Object>>> body = rows();
        assertThat(write(body, operator)).isSameAs(body);
        verify(scrubber, never()).scrub(any(), any(), any(), any(), any(), anyBoolean());

        when(access.noProcessSecretPolicyAnywhere()).thenReturn(false);
        write(rows(), operator);
        verify(scrubber, times(1)).scrub(any(), any(), any(), any(), any(), anyBoolean());
    }
}
