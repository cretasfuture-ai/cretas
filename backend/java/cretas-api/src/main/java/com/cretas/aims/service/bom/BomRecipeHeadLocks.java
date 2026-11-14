package com.cretas.aims.service.bom;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 写 BOM Family 成本的锁序唯一入口 (N1 2026-11-11; 红线 3 2026-11-13 扩到全部重算调用方)。
 *
 * <p>成本重算 ({@code BomRecipeServiceImpl#recomputeFamilyCosts}) 会读同一 BOM Family 所有成员的明细行,
 * 再改写全族的配方头 (联产品共享投入按比例分摊)。配方头没有乐观锁、Hibernate 整行 UPDATE, 所以:
 * 只锁自己那个头会互等成环 (PG 判死锁回滚一方); 不锁则用并发前读到的行算成本, 再整行写回, 盖掉别人刚提交的成本。</p>
 *
 * <p><b>全局锁序</b> (所有写配方族的路径都按这个方向, 不会成环):</p>
 * <ol>
 *   <li>产品行 {@code products FOR UPDATE} —— 只有分配版本 / 激活 / 整表更新的路径拿; 激活 / 整表更新拿多个时按产品 id 升序。
 *       ⚠️ <b>还不是全局升序</b>: {@code initializeFamilyAndInputSkeletons} 与 {@code rebindDraftFamilyToExactRevision}
 *       在已持有请求产品 P (+ 头锁) 之后, 才为新增的联产 SKU Q 锁产品行 —— 联产输出要先按 P 解析工艺图才知道,
 *       没法低成本提前一次性收集。若另一事务同时激活一个含 P、Q 且 Q &lt; P 的族 (先锁 Q 再等 P), 两边可成环,
 *       PG 判死锁回滚其中一方 (整个事务回滚, 不写坏数据, 用户重试即可)。窗口很窄: 两处在锁 Q 之前都先查
 *       「Q 已有草稿」并 409 (BOM_FAMILY_EXISTING_DRAFT_CONFLICT), 而激活 / 整表更新只作用于草稿族 ——
 *       成环要求含 Q 的草稿族恰好在这次检查之后才提交、并在本事务锁 Q 之前被另一事务激活。
 *       收敛办法 (未做, 已登记): 先解析工艺图拿到全部输出 SKU, 再按 id 升序一次锁全部产品。</li>
 *   <li>物料 → 调价建议 (调价批准; 与确认入库同向)。</li>
 *   <li><b>整族配方头</b>, 按配方 id 升序一次锁住 ({@link #lockWithFamily})。</li>
 *   <li>明细行 {@code bom_recipe_items FOR UPDATE}。</li>
 *   <li>激活时被替换的旧 ACTIVE 版本配方头, 按 id 升序 (见 {@code BomRecipeServiceImpl#archiveCompetingVersions})。</li>
 * </ol>
 * <p>⛔ 不要先锁明细行再锁头 (2026-11-11 的 N1 是「行 → 头」, 2026-11-13 统一改成「头 → 行」: 整表更新 / 激活 /
 * 升级会在持有头之后改写明细行, 两个方向并存就会成环)。</p>
 *
 * <p><b>本事务持有登记</b>: 每次加锁 / 本事务新建一个族, 都记进当前事务的资源里;
 * 重算入口用 {@link #requireHeld} 检查 —— 新加一个重算调用方却忘了先加锁, 在真实事务里会直接失败,
 * 而不是静默地用旧数据算成本。没有事务同步的场合 (纯 Mockito 单测) 不登记也不检查。</p>
 *
 * <p>⛔ 调用前不要无锁读过同族配方: 先无锁读进来的托管对象, 之后的 FOR UPDATE 不会刷新它的字段。
 * 所以锁必须放在入口最前面, 不能放进重算里面 (那时行和头都已经读过、甚至改过了)。</p>
 */
public final class BomRecipeHeadLocks {

    private static final Object HELD_KEY = new Object();

    /** 重算时本事务没有持有该族的锁 (多为加锁前后被并发改绑): 409, 用户刷新重试。 */
    public static final String FAMILY_LOCK_STALE = "BOM_FAMILY_LOCK_STALE";

    private BomRecipeHeadLocks() {
    }

    /** 锁住目标配方所在 Family 的全部配方头 (按 id 升序), 返回目标配方; 不存在则为空。 */
    public static Optional<BomRecipe> lockWithFamily(BomRecipeRepository recipes, String factoryId, String recipeId) {
        Optional<String> familyId = recipes.findBomFamilyIdByIdAndFactoryId(recipeId, factoryId);
        if (familyId.isEmpty() || familyId.get().isBlank()) {
            Optional<BomRecipe> alone = recipes.lockByIdAndFactoryId(recipeId, factoryId);
            alone.ifPresent(BomRecipeHeadLocks::registerHeld);
            return alone;
        }
        List<BomRecipe> family = recipes.lockFamilyByFactoryIdAndBomFamilyIdOrderById(factoryId, familyId.get());
        registerHeldFamily(factoryId, familyId.get());
        Optional<BomRecipe> target = family.stream()
                .filter(member -> recipeId.equals(member.getId()))
                .findFirst();
        if (target.isPresent()) {
            return target;
        }
        // 读 family id 与加锁之间配方被并发改绑到别的 family: 退回只锁它自己 (族锁仍按序持有, 不会成环);
        // 登记的是它自己, 不是它的新族 —— 重算整族时 requireHeld 会拒绝, 让调用方重试。
        Optional<BomRecipe> moved = recipes.lockByIdAndFactoryId(recipeId, factoryId);
        moved.ifPresent(recipe -> register(recipeKey(factoryId, recipe.getId())));
        return moved;
    }

    /**
     * 本事务刚新建的族 (新 UUID, 别的事务看不见这些行, 无从争用): 登记为已持有。
     * ⛔ 只能用在本事务生成的 family id 上, 不能拿来给已存在的族「免锁」。
     */
    public static void registerCreatedFamily(String factoryId, String familyId) {
        registerHeldFamily(factoryId, familyId);
    }

    /**
     * 重算入口的守卫: 被重算的族必须已在本事务里按上面的锁序拿到 (或是本事务新建的)。
     * 没有事务同步 (单测) 时不检查。
     */
    public static void requireHeld(BomRecipe changed) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        Set<String> held = heldSet(false);
        String key = keyOf(changed);
        if (held == null || !held.contains(key)) {
            // 运行时最常见的成因是加锁前后配方被并发改绑到别的族 (见 lockWithFamily 的回退分支): 对用户是
            // 「别人刚改过, 重试即可」, 所以给 409 而不是通用 500。技术细节留在 cause 里进日志。
            IllegalStateException detail = new IllegalStateException(
                    "BOM 成本重算前没有按锁序锁住整族配方头: recipe=" + changed.getId()
                    + ", family=" + changed.getBomFamilyId()
                    + " (先调用 BomRecipeHeadLocks.lockWithFamily, 见类注释)");
            throw new BusinessException(409, "配方已被别人改动，请刷新后重试", detail)
                    .withCode(FAMILY_LOCK_STALE)
                    .withHint("刷新配方页面后重新操作；若反复出现，请联系管理员");
        }
    }

    private static void registerHeld(BomRecipe recipe) {
        register(keyOf(recipe));
    }

    private static void registerHeldFamily(String factoryId, String familyId) {
        register(familyKey(factoryId, familyId));
    }

    private static String keyOf(BomRecipe recipe) {
        String familyId = recipe.getBomFamilyId();
        return familyId == null || familyId.isBlank()
                ? recipeKey(recipe.getFactoryId(), recipe.getId())
                : familyKey(recipe.getFactoryId(), familyId);
    }

    private static String familyKey(String factoryId, String familyId) {
        return factoryId + "|F|" + familyId;
    }

    private static String recipeKey(String factoryId, String recipeId) {
        return factoryId + "|R|" + recipeId;
    }

    private static void register(String key) {
        Set<String> held = heldSet(true);
        if (held != null) {
            held.add(key);
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<String> heldSet(boolean create) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        Set<String> held = (Set<String>) TransactionSynchronizationManager.getResource(HELD_KEY);
        if (held == null && create) {
            Set<String> fresh = new HashSet<>();
            TransactionSynchronizationManager.bindResource(HELD_KEY, fresh);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void suspend() {
                    TransactionSynchronizationManager.unbindResourceIfPossible(HELD_KEY);
                }

                @Override
                public void resume() {
                    TransactionSynchronizationManager.bindResource(HELD_KEY, fresh);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(HELD_KEY);
                }
            });
            held = fresh;
        }
        return held;
    }
}
