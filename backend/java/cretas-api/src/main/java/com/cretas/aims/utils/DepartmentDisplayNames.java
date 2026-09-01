package com.cretas.aims.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 把 {@code users.department} 里的英文 code 翻译成中文显示名。
 *
 * <h2>为什么需要它（缺陷 15，2026-09-01 LIUSHANMEN 生产走查）</h2>
 * 请购单详情的「请购部门」恒为空；顺着查发现更大的一片：
 * {@code UserMapper} 里 {@code .departmentDisplayName(user.getDepartment())} 是**原样透传 code**，
 * 于是账号管理、HR 花名册、工牌、导出、RN 个人信息与新员工屏
 * **都在把 {@code procurement} / {@code warehouse} 这种内部 code 显示给一线**。
 * 与缺陷 5/6/7/23 同族：把内部标识符摆到操作者面前当业务名用。
 *
 * <h2>⛔ 为什么不是查 {@code departments} 表</h2>
 * 那是最先想到的做法，**它会是一个永远不生效的空转**：
 * 2026-09-01 生产实测 {@code SELECT count(*) FROM departments} = **0**（全工厂），
 * 阳性对照同库 {@code users} = 82 行，证明是表真空而不是查询失败。
 * 仓里 {@code DepartmentController / DepartmentService / DepartmentRepository} 整套都在，
 * 但没有任何迁移往里 INSERT，也没有客户在用 ⇒ join 出来的名字恒为 null。
 *
 * <h2>⚠️ 本类是过渡，不是终态</h2>
 * <b>解冻条件</b>：等 {@code departments} 表真的有数据（客户开始维护部门，或产品决定给它种子），
 * 就应该改成数据驱动，并把这张表删掉。⛔ 在那之前不要把它当成部门的权威定义 ——
 * 它只回答「这个 code 给人看时该显示成什么」，不回答「这个工厂有哪些部门」。
 *
 * <h2>⚠️ 形态 D 如实登记：这是仓里第三份「部门知识」</h2>
 * 另外两份在 {@code web-admin/src/types/auth.ts} 与
 * {@code frontend/CretasFoodTrace/src/types/auth.ts}（**同一张角色→部门 code 表抄了两遍**，
 * 本次改动之前就存在）。那两份是 <b>角色 → code</b>，本类是 <b>code → 显示名</b>，
 * 关注点不同，但同族。
 * <p>放后端的理由：显示名如果放前端，web-admin 和 RN 要各写一遍 ——
 * 那是**确定**会漂的两份；而放在后端唯一构造点，六个消费屏（含移动端）一次修好。
 *
 * <h2>取值域来自生产实测，不是猜的</h2>
 * 2026-09-01 查 {@code cretas_prod_db}，{@code users.department} 去重后共 14 个值：
 * <pre>
 * F006:        sales(10) finance(6) PROCESSING(3)
 * LIUSHANMEN:  production(11) warehouse(2) quality(2) management(1) finance(1)
 *              procurement(1) system(1) hr(1) dispatch(1) workshop(1) none(1)
 *              equipment(1) sales(1)
 * </pre>
 * ⚠️ <b>大小写不一致</b>（F006 是 {@code PROCESSING}，六膳门是 {@code production}）
 * ⇒ 匹配必须大小写不敏感，⛔ 不能用 {@code equals}。
 *
 * <h2>⛔ 查不到时回退原值，不回退空白</h2>
 * 「这个 code 我不认识」和「这个人没有部门」是两件事。回退成空白会把前者伪装成后者
 * （本仓形态 A¹⁰：兜底的默认值会把「我不知道」翻译成一个假读数）。
 * 而且 {@code department} 是自由文本（{@code UserServiceImpl} 写入时不校验），
 * 里面**已经**存在中文值（如 {@code TimeStatsServiceImpl} 写的「生产部」）——
 * 那些原样返回本来就是对的。
 */
public final class DepartmentDisplayNames {

    private DepartmentDisplayNames() {
    }

    /**
     * code（小写）→ 中文显示名。
     *
     * ⚠️ 命名沿用一线的叫法（「采购部」而不是「采购部门」）——
     * 既有的 {@code entity.enums.Department} 枚举用的是「加工部门」那种，
     * 但它只有 5 个值、覆盖生产 14 个里的 3 个，**已经过期**，
     * ⛔ 没有复用它，也没有去扩它（那是个 entity 枚举，取值域与本表不是一回事）。
     */
    private static final Map<String, String> DISPLAY_NAMES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // —— 以下 14 个是 2026-09-01 生产实测存在的全部取值 ——
        m.put("production", "生产部");
        m.put("processing", "加工部");
        m.put("warehouse", "仓储部");
        m.put("quality", "质量部");
        m.put("procurement", "采购部");
        m.put("sales", "销售部");
        m.put("finance", "财务部");
        m.put("hr", "人事部");
        m.put("equipment", "设备部");
        m.put("dispatch", "调度部");
        m.put("workshop", "车间");
        m.put("management", "管理部");
        m.put("system", "系统管理");
        // ⚠️ "none" 是真实存在的取值(六膳门 1 人)。它表达的是「建号时没选部门」,
        //    ⛔ 不要翻成空字符串 —— 那会让调用方以为这个人没有 department 字段,
        //    与「确实没填」不可区分。显式说出来。
        m.put("none", "未分配部门");
        DISPLAY_NAMES = Collections.unmodifiableMap(m);
    }

    /**
     * @param rawDepartment {@code users.department} 的原始值，可能是 code、中文、空或 null
     * @return 认识的 code → 中文显示名；其余**原样返回**（含 null 与空白，交给调用方决定怎么显示）
     */
    public static String display(String rawDepartment) {
        if (rawDepartment == null) {
            return null;
        }
        String trimmed = rawDepartment.trim();
        if (trimmed.isEmpty()) {
            return rawDepartment;
        }
        String hit = DISPLAY_NAMES.get(trimmed.toLowerCase(Locale.ROOT));
        // ⛔ 查不到就是原值 —— 见类注释「回退原值不回退空白」。
        return hit != null ? hit : rawDepartment;
    }

    /** 供测试与排查使用：当前认识的全部 code（小写）。 */
    public static Map<String, String> knownCodes() {
        return DISPLAY_NAMES;
    }
}
