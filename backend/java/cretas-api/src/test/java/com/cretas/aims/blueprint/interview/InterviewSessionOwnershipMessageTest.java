package com.cretas.aims.blueprint.interview;

import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.conversation.FactoryAssemblyConversationSessionRepository;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.FactoryAssemblyConversationService.ConversationActor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 找不到「本人的」会话时：属于别人 → 给出路；根本没有/已失效 → 让本人重新开始。 */
class InterviewSessionOwnershipMessageTest {

    private static final ConversationActor ME = new ConversationActor(AuthenticatedActorType.PLATFORM_ADMIN, 9L);

    private FactoryBuildInterviewService service() {
        var repo = mock(FactoryAssemblyConversationSessionRepository.class);
        when(repo.findBySessionIdAndFactoryIdAndUserIdAndActorType(anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        var someoneElses = FactoryAssemblyConversationSession.createForParameterCollection(
                "F1", 7L, AuthenticatedActorType.PLATFORM_ADMIN, "对话建厂", null, "开场");
        ReflectionTestUtils.setField(someoneElses, "sessionId", "s-other");
        when(repo.findBySessionIdAndFactoryId("s-other", "F1")).thenReturn(Optional.of(someoneElses));
        when(repo.findBySessionIdAndFactoryId("s-gone", "F1")).thenReturn(Optional.empty());
        var service = new FactoryBuildInterviewService();
        ReflectionTestUtils.setField(service, "sessionRepository", repo);
        return service;
    }

    @Test
    void anotherOperatorsSessionExplainsWhoOwnsItAndWhatToDoNext() {
        assertThatThrownBy(() -> service().progress("F1", "s-other", ME))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo("INTERVIEW_SESSION_OWNED_BY_ANOTHER_OPERATOR");
                    assertThat(error.getMessage()).contains("另一位操作人").contains("开始新的访谈版本").doesNotContain("s-other");
                });
    }

    @Test
    void aSessionThatIsGoneIsNotBlamedOnAnotherOperator() {
        assertThatThrownBy(() -> service().progress("F1", "s-gone", ME))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo("INTERVIEW_SESSION_NOT_FOUND");
                    assertThat(error.getMessage()).contains("失效或已结束").doesNotContain("另一位操作人");
                });
    }
}
