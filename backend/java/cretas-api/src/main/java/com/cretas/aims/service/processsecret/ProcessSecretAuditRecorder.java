package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretAccessEvent;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.repository.processsecret.ProcessSecretAccessEventRepository;
import com.cretas.aims.security.AuthenticatedActor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计写入走独立事务: 读请求是只读事务、被拒请求随后抛异常回滚, 这两种情况审计都必须留下。
 * ⛔ 只记 ID / 动作 / 结论, 不记工艺正文。
 */
@Component
public class ProcessSecretAuditRecorder {

    private final ProcessSecretAccessEventRepository repository;

    public ProcessSecretAuditRecorder(ProcessSecretAccessEventRepository repository) {
        this.repository = repository;
    }

    /** 写失败会抛异常 (事务已回滚); 调用方据此在「放行」时 fail-closed。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String factoryId, String productTypeId, AuthenticatedActor actor,
                       ProcessSecretAction action, String decision, String reason, String resourceRef) {
        repository.save(ProcessSecretAccessEvent.builder()
                .factoryId(factoryId)
                .productTypeId(productTypeId)
                .actorType(actor == null ? "UNKNOWN" : actor.type().name())
                .actorId(actor == null ? null : actor.id())
                .action(action)
                .decision(decision)
                .reason(truncate(reason, 200))
                .resourceRef(truncate(resourceRef, 200))
                .build());
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
