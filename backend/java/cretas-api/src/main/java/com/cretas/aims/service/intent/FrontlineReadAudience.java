package com.cretas.aims.service.intent;

import java.util.Map;
import java.util.Set;

/**
 * 一线角色「读意图」的受众名单 (只管<b>澄清选项给不给选</b>, 不管能不能执行)。
 *
 * <p><b>缺口</b> (2026-10-07 质检员真测): 质检员说「今天待质检的批次」, 澄清按钮里出现了「今日待收货清单」
 * —— 那是仓库员收货用的。澄清选项早就按角色过滤了 ({@code ClarificationCandidateService#callerMayUse}:
 * 权限码 / {@code required_roles} / 一线写操作开放名单), 但<b>读意图</b>只要 {@code required_roles} 没配就对所有角色放行,
 * 而这类仓库工作台读意图在库里正是 {@code required_roles = NULL} (本机 F006 副本实测 11 个, 含这一个)。
 * 过滤器没漏, 是它看的那份名单是空的。
 *
 * <p><b>为什么放在代码里</b>: 正解是给这些意图配 {@code required_roles}, 但那是 {@code ai_intent_configs} 的数据迁移,
 * 而且会连带让一线角色「执行」也被拒 (「一线角色查询不受限」是 owner 2026-10-06 的裁定, 不在本次范围)。
 * 这里只收窄「选项里出现什么」: 一个不该给这个角色的近邻意图, 不该占一个按钮、把他问两遍。
 *
 * <p><b>只对一线角色生效</b>: 管理员 / 主管等非一线角色不受影响 (他们本来就跨岗位看全部)。
 * 名单里没有的意图一律照旧 —— 本类只会<b>去掉</b>选项, 不会加选项、不会放行任何原本被拒的东西。
 */
public final class FrontlineReadAudience {

    /** 意图码 → 它面向的一线角色。不在表里 = 不限制。 */
    private static final Map<String, Set<String>> ONLY_FOR = Map.of(
            // 仓库收货工作台: 今日 / 明日到货的采购单待收清单 (仓管查; 质检员 / 操作员在 App 里没有这个入口)
            "MATERIAL_TODAY_RECEIVING_QUERY", Set.of("warehouse_worker"),
            "WAREHOUSE_TODAY_RECEIVING_PENDING", Set.of("warehouse_worker"));

    private FrontlineReadAudience() {
    }

    /** 这个意图能不能作为澄清选项给这个角色看。非一线角色、名单外的意图一律 true。 */
    public static boolean offeredTo(String intentCode, String userRole) {
        if (intentCode == null || !FrontlineAiWriteGrantService.isFrontline(userRole)) {
            return true;
        }
        Set<String> audience = ONLY_FOR.get(intentCode);
        return audience == null || audience.contains(userRole);
    }

    /** 名单里所有的意图码 (测试用: 参数化遍历 + 防名单被清空)。 */
    public static Set<String> listedIntentCodes() {
        return ONLY_FOR.keySet();
    }
}
