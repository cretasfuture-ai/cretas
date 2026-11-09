package com.cretas.aims.repository;

import com.cretas.aims.entity.SemiFinishedInventory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.LockModeType;
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
 * 所以先只查 id (不装实体; 这一查会把本事务对这张表的改动先 flush 下去), 再对每一行
 * {@code refresh(row, PESSIMISTIC_WRITE)} —— 加锁并用库里的值覆盖, 不和旧对象比版本。
 * 锁到之后再核一次条件 (软删 / 工厂 / 批次号), 等锁期间被别人改掉的行不返回。
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
            SemiFinishedInventory row = entityManager.find(SemiFinishedInventory.class, id);
            if (row == null) {
                continue;
            }
            try {
                entityManager.refresh(row, LockModeType.PESSIMISTIC_WRITE);
            } catch (EntityNotFoundException gone) {
                continue;
            }
            if (row.getDeletedAt() == null && stillMatches.test(row)) {
                rows.add(row);
            }
        }
        return rows;
    }
}
