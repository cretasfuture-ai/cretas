package com.cretas.aims.entity.workprocess;

import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工序任务状态的中文名只有一个来源: {@link Status#getDisplayName()}。
 * 2026-10-06 prod 实测一线看到「状态 → IN_PROGRESS」。
 */
@DisplayName("工序任务状态中文名 (唯一来源)")
class WorkProcessTaskStatusLabelTest {

    private static final Map<Status, String> EXPECTED = Map.of(
            Status.PENDING, "待开始",
            Status.IN_PROGRESS, "进行中",
            Status.COMPLETED, "已完成",
            Status.SKIPPED, "已跳过",
            Status.CANCELLED, "已取消");

    @Test
    @DisplayName("五个状态逐个是预期的中文名, 且没有一个等于枚举码")
    void everyStatusHasItsChineseLabel() {
        // 枚举新增状态而没补这张表 → 这里先红 (别让新状态悄悄回退成英文码)
        assertThat(EXPECTED.keySet()).containsExactlyInAnyOrder(Status.values());
        for (Status status : Status.values()) {
            assertThat(status.getDisplayName()).as(status.name()).isEqualTo(EXPECTED.get(status));
            assertThat(status.getDisplayName()).as(status.name()).isNotEqualTo(status.name());
        }
    }

    @Test
    @DisplayName("状态码 → 中文名: 大小写 / 空白不敏感; 对照: 空和不认识的码返回 null, 不回退成英文码")
    void codeLookupIsLenientButNeverEchoesTheCode() {
        assertThat(Status.displayNameOf("IN_PROGRESS")).isEqualTo("进行中");
        assertThat(Status.displayNameOf(" in_progress ")).isEqualTo("进行中");
        assertThat(Status.displayNameOf("COMPLETED")).isEqualTo("已完成");

        assertThat(Status.displayNameOf(null)).isNull();
        assertThat(Status.displayNameOf("  ")).isNull();
        assertThat(Status.displayNameOf("SOMETHING_ELSE")).isNull();
    }
}
