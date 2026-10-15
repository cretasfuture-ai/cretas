package com.cretas.aims.service.module;

/**
 * 一次模块开关操作要落到哪一层。
 *
 * <h2>为什么这是个显式参数, 而不是「总是双写」</h2>
 *
 * 「关掉一个模块」涉及两层, 而<b>两层分开配是合法状态</b>(Steve 2026-08-14 拍板):
 *
 * <pre>
 *   前端可见性  factory_feature_config    RN / web-admin 屏幕藏不藏
 *   后端门禁    factory_module_configs    接口 403 (@RequireModule 读它)
 * </pre>
 *
 * 灰度期间「先开接口、后开界面」= 隐藏 + 开放, 这是**有意为之**不是配错。
 * 所以单一入口必须能表达四种组合中的每一种 —— 盲目双写会把「灰度中」这个
 * 合法状态从产品里抹掉。四种组合的命名见 {@link ModuleSwitchState}。
 *
 * @since 2026-08-14
 */
public enum ModuleSwitchLayers {

    /** 两层一起改 —— 客户在界面上点「关闭模块」时的默认语义。 */
    BOTH,

    /** 只改后端门禁, 界面维持原样。可用于「界面还在但接口先关」的排障/收紧。 */
    BACKEND_ONLY,

    /** 只改前端可见性, 接口维持开放 —— 灰度期间「先开接口后开界面」走这条。 */
    FRONTEND_ONLY;

    public boolean touchesBackend() {
        return this == BOTH || this == BACKEND_ONLY;
    }

    public boolean touchesFrontend() {
        return this == BOTH || this == FRONTEND_ONLY;
    }
}
