package com.cretas.aims.controller;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.issue.IssueDispositionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 「发现的问题」处置接口的工厂边界: 登录账号不属于路径里的工厂 ⇒ 403, 且服务层一次都没被调用 (不读、不写)。
 */
class IssueDispositionControllerContractTest {

    private final IssueDispositionService service = mock(IssueDispositionService.class);
    private final IssueDispositionController controller = new IssueDispositionController(service);

    private static MockHttpServletRequest loggedInTo(String factoryId) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 71L, factoryId, "factory_super_admin"));
        return r;
    }

    @Test
    void anAccountFromAnotherFactoryIsForbiddenOnEveryEndpoint() {
        MockHttpServletRequest other = loggedInTo("F-OTHER");
        var ref = new IssueDispositionController.RefRequest("DELIVERY_RISK", "r1", "交付风险", null);
        var close = new IssueDispositionController.CloseRequest("DELIVERY_RISK", "r1", "交付风险", null, "HANDLED", "处理好了");

        assertForbidden(() -> controller.list("F1", other));
        assertForbidden(() -> controller.assignedToMe("F1", other));
        assertForbidden(() -> controller.close("F1", close, other));
        assertForbidden(() -> controller.convert("F1", ref, other));
        assertForbidden(() -> controller.reopen("F1", ref, other));
        verifyNoInteractions(service);
    }

    @Test
    void positiveControl_theSameAccountOnItsOwnFactoryReachesTheService() {
        var res = controller.list("F-OTHER", loggedInTo("F-OTHER"));
        assertThat(res.getSuccess()).isTrue();
    }

    private static void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
    }
}
