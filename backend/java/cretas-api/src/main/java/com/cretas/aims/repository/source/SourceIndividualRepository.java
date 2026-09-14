package com.cretas.aims.repository.source;

import com.cretas.aims.entity.source.SourceIndividual;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 源头个体档案仓储。
 *
 * <p>软删过滤由实体类上的 {@code @Where(deleted_at IS NULL)} 负责 —— 这里的每一条查询
 * 看到的都已经是活行, ⛔ 不要再手写 {@code AND deleted_at IS NULL}(写了也不会生效两次,
 * 只会让下一个人以为某些查询漏了过滤)。
 */
@Repository
public interface SourceIndividualRepository extends JpaRepository<SourceIndividual, String> {

    /** 档案编号在工厂内唯一(部分唯一索引) ⇒ 这里可以是 Optional。 */
    Optional<SourceIndividual> findByFactoryIdAndIndividualCode(String factoryId, String individualCode);

    /**
     * 按物理标识(耳标/脚环号)查。
     *
     * <p>🔴 返回 {@code List} 而不是 {@code Optional} 是<b>刻意</b>的: 耳标会脱落重挂、
     * 跨场引进可能撞号 ⇒ 库上没有唯一约束, 真的可能有多条。
     * 用 {@code Optional} 会在撞号那天抛 {@code NonUniqueResultException},
     * 而那个异常长得像「查询写错了」, 不像「这个耳标号有两头牛」。
     * 让调用方拿到全部命中并自己决定怎么说, 是唯一诚实的形状。
     *
     * <p>按建档时间倒序 —— 重挂耳标的场景里, 最近建的那条才是当前个体。
     */
    List<SourceIndividual> findByFactoryIdAndTagNumberOrderByCreatedAtDesc(String factoryId, String tagNumber);

    Optional<SourceIndividual> findByIdAndFactoryId(String id, String factoryId);

    Page<SourceIndividual> findByFactoryId(String factoryId, Pageable pageable);

    /**
     * 关键词检索: 档案编号 / 耳标号 / 物种 / 品种 / 来源场 任一命中即可。
     *
     * <p>关键词为空时本查询不该被调用(走 {@link #findByFactoryId})——
     * 这里仍然对空串保持可用(LIKE '%%' 命中全部), 但那是兜底, 不是入口。
     */
    @Query("""
            SELECT si FROM SourceIndividual si
             WHERE si.factoryId = :factoryId
               AND (LOWER(si.individualCode)    LIKE LOWER(CONCAT('%', :keyword, '%'))
                 OR LOWER(COALESCE(si.tagNumber, ''))   LIKE LOWER(CONCAT('%', :keyword, '%'))
                 OR LOWER(COALESCE(si.species, ''))     LIKE LOWER(CONCAT('%', :keyword, '%'))
                 OR LOWER(COALESCE(si.breed, ''))       LIKE LOWER(CONCAT('%', :keyword, '%'))
                 OR LOWER(COALESCE(si.originFarm, ''))  LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<SourceIndividual> searchByKeyword(@Param("factoryId") String factoryId,
                                           @Param("keyword") String keyword,
                                           Pageable pageable);

    boolean existsByFactoryIdAndIndividualCode(String factoryId, String individualCode);
}
