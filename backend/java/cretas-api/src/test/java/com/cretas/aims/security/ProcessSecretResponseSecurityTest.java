package com.cretas.aims.security;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService.Decision;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 响应裁剪: 受保护产品的配方 / 明细 / 工艺 DTO 不论嵌在哪一层, 无权者只拿到带 RESTRICTED 标记的摘要;
 * 有权者、未受保护产品原样返回。用的是真 Jackson 序列化, 断言落在最终 JSON 上。
 */
class ProcessSecretResponseSecurityTest {

    static final String F = "F-A";
    static final String SECRET = "P17";
    static final String PLAIN = "PLAIN";

    ProcessSecretAccessService access;
    BomRecipeRepository recipes;
    ProcessSecretResponseAdvice advice;
    ObjectMapper json;

    @BeforeEach
    void setUp() {
        access = mock(ProcessSecretAccessService.class);
        recipes = mock(BomRecipeRepository.class);
        json = new ObjectMapper().registerModule(new JavaTimeModule());
        advice = new ProcessSecretResponseAdvice(access, recipes, mock(com.cretas.aims.repository.ProductionPlanRepository.class), new com.cretas.aims.service.processsecret.ProcessSecretPayloadScrubber(access, recipes, org.mockito.Mockito.mock(com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository.class), org.mockito.Mockito.mock(com.cretas.aims.repository.ProductionPlanRepository.class)), json);
        when(access.protectedProductTypeIds(F)).thenReturn(Set.of(SECRET));
        when(access.audit(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
    }

    ServletServerHttpRequest request(AuthenticatedActor actor) {
        MockHttpServletRequest raw = new MockHttpServletRequest("GET", "/api/mobile/F-A/bom/recipes");
        if (actor != null) raw.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        return new ServletServerHttpRequest(raw);
    }

    static AuthenticatedActor user(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, id, F, "operator");
    }

    void decision(long userId, Outcome outcome) {
        when(access.decide(eq(user(userId)), eq(F), eq(SECRET), eq(ProcessSecretAction.VIEW_FULL)))
                .thenReturn(new Decision(outcome, ProcessSecretStorageMode.CLOUD_RESTRICTED, "x"));
    }

    static BomRecipe recipe(String id, String product) {
        BomRecipe r = new BomRecipe();
        r.setId(id);
        r.setFactoryId(F);
        r.setProductTypeId(product);
        r.setProductName("产品" + product);
        r.setRecipeCode("R-" + id);
        r.setVersion(3);
        r.setOverallYieldRate(new BigDecimal("0.8731"));
        BomRecipeItem item = new BomRecipeItem();
        item.setId(7L);
        item.setRecipeId(id);
        item.setFactoryId(F);
        item.setMaterialName("秘方香料-" + product);
        item.setStandardQuantity(new BigDecimal("3.25"));
        r.setItems(List.of(item));
        return r;
    }

    JsonNode write(Object body, AuthenticatedActor actor) {
        Object out = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request(actor), null);
        return json.valueToTree(out);
    }

    @Test
    void restrictedCallerGetsSummaryWithExplicitMarkerAndNoRecipeContent() {
        decision(5, Outcome.DENY);
        ApiResponse<BomRecipe> body = ApiResponse.success(recipe("r1", SECRET));
        JsonNode data = write(body, user(5)).get("data");
        assertThat(data.get("productTypeId").asText()).isEqualTo(SECRET);
        assertThat(data.get("processSecret").get("visibility").asText()).isEqualTo("RESTRICTED");
        String text = data.toString();
        assertThat(text).doesNotContain("秘方香料").doesNotContain("3.25").doesNotContain("0.8731")
                .doesNotContain("items");
    }

