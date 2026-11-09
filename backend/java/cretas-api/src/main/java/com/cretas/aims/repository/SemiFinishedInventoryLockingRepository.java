package com.cretas.aims.repository;

import com.cretas.aims.entity.SemiFinishedInventory;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 半成品库存行的加锁读取 —— 拿到行锁之后一律<b>从库里重读</b>这一行 (R6, 2026-11-09)。
 *
 * <p>为什么不能只靠 {@code @Lock} 查询: 同一事务里先前读过这一行时 (结单预览、折算投料量、找来源都会先读),
 * 别的事务又提交了一笔扣减, {@code SELECT … FOR UPDATE} 等到锁之后 Hibernate 拿库里的版本和持久化上下文里的
 * 旧对象比 (表和实体都有 {@code @Version}), 不一致就抛乐观锁异常整单回滚 —— 不会静默覆盖, 但一次本该成功的
 * 扣减/还原变成 500, 余额够不够也没按真余额判 (R6 真库实测, 见 SemiFinishedLockRefreshPostgresContractTest)。
 *
 * <p>所以这里的每个方法: 先只查 id (这一查会把本事务对这张表的改动先 flush, 不丢自己的写) →
 * 逐行 {@code refresh(row, PESSIMISTIC_WRITE)} 加锁并用库里的值覆盖 → 锁到之后再核一次条件 → 返回。
 * 所有调用方 (扣减、还原、撤回、放行、扣留) 拿到的都是锁内的真实余额,
 * ⛔ 不需要、也不要在调用方各自再补一次刷新。
 *
 * <p>{@code @Lock} 注解留在这里作契约 (闸测试读它), 实际加锁在 {@link SemiFinishedInventoryLockingRepositoryImpl}。
 */
public interface SemiFinishedInventoryLockingRepository {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SemiFinishedInventory> findForUpdateByIntermediateBatchNoAndDeletedAtIsNull(String intermediateBatchNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SemiFinishedInventory> findForUpdateByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(
            String factoryId, String intermediateBatchNo);

    /** 按 ID 加悲观写锁 (SP2 二次加工计划创建时扣减 WIP 用)。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SemiFinishedInventory> findByIdForUpdate(Long id);

    /** 放行决定时按 id + 工厂加写锁 (防两个质检员同时放行/判不合格同一行)。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SemiFinishedInventory> findByIdAndFactoryIdForUpdate(Long id, String factoryId);

    /**
     * CCP 偏离扣留 (审查中 8): 先对这些行加行锁 (SELECT … FOR UPDATE) 再改 —— 锁住之后没有别的事务能在
     * 「读」和「写」之间扣减。按 id 固定加锁顺序, 防两个扣留事务交叉等锁死锁 (审查 L2)。
     * (不用批量 UPDATE: 它要清空持久化上下文, 同一事务里随后的读取全部重载, 已持有的实体也会脱管。)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<SemiFinishedInventory> lockForDeviationHold(String factoryId, Collection<Long> ids);
}
