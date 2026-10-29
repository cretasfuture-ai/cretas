package com.cretas.aims.repository.workflow;

import com.cretas.aims.entity.workflow.ApprovalHistory;
import com.cretas.aims.entity.workflow.ApprovalHistory.HistoryAction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S5-12: 采购单审批速览只给「在这个实例上操作过」的人 —— 这条派生查询的真实 Hibernate 启动与读数门。
 * 工厂、实例、操作人三者都要对上, 换掉任何一个都必须是 false。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ApprovalHistoryActorExistsRepositoryQueryValidationTest {

    @Autowired ApprovalHistoryRepository historyRepository;

    @Test
    void existsOnlyForThatFactoryInstanceAndActor() {
        historyRepository.saveAndFlush(ApprovalHistory.builder()
                .factoryId("S512-OA")
                .instanceId("inst-1")
                .nodeId("finance_approval")
                .action(HistoryAction.APPROVE)
                .actorId(7L)
                .createdAt(LocalDateTime.now())
                .build());

        assertThat(historyRepository.existsByFactoryIdAndInstanceIdAndActorId("S512-OA", "inst-1", 7L)).isTrue();
        assertThat(historyRepository.existsByFactoryIdAndInstanceIdAndActorId("S512-OA", "inst-1", 8L)).isFalse();
        assertThat(historyRepository.existsByFactoryIdAndInstanceIdAndActorId("S512-OA", "inst-2", 7L)).isFalse();
        assertThat(historyRepository.existsByFactoryIdAndInstanceIdAndActorId("OTHER", "inst-1", 7L)).isFalse();
    }
}
