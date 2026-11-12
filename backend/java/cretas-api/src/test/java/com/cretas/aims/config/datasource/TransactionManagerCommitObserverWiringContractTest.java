package com.cretas.aims.config.datasource;

import com.cretas.aims.ai.tool.ToolWriteCommitObserver;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.JpaTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 写入确认三洞 #2 的接线: 生产事务管理器必须挂上提交观察者, 否则写工具数不到「出错前已提交」,
 * 结果不明会退化成「失败」(机制在、没接上 —— 形态 B)。
 */
class TransactionManagerCommitObserverWiringContractTest {

    @Test
    void bothProductionTransactionManagersCountCommitsForWriteTools() {
        JpaTransactionManager primary = (JpaTransactionManager) new PrimaryDataSourceConfig()
                .primaryTransactionManager(mock(EntityManagerFactory.class));
        JpaTransactionManager smartbi = (JpaTransactionManager) new SmartBIPostgresDataSourceConfig()
                .smartbiPostgresTransactionManager(mock(EntityManagerFactory.class));

        assertThat(primary.getTransactionExecutionListeners()).contains(ToolWriteCommitObserver.INSTANCE);
        assertThat(smartbi.getTransactionExecutionListeners()).contains(ToolWriteCommitObserver.INSTANCE);
    }
}
