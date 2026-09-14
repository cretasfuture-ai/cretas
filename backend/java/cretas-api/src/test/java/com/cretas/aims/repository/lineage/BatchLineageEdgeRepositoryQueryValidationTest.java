package com.cretas.aims.repository.lineage;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本 PR 新增的三个 JPQL 查询方法的<b>真实执行</b>验证。
 *
 * <h2>为什么单独建一个类</h2>
 *
 * <p>新增的 {@code findMatchingEdges} / {@code findByFactoryIdAndSourceIdIn} /
 * {@code findByFactoryIdAndTargetIdIn} 此前<b>一次都没被真实执行过</b>：
 * 服务层三个测试对这个仓库全都用 {@code @Mock}。
 *
 * <p>Spring Data 在仓库 bean 创建时会对 {@code @Query} 做一次 JPQL 语法校验，
 * 所以既有测试绿只能说明这三段「<b>能解析</b>」—— 不能说明「<b>结果正确</b>」。
 * 而这三个方法要守的恰恰是结果语义：
 * <ul>
 *   <li>{@code findMatchingEdges} 是幂等判据的取数口，多字段必须<b>精确</b>联合匹配；</li>
 *   <li>两个 {@code ...IdIn} 是血缘图组装的防 N+1 取数口，必须<b>按工厂隔离</b>。</li>
 * </ul>
 *
 * <p>⚠️ 本类用独立内存库并把 {@code JSONB} 映成 {@code VARCHAR}：
 * H2 的原生 JSON 类型读回来是双重编码的字符串，hypersistence 反序列化会炸。
 * 那是 H2 与 PG 的类型差异，不是被测行为，别让它污染这里的读数。
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:lineage_repo_qv;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
                + ";DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=MONTH,YEAR,VALUE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS VARCHAR",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.flyway.enabled=false",
        "spring.sql.init.mode=never",
        "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackageClasses = {BatchLineageEdge.class, com.cretas.aims.entity.BaseEntity.class})
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository.lineage")
@DisplayName("BatchLineageEdgeRepository 新增查询 — 真 H2 执行")
class BatchLineageEdgeRepositoryQueryValidationTest {

    private static final String F1 = "F-QV-1";
    private static final String F2 = "F-QV-2";

    @Autowired private BatchLineageEdgeRepository repo;

    @BeforeEach
    void seed() {
        // ⛔ 不能 deleteAll(): @PreRemove 禁止物理删除（append-only forensic）。
        repo.findAll().forEach(e -> { /* 只读一次确认可达 */ });
        repo.saveAndFlush(edge(F1, "RAW_TO_PRODUCTION", "MATERIAL_BATCH", "mb-1",
                "PRODUCTION_BATCH", "pb-1", "consumption:1"));
        repo.saveAndFlush(edge(F1, "RAW_TO_PRODUCTION", "MATERIAL_BATCH", "mb-1",
                "PRODUCTION_BATCH", "pb-1", "consumption:2"));
        repo.saveAndFlush(edge(F1, "RAW_TO_PRODUCTION", "MATERIAL_BATCH", "mb-2",
                "PRODUCTION_BATCH", "pb-1", "consumption:3"));
        repo.saveAndFlush(edge(F1, "PRODUCTION_TO_FINISHED", "PRODUCTION_BATCH", "pb-1",
                "FINISHED_BATCH", "fg-1", "finishedBatch:fg-1"));
        // 另一个工厂的同名节点 —— 跨租户隔离的阴性对照
        repo.saveAndFlush(edge(F2, "RAW_TO_PRODUCTION", "MATERIAL_BATCH", "mb-1",
                "PRODUCTION_BATCH", "pb-1", "consumption:1"));
    }

