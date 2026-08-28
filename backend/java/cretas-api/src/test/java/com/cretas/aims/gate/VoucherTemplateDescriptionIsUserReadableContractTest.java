package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库<b>种子数据</b>里的用户可见文案，不许写 Java 类名 / 内部迭代编号。
 *
 * <h2>🔴 2026-08-28 逐屏走查：面向用户的文案有<b>第三个</b>产出端</h2>
 *
 * <p>同一天已经建了两道闸：</p>
 * <ol>
 *   <li>前端源码（<code>web-admin/src</code> 的 .vue/.ts）</li>
 *   <li>后端 Java 字面量（<code>DecisionTypeMetadataRegistry</code> 的 description / setRemark）</li>
 * </ol>
 *
 * <p>然后走到「财务管理 / 凭证模板」，「说明」列 <b>7 行全部</b>长这样：</p>
 *
 * <pre>
 *   借: 6602.02 管理费用-折旧; 贷: 1602 累计折旧. mirror DepreciationVoucherGenerator (Map input).
 *   借: 6602.01 管理费用-损耗; 贷: 1405 库存商品. mirror ExpenseVoucherGenerator.
 *   价税分离三行: 借 1405 …; 贷 2202 应付账款(含税). mirror PurchasePaymentVoucherGenerator SP11.
 * </pre>
 *
 * <p>前半段（会计分录）对会计人员有用；后半段是<b>给开发者的实现备注</b> ——
 * <code>mirror</code> 是英文动词，<code>XxxVoucherGenerator</code> 是 Java 类名，
 * <code>SP11</code> 是内部迭代编号。</p>
 *
 * <p>▎它们不在任何一个 .vue / .java 里 —— 它们在 <b>Flyway 种子 SQL</b> 里。
 * ▎<b>「我看到的是全部吗」在同一天开火了第三次。</b></p>
 *
 * <h2>⚠️ 这道闸刻意只盯「会被 INSERT 进用户可见列」的文本</h2>
 *
 * <p>迁移 SQL 里出现类名是<b>正常</b>的（注释、索引名、约束名、JSON 里的表达式）。
 * 一次性禁全文会误报到不可用。⇒ 只扫单引号字符串里
 * <b>同时含中文和 <code>mirror &lt;类名&gt;</code> 这种实现备注</b>的那种。</p>
 *
 * <p>⚠️ 源码级代理判据：它看不见运维手工 UPDATE 进去的文案，也看不见拼接。
 * 作用是让「再往种子里写一句实现备注」必须显式绕过一道写着理由的闸。</p>
 */
class VoucherTemplateDescriptionIsUserReadableContractTest {

    private static final Path FLYWAY = Path.of("src/main/resources/db/flyway");

    /**
     * 已经在 prod 跑过的迁移 —— <b>只能冻结，不能修</b>。
     *
     * <p>⛔ 改这些文件会破坏 Flyway checksum，下次启动直接失败。
     * 它们写进数据库的那些文本由 <code>V20261031_03__voucher_template_description_drop_java_class_names.sql</code>
     * 的 UPDATE <b>就地清理</b>；文件本身留着不动。</p>
     *
     * <p>⚠️ 这是「闸和另一条纪律冲突时怎么办」的一个实例：
     * 判据是对的（种子不该写实现备注），但<b>修法不能是改文件</b>。
     * ⇒ 冻结历史 + 只禁新增，并把「为什么不能改」写在这里，
     * 免得下一个人看到红就去改那个迁移。</p>
     *
     * <p>⛔ 往这里加新文件名 = 又往种子里写了一句实现备注。加之前先问：
     * 能不能写成 <code>--</code> 注释？</p>
     */
    private static final List<String> FROZEN_LEGACY_MIGRATIONS = List.of(
            // 7 条 —— 最初的种子
            "V20260605_03__voucher_template_seeds.sql",
            // 2 条 —— 价税分离升级时**又写了一遍**同样的实现备注
            //   ⚠️ 第一版清单只列了上面那一个: 我数了「屏上有 7 行」就以为源头只有一处,
            //   而屏上那 7 行里有 2 行的文本来自这个后写的迁移(它 UPDATE 覆盖了前面的)。
            //   ⇒ **数屏上的行数 ≠ 数源头的处数。** 是闸自己把第二个源头报出来的。
            "V20261019_02__voucher_template_tax_split_upgrade.sql");

    /** ` mirror XxxGenerator` / ` mirror Sprint 3 E XxxGenerator` 这一族实现备注。 */
    private static final Pattern IMPL_NOTE = Pattern.compile("\\smirror\\s+\\S");

