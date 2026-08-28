package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.SalesException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 销售异常单 Repository。
 */
@Repository
public interface SalesExceptionRepository extends JpaRepository<SalesException, String> {

    List<SalesException> findByFactoryIdOrderByAttemptedAtDesc(String factoryId);

    List<SalesException> findByFactoryIdAndDeliveryRecordIdOrderByAttemptedAtDesc(
            String factoryId, String deliveryRecordId);

    /**
     * 取当天该工厂已经发出去的<b>最大</b>单号，用于生成下一个号。
     *
     * <h2>🔴 为什么必须是 native SQL</h2>
     * 问题是「这个号<b>被占用</b>了吗」。本仓软删全局生效
     * ({@code BaseEntity @SQLDelete} + {@code SalesException @Where(deleted_at IS NULL)}),
     * 任何 JPQL / 派生查询都会被 Hibernate 悄悄加上 {@code AND deleted_at IS NULL},
     * 于是答案变成「有没有一行<b>活着的</b>记录用了它」——
     * 一个被软删的号会被判成「没人用」而重新发出去，历史行与新行同号。
     * 本仓已记录 57 个发号器里 20 个坏在这一条上。
     *
     * <p>{@code @Where} 只作用于 HQL / Criteria，<b>不作用于 native SQL</b>。
     * 所以这条查询直接读物理表，既不看 {@code deleted_at} 也不看 {@code status}，
     * 与迁移里那条<b>全量</b>唯一索引 {@code uidx_sales_exceptions_number}
     * (⛔ 不带 {@code WHERE deleted_at IS NULL}) 是同一个口径。
     *
     * <p>⛔ 同样的理由，这里也不能写成 {@code count(...) + 1}:
     * 采购侧 {@code generateExceptionNumber} 数的是「PENDING 的条数」，
     * 只要有一张被决策成 RESOLVED，下一个号就退回去撞上已存在的号。
     * <b>取号只能看已发出的最大号，不能看「还剩几条」。</b>
     *
     * <h2>为什么 ORDER BY 要先按长度</h2>
     * 序号是 {@code %04d} 零填充，四位以内字典序 == 数值序；
     * 但一旦某天超过 9999 变成五位，{@code "10000" < "9999"} 字典序会给出错的最大值。
     * 先按 {@code LENGTH} 降序就把这一格也覆盖掉了。
     *
     * @param factoryId     工厂
     * @param prefixPattern 形如 {@code "SE-F006-20260829-%"}
     * @return 至多一条；空表示今天该工厂还没发过号
     */
    @Query(value = "SELECT e.exception_number FROM sales_exceptions e "
            + "WHERE e.factory_id = :factoryId AND e.exception_number LIKE :prefixPattern "
            + "ORDER BY LENGTH(e.exception_number) DESC, e.exception_number DESC "
            + "LIMIT 1",
            nativeQuery = true)
    List<String> findHighestNumberForPrefix(@Param("factoryId") String factoryId,
                                            @Param("prefixPattern") String prefixPattern);
}
