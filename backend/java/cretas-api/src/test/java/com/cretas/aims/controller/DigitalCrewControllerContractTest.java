package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.ai.IntentConfirmationRequest;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.crew.DigitalCrewService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 数字班组接口的边界: 别厂账号每个接口 403 且服务层零调用; 确认人身份只从会话取 (请求体没有身份字段);
 * 写入口 (预览 / 确认) 的粗筛只认 read_write, 配置写入只认 system:read_write。
 */
class DigitalCrewControllerContractTest {

    private final DigitalCrewService service = mock(DigitalCrewService.class);
    private final DigitalCrewController controller = new DigitalCrewController(service);

    private static MockHttpServletRequest loggedInTo(String factoryId, long userId) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, factoryId, "operator"));
        return r;
    }

    private static IntentConfirmationRequest body() {
        return new IntentConfirmationRequest("a".repeat(64), Instant.now().plusSeconds(60), "req-12345678", "idem-12345678");
    }

    @Test
    void anAccountFromAnotherFactoryIsForbiddenEverywhereAndTheServiceIsNeverCalled() {
        MockHttpServletRequest other = loggedInTo("F-OTHER", 9L);
        assertForbidden(() -> controller.members("F1", other));
        assertForbidden(() -> controller.settings("F1", other));
        assertForbidden(() -> controller.updateMembers("F1", new DigitalCrewController.MembersRequest(Map.of("planner", false), "不用了"), other));
        assertForbidden(() -> controller.updateRoleMapping("F1", new DigitalCrewController.RoleMappingRequest(Map.of("operator", "keeper"), "换人"), other));
        assertForbidden(() -> controller.ask("F1", "planner", new DigitalCrewController.AskRequest("查计划", null), other));
        assertForbidden(() -> controller.inbox("F1", 0, 50, other));
        assertForbidden(() -> controller.preview("F1", 1L, other));
        assertForbidden(() -> controller.confirm("F1", 1L, "proof", body(), other));
        assertForbidden(() -> controller.reject("F1", 1L, new DigitalCrewController.RejectRequest("不办"), other));
        assertForbidden(() -> controller.ledger("F1", null, other));
        verifyNoInteractions(service);
    }

    @Test
    void confirmerIsTheSessionUser() {
        DigitalCrewService.ItemView done = new DigitalCrewService.ItemView(1L, "planner", "数字计划员", "X", "x", "t", "u",
                "production", "production:read_write", "DONE", "已办成", 1, null, "a", null, "b", null, null, null,
                "SUCCESS", null, null, true, false);
        org.mockito.Mockito.when(service.confirm(eq("F1"), any(), eq(1L), any())).thenReturn(done);
        controller.confirm("F1", 1L, "proof", body(), loggedInTo("F1", 42L));
        verify(service).confirm(eq("F1"), eq(new DigitalCrewService.Actor(AuthenticatedActorType.FACTORY_USER, 42L, "operator")),
                eq(1L), any());
    }

    @Test
    void writeEntryPointsOnlyAdmitReadWriteCodes() throws Exception {
        for (String name : new String[]{"preview", "confirm"}) {
            Method m = Arrays.stream(DigitalCrewController.class.getMethods()).filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            String[] codes = m.getAnnotation(RequirePermission.class).value();
            assertThat(codes).isNotEmpty().allMatch(c -> c.endsWith(":read_write"));
        }
        for (String name : new String[]{"updateMembers", "updateRoleMapping"}) {
            Method m = Arrays.stream(DigitalCrewController.class.getMethods()).filter(x -> x.getName().equals(name)).findFirst().orElseThrow();
            assertThat(m.getAnnotation(RequirePermission.class).value()).containsExactly("system:read_write");
        }
    }

    @Test
    void blankProofHeaderIsRejectedBeforeTheService() {
        assertThatThrownBy(() -> controller.confirm("F1", 1L, " ", body(), loggedInTo("F1", 42L)))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(service);
    }

    private static void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
    }
}
