package com.cretas.aims.service.consent;

import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调用点没带工厂时, 保密产品名按「本次执行」的工厂查: 请求线程取登录 token 里的工厂,
 * 流式对话的 SSE 工作线程 (没有 Servlet 请求) 取控制器绑上的可信身份。
 * 审查发现: 只认请求属性时, 流式里走到的参数抽取 / 消歧 / 选工具等出口查不到工厂, 保密产品名原样外发。
 */
class LlmEgressGuardFactoryFallbackTest {

    private static final String SAID = "秘制酱牛肉入库 50 公斤";

    private ProcessSecretSampleFilter processSecret;
    private LlmEgressGuard guard;

    @BeforeEach
    void setUp() {
        processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames("F006")).thenReturn(Set.of("秘制酱牛肉"));
        guard = new LlmEgressGuard(new ObjectMapper(), processSecret);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void requestThreadUsesTheFactoryFromTheLoginToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("factoryId", "F006");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(guard.texts("t", null, SAID).text(0)).doesNotContain("秘制酱牛肉").contains("[保密产品A]");
    }

    @Test
    void sseWorkerThreadUsesTheTrustedActorBoundByTheController() {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 77L, "F006", "factory_super_admin"))) {
            assertThat(guard.texts("t", null, SAID).text(0)).doesNotContain("秘制酱牛肉").contains("[保密产品A]");
        }
    }

    /**
     * Steve 2026-10-29: 不知道按哪家工厂脱敏时不发 (以前名单为空、原文照发 —— 本测试原来钉的正是那个旧行为)。
     * 守的性质: 「没有工厂上下文 → 外发被拒、原文不出去」, 而不是某个字面输出。
     */
    @Test
    void noRequestAndNoTrustedActorIsRefusedInsteadOfSentUnmasked() {
        LlmEgressRedactor.Result r = guard.texts("t", null, SAID);
        assertThat(r.refused()).isTrue();
        assertThat(r.refusal()).isEqualTo("没有工厂上下文");
        verify(processSecret, never()).protectedProductNames(anyString());
    }

    @Test
    void platformActorAnalysingAFactoryWithExplicitFactoryGetsThatFactorysNamesMasked() {
        bindPlatformRequest(null);
        LlmEgressRedactor.Result r = guard.texts("t", "F006", SAID);
        assertThat(r.refused()).isFalse();
        assertThat(r.text(0)).doesNotContain("秘制酱牛肉").contains("[保密产品A]");
    }

    @Test
    void platformActorWithoutExplicitFactoryIsRefusedEvenIfTheTokenCarriesAFactory() {
        // 平台账号的 token 里即使带了某个工厂, 也不能代表它此刻在分析的那家: 不按 token 猜
        bindPlatformRequest("F006");
        LlmEgressRedactor.Result r = guard.texts("t", null, SAID);
        assertThat(r.refused()).isTrue();
        verify(processSecret, never()).protectedProductNames(anyString());
    }

    @Test
    void platformActorOnTheSseWorkerThreadWithoutExplicitFactoryIsRefused() {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "F006", "super_admin"))) {
            assertThat(guard.texts("t", null, SAID).refused()).isTrue();
            assertThat(guard.texts("t", "F006", SAID).text(0)).contains("[保密产品A]");
        }
    }

    @Test
    void factoryUserExplicitFactoryIsUnchanged() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("factoryId", "F006");
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 77L, "F006", "factory_super_admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(guard.texts("t", "F006", SAID).text(0)).doesNotContain("秘制酱牛肉").contains("[保密产品A]");
        assertThat(guard.texts("t", null, SAID).text(0)).doesNotContain("秘制酱牛肉").contains("[保密产品A]");
    }

    @Test
    void standaloneInstanceForUnitTestsStillSendsWithoutFactory() {
        // 单测直接 new 被测类用的默认实例: 不查保密产品名, 也不因没有工厂而拒绝 (生产实例都由容器注入)
        assertThat(LlmEgressGuard.standalone().texts("t", null, "今天入库多少").refused()).isFalse();
    }

    private static void bindPlatformRequest(String tokenFactory) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (tokenFactory != null) {
            request.setAttribute("factoryId", tokenFactory);
        }
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, tokenFactory, "super_admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
