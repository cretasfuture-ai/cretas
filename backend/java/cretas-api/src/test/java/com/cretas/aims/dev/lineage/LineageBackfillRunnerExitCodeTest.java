package com.cretas.aims.dev.lineage;

import com.cretas.aims.service.lineage.LineageBackfillService;
import com.cretas.aims.service.lineage.LineageRecordOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回填进程退出码：0 全部成功 / 1 有失败 / 2 什么都没做 / 3 没量到（自检不通过）。
 * 退出码是这个进程唯一的结论通道 —— 每一态都要有一个能让它出现的输入。
 */
@DisplayName("LineageBackfillRunner 退出码与 SUMMARY")
class LineageBackfillRunnerExitCodeTest {

    private final LineageBackfillService service = mock(LineageBackfillService.class);

    private LineageBackfillRunner runner(GenericApplicationContext context, String factories) {
        if (!context.isActive()) {
            context.refresh();
        }
        LineageBackfillRunner r = new LineageBackfillRunner(service, context);
        ReflectionTestUtils.setField(r, "factories", factories);
        ReflectionTestUtils.setField(r, "dryRun", true);
        return r;
    }

    private static LineageBackfillService.Report report(String factoryId, LineageRecordOutcome... outcomes) {
        LineageBackfillService.Counters raw = new LineageBackfillService.Counters();
        for (LineageRecordOutcome o : outcomes) {
            ReflectionTestUtils.invokeMethod(raw, "record", o);
        }
        return new LineageBackfillService.Report(factoryId, true, raw,
                new LineageBackfillService.Counters(), new LineageBackfillService.Counters(),
                new LineageBackfillService.Counters(), new LineageBackfillService.Counters());
    }

    @Test
    @DisplayName("全部工厂跑完且无失败行 → 0")
    void allFactoriesClean_exit0() {
        when(service.backfillFactory(anyString(), anyBoolean()))
                .thenAnswer(inv -> report(inv.getArgument(0), LineageRecordOutcome.INSERTED));
        LineageBackfillRunner r = runner(new GenericApplicationContext(), "F006, LIUSHANMEN");
        r.run(null);
        assertThat(r.getExitCode()).isEqualTo(LineageBackfillRunner.EXIT_OK);
        assertThat(r.summaryLine()).startsWith("[lineage-backfill] SUMMARY exit=0 ")
                .contains("成功=[F006, LIUSHANMEN]");
    }

    @Test
    @DisplayName("一个工厂抛异常 → 1, 且后面的工厂照样跑")
    void oneFactoryThrows_exit1_andTheOthersStillRun() {
        when(service.backfillFactory(eq("F006"), anyBoolean())).thenThrow(new IllegalStateException("boom"));
        when(service.backfillFactory(eq("LIUSHANMEN"), anyBoolean())).thenReturn(report("LIUSHANMEN"));
        LineageBackfillRunner r = runner(new GenericApplicationContext(), "F006,LIUSHANMEN");
        r.run(null);
        assertThat(r.getExitCode()).isEqualTo(LineageBackfillRunner.EXIT_FAILED);
        verify(service).backfillFactory("LIUSHANMEN", true);
        assertThat(r.summaryLine()).startsWith("[lineage-backfill] SUMMARY exit=1 ")
                .contains("F006(异常: IllegalStateException: boom)").contains("成功=[LIUSHANMEN]");
    }

    @Test
    @DisplayName("没抛异常但有失败行 → 1 (不能把「写失败的行」读成成功)")
    void failedRows_exit1() {
        when(service.backfillFactory(anyString(), anyBoolean()))
                .thenReturn(report("F006", LineageRecordOutcome.INSERTED, LineageRecordOutcome.FAILED));
        LineageBackfillRunner r = runner(new GenericApplicationContext(), "F006");
        r.run(null);
        assertThat(r.getExitCode()).isEqualTo(LineageBackfillRunner.EXIT_FAILED);
        assertThat(r.summaryLine()).contains("失败行合计=1");
    }

    @Test
    @DisplayName("出现「延后待定」(读数作废) → 1")
    void scheduledRows_exit1() {
        when(service.backfillFactory(anyString(), anyBoolean()))
                .thenReturn(report("F006", LineageRecordOutcome.SCHEDULED));
        LineageBackfillRunner r = runner(new GenericApplicationContext(), "F006");
        r.run(null);
        assertThat(r.getExitCode()).isEqualTo(LineageBackfillRunner.EXIT_FAILED);
        assertThat(r.summaryLine()).contains("延后待定合计=1");
    }

    @Test
    @DisplayName("LINEAGE_BACKFILL_FACTORIES 为空 → 2, 一个工厂都不碰")
    void noFactories_exit2() {
        LineageBackfillRunner r = runner(new GenericApplicationContext(), " , ");
        r.run(null);
        assertThat(r.getExitCode()).isEqualTo(LineageBackfillRunner.EXIT_NOTHING_TO_DO);
        verify(service, never()).backfillFactory(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("运行时自检不通过 (调度开着) → 3, 一行都不执行")
    void preflightFails_exit3_andNothingRuns() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME,
                new RootBeanDefinition(ScheduledAnnotationBeanPostProcessor.class));
        LineageBackfillRunner r = runner(new GenericApplicationContext(bf), "F006");
        r.run(null);
        assertThat(r.getExitCode()).isEqualTo(LineageBackfillRunner.EXIT_NOT_MEASURED);
        assertThat(r.summaryLine()).startsWith("[lineage-backfill] SUMMARY exit=3 ").contains("调度开着");
        verify(service, never()).backfillFactory(anyString(), anyBoolean());
    }

    @Test
    @DisplayName("还没执行 run 时退出码是 3 (没量到), 不是 0")
    void beforeRun_exitIsNotMeasured() {
        LineageBackfillRunner r = runner(new GenericApplicationContext(), "F006");
        assertThat(r.getExitCode()).isEqualTo(LineageBackfillRunner.EXIT_NOT_MEASURED);
    }

    @Test
    @DisplayName("main 在上下文建起来之前就能认出回填 profile (命令行 / 系统属性)")
    void requestedDetectsTheProfileBeforeTheContextExists() {
        assertThat(LineageBackfillMode.requested(new String[]{"--spring.profiles.active=pg-prod,lineage-backfill"}))
                .isTrue();
        assertThat(LineageBackfillMode.requested(new String[]{"--spring.profiles.active=pg-prod, lineage-backfill"}))
                .isTrue();
        assertThat(LineageBackfillMode.requested(new String[]{"--spring.profiles.active=pg-prod"})).isFalse();
        assertThat(LineageBackfillMode.requested(new String[]{"--spring.profiles.active=lineage-backfill-x"}))
                .isFalse();
        String before = System.getProperty("spring.profiles.active");
        try {
            System.setProperty("spring.profiles.active", "pg-prod,lineage-backfill");
            assertThat(LineageBackfillMode.requested(new String[0])).isTrue();
        } finally {
            if (before == null) {
                System.clearProperty("spring.profiles.active");
            } else {
                System.setProperty("spring.profiles.active", before);
            }
        }
    }
}
