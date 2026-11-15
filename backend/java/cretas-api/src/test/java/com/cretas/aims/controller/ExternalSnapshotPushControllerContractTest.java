package com.cretas.aims.controller;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.integration.ExternalIntegrationService;
import com.cretas.aims.service.integration.ExternalSnapshotPushLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 推送入口本身的行为 (服务层 mock, 限流器是真的 {@link ExternalSnapshotPushLimiter}):
 * 凭证先验, 无效 = 401 且不读请求体、不占限流桶; 限流按授权行分桶 —— 伪造任意 X-Forwarded-For 的匿名洪泛
 * 挡不住合法凭证 (复审中-新1); 同一授权超过上限才 429, 别的授权不受影响;
 * 超过字节上限在反序列化前拒收 (审查低1); 并发撞唯一约束重试一次 (审查低5)。
 */
class ExternalSnapshotPushControllerContractTest {

    static final String URL = "/api/public/external-snapshots/push";
    static final String TOKEN_A = "cxs_test-only-authorization-a";
    static final String TOKEN_B = "cxs_test-only-authorization-b";
    static final String BODY = "{\"system\":\"blacklake\",\"sourceOrgId\":\"1\",\"collectedAt\":\"2026-11-15T00:01:00Z\","
            + "\"datasets\":{\"report_output\":[{\"reportDate\":\"2026-11-14\",\"goodQty\":\"1\"}]}}";
    static final ExternalIntegrationService.PushResult OK = new ExternalIntegrationService.PushResult("F1", "blacklake",
            "2026-11-15 08:01", List.of(new ExternalIntegrationService.PushedDataset("report_output", 1, true)));

    final ExternalIntegrationService service = mock(ExternalIntegrationService.class);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        doThrow(new BusinessException(401, "推送凭证缺失或无效")).when(service).authenticatePush(any());
        doReturn(new ExternalIntegrationService.PushPrincipal("F1", "blacklake")).when(service).authenticatePush(TOKEN_A);
        doReturn(new ExternalIntegrationService.PushPrincipal("F2", "blacklake")).when(service).authenticatePush(TOKEN_B);
        when(service.push(anyString(), any())).thenReturn(OK);
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
        mvc = MockMvcBuilders.standaloneSetup(
                        new ExternalSnapshotPushController(service, mapper, new ExternalSnapshotPushLimiter()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private static MockHttpServletRequestBuilder push(String token, byte[] body) {
        MockHttpServletRequestBuilder b = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
        return token == null ? b : b.header(ExternalSnapshotPushController.TOKEN_HEADER, token);
    }

    private static byte[] body() {
        return BODY.getBytes(StandardCharsets.UTF_8);
    }

    private int statusOf(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void missingOrInvalidTokenIs401BeforeTheBodyIsParsed() throws Exception {
        mvc.perform(push(null, "not json at all".getBytes(StandardCharsets.UTF_8))).andExpect(status().isUnauthorized());
        mvc.perform(push("cxs_guessed", "not json at all".getBytes(StandardCharsets.UTF_8))).andExpect(status().isUnauthorized());
        verify(service, never()).push(anyString(), any());
    }

    @Test
    void anAnonymousFloodWithForgedForwardedForDoesNotBlockTheLegitimatePush() throws Exception {
        for (int i = 0; i < 200; i++) {
            // 一半不带 XFF (Java 看来都是 127.0.0.1), 一半每次伪造一个新 XFF
            MockHttpServletRequestBuilder junk = push("cxs_junk-" + i, body());
            if (i % 2 == 0) junk = junk.header("X-Forwarded-For", "10.0." + (i % 250) + ".1");
            assertThat(statusOf(junk)).as("junk #" + i).isEqualTo(401);
        }
        // 与洪泛同一来源 (无 XFF / 同一个 XFF) 的合法凭证照常推上去
        mvc.perform(push(TOKEN_A, body())).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(push(TOKEN_A, body()).header("X-Forwarded-For", "10.0.0.1")).andExpect(status().isOk());
        verify(service, times(2)).push(eq(TOKEN_A), any());
    }

    @Test
    void onlyTheSameAuthorizationOverTheLimitGets429WhateverItsForwardedFor() throws Exception {
        int ok = 0;
        for (int i = 0; i < ExternalSnapshotPushLimiter.PUSHES_PER_WINDOW; i++) {
            // 换 XFF 不能多拿额度: 桶跟着授权走
            if (statusOf(push(TOKEN_A, body()).header("X-Forwarded-For", "203.0.113." + i)) == 200) ok++;
        }
        assertThat(ok).isEqualTo(ExternalSnapshotPushLimiter.PUSHES_PER_WINDOW);
        mvc.perform(push(TOKEN_A, body()).header("X-Forwarded-For", "198.51.100.7"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.success").value(false));
        // 另一家授权不受影响
        mvc.perform(push(TOKEN_B, body())).andExpect(status().isOk());
        verify(service, times(ExternalSnapshotPushLimiter.PUSHES_PER_WINDOW)).push(eq(TOKEN_A), any());
    }

    @Test
    void aBodyOverTheByteLimitIsRefusedWith413AndNeverReachesTheService() throws Exception {
        byte[] big = new byte[ExternalIntegrationService.MAX_PUSH_BODY_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        // 业务码 413; GlobalExceptionHandler 把不在映射表里的业务码按 HTTP 400 发 (与行数超限同一形状)
        mvc.perform(push(TOKEN_A, big)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(413));
        verify(service, never()).push(anyString(), any());
        // 正好在上限上的合法 JSON 照常进服务层 (上限不是「少一个字节」的错位)
        byte[] json = body();
        byte[] exact = new byte[ExternalIntegrationService.MAX_PUSH_BODY_BYTES];
        java.util.Arrays.fill(exact, (byte) ' ');
        System.arraycopy(json, 0, exact, 0, json.length);
        mvc.perform(push(TOKEN_A, exact)).andExpect(status().isOk());
        verify(service, times(1)).push(anyString(), any());
    }

    @Test
    void aConcurrentDuplicateIsRetriedOnceAndAnsweredAsIdempotent() throws Exception {
        when(service.push(anyString(), any()))
                .thenThrow(new DataIntegrityViolationException("uq_ess_point"))
                .thenReturn(OK);
        mvc.perform(push(TOKEN_A, body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.datasets[0].duplicate").value(true));
        verify(service, times(2)).push(anyString(), any());
    }
}
