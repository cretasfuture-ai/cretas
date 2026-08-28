package com.cretas.aims.entity.canvas;

/**
 * Canvas-Thresholds 阈值分类.
 *
 * <p>Phase A 首发 6 类:
 * <ul>
 *   <li>{@link #INVENTORY} — 库存健康 (周转 / 临期 / 损耗 / 库龄)</li>
 *   <li>{@link #IOT}       — IoT 冷链 / 环境 (温度 / 湿度)</li>
 *   <li>{@link #CREDIT}    — 客户信用 (预警利用率)</li>
 *   <li>{@link #BOM}       — BOM 递归 (max depth)</li>
 *   <li>{@link #SHORTAGE}  — 缺料分析 (默认 lead time)</li>
 *   <li>{@link #AI}        — AI 复杂度路由 (mode threshold + ambiguous zone)</li>
 * </ul>
 *
 * <h2>2026-08-28 补齐的 9 类 —— 修的是一个「机制在、接不上」的裂缝</h2>
 * {@link com.cretas.aims.service.canvas.ThresholdKeys} 早就声明了
 * {@code sales.*} / {@code production.*} / {@code quality.*} 等 15 个前缀的 key，
 * 而本枚举当时只有 6 个值。于是
 * {@code CanvasThresholdsController.parseCategory} 会拒绝这些分类，
 * <b>那 38 个 key 在 API / UI 上一行都创建不出来</b>，永远只能走代码里的 hard-coded fallback ——
 * 「阈值可配置」这件事对它们从来没成立过。
 *
 * <p>本次把枚举补齐到与 {@code ThresholdKeys} 已声明的前缀一一对应。
 *
 * <h3>⛔ 加值安全，删值/改名不安全</h3>
 * {@code FactoryThreshold.category} 是 {@code @Enumerated(EnumType.STRING)}。
 * <b>库里一旦出现本枚举没有的字符串，任何 {@code findByFactoryId...} 全表查询都会在反序列化时抛
 * {@code IllegalArgumentException}，把整个阈值中心页面打挂 —— 不是只挂那一行。</b>
 * 所以：
 * <ul>
 *   <li><b>加</b>枚举值是安全方向（先有值，后有数据）；</li>
 *   <li><b>删/改名</b>必须先确认库里零行使用，且要与 seed 迁移同一次上线。</li>
 * </ul>
 *
 * <p>⚠️ 前端 {@code web-admin/src/api/thresholdsHub.ts} 里有一份平行的 TS 联合类型，
 * 两份必须同时改 —— 它是这个枚举的第二份副本，会漂。
 *
 * @since Canvas Phase A (2026-05-21)
 */
public enum ThresholdCategory {

    // ── Phase A 首发 ────────────────────────────────────────────────
    INVENTORY,
    IOT,
    CREDIT,
    BOM,
    SHORTAGE,
    AI,

    // ── 2026-08-28 补齐 (ThresholdKeys 早已声明, 此前建不出来) ──────
    /** 销售: 达成率 / 毛利率 / 增长率红黄线, 以及发货超发上限。 */
    SALES,
    /** 采购: 价格波动预警比率。 */
    PURCHASE,
    /** 定价: 深度折扣防呆比率。 */
    PRICING,
    /** 生产: 计划 / 报工相关阈值。 */
    PRODUCTION,
    /** 采购分析 (SmartBI): 合格率 / 供应商集中度红黄线。 */
    PROCUREMENT,
    /** 质量: 合格率红黄线。 */
    QUALITY,
    /** 财务: 账龄 / 预算执行率红黄线。 */
    FINANCE,
    /** 加工: 合格率判定阈值。 */
    PROCESSING,
    /** 人效: 偏差告警阈值。 */
    EFFICIENCY
}
