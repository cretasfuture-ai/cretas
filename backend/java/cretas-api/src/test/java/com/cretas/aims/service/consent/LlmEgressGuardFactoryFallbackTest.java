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

    @Test
    void noRequestAndNoTrustedActorMeansNoFactoryLookup() {
        assertThat(guard.texts("t", null, SAID).text(0)).contains("秘制酱牛肉");
        verify(processSecret, never()).protectedProductNames(anyString());
    }
}