    @Test
    @DisplayName("findMatchingEdges: 六个字段联合精确匹配, 不跨工厂、不跨 edgeType")
    void findMatchingEdgesMatchesExactlyOnAllSixColumns() {
        List<BatchLineageEdge> hit = repo.findMatchingEdges(
                F1, "RAW_TO_PRODUCTION", "MATERIAL_BATCH", "mb-1", "PRODUCTION_BATCH", "pb-1");
        // 同一对批次之间两条不同 refKey 的真实投料 —— 两条都该命中（这是幂等判据的输入）
        assertThat(hit).hasSize(2);
        assertThat(hit).allSatisfy(e -> assertThat(e.getFactoryId()).isEqualTo(F1));
        assertThat(hit).extracting(e -> e.getMeta().get("refKey"))
                .containsExactlyInAnyOrder("consumption:1", "consumption:2");

        // 阴性：换 edgeType 就不该命中（若索引/查询漏了 edge_type，这条会红）
        assertThat(repo.findMatchingEdges(
                F1, "PRODUCTION_TO_FINISHED", "MATERIAL_BATCH", "mb-1", "PRODUCTION_BATCH", "pb-1"))
                .isEmpty();
        // 阴性：换 sourceId 不该命中
        assertThat(repo.findMatchingEdges(
                F1, "RAW_TO_PRODUCTION", "MATERIAL_BATCH", "mb-9", "PRODUCTION_BATCH", "pb-1"))
                .isEmpty();
        // 阴性：换工厂只命中那个工厂自己的那一条（同 id 同类型, 纯靠 factoryId 隔开）
        assertThat(repo.findMatchingEdges(
                F2, "RAW_TO_PRODUCTION", "MATERIAL_BATCH", "mb-1", "PRODUCTION_BATCH", "pb-1"))
                .hasSize(1);
    }

    @Test
    @DisplayName("findByFactoryIdAndSourceIdIn: 批量取出边, 且不跨工厂")
    void findBySourceIdInIsBatchedAndTenantScoped() {
        List<BatchLineageEdge> edges = repo.findByFactoryIdAndSourceIdIn(F1, List.of("mb-1", "mb-2"));

        assertThat(edges).hasSize(3);   // mb-1 两条 + mb-2 一条，F2 那条不在内
        assertThat(edges).allSatisfy(e -> assertThat(e.getFactoryId()).isEqualTo(F1));
        assertThat(edges).extracting(BatchLineageEdge::getSourceId)
                .containsExactlyInAnyOrder("mb-1", "mb-1", "mb-2");

        // 阴性 + 必发生：同一组 id 换到 F2 只剩 1 条 —— 若查询漏了 factoryId 谓词，两边都会是 4 条。
        assertThat(repo.findByFactoryIdAndSourceIdIn(F2, List.of("mb-1", "mb-2"))).hasSize(1);
    }

    @Test
    @DisplayName("findByFactoryIdAndTargetIdIn: 按目标节点批量取入边, 且不跨工厂")
    void findByTargetIdInIsBatchedAndTenantScoped() {
        List<BatchLineageEdge> edges = repo.findByFactoryIdAndTargetIdIn(F1, List.of("pb-1", "fg-1"));

        assertThat(edges).hasSize(4);   // 三条进 pb-1 + 一条进 fg-1
        assertThat(edges).allSatisfy(e -> assertThat(e.getFactoryId()).isEqualTo(F1));
        assertThat(edges).extracting(BatchLineageEdge::getTargetId)
                .containsExactlyInAnyOrder("pb-1", "pb-1", "pb-1", "fg-1");

        assertThat(repo.findByFactoryIdAndTargetIdIn(F2, List.of("pb-1", "fg-1"))).hasSize(1);
        // 不存在的 id ⇒ 空列表（⛔ 不是抛异常）
        assertThat(repo.findByFactoryIdAndTargetIdIn(F1, List.of("nope"))).isEmpty();
    }

    private static BatchLineageEdge edge(String factoryId, String edgeType,
                                         String sourceType, String sourceId,
                                         String targetType, String targetId, String refKey) {
        BatchLineageEdge e = new BatchLineageEdge();
        e.setFactoryId(factoryId);
        e.setEdgeType(edgeType);
        e.setSourceType(sourceType);
        e.setSourceId(sourceId);
        e.setTargetType(targetType);
        e.setTargetId(targetId);
        e.setQuantityUsed(new BigDecimal("1.5"));
        e.setUnit("kg");
        Map<String, Object> meta = new HashMap<>();
        meta.put("refKey", refKey);
        e.setMeta(meta);
        return e;
    }
}
