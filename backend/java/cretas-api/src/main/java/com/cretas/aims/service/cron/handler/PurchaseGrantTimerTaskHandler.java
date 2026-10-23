package com.cretas.aims.service.cron.handler;

import com.cretas.aims.service.cron.TaskHandler;
import com.cretas.aims.service.intent.PurchaseGrantTimerExecutionService;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Fixed Canvas-Cron adapter. No tool name, impersonated request, or arbitrary execution payload. */
@Component("purchaseGrantTimerTaskHandler") @RequiredArgsConstructor
public class PurchaseGrantTimerTaskHandler implements TaskHandler {
    private final PurchaseGrantTimerExecutionService timers;
    @Override public void execute(Map<String,Object> context) {
        if(context==null || context.containsKey("manualRun")
                || !Set.of("taskId","taskCode","factoryId","lastRunAt").containsAll(context.keySet())
                || !(context.get("taskId") instanceof UUID taskId)
                || !(context.get("factoryId") instanceof String factory) || factory.isBlank()
                || !(context.get("taskCode") instanceof String code) || code.isBlank())
            throw new SecurityException("仅接受真实Canvas定时上下文；禁止手动执行或传入业务/身份字段");
        var result=timers.fire(taskId,factory,code);
        if(!"SUCCEEDED".equals(result.state()))
            throw new IllegalStateException("PURCHASE_TIMER_"+result.state()+": "+result.failureReason());
    }
}
