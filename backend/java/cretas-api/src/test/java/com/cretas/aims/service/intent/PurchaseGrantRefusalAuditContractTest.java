package com.cretas.aims.service.intent;

import com.cretas.aims.entity.AIAuditLog;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PurchaseGrantRefusalAuditContractTest {
    EntityManager em;
    PurchaseGrantRefusalAuditService audit;
    @BeforeEach void setup() {
        em = mock(EntityManager.class); audit = new PurchaseGrantRefusalAuditService();
        ReflectionTestUtils.setField(audit, "entityManager", em);
    }
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }
    void actor(AuthenticatedActorType type, String factory) {
        var request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(type, 1698L, factory, "procurement_manager"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @Test void missingAndPlatformActorsNeverCreateNumericCollisionAudit() {
        assertThat(audit.record("F006", "preview-1", new BusinessException("拒绝"))).isEmpty();
        actor(AuthenticatedActorType.PLATFORM_ADMIN, null);
        assertThat(audit.record("F006", "preview-1", new BusinessException("拒绝"))).isEmpty();
        verifyNoInteractions(em);
    }
    @Test void actualTrustedFactoryOwnsEventAndServerCorrelationWithoutProofOrCommand() {
        actor(AuthenticatedActorType.FACTORY_USER, "F006");
        var id = audit.record("F006", "preview-175", new BusinessException("grant is revoked"));
        ArgumentCaptor<AIAuditLog> capture = ArgumentCaptor.forClass(AIAuditLog.class);
        verify(em).persist(capture.capture()); verify(em).flush();
        var event = capture.getValue();
        assertThat(event.getFactoryId()).isEqualTo("F006"); assertThat(event.getUserId()).isEqualTo(1698L);
        assertThat(event.getSessionId()).isEqualTo(id.orElseThrow()).startsWith("grant-refusal:");
        assertThat(event.getQuestionType()).isEqualTo("grant_refusal");
        assertThat(event.getQuestion()).isEqualTo("operation=DEFERRED_EXECUTE;attemptedGrant=preview-175");
        assertThat(event.getErrorMessage()).contains("grant is revoked");
        assertThat(event.getIsSuccess()).isFalse(); assertThat(event.getConsumedQuota()).isFalse();
    }
    @Test void mismatchedTenantNeverWritesOtherTenantOrCopiesItsAttemptedTarget() {
        actor(AuthenticatedActorType.FACTORY_USER, "F006");
        audit.record("FOREIGN", "preview-175", new BusinessException("拒绝"));
        ArgumentCaptor<AIAuditLog> capture = ArgumentCaptor.forClass(AIAuditLog.class);verify(em).persist(capture.capture());
        assertThat(capture.getValue().getFactoryId()).isEqualTo("F006");
        assertThat(capture.getValue().getQuestion()).doesNotContain("preview-175").contains("WITHHELD");
        assertThat(capture.getValue().getErrorMessage()).startsWith("REQUEST_SCOPE_MISMATCH");
    }

    @Test void actualControllerCannotClaimAuditSucceededWhenTheIndependentStoreFails() {
        actor(AuthenticatedActorType.FACTORY_USER, "F006");
        var core = mock(ControlledPurchaseTaskExecutionService.class);
        var writer = mock(PurchaseGrantRefusalAuditService.class);
        var original = new BusinessException(403, "grant is revoked");
        when(core.executeLater("F006", "preview-175", 2L)).thenThrow(original);
        when(writer.record("F006", "preview-175", original)).thenThrow(new IllegalStateException("audit store unavailable"));
        try (var context = controllerContext(core, writer)) {
            var controller = context.getBean(com.cretas.aims.controller.AiPurchaseTaskGrantController.class);
            Throwable failure = catchThrowable(() -> controller.executeLater("F006", "preview-175",
                    new com.cretas.aims.controller.AiPurchaseTaskGrantController.ExecuteLaterRequest(2L)));
            assertThat(failure).isInstanceOf(BusinessException.class).hasMessageContaining("审计");
            assertThat(((BusinessException) failure).getCode()).isEqualTo(503);
            assertThat(((BusinessException) failure).getErrorCode()).isEqualTo("GRANT_REFUSAL_AUDIT_UNAVAILABLE");
            verify(core).executeLater("F006", "preview-175", 2L); verifyNoMoreInteractions(core);
            verify(writer).record("F006", "preview-175", original);
        }
    }

    @Test void actualControllerPreservesRefusalAndPublishesOnlyTheCommittedServerCorrelation() {
        actor(AuthenticatedActorType.FACTORY_USER, "F006");
        var core = mock(ControlledPurchaseTaskExecutionService.class);
        var writer = mock(PurchaseGrantRefusalAuditService.class);
        var original = new BusinessException(403, "grant is revoked");
        when(core.executeLater("F006", "preview-175", 2L)).thenThrow(original);
        when(writer.record("F006", "preview-175", original)).thenReturn(java.util.Optional.of("grant-refusal:server-fixture"));
        try (var context = controllerContext(core, writer)) {
            var controller = context.getBean(com.cretas.aims.controller.AiPurchaseTaskGrantController.class);
            Throwable failure = catchThrowable(() -> controller.executeLater("F006", "preview-175",
                    new com.cretas.aims.controller.AiPurchaseTaskGrantController.ExecuteLaterRequest(2L)));
            assertThat(failure).isSameAs(original); assertThat(original.getCode()).isEqualTo(403);
            assertThat(original.getActionHint()).contains("grant-refusal:server-fixture");
            verify(core).executeLater("F006", "preview-175", 2L); verifyNoMoreInteractions(core);
            verify(writer).record("F006", "preview-175", original);
        }
    }

    private org.springframework.context.annotation.AnnotationConfigApplicationContext controllerContext(
            ControlledPurchaseTaskExecutionService core, PurchaseGrantRefusalAuditService writer) {
        var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
        context.getBeanFactory().registerSingleton("grantExecution", core);
        context.getBeanFactory().registerSingleton("grantAudit", writer);
        context.register(com.cretas.aims.controller.AiPurchaseTaskGrantController.class);
        context.refresh(); return context;
    }

    @Test void unknownCommitOutcomeIsNeverFalselyRecordedAsAConfirmedRefusal() {
        var core = mock(ControlledPurchaseTaskExecutionService.class);
        var writer = mock(PurchaseGrantRefusalAuditService.class);
        var unknown = new IllegalStateException("unconfirmed commit outcome");
        when(core.executeLater("F006", "preview-175", 2L)).thenThrow(unknown);
        try (var context = controllerContext(core, writer)) {
            var controller = context.getBean(com.cretas.aims.controller.AiPurchaseTaskGrantController.class);
            assertThat(catchThrowable(() -> controller.executeLater("F006", "preview-175",
                    new com.cretas.aims.controller.AiPurchaseTaskGrantController.ExecuteLaterRequest(2L)))).isSameAs(unknown);
            verifyNoInteractions(writer); verify(core).executeLater("F006", "preview-175", 2L); verifyNoMoreInteractions(core);
        }
    }

    @Test void typedGrantRejectionMapsToForbiddenAndCarriesCommittedCorrelation() {
        actor(AuthenticatedActorType.FACTORY_USER, "F006");
        var core = mock(ControlledPurchaseTaskExecutionService.class);
        var writer = mock(PurchaseGrantRefusalAuditService.class);
        var original = new PurchaseTaskGrantService.GrantRejectedException("grant is revoked");
        when(core.executeLater("F006", "preview-175", 2L)).thenThrow(original);
        when(writer.record("F006", "preview-175", original)).thenReturn(java.util.Optional.of("grant-refusal:typed-fixture"));
        try (var context = controllerContext(core, writer)) {
            var controller = context.getBean(com.cretas.aims.controller.AiPurchaseTaskGrantController.class);
            Throwable failure = catchThrowable(() -> controller.executeLater("F006", "preview-175",
                    new com.cretas.aims.controller.AiPurchaseTaskGrantController.ExecuteLaterRequest(2L)));
            assertThat(failure).isInstanceOf(BusinessException.class).hasMessage("grant is revoked");
            var mapped = (BusinessException) failure;
            assertThat(mapped.getCode()).isEqualTo(403); assertThat(mapped.getErrorCode()).isEqualTo("PURCHASE_GRANT_REJECTED");
            assertThat(mapped.getCause()).isSameAs(original);
            assertThat(mapped.getActionHint()).contains("grant-refusal:typed-fixture");
            verify(core).executeLater("F006", "preview-175", 2L); verifyNoMoreInteractions(core);
            verify(writer).record("F006", "preview-175", original);
        }
    }
}
