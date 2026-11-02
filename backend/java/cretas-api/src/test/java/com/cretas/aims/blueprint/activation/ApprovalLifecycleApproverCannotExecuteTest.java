package com.cretas.aims.blueprint.activation;

import com.cretas.aims.entity.blueprintapproval.FactoryAssemblyApproval;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.blueprintapproval.FactoryAssemblyApprovalRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HTTP 层的执行入口：审批人（或申请人兼审批人）来执行一律拒绝，且不碰执行台账。 */
class ApprovalLifecycleApproverCannotExecuteTest {

    private static final ActorRef REQUESTER = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 7);
    private static final ActorRef APPROVER = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 8);

    private FactoryAssemblyApprovalLifecycle lifecycleWithExecutor(ActorRef executor, FactoryActivationExecutionStore store) {
        var approvals = mock(FactoryAssemblyApprovalRepository.class);
        var row = new FactoryAssemblyApproval();
        row.setId("a1");
        row.setFactoryId("F1");
        row.setCapabilityId("modules");
        row.setStatus("APPROVED");
        row.setRequestedActorType(REQUESTER.type());
        row.setRequestedActorId(REQUESTER.id());
        row.setApprovedActorType(APPROVER.type());
        row.setApprovedActorId(APPROVER.id());
        when(approvals.lockByIdAndFactoryId("a1", "F1")).thenReturn(Optional.of(row));
        var actors = mock(AssemblyActivationActorResolver.class);
        when(actors.authenticated(any(HttpServletRequest.class), anyString(), any())).thenReturn(executor);
        return new FactoryAssemblyApprovalLifecycle(approvals, mock(FactoryBlueprintActivationCenter.class), actors,
                new ObjectMapper(), Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC), store);
    }

    @Test
    void theApproverCannotExecuteTheirOwnApproval() {
        var store = mock(FactoryActivationExecutionStore.class);
        var lifecycle = lifecycleWithExecutor(APPROVER, store);
        assertThatThrownBy(() -> lifecycle.execute(mock(HttpServletRequest.class), "F1", "a1", "digest", "key"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("审批人不能自审或执行自己的审批");
        verify(store, never()).claim(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void aDifferentExecutorGetsPastTheSeparationCheck() {
        var store = mock(FactoryActivationExecutionStore.class);
        var lifecycle = lifecycleWithExecutor(new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, 9), store);
        // 后面的权限/摘要校验会另外拒绝（桩没有给），但拒绝的原因不能是职责分离这一条
        assertThatThrownBy(() -> lifecycle.execute(mock(HttpServletRequest.class), "F1", "a1", "digest", "key"))
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(String.valueOf(error.getMessage()))
                        .doesNotContain("审批人不能自审或执行自己的审批"));
    }
}
