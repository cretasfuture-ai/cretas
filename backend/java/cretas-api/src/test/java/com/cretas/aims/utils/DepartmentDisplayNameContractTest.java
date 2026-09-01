package com.cretas.aims.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缺陷 15（2026-09-01 LIUSHANMEN 生产走查）：{@code users.department} 的英文 code
 * 被原样显示给一线。
 *
 * <h2>类名以 ContractTest 结尾是刻意的</h2>
 * 本仓 Java CI 的选择器是
 * {@code *RepositoryQueryValidationTest,*StartupGuardTest,FlywayVersionUniquenessTest,*ContractTest}
 * —— 不在其中的类**只编译不执行**。⛔ 起别的名字等于写一条永远不跑的测试。
 *
 * <h2>这组用例真正在守的三条</h2>
 * ① 生产上**实际存在**的 14 个 code 都能翻译（取值域是查出来的，不是猜的）<br>
 * ② 匹配**大小写不敏感** —— F006 存的是 {@code PROCESSING}，六膳门存的是 {@code production}<br>
 * ③ 认不出的值**原样返回**，⛔ 不回退空白 ——
 *    「这个 code 我不认识」和「这个人没有部门」是两件事（形态 A¹⁰）
 */
@DisplayName("缺陷 15 — 部门 code 的显示名解析")
class DepartmentDisplayNameContractTest {

    /**
     * 2026-09-01 查 {@code cretas_prod_db} 得到的**全部** 14 个取值。
     * ⚠️ 这不是示例，是取值域本身。将来生产上出现新 code 时，这里应当同步扩。
     */
    private static final List<String> PRODUCTION_VALUES = List.of(
            "sales", "finance", "PROCESSING", "production", "warehouse", "quality",
            "management", "procurement", "system", "hr", "dispatch", "workshop",
            "none", "equipment");

    @Test
    @DisplayName("① 生产上实际存在的 14 个 code 全部能翻译成中文，一个不漏")
    void everyProductionValueResolvesToChinese() {
        for (String raw : PRODUCTION_VALUES) {
            String display = DepartmentDisplayNames.display(raw);
            assertNotNull(display, raw);
            assertNotEqualsIgnoringCase(raw, display,
                    "生产上存在的 code `" + raw + "` 没有被翻译，会原样显示给一线");
            assertTrue(containsChinese(display),
                    "`" + raw + "` 翻出来的不是中文: " + display);
        }
    }

    @ParameterizedTest(name = "② {0} → {1}（大小写不敏感）")
    @CsvSource({
            "production,     生产部",
            "PRODUCTION,     生产部",
            "Production,     生产部",
            "PROCESSING,     加工部",
            "processing,     加工部",
            "procurement,    采购部",
            "  warehouse  ,  仓储部",
    })
    @DisplayName("② 大小写与首尾空白都不影响匹配")
    void matchIsCaseInsensitiveAndTrimmed(String raw, String expected) {
        assertEquals(expected, DepartmentDisplayNames.display(raw));
    }

    @Test
    @DisplayName("🔴 ③ 认不出的 code 原样返回，⛔ 不是空白")
    void unknownCodeFallsBackToRawValue() {
        String unknown = "some_new_dept_2027";
        String display = DepartmentDisplayNames.display(unknown);

        assertEquals(unknown, display);
        // 阴性对照 —— 这三条才是本用例真正在守的东西:
        // 回退成空白/空串/占位符都会把「这个 code 我不认识」伪装成「这个人没有部门」。
        assertFalse(display.isEmpty());
        assertFalse(display.isBlank());
        assertNotEquals("-", display);
    }

    @Test
    @DisplayName("已经是中文的值原样返回 —— department 是自由文本，里面本来就有中文")
    void chineseValuePassesThrough() {
        // TimeStatsServiceImpl 就往这个字段写过「生产部」。
        assertEquals("生产部", DepartmentDisplayNames.display("生产部"));
        assertEquals("某个客户自己填的部门", DepartmentDisplayNames.display("某个客户自己填的部门"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("空串/空白原样返回，交给调用方决定显示成什么")
    void blankPassesThrough(String blank) {
        // ⛔ 不在这里替调用方决定显示 '-' 还是 '未填写' —— 那是展示层的事。
        assertEquals(blank, DepartmentDisplayNames.display(blank));
    }

    @Test
    @DisplayName("null 返回 null，⛔ 不返回空串（两者对调用方含义不同）")
    void nullStaysNull() {
        assertNull(DepartmentDisplayNames.display(null));
    }

    @Test
    @DisplayName("⚠️ 'none' 是真实取值，必须显式说出来而不是翻成空")
    void noneIsAnExplicitValueNotEmptiness() {
        String display = DepartmentDisplayNames.display("none");
        assertEquals("未分配部门", display);
        // 六膳门确实有 1 个账号的 department 就是字符串 "none"。
        // 若翻成空串，调用方无法把它与「这个字段没值」区分开。
        assertFalse(display.isBlank());
    }

    @Test
    @DisplayName("阳性对照：字典真的被加载了（防止扫到空表而恒绿）")
    void dictionaryIsNotEmpty() {
        assertEquals(PRODUCTION_VALUES.size(), DepartmentDisplayNames.knownCodes().size(),
                "字典条目数应与生产实测取值域一致；生产出现新 code 时两处一起改");
    }

    // ==================== helpers ====================

    private static boolean containsChinese(String text) {
        return text.codePoints().anyMatch(cp -> cp >= 0x3400 && cp <= 0x9FFF);
    }

    private static void assertNotEqualsIgnoringCase(String raw, String display, String message) {
        assertFalse(raw.equalsIgnoreCase(display), message);
    }

    private static void assertNotEquals(String unexpected, String actual) {
        assertFalse(unexpected.equals(actual), "不应等于 " + unexpected);
    }
}
