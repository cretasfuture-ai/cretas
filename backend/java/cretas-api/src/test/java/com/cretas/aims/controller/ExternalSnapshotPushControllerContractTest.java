package com.cretas.aims.controller;

import com.cretas.aims.aspect.RateLimitAspect;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.integration.ExternalIntegrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审查低1 / 低5: 推送入口在反序列化之前先拒「没有凭证」和「超过字节上限」, 按 IP 限流 (真实 {@link RateLimitAspect});
 * 并发推送撞唯一约束时重试一次。服务层是 mock —— 这里只守入口本身的行为。
 */
class ExternalSnapshotPushControllerContractTest {

    static final String URL = "/api/public/external-snapshots/push";
    static final String TOKEN = "cxs_test-only-not-a-real-token";
    static final String BODY = "{\"system\":\"blacklake\",\"sourceOrgId\":\"1\",\"collectedAt\":\"2026-11-15T00:01:00Z\","
            + "\"datasets\":{\"report_output\":[{\"reportDate\":\"2026-11-14\",\"goodQty\":\"1\"}]}}";
    static final ExternalIntegrationService.PushResult OK = new ExternalIntegrationService.PushResult("F1", "blacklake",
            "2026-11-15 08:01", List.of(new ExternalIntegrationService.PushedDataset("report_output", 1, true)));

    final ExternalIntegrationService service = mock(ExternalIntegrationService.class);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build();
        AspectJProxyFactory factory = new AspectJProxyFactory(new ExternalSnapshotPushController(service, mapper));
        factory.addAspect(new RateLimitAspect());
        ExternalSnapshotPushController proxy = factory.getProxy();
        mvc = MockMvcBuilders.standaloneSetup(proxy)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    private static MockHttpServletRequestBuilder push(String token, byte[] body) {
        MockHttpServletRequestBuilder b = post(URL).contentType(MediaType.APPLICATION_JSON).content(body);
        return token == null ? b : b.header(ExternalSnapshotPushController.TOKEN_HEADER, token);
    }

    @Test
    void missingOrMalformedTokenIsRefusedBeforeTheBodyIsParsed() throws Exception {
        mvc.perform(push(null, "not json at all".getBytes(StandardCharsets.UTF_8))).andExpect(status().isUnauthorized());
        mvc.perform(push("Bearer x", "not json at all".getBytes(StandardCharsets.UTF_8))).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void aBodyOverTheByteLimitIsRefusedWith413AndNeverReachesTheService() throws Exception {
        byte[] big = new byte[ExternalIntegrationService.MAX_PUSH_BODY_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        // 业务码 413; GlobalExceptionHandler 把不在映射表里的业务码按 HTTP 400 发 (与行数超限同一形状)
        mvc.perform(push(TOKEN, big)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(413));
        verifyNoInteractions(service);
        // 正好在上限上的合法 JSON 照常进服务层 (上限不是「少一个字节」的错位)
        byte[] json = BODY.getBytes(StandardCharsets.UTF_8);
        byte[] exact = new byte[ExternalIntegrationService.MAX_PUSH_BODY_BYTES];
        java.util.Arrays.fill(exact, (byte) ' ');
        System.arraycopy(json, 0, exact, 0, json.length);
        when(service.push(anyString(), any())).thenReturn(OK);
        mvc.perform(push(TOKEN, exact)).andExpect(status().isOk());
        verify(service, times(1)).push(anyString(), any());
    }

    @Test
    void aConcurrentDuplicateIsRetriedOnceAndAnsweredAsIdempotent() throws Exception {
        when(service.push(anyString(), any()))
                .thenThrow(new DataIntegrityViolationException("uq_ess_point"))
                .thenReturn(OK);
        mvc.perform(push(TOKEN, BODY.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.datasets[0].duplicate").value(true));
        verify(service, times(2)).push(anyString(), any());
    }

    @Test
    void pushesAreRateLimitedPerIp() throws Exception {
        when(service.push(anyString(), any())).thenReturn(OK);
        int ok = 0;
        int limited = 0;
        for (int i = 0; i < 31; i++) {
            int code = mvc.perform(push(TOKEN, BODY.getBytes(StandardCharsets.UTF_8))).andReturn().getResponse().getStatus();
            if (code == 200) ok++;
            if (code == 429) limited++;
        }
        assertThat(ok).isEqualTo(30);
        assertThat(limited).isEqualTo(1);
    }
}
