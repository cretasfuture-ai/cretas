package com.cretas.aims.service.workflow;

import org.springframework.lang.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 在<b>独立的只读事务</b>里读一项工厂配置, 读失败只影响这次读, 不把调用方的事务标成 rollback-only。
 *
 * <p>#1580 独立审查低危 4: 「允许本人审批」开关和改价阈值的读取都是「读不到就用默认值」。但读取若发生在
 * 调用方的 {@code @Transactional} 里, JPA 查询抛 PersistenceException 时会把<b>外层</b>事务标记为回滚;
 * 我们 catch 住继续走, 到提交时却炸 UnexpectedRollbackException —— 「用默认值继续」名存实亡。
 * 用 REQUIRES_NEW + readOnly 把这次读隔离出去。没有事务管理器 (手工构造的单测) 时直接读。
 */
public final class IsolatedSettingsRead {

    private IsolatedSettingsRead() {
    }

    public static <T> T read(@Nullable PlatformTransactionManager transactionManager, Supplier<T> reader) {
        if (transactionManager == null) {
            return reader.get();
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template.execute(status -> reader.get());
    }
}
