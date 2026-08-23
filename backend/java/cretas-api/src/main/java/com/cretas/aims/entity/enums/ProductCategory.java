package com.cretas.aims.entity.enums;

/**
 * 产品/菜品分类常量
 * ProductType.productCategory 使用 String 存储，此类定义标准值
 * 支持食品工厂和餐饮门店两种业态
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2026-02-19
 */
public final class ProductCategory {

    private ProductCategory() {}

    // ==================== 工厂通用 ====================
    /** 成品 */
    public static final String FINISHED_PRODUCT = "FINISHED_PRODUCT";
    /** 原材料 */
    public static final String RAW_MATERIAL = "RAW_MATERIAL";
    /** 包装材料 */
    public static final String PACKAGING = "PACKAGING";
    /** 调味料 */
    public static final String SEASONING = "SEASONING";
    /** 客供料（客户自带原料加工 — 来料加工，工厂只收加工费） */
    public static final String CUSTOMER_MATERIAL = "CUSTOMER_MATERIAL";
    /** 纯代工（工厂自备原料的代工/OEM — 给客户品牌代生产，有原料成本，区别于客供料来料加工） */
    public static final String CONTRACT_MANUFACTURING = "CONTRACT_MANUFACTURING";

    // ==================== 餐饮扩展 ====================
    /** 菜品（餐饮出品） */
    public static final String DISH = "DISH";
    /** 套餐（多菜品组合） */
    public static final String COMBO = "COMBO";
    /** 半成品（中央厨房预制） */
    public static final String SEMI_FINISHED = "SEMI_FINISHED";
    /** 加料/配料（可选附加项） */
    public static final String ADD_ON = "ADD_ON";

    /**
     * 判断是否为餐饮类产品
     */
    public static boolean isRestaurantCategory(String category) {
        return DISH.equals(category) || COMBO.equals(category)
                || ADD_ON.equals(category);
    }

    /**
     * 旧商品目录的宽口径兼容判断；不再用于销售订单商品行。
     *
     * <h2>2026-08-12 Steve 拍板(六膳门张权真机反馈)</h2>
     * 「有啥不能卖的 给钱 我都能卖」——原料、辅料、包材都卖;
     * 「半成品卖 过分了」——<b>只有半成品不卖</b>; 结论原话:「出了半成品全开吧」。
     *
     * <h2>为什么是黑名单不是白名单</h2>
     * 白名单会误伤 {@code product_category} 为空的历史数据 —— 那些是早于分类体系建的
     * 商品, 它们现在能正常下单, 换成白名单会<b>静默消失</b>。「除了 X 全开」这句话的
     * 忠实编码就是黑名单。
     *
     * <h2>销售订单边界</h2>
     * 销售订单必须使用 {@link #isSalesOrderSellable(String)} 的严格白名单；本方法只为
     * 仍依赖旧宽口径的其他目录调用保留，避免无关业务在同批改造中改变。
     *
     * <p>⚠️ 与 {@link #isSku} 无关: 半成品<b>是</b> SKU(生产计划/批次要生产它),
     * 销售订单当前允许成品和半成品。共享的
     * {@code findVisibleByFactoryIdAndIsActiveTrue} 服务于生产侧, <b>不要</b>动它 ——
     * 它有 11 个前端调用点(生产计划/批次/工时/毛利红线/成本差异/餐饮)。
     */
    public static boolean isSellable(String category) {
        // 空/未分类 → 可售(见上: 白名单会误伤历史数据)
        if (category == null || category.isBlank()) {
            return true;
        }
        return !SEMI_FINISHED.equalsIgnoreCase(category.trim());
    }

    /**
     * 销售订单成品行的严格口径。物料字典中的原料/包材应通过 suppliedMaterials，
     * 不得伪装成销售订单商品；未知或空分类也必须失败关闭。
     */
    public static boolean isSalesOrderSellable(String category) {
        if (category == null || category.isBlank()) {
            return false;
        }
        String normalized = category.trim().toUpperCase(java.util.Locale.ROOT);
        return FINISHED_PRODUCT.equals(normalized) || SEMI_FINISHED.equals(normalized);
    }

    /** SKU 管理目录范围；原料、包材、调味料仍由物料字典管理。 */
    public static boolean isSku(String category) {
        return FINISHED_PRODUCT.equals(category)
                || SEMI_FINISHED.equals(category)
                || CONTRACT_MANUFACTURING.equals(category)
                || CUSTOMER_MATERIAL.equals(category);
    }
}
