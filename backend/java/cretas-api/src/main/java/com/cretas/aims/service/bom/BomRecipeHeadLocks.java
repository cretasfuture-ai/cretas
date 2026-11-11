package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.repository.bom.BomRecipeRepository;

import java.util.List;
import java.util.Optional;

/**
 * 改价路径锁配方头的唯一入口 (N1, 2026-11-11)。
 *
 * <p>调价批准与包材成本更正都是「先锁配方行, 再锁配方头, 再重算整族成本」。重算会改写同一 BOM Family
 * 所有成员的配方头 (联产品共享投入按比例分摊), 所以两个成员同时改价时, 只锁自己那一个头会形成环:
 * 甲锁 A 头后去写 B 头, 乙锁 B 头后去写 A 头, PostgreSQL 检测到死锁回滚其中一方。</p>
 *
 * <p>这里改成: 先取 family id (标量, 不把实体读进持久化上下文), 再按配方 id 升序一次锁住全族的头。
 * 后到的一方在第一个头上排队, 不再互等。没有 family 的配方只锁自己。</p>
 *
 * <p><b>走这个锁序的路径</b> (行 FOR UPDATE → 整族头 → 读头 → 写): 调价批准、包材成本更正、
 * 草稿明细的新增 / 修改 / 删除 ({@code addItem} 新增行无需锁行)。</p>
 *
 * <p><b>还没走的路径</b> (独立审查 2026-11-11 指出, 本次未改): 建配方 / 整表更新 / 克隆 / 激活 / 升级工艺 /
 * 调料保存 / 试算 / 族产出分摊设置等其余 {@code recomputeFamilyCosts} 调用方。它们不锁配方头 (按产品锁或不加锁),
 * 与上面的路径并发时 PG 仍可能判死锁回滚一方 (不写坏数据), 或用并发前读到的行重算配方头。
 * ⛔ 不要把这里写成「所有路径」—— 要扩面就逐个接入并补并发测试。</p>
 *
 * <p>⛔ 调用前不要无锁读过同族配方: 先无锁读进来的托管对象, 之后的 FOR UPDATE 不会刷新它的字段。</p>
 */
public final class BomRecipeHeadLocks {

    private BomRecipeHeadLocks() {
    }

    /** 锁住目标配方所在 Family 的全部配方头 (按 id 升序), 返回目标配方; 不存在则为空。 */
    public static Optional<BomRecipe> lockWithFamily(BomRecipeRepository recipes, String factoryId, String recipeId) {
        Optional<String> familyId = recipes.findBomFamilyIdByIdAndFactoryId(recipeId, factoryId);
        if (familyId.isEmpty() || familyId.get().isBlank()) {
            return recipes.lockByIdAndFactoryId(recipeId, factoryId);
        }
        List<BomRecipe> family = recipes.lockFamilyByFactoryIdAndBomFamilyIdOrderById(factoryId, familyId.get());
        Optional<BomRecipe> target = family.stream()
                .filter(member -> recipeId.equals(member.getId()))
                .findFirst();
        // 读 family id 与加锁之间配方被并发改绑到别的 family: 退回只锁它自己 (族锁仍按序持有, 不会成环)
        return target.isPresent() ? target : recipes.lockByIdAndFactoryId(recipeId, factoryId);
    }
}
