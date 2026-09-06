package com.cretas.aims.repository;

import com.cretas.aims.entity.tool.ToolMetadata;
import jakarta.persistence.EntityManager;
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
 * tool_metadata 的表 2026-09-06 之前在任何库里都没建过 (V20261101_36 补建)，所以
 * {@code ToolMetadataRepository} 的派生查询与 {@code @Modifying} JPQL 从来没有在任何数据库上跑过。
 * 这里把 SkillManageTool deprecate_tool / reactivate_tool 真正走的两条路
 * ({@code findByToolName} + save) 和 {@code incrementCallStats} 各跑一遍。
 *
 * <p>H2 这里建表用的是实体 (ddl-auto=create)，⛔ 不证明 V20261101_36 的列与实体一致 ——
 * 那一条由 prod schema 副本上的 validate 起动证明 (见 PR 正文)。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ToolMetadataRepositoryQueryValidationTest {

    @Autowired ToolMetadataRepository repository;
    @Autowired EntityManager entityManager;

    @Test
    void deprecateThenReactivateRoundTripsThroughFindByToolName() {
        // deprecate_tool 分支: findByToolName 为空 -> builder 新建 -> save
        assertThat(repository.findByToolName("lineb_probe_tool")).isEmpty();
        ToolMetadata created = ToolMetadata.builder().toolName("lineb_probe_tool").build();
        created.setDeprecationNotice("line B probe");
        repository.saveAndFlush(created);

        ToolMetadata reloaded = repository.findByToolName("lineb_probe_tool").orElseThrow();
        assertThat(reloaded.getDeprecationNotice()).isEqualTo("line B probe");
        // @Builder.Default 的四个默认值要真的落进行里 (NotNull 列没有 Java 默认会插 NULL)
        assertThat(reloaded.getActionType()).isEqualTo("READ");
        assertThat(reloaded.getRiskLevel()).isEqualTo("LOW");
        assertThat(reloaded.getCallCount()).isZero();
        assertThat(reloaded.getDomainTags()).isEqualTo("[]");
        assertThat(repository.findByDeprecationNoticeIsNotNull()).extracting(ToolMetadata::getToolName)
                .contains("lineb_probe_tool");

        // reactivate_tool 分支: 清掉 deprecation_notice
        reloaded.setDeprecationNotice(null);
        repository.saveAndFlush(reloaded);
        assertThat(repository.findByToolName("lineb_probe_tool").orElseThrow().getDeprecationNotice()).isNull();
    }

    @Test
    void incrementCallCountUpdatesCountersAndTimestamp() {
        ToolMetadata created = repository.saveAndFlush(
                ToolMetadata.builder().toolName("lineb_stats_tool").build());
        LocalDateTime calledAt = LocalDateTime.of(2026, 9, 6, 12, 0);

        int updated = repository.incrementCallCount("lineb_stats_tool", calledAt, 1L, 0L);
        entityManager.clear();

        assertThat(updated).isEqualTo(1);
        ToolMetadata reloaded = repository.findById(created.getId()).orElseThrow();
        assertThat(reloaded.getCallCount()).isEqualTo(1L);
        assertThat(reloaded.getSuccessCount()).isEqualTo(1L);
        assertThat(reloaded.getFailureCount()).isZero();
        assertThat(reloaded.getLastCalledAt()).isEqualTo(calledAt);

        // 阴性对照: 不存在的工具名一行都不更新
        assertThat(repository.incrementCallCount("no_such_tool", calledAt, 0L, 1L)).isZero();
    }
}
