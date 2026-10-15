package com.cretas.aims.service.module;

/**
 * 一个模块在<b>两层</b>上的组合状态 —— 把「不一致」变成一个显式、可解释的状态,
 * 而不是一个必须被消灭的错误。
 *
 * <pre>
 *   前端可见  后端开放   状态              含义
 *   ✅        ✅         ACTIVE           正常启用
 *   ❌        ✅         ROLLOUT          灰度中 —— 接口先开、界面还没放出来。**合法**
 *   ✅        ❌         MISCONFIGURED    配错了 —— 界面点得进去, 接口 403。要告警
 *   ❌        ❌         DISABLED         已关闭
 * </pre>
 *
 * <h2>⛔ 为什么 ROLLOUT 不算错</h2>
 *
 * 因为「两层分开配」有合法用途(灰度期间先开接口后开界面)。如果把所有不一致
 * 都当缺陷去「对齐」, 就等于把这个能力从产品里删掉。判据是**方向**:
 * 界面藏着而接口开着 → 用户碰不到, 无害; 界面露着而接口关着 → 用户点了报错, 有害。
 *
 * <h2>⚠️ 「后端开放」这个读数今天普遍为真, 但不代表门禁在起作用</h2>
 *
 * {@code getEffectiveConfig} 的 {@code moduleEnabled} 初值就是 {@code true},
 * 只有 ① {@code module_schemas} 有这个码 ② 该厂有 PUBLISHED 的
 * {@code factory_configurations} ③ {@code factory_module_configs} 行的
 * {@code config_version} 精确等于那个版本号 —— 三样齐了才会被覆盖。
 * 少了任何一样, 后端层读出来都是「开放」。本地实测 ② 全库 0 行 ⇒ 后端门禁层整体是死的。
 * ⇒ 看到 ACTIVE 不要读成「门禁在守着它」, 只能读成「接口现在通」。
 *
 * @since 2026-08-14
 */
public enum ModuleSwitchState {

    ACTIVE("正常启用", false),
    ROLLOUT("灰度中 · 接口已开放, 界面尚未放出", false),
    MISCONFIGURED("配置冲突 · 界面可进入但接口会拒绝", true),
    DISABLED("已关闭", false);

    private final String description;
    private final boolean needsAttention;

    ModuleSwitchState(String description, boolean needsAttention) {
        this.description = description;
        this.needsAttention = needsAttention;
    }

    public String getDescription() {
        return description;
    }

    /** 只有 {@link #MISCONFIGURED} 为真 —— 界面据此告警, 其余三态都不该报红。 */
    public boolean isNeedsAttention() {
        return needsAttention;
    }

    public static ModuleSwitchState of(boolean frontendVisible, boolean backendEnabled) {
        if (frontendVisible) {
            return backendEnabled ? ACTIVE : MISCONFIGURED;
        }
        return backendEnabled ? ROLLOUT : DISABLED;
    }
}
