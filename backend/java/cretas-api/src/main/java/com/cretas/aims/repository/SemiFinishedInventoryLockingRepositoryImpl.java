package com.cretas.aims.repository;

import com.cretas.aims.entity.SemiFinishedInventory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * {@link SemiFinishedInventoryLockingRepository} 的实现, 见接口说明。
 * Spring Data 按类名后缀 {@code Impl} 把它挂到 {@link SemiFinishedInventoryRepository} 上。
 *
 * <p>⛔ 不能写成「{@code SELECT … FOR UPDATE} 查实体, 再 refresh」: 那一行已在持久化上下文里时,
 * Hibernate 在锁查询返回前拿库里的版本和旧对象比, 不一致直接抛乐观锁异常 (R6 真库实测), 根本走不到 refresh。
 * 所以先只查 id (不装实体; 这一查会把本事务对这张表的改动先 flush 下去), 再对每一行用一条只取 id 的
 * {@code SELECT … FOR UPDATE} 加锁, 锁到之后 {@code refresh} —— 用库里的值覆盖, 不和旧对象比版本。
 * 锁到之后再核一次条件 (软删 / 工厂 / 批次号), 等锁期间被别人改掉的行不返回。
 *
 * <p>⛔ 不用 {@code refresh(row, PESSIMISTIC_WRITE)} 一步加锁: 等锁期间那一行被别人硬删时它抛
 * {@code EntityNotFoundException}, 这是 PersistenceException, Hibernate 已把整个事务标成只能回滚 ——
 * 在这里 catch 掉也没用, 调用方一路成功走到提交才炸 {@code UnexpectedRollbackException} (#1550 审查 5)。
 * 先用加锁查询: 行没了就是查不到 (零行, 不抛), 跳过; 查到了就已经锁住, 之后的 refresh 读的一定是在的行。
 */
public class SemiFinishedInventoryLockingRepositoryImpl implements SemiFinishedInventoryLockingRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<SemiFinishedInventory> findForUpdateByIntermediateBatchNoAndDeletedAtIsNull(
            String intermediateBatchNo) {
        return single(entityManager.createQuery("SELECT w.id FROM SemiFinishedInventory w "
                        + "WHERE w.intermediateBatchNo = :intermediateBatchNo AND w.deletedAt IS NULL", Long.class)
                        .setParameter("intermediateBatchNo", intermediateBatchNo),
                row -> Objects.equals(intermediateBatchNo, row.getIntermediateBatchNo()));
    }

    @Override
    public Optional<SemiFinishedInventory> findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
            String factoryId, String intermediateBatchNo) {
        return single(entityManager.createQuery("SELECT w.id FROM SemiFinishedInventory w WHERE w.factoryId = :factoryId "
                        + "AND w.intermediateBatchNo = :intermediateBatchNo AND w.deletedAt IS NULL", Long.class)
                        .setParameter("factoryId", factoryId)
                        .setParameter("intermediateBatchNo", intermediateBatchNo),
                row -> Objects.equals(factoryId, row.getFactoryId())
                        && Objects.equals(intermediateBatchNo, row.getIntermediateBatchNo()));
    }

    @Override
    public Optional<SemiFinishedInventory> findByIdForUpdate(Long id) {
        return single(entityManager.createQuery("SELECT w.id FROM SemiFinishedInventory w "
                        + "WHERE w.id = :id AND w.deletedAt IS NULL", Long.class)
                        .setParameter("id", id),
                row -> true);
    }

    @Override
    public Optional<SemiFinishedInventory> findByIdAndFactoryIdForUpdate(Long id, String factoryId) {
        return single(entityManager.createQuery("SELECT w.id FROM SemiFinishedInventory w WHERE w.id = :id "
                        + "AND w.factoryId = :factoryId AND w.deletedAt IS NULL", Long.class)
                        .setParameter("id", id)
                        .setParameter("factoryId", factoryId),
                row -> Objects.equals(factoryId, row.getFactoryId()));
    }

    @Override
    public List<SemiFinishedInventory> lockForDeviationHold(String factoryId, Collection<Long> ids) {
        return lockedFresh(entityManager.createQuery("SELECT w.id FROM SemiFinishedInventory w "
                        + "WHERE w.factoryId = :factoryId AND w.id IN :ids AND w.deletedAt IS NULL ORDER BY w.id",
                        Long.class)
                        .setParameter("factoryId", factoryId)
                        .setParameter("ids", ids),
                row -> Objects.equals(factoryId, row.getFactoryId()));
    }

    private Optional<SemiFinishedInventory> single(TypedQuery<Long> idQuery, Predicate<SemiFinishedInventory> stillMatches) {
        List<SemiFinishedInventory> rows = lockedFresh(idQuery, stillMatches);
        if (rows.size() > 1) {
            throw new IncorrectResultSizeDataAccessException(1, rows.size());
        }
        return rows.stream().findFirst();
    }

    /** 按 id 升序逐行加锁 (固定加锁顺序, 防交叉等锁死锁) 并从库里重读; 锁到之后不再满足条件的行丢掉。 */
    private List<SemiFinishedInventory> lockedFresh(TypedQuery<Long> idQuery,
                                                    Predicate<SemiFinishedInventory> stillMatches) {
        List<Long> ids = idQuery.getResultList().stream().filter(Objects::nonNull).distinct().sorted().toList();
        List<SemiFinishedInventory> rows = new ArrayList<>(ids.size());
        for (Long id : ids) {
            // 先锁 (只取 id, 不装实体): 等锁期间被硬删的行在这里就是零行, 不抛异常、不污染事务
            //    (只同步这张表: 与上面的 id 查询同一个 flush 范围, 不把别的表的未提交改动一并刷下去)
            if (entityManager.createNativeQuery("SELECT id FROM semi_finished_inventory WHERE id = :id FOR UPDATE")
                    .unwrap(org.hibernate.query.NativeQuery.class)
                    .addSynchronizedEntityClass(SemiFinishedInventory.class)
                    .setParameter("id", id).getResultList().isEmpty()) {
                continue;
            }
            SemiFinishedInventory row = entityManager.find(SemiFinishedInventory.class, id);
            if (row == null) {
                continue;
            }
            entityManager.refresh(row);   // 已持有行锁, 这一行一定还在; 用库里的值覆盖持久化上下文里的旧对象
            if (row.getDeletedAt() == null && stillMatches.test(row)) {
                rows.add(row);
            }
        }
        return rows;
    }
}
