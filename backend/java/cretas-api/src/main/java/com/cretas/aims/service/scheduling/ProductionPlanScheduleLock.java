package com.cretas.aims.service.scheduling;

import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.repository.ProductionPlanRepository;

import java.util.Collection;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 排程落库前锁住要写排程行的生产计划 (缺口 18 后续)。
 *
 * <p>取消生产计划会锁住计划行, 再作废它没开工的排程。生成排程 / 插单在计划读出来之后、写排程行之前若不锁,
 * 取消先提交的话, 新写的排程行就挂在已取消的计划下 (继续占产线)。这里按 id 排序逐个锁住并读已提交的状态:
 * 加锁顺序固定为「生产计划 → 日调度计划」, 与取消一致。
 *
 * <p>普通工具类, 不是 Spring Bean: 调用方用自己已注入的 repository。
 */
public final class ProductionPlanScheduleLock {

    private ProductionPlanScheduleLock() {
    }

    /**
     * 按 id 排序逐个锁住; 返回第一个已取消 / 已完成的计划 id, 都没有就 null。
     * 查不到行 (锁之前已校验过存在) 不在这里判, 交给调用方原有的存在性校验。
     */
    public static String lockAndFindClosed(ProductionPlanRepository repository, String factoryId,
                                           Collection<String> planIds) {
        String closed = null;
        for (String id : new TreeSet<>(planIds.stream().filter(Objects::nonNull).toList())) {
            String status = repository.lockStatusForUpdate(id, factoryId).orElse(null);
            if (closed == null && (ProductionPlanStatus.CANCELLED.name().equals(status)
                    || ProductionPlanStatus.COMPLETED.name().equals(status))) {
                closed = id;
            }
        }
        return closed;
    }
}