    @Test
    void authorizedCallerAndUnprotectedProductPassThroughUnchanged() {
        decision(10, Outcome.ALLOW);
        ApiResponse<BomRecipe> secret = ApiResponse.success(recipe("r1", SECRET));
        Object out = advice.beforeBodyWrite(secret, null, MediaType.APPLICATION_JSON, null, request(user(10)), null);
        assertThat(out).isSameAs(secret);
        assertThat(secret.getData()).isInstanceOf(BomRecipe.class);

        ApiResponse<BomRecipe> plain = ApiResponse.success(recipe("r2", PLAIN));
        Object out2 = advice.beforeBodyWrite(plain, null, MediaType.APPLICATION_JSON, null, request(user(5)), null);
        assertThat(((ApiResponse<?>) out2).getData()).isInstanceOf(BomRecipe.class);
        verify(access, never()).decide(any(), eq(F), eq(PLAIN), any());
    }

    @Test
    void factoryWithoutAnyPolicyTakesFastPathWithoutSerializing() {
        when(access.protectedProductTypeIds(F)).thenReturn(Set.of());
        ApiResponse<BomRecipe> body = ApiResponse.success(recipe("r1", SECRET));
        Object out = advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request(user(5)), null);
        assertThat(out).isSameAs(body);
        assertThat(body.getData()).isInstanceOf(BomRecipe.class);
        verify(access, never()).decide(any(), any(), any(), any());
    }

    @Test
    void nestedInPageAndMapOnlyTheProtectedEntryIsSummarised() {
        decision(5, Outcome.DENY);
        PageImpl<BomRecipe> page = new PageImpl<>(List.of(recipe("r1", SECRET), recipe("r2", PLAIN)),
                org.springframework.data.domain.PageRequest.of(0, 10), 2);
        JsonNode data = write(ApiResponse.success(Map.of("page", page)), user(5)).get("data");
        JsonNode content = data.get("page").get("content");
        assertThat(content.get(0).get("processSecret").get("visibility").asText()).isEqualTo("RESTRICTED");
        assertThat(content.get(0).toString()).doesNotContain("秘方香料");
        assertThat(content.get(1).has("processSecret")).isFalse();
        assertThat(content.get(1).toString()).contains("秘方香料-PLAIN");
    }

    @Test
    void standaloneItemIsResolvedThroughItsRecipe() {
        decision(5, Outcome.DENY);
        BomRecipe owner = recipe("r1", SECRET);
        when(recipes.findById("r1")).thenReturn(Optional.of(owner));
        JsonNode data = write(ApiResponse.success(owner.getItems()), user(5)).get("data");
        assertThat(data.get(0).get("processSecret").get("visibility").asText()).isEqualTo("RESTRICTED");
        assertThat(data.toString()).doesNotContain("秘方香料").doesNotContain("3.25");
    }

    @Test
    void workflowDtoNodesAreHidden() {
        decision(5, Outcome.DENY);
        ProductProcessWorkflowDTO dto = new ProductProcessWorkflowDTO();
        dto.setId(3L);
        dto.setFactoryId(F);
        dto.setProductTypeId(SECRET);
        dto.setStatus("PUBLISHED");
        ProductProcessWorkflowDTO.Node node = new ProductProcessWorkflowDTO.Node();
        node.setId("n1");
        node.setKind("PROCESS");
        node.setData(Map.of("processName", "低温慢煮 62℃ 40min"));
        dto.setNodes(List.of(node));
        JsonNode data = write(ApiResponse.success(dto), user(5)).get("data");
        assertThat(data.get("processSecret").get("visibility").asText()).isEqualTo("RESTRICTED");
        assertThat(data.toString()).doesNotContain("低温慢煮").doesNotContain("nodes");
    }

    @Test
    void unauthenticatedOrNonJsonResponsesAreUntouched() {
        ApiResponse<BomRecipe> body = ApiResponse.success(recipe("r1", SECRET));
        assertThat(advice.beforeBodyWrite(body, null, MediaType.APPLICATION_JSON, null, request(null), null))
                .isSameAs(body);
        assertThat(advice.beforeBodyWrite(body, null, MediaType.APPLICATION_OCTET_STREAM, null,
                request(user(5)), null)).isSameAs(body);
    }
}
