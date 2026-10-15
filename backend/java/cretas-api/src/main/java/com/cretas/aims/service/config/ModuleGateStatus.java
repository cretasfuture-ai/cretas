package com.cretas.aims.service.config;

import java.util.List;

/**
 * 工厂级模块门禁**现在到底生不生效** —— 把一个此前完全静默的事实变成可读状态。
 *
 * <h2>要解决的问题</h2>
 *
 * {@code FactoryConfigServiceImpl#getEffectiveConfig} 里 {@code moduleEnabled} 的初值是
 * {@code true}, 只有<b>三个条件同时成立</b>才会被工厂配置覆盖:
 *
 * <ol>
 *   <li>{@code module_schemas} 里有这个码(否则走 LEGACY 分支直接 {@code enabled=true} 返回)</li>
 *   <li>该工厂有 <b>PUBLISHED</b> 的 {@code factory_configurations}</li>
 *   <li>{@code factory_module_configs} 有一行, 且 {@code config_version} <b>恰好等于</b>已发布版本</li>
 * </ol>
 *
 * 缺任何一条都是同一个结果:<b>全放行</b>。而且<b>不报错、不留痕</b> ——
 * 少一行、版本差一位、还没发布过, 三种完全不同的原因长得一模一样。
 * 2026-08-14 实测本地 {@code factory_configurations} <b>全表 0 行</b>,
 * 即工厂级模块开关<b>整层没有在拦</b>, 而任何界面/接口都看不出这一点:
 * {@code GET /config/disabled-modules} 在无已发布配置时返回<b>空列表</b> ——
 * 与「一个模块都没禁用」返回同一个东西。
 *
 * <h2>⛔ 这个类【不改变】任何放行/拦截行为</h2>
 *
 * 条件 1、2 的 fail-open 是<b>有依据的</b>, 不该被消灭:
 * <ul>
 *   <li>条件 1 —— 代码里唯一带理由的分支:{@code // Module not canvas-configured — return
 *       LEGACY default}。没在画布里配过的模块不该被画布门禁拦。</li>
 *   <li>条件 2 —— canvas-v3 是 {@code Draft → Review → Approve → Publish} 生命周期:
 *       DRAFT 不该影响运行时;而全新工厂零配置若 fail-closed 就是<b>开厂即全站 403</b>。</li>
 * </ul>
 * 条件 3(版本对不上)则<b>没有任何依据</b> —— 没注释、没文档、没测试,
 * 那一条由 {@link Reason#MODULE_ROWS_ON_STALE_VERSION} 单独报出来。
 *
 * <h2>⚠️ 它说的是「工厂级开关这一层」, 不是「接口没人守」</h2>
 *
 * {@code ModuleEnabledInterceptor#preHandle} 有<b>四道</b>判定, 只有第一道依赖本状态:
 * 未登录 → 401、跨工厂 → 拒、{@code canAccessModule}(角色权限)→ 拒, 这三道<b>都是活的</b>。
 * ⇒ {@code effective=false} 读作「客户关掉一个模块不会 403」, <b>不要</b>读成「接口是裸的」。
 *
 * @param effective                      工厂级开关这一层现在是否真的在起作用
 * @param reason                         不生效时的原因(生效时为 {@link Reason#EFFECTIVE})
 * @param detail                         给人看的一句话
 * @param publishedVersion               当前已发布版本号;无已发布配置时为 {@code null}
 * @param moduleRowsAtPublishedVersion   落在已发布版本上的模块开关行数
 * @param staleVersionModuleCodes        有行、但版本对不上已发布版本的模块码(条件 3 的失效面)
 * @param pendingWorkflowStatus          有 DRAFT/PENDING_REVIEW/APPROVED 在等发布时的状态名, 否则 null
 * @since 2026-08-14
 */
