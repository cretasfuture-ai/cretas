package com.cretas.aims.audit;

import com.cretas.aims.entity.audit.PlatformDataAccessLog;
import com.cretas.aims.repository.audit.PlatformDataAccessLogRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台人员读取工厂数据留痕: 平台身份 (平台令牌 / PLATFORM 工厂令牌 / 平台 bypass 角色)
 * 访问某工厂的接口 —— 含读请求、含被后续守卫拒绝的请求 —— 都写一行; 工厂用户访问本厂不写;
 * 查询参数只记名字不记值; 写库失败或队列满都不影响业务请求。
 */
class PlatformDataAccessLogSecurityTest {

    PlatformDataAccessLogWriter writer;
    PlatformDataAccessLogInterceptor interceptor;

    @BeforeEach
    void setUp() {
        writer = mock(PlatformDataAccessLogWriter.class);
        interceptor = new PlatformDataAccessLogInterceptor(writer);
    }

    static MockHttpServletRequest request(String method, String uri, String pathFactory, String query) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        if (pathFactory != null) {
            r.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("factoryId", pathFactory));
        }
        r.setQueryString(query);
        return r;
    }

    static void as(MockHttpServletRequest r, AuthenticatedActorType type, long id, String tokenFactory, String role) {
        r.setAttribute("userId", id);
        r.setAttribute("username", "u" + id);
        r.setAttribute("role", role);
        if (tokenFactory != null) r.setAttribute("factoryId", tokenFactory);
        r.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(type, id, tokenFactory, role));
    }

    PlatformDataAccessLog run(MockHttpServletRequest r, int status) {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        interceptor.preHandle(r, resp, null);
        resp.setStatus(status);
        interceptor.afterCompletion(r, resp, null, null);
        ArgumentCaptor<PlatformDataAccessLog> row = ArgumentCaptor.forClass(PlatformDataAccessLog.class);
        verify(writer, times(1)).submit(row.capture());
        return row.getValue();
    }

    @Test
    void platformAdminReadingAFactoryIsRecordedWithoutQueryValues() {
        MockHttpServletRequest r = request("GET", "/api/mobile/F006/bom/recipes", "F006",
                "status=ACTIVE&keyword=%E7%A7%98%E6%96%B9");
        as(r, AuthenticatedActorType.PLATFORM_ADMIN, 900L, null, "super_admin");
        PlatformDataAccessLog row = run(r, 200);
        assertThat(row.getTargetFactoryId()).isEqualTo("F006");
        assertThat(row.getTargetSource()).isEqualTo(PlatformDataAccessLog.TargetSource.PATH);
        assertThat(row.getActorType()).isEqualTo("PLATFORM_ADMIN");
        assertThat(row.getActorUserId()).isEqualTo(900L);
        assertThat(row.getHttpMethod()).isEqualTo("GET");
        assertThat(row.getResponseStatus()).isEqualTo(200);
        assertThat(row.getQueryParamNames()).isEqualTo("status,keyword");
        assertThat(row.toString()).doesNotContain("ACTIVE").doesNotContain("秘方");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void refusedPlatformRequestIsStillRecorded() {
        MockHttpServletRequest r = request("GET", "/api/mobile/F006/bom/recipes/r1/seasoning", "F006", null);
        as(r, AuthenticatedActorType.PLATFORM_ADMIN, 900L, null, "developer");
        assertThat(run(r, 403).getResponseStatus()).isEqualTo(403);
    }

    @Test
    void legacyPlatformFactoryTokenAndBypassRoleOnFactoryTokenAreRecorded() {
        MockHttpServletRequest legacy = request("GET", "/api/mobile/F006/x", "F006", null);
        as(legacy, AuthenticatedActorType.FACTORY_USER, 5L, "PLATFORM", "operator");
        assertThat(run(legacy, 200).getActorFactoryId()).isEqualTo("PLATFORM");

        writer = mock(PlatformDataAccessLogWriter.class);
        interceptor = new PlatformDataAccessLogInterceptor(writer);
        MockHttpServletRequest bypass = request("GET", "/api/mobile/F006/x", "F006", null);
        as(bypass, AuthenticatedActorType.FACTORY_USER, 6L, "F001", "platform_admin");
        assertThat(run(bypass, 200).getActorRole()).isEqualTo("platform_admin");
    }

    @Test
    void queryParameterTargetIsRecorded() {
        MockHttpServletRequest r = request("GET", "/api/platform/smartbi-config", null, "factoryId=F006&size=20");
        as(r, AuthenticatedActorType.PLATFORM_ADMIN, 900L, null, "platform_admin");
        PlatformDataAccessLog row = run(r, 200);
        assertThat(row.getTargetFactoryId()).isEqualTo("F006");
        assertThat(row.getTargetSource()).isEqualTo(PlatformDataAccessLog.TargetSource.QUERY);
    }

    @Test
    void factoryUserReadingOwnFactoryIsNotRecorded() {
        // 阴性对照: 工厂自己的账号读本厂, 审计表不增加
        MockHttpServletRequest r = request("GET", "/api/mobile/F006/bom/recipes", "F006", null);
        as(r, AuthenticatedActorType.FACTORY_USER, 10L, "F006", "factory_super_admin");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        interceptor.preHandle(r, resp, null);
        interceptor.afterCompletion(r, resp, null, null);
        verify(writer, never()).submit(any());
    }

    @Test
    void platformRoleOnAFactoryTokenReadingItsOwnFactoryIsNotRecorded() {
        // 带平台 bypass 角色的工厂令牌访问的是自己令牌上的工厂: 那是本厂, 不算「平台读别厂」
        MockHttpServletRequest own = request("GET", "/api/mobile/F006/x", "F006", null);
        as(own, AuthenticatedActorType.FACTORY_USER, 6L, "F006", "platform_admin");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        interceptor.preHandle(own, resp, null);
        interceptor.afterCompletion(own, resp, null, null);
        verify(writer, never()).submit(any());
    }

    @Test
    void asyncRequestIsRecordedOnceNotTwice() {
        MockHttpServletRequest r = request("GET", "/api/mobile/F006/ai/stream", "F006", null);
        as(r, AuthenticatedActorType.PLATFORM_ADMIN, 900L, null, "super_admin");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        interceptor.preHandle(r, resp, null);
        interceptor.afterConcurrentHandlingStarted(r, resp, null);
        interceptor.afterCompletion(r, resp, null, null);
        verify(writer, times(1)).submit(any());
    }

    @Test
    void writerNeverFailsTheRequestWhenQueueIsFullOrDatabaseFails() {
        PlatformDataAccessLogRepository repo = mock(PlatformDataAccessLogRepository.class);
        Executor rejecting = command -> { throw new RejectedExecutionException("full"); };
        PlatformDataAccessLogWriter full = new PlatformDataAccessLogWriter(repo, rejecting);
        assertThatCode(() -> full.submit(PlatformDataAccessLog.builder().targetFactoryId("F006").build()))
                .doesNotThrowAnyException();

        when(repo.save(any())).thenThrow(new IllegalStateException("db down"));
        PlatformDataAccessLogWriter inline = new PlatformDataAccessLogWriter(repo, Runnable::run);
        assertThatCode(() -> inline.submit(PlatformDataAccessLog.builder().targetFactoryId("F006").build()))
                .doesNotThrowAnyException();
        verify(repo).save(any());
    }

    @Test
    void writerPersistsTheRow() {
        PlatformDataAccessLogRepository repo = mock(PlatformDataAccessLogRepository.class);
        PlatformDataAccessLogWriter inline = new PlatformDataAccessLogWriter(repo, Runnable::run);
        PlatformDataAccessLog row = PlatformDataAccessLog.builder().targetFactoryId("F006").build();
        inline.submit(row);
        verify(repo).save(row);
    }
}
