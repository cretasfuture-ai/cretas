package com.cretas.aims.repository.lineage;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 批次溯源有向边 Repository — append-only forensic, 不删.
 *
 * <p>合并自 Phase 1 D3 + Canvas Phase A (2026-05-22):
 * <ul>
 *   <li>findByFactoryIdAndSourceIdAndSourceType / findByFactoryIdAndTargetIdAndTargetType — 两边共用</li>
 *   <li>findByEdgeTypeAndEventTimeBetween + countByFactoryId — D3 service layer 入口</li>
 *   <li>findByFactoryId — Canvas Phase A 全量入口</li>
 * </ul>
 */
@Repository
public interface BatchLineageEdgeRepository extends JpaRepository<BatchLineageEdge, String> {

    /** 查询某源批次的全部下游 edge (forward walk). */
    List<BatchLineageEdge> findByFactoryIdAndSourceIdAndSourceType(
            String factoryId, String sourceId, String sourceType);

    /** 查询某目标批次的全部上游 edge (backward walk). */
    List<BatchLineageEdge> findByFactoryIdAndTargetIdAndTargetType(
            String factoryId, String targetId, String targetType);

    /** 按 edgeType + 时间窗查询 (D3 service 合规审计场景). */
    @Query("SELECT e FROM BatchLineageEdge e " +
           "WHERE e.factoryId = :factoryId " +
           "AND e.edgeType = :edgeType " +
           "AND e.eventTime BETWEEN :fromTime AND :toTime " +
           "ORDER BY e.eventTime ASC")
    List<BatchLineageEdge> findByEdgeTypeAndEventTimeBetween(
            @Param("factoryId") String factoryId,
            @Param("edgeType") String edgeType,
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime);

    /**
     * 幂等检查专用 — 精确定位 (factory, edgeType, source, target) 这一组边.
     *
     * <p><b>为什么不把 {@code meta->>'refKey'} 也写进查询</b>: {@code meta} 是 jsonb,
     * JSON 路径谓词是 PostgreSQL 方言, 而本仓的 JPA slice 测试跑在 H2 上 ——
     * 把它写进 JPQL 会让闸在 H2 上直接起不来(而那是我们唯一能自动跑的地方)。
     * 这一组的基数天然极小(同一对批次之间的边通常 0~2 条), 拉回来在 Java 侧按
     * refKey 比对既准确又便宜。⛔ 不要为了"少一次 Java 循环"把它改成 native query。
     *
     * <p>append-only, 不带 deleted_at 过滤 (与本实体其它查询一致, forensic 语义)。
     */
    @Query("SELECT e FROM BatchLineageEdge e " +
           "WHERE e.factoryId = :factoryId " +
           "AND e.edgeType = :edgeType " +
           "AND e.sourceType = :sourceType AND e.sourceId = :sourceId " +
           "AND e.targetType = :targetType AND e.targetId = :targetId")
    List<BatchLineageEdge> findMatchingEdges(@Param("factoryId") String factoryId,
                                             @Param("edgeType") String edgeType,
                                             @Param("sourceType") String sourceType,
                                             @Param("sourceId") String sourceId,
                                             @Param("targetType") String targetType,
                                             @Param("targetId") String targetId);

    /**
     * 一次拉回一组节点的全部出边 — 血缘视图组装用, 防 N+1.
     *
     * <p>只按 id 过滤不按 type: 调用方拿到之后在内存里按 (type,id) 配对即可,
     * 而把 type 也写进 IN 会需要元组比较 (JPQL 支持差)。
     */
    @Query("SELECT e FROM BatchLineageEdge e WHERE e.factoryId = :factoryId AND e.sourceId IN :nodeIds")
    List<BatchLineageEdge> findByFactoryIdAndSourceIdIn(@Param("factoryId") String factoryId,
                                                        @Param("nodeIds") Collection<String> nodeIds);

    /** 一次拉回一组节点的全部入边 — 血缘视图组装用, 防 N+1. */
    @Query("SELECT e FROM BatchLineageEdge e WHERE e.factoryId = :factoryId AND e.targetId IN :nodeIds")
    List<BatchLineageEdge> findByFactoryIdAndTargetIdIn(@Param("factoryId") String factoryId,
                                                        @Param("nodeIds") Collection<String> nodeIds);

    /** 工厂全部 edges 计数 — 监控指标 dashboard 用. */
    long countByFactoryId(String factoryId);

    /** Canvas Phase A 全量入口 — 跨多种 source/target 类型组成 DAG. */
    List<BatchLineageEdge> findByFactoryId(String factoryId);
}
