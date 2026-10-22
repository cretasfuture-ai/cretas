package com.cretas.aims.service.intent;

import com.cretas.aims.entity.ai.*;
import com.cretas.aims.entity.cron.ScheduledTask;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ai.*;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.security.*;
import com.cretas.aims.service.cron.DynamicScheduler;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Procurement-owned one-shot timer. No generic tool, HTTP impersonation, or retry of uncertain effects. */
@Service @RequiredArgsConstructor
public class PurchaseGrantTimerExecutionService {
    public static final String HANDLER="purchaseGrantTimerTaskHandler";
    private final PurchaseGrantTimerBindingRepository bindings;
    private final ScheduledTaskRepository tasks;
    private final AiPurchaseTaskGrantRepository grants;
    private final ControlledPurchaseTaskExecutionService controlled;
    private final PurchaseGrantRefusalAuditService audit;
    private final PlatformTransactionManager transactions;
    private final DynamicScheduler scheduler;
    private final EntityManager em;

    @Transactional
    public View bind(String factory, String grantId, Long expectedVersion, LocalDateTime executeAt) {
        var grant=controlled.admitTimerBinding(factory,grantId,expectedVersion);
        if(executeAt==null || !executeAt.isAfter(LocalDateTime.now().plusSeconds(2))
                || !executeAt.isBefore(grant.getExpiresAt())) throw refused("执行时间必须在当前授权有效期内且晚于当前时间");
        if(bindings.findByGrantIdAndFactoryIdAndUserId(grantId,factory,grant.getUserId()).isPresent())
            throw refused("该授权已有定时绑定，请读取原结果，不可重复安排");
        LocalDateTime at=executeAt.withNano(0);
        ScheduledTask task=new ScheduledTask();
        task.setFactoryId(factory); task.setTaskCode("purchase-grant-"+UUID.randomUUID());
        task.setTaskName("已确认采购授权一次执行"); task.setHandlerBeanName(HANDLER);
        task.setCronExpression(cron(at)); task.setEnabled(true);
        task=tasks.saveAndFlush(task);
        var token=em.find(IntentPreviewToken.class,grant.getPreviewTokenId());
        PurchaseGrantTimerBinding b=new PurchaseGrantTimerBinding();
        b.setId(PurchaseGrantTimerActor.bindingId(grantId));
        b.setFactoryId(factory); b.setGrantId(grantId); b.setUserId(grant.getUserId());
        b.setScheduledTaskId(task.getId()); b.setTaskRevision(task.getVersion());
        b.setExpectedGrantVersion(expectedVersion); b.setInputDigest(grant.getInputDigest());
        b.setScopeDigest(PurchaseGrantTimerActor.scopeDigest(grant));
        b.setInputSnapshot(grant.getApprovedInputSnapshot()); b.setCommandDigest(token.getCommandDigest());
        b.setExpiresAt(grant.getExpiresAt()); b.setExecuteAt(at); b.setCronExpression(task.getCronExpression()); b.setState("PENDING");
        bindings.saveAndFlush(b);
        // Register only committed tasks; failure to refresh is visible via persisted PENDING and can be reloaded on restart.
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){scheduler.reload();}
            });
        return view(b);
    }

    @Transactional(readOnly=true)
    public View status(String factory,String grantId) {
        controlled.status(factory,grantId); // Current identity and tenant checks remain identical to the delivered status API.
        var actor=requestActor(factory);
        var b=bindings.findByGrantIdAndFactoryIdAndUserId(grantId,factory,actor.id()).orElse(null);
        if(b==null) return null;
        return visibleView(factory,b);
    }

    /** Explicit evidence reconciliation, without executing or retrying the purchase. GET remains zero-write. */
    @Transactional
    public View reconcileStatus(String factory,String grantId) {
        controlled.status(factory,grantId);
        var actor=requestActor(factory);
        var existing=bindings.findByGrantIdAndFactoryIdAndUserId(grantId,factory,actor.id()).orElseThrow(()->refused("不存在当前授权的定时绑定"));
        var b=lockCurrentTask(existing.getScheduledTaskId(),factory);
        if("UNKNOWN".equals(b.getState())) reconcile(b);
        else if("PENDING".equals(b.getState()) && isMissed(b)) refuseMissed(b);
        return visibleView(factory,b);
    }
    private View visibleView(String factory,PurchaseGrantTimerBinding b) {
        var result=view(b);
        return controlled.canViewTimerSnapshot(factory)?result:new View(result.bindingId(),result.grantId(),result.taskId(),
                result.executeAt(),result.expiresAt(),result.state(),result.purchaseOrderId(),result.failureReason(),null,result.serverNow(),result.serverZone());
    }

    /** Restart never replays an in-flight/unknown effect; future PENDING tasks remain for normal Canvas reload. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        new TransactionTemplate(transactions).executeWithoutResult(s->{
            for(var candidate:bindings.findByStateIn(List.of("PENDING","RUNNING","UNKNOWN"))) {
                var b=lockCurrentTask(candidate.getScheduledTaskId(),candidate.getFactoryId());
                if("RUNNING".equals(b.getState()) || "UNKNOWN".equals(b.getState())) reconcileIfUncertain(b);
                else if("PENDING".equals(b.getState()) && isMissed(b)) {
                    refuseMissed(b);
                }
            }
        });
        scheduler.reload();
    }

    /** Only the fixed TaskHandler invokes this method; callback context supplies no actor/command. */
    public View fire(UUID taskId,String factory,String taskCode) {
        TransactionTemplate tx=new TransactionTemplate(transactions);
        Boolean claimed=tx.execute(s->{
            var b=lockCurrentTask(taskId,factory);
            if(!"PENDING".equals(b.getState())) { reconcileIfUncertain(b); return false; }
            // Early callback/manual invocations never claim the schedule.
            if(LocalDateTime.now().isBefore(b.getExecuteAt())) throw refused("尚未到达已绑定执行时间");
            b.setState("RUNNING"); bindings.flush(); return true;
        });
        if(Boolean.TRUE.equals(claimed)) {
            try {
                tx.executeWithoutResult(s->{
                    var b=lockCurrentTask(taskId,factory);
                    if(!"RUNNING".equals(b.getState())) throw refused("定时执行已终结或结果待核对");
                    ScheduledTask task=em.find(ScheduledTask.class,taskId,LockModeType.PESSIMISTIC_WRITE);
                    validateTask(b,task,taskCode);
                    var outcome=controlled.executeScheduled(PurchaseGrantTimerActor.fromPersisted(b,em));
                    // Canonical consume CAS deliberately clears the persistence context. Reattach durable outcome rows.
                    b=lockCurrentTask(taskId,factory);
                    task=em.find(ScheduledTask.class,taskId,LockModeType.PESSIMISTIC_WRITE);
                    // Controlled service registers a beforeCommit identity/token/grant admission check.
                    b.setState("SUCCEEDED"); b.setResultPurchaseOrderId(outcome.purchaseOrderId());
                    task.setEnabled(false); bindings.flush(); tasks.flush();
                });
            } catch(RuntimeException failure) {
                boolean confirmed=failure instanceof BusinessException || failure instanceof PurchaseTaskGrantService.GrantRejectedException;
                tx.executeWithoutResult(s->{
                    var b=lockCurrentTask(taskId,factory);
                    // If commit actually succeeded, persisted consumption takes precedence over exception classification.
                    if(!reconcile(b)) {
                        b.setState(confirmed?"REFUSED":"UNKNOWN");
                        String reason=failure.getMessage(); b.setFailureReason(reason==null?failure.getClass().getSimpleName():reason.substring(0,Math.min(500,reason.length())));
                        disable(b);
                        if(confirmed) audit.recordTimer(b,failure);
                    }
                });
            }
        }
        return tx.execute(s->view(lockCurrentTask(taskId,factory)));
    }

    private PurchaseGrantTimerBinding lockCurrentTask(UUID taskId,String factory) {
        // Hibernate can reject the stale managed @Version while materializing the locking
        // query, before refresh is reached. Preserve pending writes/DB locks, then discard
        // first-level snapshots so the scoped lock reads the current committed row.
        em.flush();
        em.clear();
        var b=bindings.lockTask(taskId,factory).orElseThrow(()->refused("不存在该工厂的采购定时绑定"));
        // Lock acquisition alone can reuse a stale first-level entity loaded by a preceding scan.
        em.refresh(b,LockModeType.PESSIMISTIC_WRITE);
        if(!Objects.equals(taskId,b.getScheduledTaskId()) || !Objects.equals(factory,b.getFactoryId()))
            throw refused("定时绑定租户或任务身份已变化");
        return b;
    }
    private void refuseMissed(PurchaseGrantTimerBinding b) {
        if(!"PENDING".equals(b.getState())) throw refused("只允许关闭仍未认领的错过任务");
        b.setState("REFUSED");b.setFailureReason("已错过执行时刻；禁止补发采购");disable(b);
        audit.recordTimer(b,refused(b.getFailureReason()));
    }
    private static boolean isMissed(PurchaseGrantTimerBinding b) {
        // Explicit recovery does not race a callback that is merely a few milliseconds late.
        return LocalDateTime.now().isAfter(b.getExecuteAt().plusSeconds(5));
    }

    private void validateTask(PurchaseGrantTimerBinding b,ScheduledTask task,String code) {
        if(task==null || !Boolean.TRUE.equals(task.getEnabled()) || !Objects.equals(task.getFactoryId(),b.getFactoryId())
                || !HANDLER.equals(task.getHandlerBeanName()) || !Objects.equals(task.getTaskCode(),code)
                || !Objects.equals(task.getVersion(),b.getTaskRevision()) || !Objects.equals(task.getCronExpression(),b.getCronExpression()))
            throw refused("定时任务配置或版本已变化，拒绝采购执行");
    }
    private void reconcileIfUncertain(PurchaseGrantTimerBinding b){
        if("RUNNING".equals(b.getState()) || "UNKNOWN".equals(b.getState())) {
            if(!reconcile(b)){b.setState("UNKNOWN");disable(b);}
        }
    }
    private boolean reconcile(PurchaseGrantTimerBinding b) {
        var g=grants.findByIdAndFactoryIdAndUserId(b.getGrantId(),b.getFactoryId(),b.getUserId()).orElse(null);
        if(g!=null && g.isConsumed() && Objects.equals(g.getConsumedRequestKey(),"timer:"+b.getId())
                && Objects.equals(b.getScopeDigest(),PurchaseGrantTimerActor.scopeDigest(g))
                && Objects.equals(g.getConsumedInputDigest(),b.getInputDigest()) && Objects.equals(g.getInputDigest(),b.getInputDigest())
                && Objects.equals(g.getApprovedInputSnapshot(),b.getInputSnapshot()) && g.getResultPurchaseOrderId()!=null
                && Objects.equals(g.getVersion(),b.getExpectedGrantVersion()+1)) {
            // Verify original persisted draft still belongs to this tenant and creator. No write or execution is attempted.
            var order=em.find(com.cretas.aims.entity.inventory.PurchaseOrder.class,g.getResultPurchaseOrderId());
            if(order!=null && Objects.equals(order.getFactoryId(),b.getFactoryId()) && Objects.equals(order.getCreatedBy(),b.getUserId())) {
                b.setState("SUCCEEDED");b.setResultPurchaseOrderId(order.getId());b.setFailureReason(null);disable(b);return true;
            }
        }
        return "SUCCEEDED".equals(b.getState());
    }
    private void disable(PurchaseGrantTimerBinding b){
        var task=em.find(ScheduledTask.class,b.getScheduledTaskId()); if(task!=null)task.setEnabled(false);
    }
    private static AuthenticatedActor requestActor(String factory){
        return AuthenticatedActor.fromCurrentRequest().filter(a->a.type()==AuthenticatedActorType.FACTORY_USER && factory.equals(a.factoryId()))
                .orElseThrow(()->refused("缺少当前工厂可信登录主体"));
    }
    static String cron(LocalDateTime at){return at.getSecond()+" "+at.getMinute()+" "+at.getHour()+" "+at.getDayOfMonth()+" "+at.getMonthValue()+" *";}
    private static BusinessException refused(String reason){return new BusinessException(403,reason).withCode("PURCHASE_TIMER_REFUSED");}
    private static View view(PurchaseGrantTimerBinding b){return new View(b.getId(),b.getGrantId(),b.getScheduledTaskId(),b.getExecuteAt(),b.getExpiresAt(),b.getState(),b.getResultPurchaseOrderId(),b.getFailureReason(),b.getInputSnapshot(),LocalDateTime.now(),java.time.ZoneId.systemDefault().getId());}
    public record View(UUID bindingId,String grantId,UUID taskId,LocalDateTime executeAt,LocalDateTime expiresAt,String state,String purchaseOrderId,String failureReason,String approvedSnapshot,LocalDateTime serverNow,String serverZone){}
}
