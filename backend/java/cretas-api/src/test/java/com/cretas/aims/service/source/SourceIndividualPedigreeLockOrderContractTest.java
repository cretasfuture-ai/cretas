package com.cretas.aims.service.source;

import com.cretas.aims.dto.source.UpdateSourceIndividualRequest;
import com.cretas.aims.entity.source.SourceIndividual;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.source.SourceIndividualRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1968 L-1: 成环校验的补锁轮次<b>不许乱序加锁</b>。
 *
 * <p>第 0 轮按 id 升序锁「自己 + 新父本 + 预读到的祖先」; 锁后重读如果发现新祖先(加锁前后有人改了链):
 * <ul>
 *   <li>新 id 排在已锁最大 id 之前 ⇒ 补锁会打乱全局锁序(可与按序加锁的事务交叉等待 → PG 40P01 → 500)
 *       ⇒ 直接 409 {@code PEDIGREE_BUSY}, 那一行<b>一次都不锁</b>;</li>
 *   <li>新 id 全部排在已锁最大 id 之后 ⇒ 锁序仍单调, 补锁后照常保存(阳性对照)。</li>
 * </ul>
 * 用 Mockito 让 {@code findParentLinks} 在预读与锁后返回不同结果, 确定性地构造「链在加锁窗口里变了」。
 */
class SourceIndividualPedigreeLockOrderContractTest {

    private static final String F = "F-LO";

    private SourceIndividualRepository repo;
    private SourceIndividualService service;
    private final List<String> lockOrder = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(SourceIndividualRepository.class);
        service = new SourceIndividualService(repo);
        for (String id : new String[]{"m", "p", "a", "z"}) {
            SourceIndividual si = SourceIndividual.builder().id(id).factoryId(F).individualCode("C-" + id).build();
            when(repo.lockByIdAndFactoryId(id, F)).thenAnswer(i -> {
                lockOrder.add(id);
                return Optional.of(si);
            });
            when(repo.findByIdAndFactoryId(id, F)).thenReturn(Optional.of(si));
        }
        when(repo.saveAndFlush(any(SourceIndividual.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static List<Object[]> row(String id, String sire) {
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{id, sire, null});
        return rows;
    }

    private static Collection<String> containing(String id) {
        return ArgumentMatchers.argThat(c -> c != null && c.contains(id));
    }

    @Test
    @DisplayName("锁后冒出排在前面的新祖先 a(< 已锁的 p) → 409 PEDIGREE_BUSY, 不锁 a, 不保存")
    void newAncestorBelowTheLockedMaxIsBusyNotOutOfOrderLock() {
        // 预读: p 没有父本; 锁后重读: p 的父本变成了 a (别人刚提交)
        when(repo.findParentLinks(eq(F), containing("p"))).thenReturn(row("p", null), row("p", "a"));
        when(repo.findParentLinks(eq(F), containing("a"))).thenReturn(row("a", null));

        BusinessException e = catchThrowableOfType(() -> service.update(F, "m",
                UpdateSourceIndividualRequest.builder().sireIndividualId("p").build()), BusinessException.class);

        assertThat(e).as("必须拒绝, 不能乱序补锁后放行").isNotNull();
        assertThat(e.getCode()).isEqualTo(409);
        assertThat(e.getErrorCode()).isEqualTo("PEDIGREE_BUSY");
        verify(repo, never()).lockByIdAndFactoryId("a", F);
        assertThat(lockOrder).containsExactly("m", "p");
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("阳性对照: 锁后冒出排在后面的新祖先 z(> 已锁的 p) → 按序补锁 z 后照常保存")
    void newAncestorAboveTheLockedMaxIsLockedInOrderAndSaves() {
        when(repo.findParentLinks(eq(F), containing("p"))).thenReturn(row("p", null), row("p", "z"));
        when(repo.findParentLinks(eq(F), containing("z"))).thenReturn(row("z", null));

        service.update(F, "m", UpdateSourceIndividualRequest.builder().sireIndividualId("p").build());

        // 校验阶段的锁序单调递增 (之后 requireParentInSameFactory 再锁一次已持有的 p, 不算新锁)
        assertThat(lockOrder.subList(0, 3)).containsExactly("m", "p", "z");
        verify(repo).saveAndFlush(any(SourceIndividual.class));
    }
}
