package com.cretas.aims.service.cron;

import com.cretas.aims.entity.cron.*;
import com.cretas.aims.repository.cron.*;
import com.cretas.aims.service.cron.handler.PurchaseGrantTimerTaskHandler;
import com.cretas.aims.service.intent.PurchaseGrantTimerExecutionService;
import net.javacrumbs.shedlock.core.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PurchaseGrantTimerSchedulerContractTest {
    @Test void actualCanvasCronFiresHandlerWithoutRequestAndManualRunNeverReachesPurchase() throws Exception {
        var tasks=mock(ScheduledTaskRepository.class);var logs=mock(ScheduledTaskRunLogRepository.class);
        var context=mock(ApplicationContext.class);var locks=mock(LockProvider.class);
        var timers=mock(PurchaseGrantTimerExecutionService.class);var handler=new PurchaseGrantTimerTaskHandler(timers);
        var task=new ScheduledTask();task.setId(UUID.randomUUID());task.setFactoryId("F006");task.setTaskCode("timer-contract");
        task.setHandlerBeanName(PurchaseGrantTimerExecutionService.HANDLER);task.setEnabled(true);
        var at=LocalDateTime.now().plusSeconds(3);
        task.setCronExpression(at.getSecond()+" "+at.getMinute()+" "+at.getHour()+" "+at.getDayOfMonth()+" "+at.getMonthValue()+" *");
        when(tasks.findByEnabledTrue()).thenReturn(List.of(task));when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(context.containsBean(task.getHandlerBeanName())).thenReturn(true);when(context.getBean(task.getHandlerBeanName())).thenReturn(handler);
        when(locks.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
        when(logs.save(any())).thenAnswer(i->i.getArgument(0));
        var fired=new CountDownLatch(1);
        when(timers.fire(task.getId(),"F006",task.getTaskCode())).thenAnswer(i->{
            assertThat(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()).isNull();
            fired.countDown();return new PurchaseGrantTimerExecutionService.View(UUID.randomUUID(),"preview-7",task.getId(),at,at.plusMinutes(1),"SUCCEEDED","PO-7",null,"snapshot",LocalDateTime.now(),java.time.ZoneId.systemDefault().getId());
        });
        var scheduler=new DynamicScheduler(tasks,logs,context,locks);
        try {
            var manual=scheduler.runNow(task.getId());assertThat(manual.getStatus()).isEqualTo(TaskRunStatus.FAILED);
            verifyNoInteractions(timers);
            scheduler.configureTasks(new ScheduledTaskRegistrar());
            assertThat(fired.await(8,TimeUnit.SECONDS)).isTrue();
            verify(timers,timeout(1500).times(1)).fire(task.getId(),"F006",task.getTaskCode());
            verify(logs,timeout(1500).atLeastOnce()).save(argThat(log->log.getStatus()==TaskRunStatus.SUCCESS));
        } finally {((ThreadPoolTaskScheduler)ReflectionTestUtils.getField(scheduler,"taskScheduler")).shutdown();}
    }
    @Test void handlerRejectsForeignPayloadFieldsAndExplicitManualSourceBeforeExecution() {
        var timers=mock(PurchaseGrantTimerExecutionService.class);var handler=new PurchaseGrantTimerTaskHandler(timers);
        for(String key:List.of("manualRun","userId","role","quantity","grantId","action","handler","serverConfirmed")) {
            var context=new HashMap<String,Object>();context.put("taskId",UUID.randomUUID());context.put("taskCode","task");context.put("factoryId","F006");context.put(key,true);
            assertThatThrownBy(()->handler.execute(context)).isInstanceOf(SecurityException.class);
        }
        verifyNoInteractions(timers);
    }
}
