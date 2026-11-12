package com.cretas.aims.ai.tool;

import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

/**
 * 数一数「这个线程上真的提交了几个数据库事务」, 让写工具在出错时分得清「确定没写」和「可能已写」
 * (写入确认三洞 #2, Fable 审阅 §8.3)。
 *
 * <p>典型场景: 工具里第一个 {@code @Transactional} 服务调用已经提交, 第二步抛异常。
 * {@link AbstractBusinessTool#execute} 会把异常吞成 {@code {success:false}}, 以前确认流程据此把凭据记「失败」,
 * 用户被告知失败后重做, 写了两遍。现在工具在 doExecute 前后各读一次本计数: 有提交 → 结果里带
 * {@code outcomeUnknown=true}, 确认流程记「结果不明」并提示先核对。</p>
 *
 * <p>挂在 JPA 事务管理器上 (见 {@code PrimaryDataSourceConfig} / {@code SmartBIPostgresDataSourceConfig}),
 * 只数<b>新开</b>、<b>非只读</b>且<b>提交成功</b>的事务 —— 加入外层事务的不算 (外层提交时才算一次),
 * 只读事务 (仓库查询自带的) 不算。
 * 不覆盖: 不经事务管理器的自动提交 JDBC、其它线程上的写入、外部系统 (短信、摄像头) 的副作用;
 * 这几类由 {@link AbstractBusinessTool} 对「非业务拒绝的异常」一律按可能已写处理来兜底。</p>
 */
public final class ToolWriteCommitObserver implements TransactionExecutionListener {

    public static final ToolWriteCommitObserver INSTANCE = new ToolWriteCommitObserver();

    private static final ThreadLocal<long[]> COMMITS = ThreadLocal.withInitial(() -> new long[1]);

    private ToolWriteCommitObserver() {
    }

    /** 本线程至今观察到的成功提交次数; 只用来做前后差值。 */
    public static long commitsOnThisThread() {
        return COMMITS.get()[0];
    }

    @Override
    public void afterCommit(TransactionExecution transaction, Throwable commitFailure) {
        // 只读事务也会回调 afterCommit (SimpleJpaRepository.findById 无外层事务时自己开一个只读事务):
        // 「先查一次再业务拒绝」什么都没写, 不能被算成「可能已写」
        if (commitFailure == null && transaction.isNewTransaction() && !transaction.isReadOnly()) {
            COMMITS.get()[0]++;
        }
    }
}