public record ModuleGateStatus(
        boolean effective,
        Reason reason,
        String detail,
        Integer publishedVersion,
        int moduleRowsAtPublishedVersion,
        List<String> staleVersionModuleCodes,
        String pendingWorkflowStatus,
        List<String> pendingPublishModuleCodes) {

    public enum Reason {
        /** 三个条件齐了, 工厂级开关真的在拦。 */
        EFFECTIVE,

        /**
         * 🔴 客户<b>已经关掉</b>了某些模块, 但那些改动停在<b>未发布的草稿</b>里, 所以还没生效。
         *
         * <h3>怎么会走到这一步</h3>
         *
         * 客户在画布/单一入口关模块 → {@code toggleModule} → {@code saveModuleConfig} →
         * {@code getOrCreateDraft} —— 行写进的是 <b>DRAFT</b> 版本的 {@code factory_module_configs}。
         * 而门禁的条件 2 要的是 <b>PUBLISHED</b>。⇒ 不点「立即发布」就永远不生效。
         *
         * <h3>⛔ 为什么它必须与 {@link #NO_PUBLISHED_CONFIG} 分开</h3>
         *
         * 两者的库状态都是「没有已发布配置」, 但含义完全相反:
         * <ul>
         *   <li>{@code NO_PUBLISHED_CONFIG} —— 客户<b>什么都没配</b>, 全开是正常的, 不该报红;</li>
         *   <li>{@code PENDING_PUBLISH} —— 客户<b>明确关了东西</b>, 而产品没生效也没说话。</li>
         * </ul>
         * 不分开的话, 客户关掉模块之后看到的是「灰度中 · 接口已开放」这种听着很正常的话 ——
         * 这正是本仓反复出现的「坏掉的样子和正常一模一样」。
         *
         * <p>⚠️ 这<b>不是</b>缺陷: 发布是 Draft→Approve→Publish 生命周期的一部分,
         * 「立即发布」按钮就在画布配置的头部。缺的只是<b>有人告诉客户还差这一步</b>。
         */
        PENDING_PUBLISH,

        /** 该工厂从来没有发布过配置, <b>且草稿里也没有关掉任何模块</b> —— 新工厂的正常状态。 */
        NO_PUBLISHED_CONFIG,

        /** 有已发布配置, 但这个版本下一行模块开关都没有 —— 没有任何模块被配置过。 */
        NO_MODULE_ROWS_AT_PUBLISHED_VERSION,

        /**
         * 🔴 有已发布配置、也有模块开关行, 但那些行的 {@code config_version} <b>对不上</b>。
         * 客户以为配好了, 实际全放行。这一条<b>没有任何设计依据</b>, 是真的说不过去。
         */
        MODULE_ROWS_ON_STALE_VERSION
    }

    /**
     * 需要有人去处理的两种:
     * <ul>
     *   <li>{@link Reason#MODULE_ROWS_ON_STALE_VERSION} —— 配错了;</li>
     *   <li>{@link Reason#PENDING_PUBLISH} —— <b>客户关了东西但没发布</b>, 产品必须说出来。</li>
     * </ul>
     * 其余的不生效都是正常状态(新工厂没配过 / 配了但没关任何模块), 不该报红。
     *
     * <p>⚠️ {@code @JsonProperty} 不是装饰:这是个 record, Jackson 默认<b>只序列化 record 组件</b>,
     * 额外的访问器方法不进 JSON。2026-08-14 实测 —— 在补上这个注解之前,
     * {@code /module-gate-status} 的响应里<b>根本没有 needsAttention 这个字段</b>,
     * 也就是说自它诞生起就没有任何消费方读得到它。
     * 又一个「写了但没接上」,是被一条断言抓出来的(那条断言我第一版还漏了)。
     */
    @com.fasterxml.jackson.annotation.JsonProperty("needsAttention")
    public boolean needsAttention() {
        return reason == Reason.MODULE_ROWS_ON_STALE_VERSION || reason == Reason.PENDING_PUBLISH;
    }
}