    /** 单引号里的字符串字面量（SQL 用 '' 转义单引号，这里够用）。 */
    private static final Pattern SQL_STRING = Pattern.compile("'((?:[^']|'')*)'");

    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]");

    @Test
    @DisplayName("阳性对照: 真的读到了迁移目录, 且里面确有中文字符串(否则下面是恒真式)")
    void positiveControl() throws IOException {
        assertTrue(Files.isDirectory(FLYWAY), "找不到 " + FLYWAY + " —— 目录搬家了, 本闸路径要跟着改");
        List<Path> sqls;
        try (var s = Files.walk(FLYWAY)) {
            sqls = s.filter(p -> p.toString().endsWith(".sql")).toList();
        }
        assertTrue(sqls.size() > 100, "只扫到 " + sqls.size() + " 个迁移 ⇒ 这份读数无效");
        long withCjk = sqls.stream().filter(p -> {
            try {
                return CJK.matcher(Files.readString(p, StandardCharsets.UTF_8)).find();
            } catch (IOException e) {
                return false;
            }
        }).count();
        assertTrue(withCjk > 20, "含中文的迁移只有 " + withCjk + " 个 ⇒ 判据够不到该守的东西");
    }

    @Test
    @DisplayName("阴性对照: 判据认得出实现备注, 也不误伤正常的会计分录")
    void negativeControl() {
        for (String bad : new String[]{
                "借: 6602.02 管理费用-折旧; 贷: 1602 累计折旧. mirror DepreciationVoucherGenerator (Map input).",
                "借: 1122 应收账款; 贷: 6001 主营业务收入. mirror Sprint 3 E SalesReceiptVoucherGenerator.",
        }) {
            assertTrue(IMPL_NOTE.matcher(bad).find(), "应认出实现备注: " + bad);
        }
        for (String ok : new String[]{
                "借: 6602.02 管理费用-折旧; 贷: 1602 累计折旧.",
                "价税分离三行: 借 1405 库存商品(未税); 贷 2202 应付账款(含税).",
                "跨仓 / 跨厂的库存调拨申请单",
        }) {
            assertFalse(IMPL_NOTE.matcher(ok).find(), "不该误伤正常文案: " + ok);
        }
    }

    @Test
    @DisplayName("⛔ 种子里会被 INSERT 给用户看的中文文案, 不带 `mirror <类名>` 这种实现备注")
    void seedTextHasNoImplementationNotes() throws IOException {
        List<String> hits = new ArrayList<>();
        try (var s = Files.walk(FLYWAY)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".sql")).toList()) {
                if (FROZEN_LEGACY_MIGRATIONS.contains(p.getFileName().toString())) continue;
                String body = Files.readString(p, StandardCharsets.UTF_8);
                // ⛔ 剥掉 `--` 注释: 注释里写实现备注是**对的**, 那是给开发者的
                StringBuilder noComments = new StringBuilder();
                for (String line : body.split("\n")) {
                    noComments.append(line.replaceAll("--.*$", "")).append('\n');
                }
                Matcher m = SQL_STRING.matcher(noComments);
                while (m.find()) {
                    String lit = m.group(1);
                    if (!CJK.matcher(lit).find()) continue;          // 只看会给中文用户看的
                    if (IMPL_NOTE.matcher(lit).find()) {
                        hits.add(FLYWAY.relativize(p) + " → " + lit.trim());
                    }
                }
            }
        }
        assertEquals(List.of(), hits,
                "这些字符串会被 INSERT 进数据库, 然后**直接渲染到屏上**"
                        + "(2026-08-28 实拍:「凭证模板」的说明列 7 行全带 Java 类名)。\n"
                        + "⇒ 实现备注写 `--` 注释里, ⛔ 不要写进会给用户看的列。\n"
                        + String.join("\n", hits));
    }

    @Test
    @DisplayName("⛔ 冻结清单只许变短: 已经不违例的历史迁移必须从清单里划掉")
    void frozenListMustNotRot() throws IOException {
        List<String> stale = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : FROZEN_LEGACY_MIGRATIONS) {
            Path p = FLYWAY.resolve(name);
            if (!Files.exists(p)) {
                missing.add(name);
                continue;
            }
            String body = Files.readString(p, StandardCharsets.UTF_8);
            StringBuilder noComments = new StringBuilder();
            for (String line : body.split("\n")) {
                noComments.append(line.replaceAll("--.*$", "")).append('\n');
            }
            boolean stillViolates = false;
            Matcher m = SQL_STRING.matcher(noComments);
            while (m.find()) {
                String lit = m.group(1);
                if (CJK.matcher(lit).find() && IMPL_NOTE.matcher(lit).find()) {
                    stillViolates = true;
                    break;
                }
            }
            if (!stillViolates) stale.add(name);
        }
        assertEquals(List.of(), missing,
                "冻结清单里的迁移文件不存在了 —— 改名或删除了? 清单要跟着改:\n" + String.join("\n", missing));
        assertEquals(List.of(), stale,
                "这些已经不违例了 —— 把它们从 FROZEN_LEGACY_MIGRATIONS 删掉, 棘轮才收得紧。\n"
                        + "⚠️ 但先确认: 你是不是**改了一个已经跑过的迁移**? 那会破坏 Flyway checksum。\n"
                        + String.join("\n", stale));
    }
}
