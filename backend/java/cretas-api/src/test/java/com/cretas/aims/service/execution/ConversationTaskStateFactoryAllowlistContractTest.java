package com.cretas.aims.service.execution;

import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 任务挂起/恢复按工厂白名单开 (cretas.ai.task-state.factories):
 * 全局关 + 名单含 A → A 开、B 关; 全局关 + 名单空 → 全关; 全局开 → 都开。
 * 除了判定本身, 还验证真实入口 (tryResume / summary) 对未入名单的工厂一次仓库都不碰。
 */
class ConversationTaskStateFactoryAllowlistContractTest {

    ConversationSessionRepository repo;
    ConversationTaskStateService service;

    @BeforeEach
    void setUp() {
        repo = mock(ConversationSessionRepository.class);
        service = new ConversationTaskStateService(repo);
    }

    @Test
    void globalOffAndListedFactoryIsOnWhileOthersStayOff() {
        service.setEnabled(false);
        service.setFactories("F-A, F-C");
        assertThat(service.isEnabledFor("F-A")).isTrue();
        assertThat(service.isEnabledFor("F-C")).isTrue();
        assertThat(service.isEnabledFor("F-B")).isFalse();
        assertThat(service.isEnabledFor("F-")).isFalse();
        assertThat(service.isEnabledFor(null)).isFalse();
    }

    @Test
    void globalOffAndEmptyListIsOffForEveryone() {
        service.setEnabled(false);
        for (String list : new String[]{"", "  ", null, " , "}) {
            service.setFactories(list);
            assertThat(service.isEnabledFor("F-A")).as("list=[%s]", list).isFalse();
            assertThat(service.isEnabledFor("")).as("blank factory, list=[%s]", list).isFalse();
        }
    }

    @Test
    void globalOnIsOnForEveryone() {
        service.setEnabled(true);
        service.setFactories("");
        assertThat(service.isEnabledFor("F-A")).isTrue();
        assertThat(service.isEnabledFor("F-B")).isTrue();
        service.setFactories("F-A");
        assertThat(service.isEnabledFor("F-B")).isTrue();
    }

    @Test
    void tryResumeAndSummaryOnlyTouchTheRepositoryForListedFactories() {
        service.setEnabled(false);
        service.setFactories("F-A");
        when(repo.findSuspendedByFactoryAndUser(anyString(), anyLong())).thenReturn(List.of());

        // 阴性对照: 未入名单的 B, 入口一次仓库都不碰, 行为与全局关逐字相同
        assertThat(service.tryResume("F-B", 1L, "回到刚才那个", s -> Optional.empty(), s -> List.of())).isEmpty();
        assertThat(service.summary("F-B", 1L, "s1")).isEmpty();
        verifyNoInteractions(repo);

        // 阳性对照: 名单内的 A 真的走到了仓库 (证明上面的「没碰」不是入口根本没跑)
        assertThat(service.tryResume("F-A", 1L, "回到刚才那个", s -> Optional.empty(), s -> List.of())).isEmpty();
        verify(repo).findSuspendedByFactoryAndUser("F-A", 1L);
    }
}
