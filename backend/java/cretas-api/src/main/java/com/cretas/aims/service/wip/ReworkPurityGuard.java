package com.cretas.aims.service.wip;

/**
 * 「这条回料能不能投进这个产品」——纯函数, 无 IO。
 *
 * <p><b>业务规则</b> (2026-08 客户实拍确认, 肉饼线):
 * <pre>
 *   投入到 ↓ ／ 回料来自 →   美式   小吃吧   熟肉饼   汉堡排
 *   美式汉堡饼                ✅      ❌       ❌       ❌
 *   小吃吧汉堡饼              ✅      ✅       ❌       ❌
 *   熟肉饼                    ✅      ✅       ✅       ❌
 *   汉堡排                    ❌      ❌       ❌       ❌
 * </pre>
 * 成因: <b>美式是纯肉无辅料</b>, 所以最"干净", 能进任何产品; 小吃吧掺了辅料, 只能进比它更"脏"的;
 * 汉堡排一概不收回料。
 *
 * <p>判据是「<b>回料的辅料集合 ⊆ 目标产品的辅料集合</b>」。这条产线上这个包含关系是一个
 * <b>全序</b> (美式 ⊂ 小吃吧 ⊂ 熟肉饼), 所以落成一个整数 {@code reworkPurityLevel} 就够
 * (数越小越干净: 美式=1 / 小吃吧=2 / 熟肉饼=3), 收不收回料另用一个布尔 {@code acceptsRework}
 * (汉堡排=false —— 它不需要全序, 且必须在等级没配时也生效)。
 *
 * <p>🔴 <b>配置口径 (照着需求描述配会漏一半)</b>: 汉堡排要配<b>两样</b> ——
 * {@code acceptsRework = false} <b>且</b> {@code reworkPurityLevel = 4}。
 * 两个字段管的是<b>两个方向</b>:
 * <ul>
 *   <li>{@code acceptsRework} 管「谁能投进我」= 矩阵里汉堡排那一<b>行</b></li>
 *   <li>{@code reworkPurityLevel} 管「我能投进谁」= 矩阵里汉堡排那一<b>列</b></li>
 * </ul>
 * 只配 false 而把等级留 null, 汉堡排的回料会走「未配置放行」那条路, <b>能被领给任何产品</b> ——
 * 而这个失败不报错, 只是多出选项。四个产品的完整配置是
 * 美式=1 / 小吃吧=2 / 熟肉饼=3 / 汉堡排=4+false。
 *
 * <p>⚠️ 如果以后出现<b>不是全序</b>的产线 (两个产品各自有对方没有的辅料), 一个整数就表达不了,
 * 那时要换成真正的集合比较。这里刻意留一个整数而不是提前做集合, 是因为集合需要「辅料集合」
 * 这份数据, 而它今天不存在 —— 提前做会做出一个只能靠假数据验收的东西。
 *
 * <p><b>抽成纯函数是刻意的</b>: 判定埋在库存查询里, 单测够不着; 而「够不着被测行为」与
 * 「行为没生效」的读数一模一样、结论相反 —— 本仓吃过这个亏 (同 {@link WipSemiOutputPlan})。
 */
public final class ReworkPurityGuard {

    private ReworkPurityGuard() {
    }

    /** 判定结果。{@link #allowed()} 是给过滤用的, 具体档位是给「为什么」用的。 */
    public enum Verdict {
        /** 两侧都配了纯度等级, 且来源不比目标脏 —— 这道闸真的判过它。 */
        ALLOWED,
        /**
         * 至少一侧没配纯度等级 ⇒ <b>不参与这道闸</b>, 放行, 并在返回项上带标记。
         *
         * <p>🔴 这是一个显式裁定 (owner 拍板), 不是遗漏。存量所有产品这两个字段都是 null:
         * 一律放行 = 闸不存在; 一律拒绝 = 上线当天所有租户的回料全部领不出来。
         * 选放行 + 带标记, 让前端能说清「这一行没经过纯度判定」—— 诚实标记而不是假装判过,
         * 与本仓「禁止降级处理」同向。⇒ 这道闸只在租户【显式配了等级】之后才对那两个产品生效。
         */
        ALLOWED_PURITY_UNCONFIGURED,
        /** 目标产品一概不收回料 ({@code acceptsRework = false}, 如汉堡排)。 */
        BLOCKED_TARGET_REJECTS_REWORK,
        /** 回料来源比目标产品"脏" (来源辅料集合不是目标的子集)。 */
        BLOCKED_SOURCE_DIRTIER_THAN_TARGET;

        public boolean allowed() {
            return this == ALLOWED || this == ALLOWED_PURITY_UNCONFIGURED;
        }
    }

    /**
     * @param sourcePurityLevel   回料来源产品的 {@code reworkPurityLevel}; null = 未配置
     * @param targetPurityLevel   要投入的目标产品的 {@code reworkPurityLevel}; null = 未配置
     * @param targetAcceptsRework 目标产品收不收回料; null = 未配置 (等同于收 —— DB 列默认 TRUE,
     *                            「未配置的产品不参与这道闸」的同一条裁定)
     */
    public static Verdict evaluate(Integer sourcePurityLevel,
                                   Integer targetPurityLevel,
                                   Boolean targetAcceptsRework) {
        // ① 「一概不收」先判, 且【不】受等级是否配置的影响 —— 汉堡排拒收与来源是谁无关。
        //    ⛔ 不要把它并进下面的 null 分支: 那会让「没配等级」把「明确说了不收」一起放行。
        if (Boolean.FALSE.equals(targetAcceptsRework)) {
            return Verdict.BLOCKED_TARGET_REJECTS_REWORK;
        }
        // ② 任一侧没配等级 → 不参与判定, 放行 + 标记 (见 ALLOWED_PURITY_UNCONFIGURED 的说明)。
        if (sourcePurityLevel == null || targetPurityLevel == null) {
            return Verdict.ALLOWED_PURITY_UNCONFIGURED;
        }
        // ③ 全序比较: 来源不比目标脏才放行。
        return sourcePurityLevel <= targetPurityLevel
                ? Verdict.ALLOWED
                : Verdict.BLOCKED_SOURCE_DIRTIER_THAN_TARGET;
    }
}
