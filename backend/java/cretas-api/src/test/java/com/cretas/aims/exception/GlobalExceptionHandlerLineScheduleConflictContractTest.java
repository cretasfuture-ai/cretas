package com.cretas.aims.exception;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.LineSchedule;
import org.hibernate.StaleObjectStateException;
import org.hibernate.StaleStateException;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 排程行乐观锁冲突的识别 (PR #1677 独立审查 B1): 生产开着 JDBC 批量写, Hibernate 6.4 在批量路径上抛的是
 * 不带实体名的 {@link StaleStateException}, 只在消息里带执行的 UPDATE 语句。按语句里的表名认 line_schedules,
 * 别的表 (含前缀相同的表) 的冲突仍是通用提示, 不吞成「排程已被别人修改」。
 *
 * <p>消息格式取自真 PostgreSQL + batch_size=100 + batch_versioned_data=true 实测
 * ({@code LineScheduleConsistencyPostgresContractTest} 加批量配置后的失败读数), 不是手编的。
 */
class GlobalExceptionHandlerLineScheduleConflictContractTest {

    /** 实测 Hibernate 6.4.10 批量路径的消息 (列表截短, 结构不变)。 */
    private static final String BATCH_LINE_SCHEDULES = "Batch update returned unexpected row count from update [0]; "
            + "actual row count: 0; expected: 1; statement executed: update line_schedules set actual_efficiency=?,"
            + "planned_quantity=?,status=?,updated_at=?,version=? where id=? and version=?";

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void batchedStaleStateOnLineSchedulesGetsTheScheduleMessage() {
        Exception spring = batched(BATCH_LINE_SCHEDULES);
        assertThat(GlobalExceptionHandler.isLineScheduleConflict(spring)).isTrue();
        ApiResponse<?> body = handler.handleOptimisticLockException(spring);
        assertThat(body.getCode()).isEqualTo(409);
        assertThat(body.getMessage()).isEqualTo(GlobalExceptionHandler.LINE_SCHEDULE_CONFLICT_MESSAGE);
    }

    @Test
    void batchedStaleStateOnAnotherTableKeepsTheGenericMessage() {
        for (String table : new String[]{"scheduling_plans", "line_schedules_history", "worker_assignments"}) {
            Exception spring = batched(BATCH_LINE_SCHEDULES.replace("update line_schedules set", "update " + table + " set"));
            assertThat(GlobalExceptionHandler.isLineScheduleConflict(spring)).as(table).isFalse();
            assertThat(handler.handleOptimisticLockException(spring).getMessage()).as(table)
                    .isEqualTo("数据已被其他用户修改，请刷新后重试");
        }
    }

    @Test
    void aBatchedMessageThatOnlyMentionsLineSchedulesOutsideTheUpdateTargetIsNotMatched() {
        // 别的表的 UPDATE, 子查询里提到 line_schedules: 冲突的不是排程行
        Exception spring = batched("Batch update returned unexpected row count from update [0]; actual row count: 0; "
                + "expected: 1; statement executed: update worker_assignments set status=? where schedule_id in "
                + "(select id from line_schedules) and version=?");
        assertThat(GlobalExceptionHandler.isLineScheduleConflict(spring)).isFalse();
    }

    @Test
    void nonBatchedPathsStillWork() {
        assertThat(GlobalExceptionHandler.isLineScheduleConflict(
                new ObjectOptimisticLockingFailureException(LineSchedule.class, "S-1"))).isTrue();
        assertThat(GlobalExceptionHandler.isLineScheduleConflict(new ObjectOptimisticLockingFailureException(
                LineSchedule.class.getName(), "S-1", "stale",
                new StaleObjectStateException(LineSchedule.class.getName(), "S-1")))).isTrue();
        // 别的实体的 StaleObjectStateException: 通用提示
        assertThat(GlobalExceptionHandler.isLineScheduleConflict(new ObjectOptimisticLockingFailureException(
                "com.cretas.aims.entity.FactorySchedulingConfig", 1L, "stale",
                new StaleObjectStateException("com.cretas.aims.entity.FactorySchedulingConfig", 1L)))).isFalse();
    }

    /** 与 Spring 对批量路径的翻译同形: ObjectOptimisticLockingFailureException 包 StaleStateException, 不带实体名。 */
    private static Exception batched(String message) {
        StaleStateException hibernate = new StaleStateException(message);
        return new ObjectOptimisticLockingFailureException(message, hibernate);
    }
}
