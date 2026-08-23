package com.cretas.aims.service.sales;

import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 一行销售订单卖的到底是什么 —— <b>唯一</b>的判定处。
 *
 * <h2>为什么要有这个类</h2>
 *
 * <p>{@code sales_order_items.product_type_id} 这一列<b>没有外键</b>，而它承载着
 * 两个（实际上是三个）互不相交的 id 域：
 *
 * <ul>
 *   <li>{@code product_types.id} —— 成品，公司自己生产的东西</li>
 *   <li>{@code raw_material_types.id} —— 原料 / 辅料 / 包材，2026-08-12 起销售订单可以直接卖</li>
 *   <li><b>两张表都查不到</b> —— 历史遗留 / 主数据被删。prod 实测存在
 *       （{@code b4f16b12-…} 「干式熟成鸡（半只）」，LIUSHANMEN，{@code FINANCE_APPROVED}）</li>
 * </ul>
 *
 * <p>下游有一列<b>有</b>外键：{@code production_plans.product_type_id →
 * product_types(id)}（{@code fkde8i961fabdlo5u4g6xiaqmyy}）。于是「把订单行的
 * id 原样透传去建生产计划」在后两种域上必然违约。
 *
 * <h2>prod 实证（2026-08-19 从 error 流全量取，口径见下）</h2>
 *
 * <pre>
 *   fkde8i961fabdlo5u4g6xiaqmyy      40 行提及（全部落在 2026-08-13 一天）
 *   其中带 Key 明细的 DETAIL 行       38 行，撞的 id 有 5 个：
 *       RMT_1785998666207            14   牛外脊西冷MB2+谷饲100天（原料）
 *       RMT_1782891387679             9   1.75L李锦记薄盐生抽（调味料）
 *       RMT_74b0b790-…                7   鸡油（原料）
 *       RMT_1784271051888             7   温氏黄油鸡（原料）
 *       b4f16b12-003d-…               1   两张表都查不到的那一个   ← 不是物料行
 * </pre>
 *
 * <p>⚠️ 最后那一个说明：只按「是不是物料」分叉<b>不够</b>。5 个撞库的 id 里有 1 个
 * 既不是成品也不是物料，{@code isMaterialLine} 对它返回 {@code false}，
 * 于是它照旧走成品路径、照旧撞同一个外键。所以本类返回<b>三态</b>而不是布尔。
 *
 * <h2>形态 D：这个判定原来有两份</h2>
 *
 * <p>{@code SalesServiceImpl} 早就有一份私有的 {@code isMaterialLine}（发货扣库存那条路在用，
 * 它让物料行去扣 {@code material_batches} 而不是成品批次）。财审联动那条路
 * <b>没有调它</b> —— 典型的形态 B（机制在、没接上）。
 *
 * <p>修法不是在联动侧再抄一份判据（那就是形态 D，两份一定会漂），
 * 而是把判定收敛到这里，两条路都调它。
 *
 * <h2>「既是启用商品又是物料」怎么办</h2>
 *
 * <p>两张表的 id 空间理论上可以重叠。prod 实测重叠 <b>5</b> 个，
 * 全部是画布生成的占位（{@code __RAW_WORKFLOW_OWNER__*}）且 {@code is_active = false}；
 * 「既是<b>启用</b>商品又是物料」的数量是 <b>0</b>。
 *
 * <p>⚠️ 这条判据依赖「没人会去启用那些画布占位」这个前提。前提一旦破裂，后果是<b>扣错库存</b>，
 * 所以撞上就 {@code ERROR} 日志 + 按成品处理（保持既有行为，不静默改道）。
 * 这段语义原样搬自 {@code SalesServiceImpl#isMaterialLine}，⛔ 搬运时不要顺手「优化」。
 *
 * <h2>⛔ 不要用 {@code RMT_} 前缀判域（先量过，不成立）</h2>
 *
 * <p>「{@code RMT_} 开头的 id 不该出现在 {@code product_type_id} 这类列上」是个很自然的
 * 静态判据，本轮认真考虑过。<b>prod 实测把它否掉了</b>：
 *
 * <pre>
 *   product_types       232 行，其中 id 以 RMT_ 开头的  9 行   ← 反例
 *   raw_material_types  484 行，其中 id 以 RMT_ 开头的 459 行   ← 也不是全部
 * </pre>
 *
 * <p>两个方向都不成立：{@code product_types} 里有 9 个 {@code RMT_} 开头的 id，
 * 而 {@code raw_material_types} 里有 25 个<b>不</b>以 {@code RMT_} 开头。
 * ⇒ 前缀既有假阳性又有假阴性，拿它当判据会误伤合法成品行。
 *
 * <p>⚠️ 报数带口径：那 9 个和上一节的「重叠 5 个」是<b>两个不同的量</b> ——
 * 9 = {@code product_types} 里前缀为 {@code RMT_} 的行数（实测全部是
 * {@code __RAW_WORKFLOW_OWNER__} 画布占位，且全部 {@code is_active = false}）；
 * 5 = 同时出现在<b>两张表</b>里的 id 数。9 里另外 4 个只在 {@code product_types} 里，
 * 物料表没有对应行。两个数不能互相替代。
 *
 * <p>▎<b>id 的域是「它在哪张表里」，不是「它长什么样」。</b>
 * 所以本类问的是表，不是前缀。⛔ 别再把前缀判据捡回来。
 */
@Service
@RequiredArgsConstructor
public class SellableLineDomainResolver {

    private static final Logger log = LoggerFactory.getLogger(SellableLineDomainResolver.class);

    /** 一行订单的 id 落在哪个域。 */
    public enum SellableDomain {
        /** 在 {@code product_types} 里 —— 可以进 {@code production_plans.product_type_id}。 */
        FINISHED_GOOD,
        /** 在 {@code raw_material_types} 里且不是启用商品 —— 库存在 {@code material_batches}。 */
        MATERIAL,
        /** 两张表都查不到 —— 主数据缺失，任何引用它的外键都会违约。 */
        UNKNOWN
    }

    private final ProductTypeRepository productTypeRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;

    /**
     * @param factoryId     工厂 ID（物料 / 启用商品的判定都按工厂隔离）
     * @param productTypeId 订单行上的 id
     * @return 三态；{@code productTypeId} 为空时返回 {@link SellableDomain#UNKNOWN}
     */
    public SellableDomain resolve(String factoryId, String productTypeId) {
        if (productTypeId == null || productTypeId.isBlank()) {
            return SellableDomain.UNKNOWN;
        }

        boolean isMaterial = rawMaterialTypeRepository
                .findByIdAndFactoryId(productTypeId, factoryId).isPresent();
        boolean isActiveProduct = productTypeRepository
                .findByIdAndFactoryId(productTypeId, factoryId)
                .map(pt -> Boolean.TRUE.equals(pt.getIsActive()))
                .orElse(false);

        if (isMaterial && isActiveProduct) {
            log.error("同一个 id 既是启用商品又是物料, 按【成品】处理(前提破裂, 需人工核查): "
                    + "factoryId={}, id={}", factoryId, productTypeId);
            return SellableDomain.FINISHED_GOOD;
        }
        if (isMaterial) {
            return SellableDomain.MATERIAL;
        }

        // ⚠️ 这一问必须是**不带工厂过滤**的 findById —— 它对齐的是外键的前置条件本身
        //    (production_plans.product_type_id → product_types(id) 不看 factory_id)。
        //    用工厂过滤版会把「跨厂但确实存在的成品」误判成 UNKNOWN, 那是另一种错。
        return productTypeRepository.findById(productTypeId)
                .map(this::toFinishedGood)
                .orElse(SellableDomain.UNKNOWN);
    }

    private SellableDomain toFinishedGood(ProductType ignored) {
        return SellableDomain.FINISHED_GOOD;
    }

    /**
     * 兼容既有调用点的布尔视图 —— {@code SalesServiceImpl#isMaterialLine} 的语义。
     *
     * <p>与三态的对应关系（等价性由 {@code MaterialLineNotAProductionPlanContractTest} 钉住）：
     * {@code MATERIAL → true}，其余 {@code → false}。
     */
    public boolean isMaterialLine(String factoryId, String productTypeId) {
        return resolve(factoryId, productTypeId) == SellableDomain.MATERIAL;
    }
}
