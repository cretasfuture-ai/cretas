package com.cretas.aims.gate;

import com.cretas.aims.service.finance.VoucherConsumptionSourcePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸：每一种 {@code material_consumptions} 的来源都必须被裁定过<b>进不进财务凭证</b>。
 *
 * <h4>为什么</h4>
 *
 * <p>产品裁定（2026-08-27，Owner）：「消耗行不进凭证，只有生产领用进」。
 * 判据落在 {@link VoucherConsumptionSourcePolicy} 的两张表里。</p>
 *
 * <p>🔴 而在此之前，凭证导出是
 * {@code for (c : findByTimeRange(factoryId, start, end))} —— <b>不过滤</b>。
 * 实测生产库里 {@code TRANSFER_OUT} 23 行 1969.00 元被记成「原料发出」，
 * 那是仓库之间搬货，货还在厂里。</p>
 *
 * <h4>它守的是「新增一种来源时有人做过决定」</h4>
 *
 * <p>⚠️ 财务输出上，静默计入和静默排除都是错的。运行时那一侧已经做到「不静默」
 * （未知来源不计入但打 warn 日志）；这道闸做的是另一半：
 * <b>让新增来源在 CI 上就红，而不是等到某个月的凭证悄悄变数</b>。</p>
 *
 * <h4>⚠️ 扫描口径：只看 MaterialConsumption 的构造点</h4>
 *
 * <p>🔴 第一版写成「全 {@code src/main} 里的 {@code setSourceType("字面量")}」，
 * <b>当场扫出 {@code SALES_ORDER} 和 {@code PRODUCTION_BATCH}</b> —— 那是<b>提醒</b>
 * 和别的实体的 sourceType，与消耗行毫无关系。同时它<b>漏掉</b>了真正的写入点
 * （{@code MANUAL_ISSUE} / {@code TRANSFER_OUT} 都写在三元表达式或常量里，
 * 匹配不到裸字面量）—— 阳性对照当场把这个错抓出来了。</p>
 *
 * <p>⇒ 改成与仓里既有的 {@code MaterialBatchSourceDocContractTest} 同一形状：
 * 扫 <b>{@code new MaterialConsumption()} 的构造点</b>，再在那一段里找来源。</p>
 *
 * <ul>
 *   <li>⚠️ 仍是代理判据：常量与变量实参（{@code SOURCE_AUTO_BOM}、
 *       {@code e.getSourceType()}）取不到值，靠
 *       {@link #productionObservedSourcesAreAllClassified} 用<b>生产实测值</b>兜住。</li>
 *   <li>⛔ 不要为了覆盖变量实参去堆正则 —— 要真判准只有一条路：把来源收敛成枚举。</li>
 * </ul>
 */
@DisplayName("每种消耗来源都必须被裁定过进不进凭证")
class VoucherConsumptionSourceCoverageContractTest {

    private static final Path MAIN_ROOT = Paths.get("src/main/java");

    /** 消耗行的构造点 —— 只在这些片段里找来源，⛔ 不满仓扫方法名。 */
    private static final String CONSTRUCTION_MARKER = "new MaterialConsumption()";

    /** 构造点之后这么多行内的 setSourceType 算它的。 */
    private static final int BLOCK_LINES = 35;

    /** 只匹配字面量实参；常量/变量实参匹配不到，见类 javadoc。 */
    private static final Pattern LITERAL_SOURCE =
            Pattern.compile("setSourceType\\(\\s*\"([A-Z_]+)\"");

    /**
     * 2026-08-27 生产库实测出现过的来源（全库 {@code material_consumptions}）。
     *
     * <p>⚠️ 这一组是<b>兜住扫描器盲区</b>用的 —— 它们大多由变量实参写入，源码扫不到。
     * ⛔ 不要因为「源码里搜不到」就从这里删条目。</p>
     */
    private static final Set<String> OBSERVED_IN_PRODUCTION = Set.of(
            "RAW_MATERIAL", "PACKAGING", "SEASONING", "SEMI_FINISHED",
            "MATERIAL_RETURN", "TRANSFER_OUT", "MANUAL_ISSUE");

    private static Set<String> literalSourcesInMain() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(MAIN_ROOT)) {
            for (Path p : (Iterable<Path>) paths.filter(Files::isRegularFile)
                    .filter(x -> x.toString().endsWith(".java"))::iterator) {
                String[] lines = stripped(p).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (!lines[i].contains(CONSTRUCTION_MARKER)) {
                        continue;
                    }
                    int end = Math.min(lines.length, i + BLOCK_LINES);
                    String block = String.join("\n", java.util.Arrays.copyOfRange(lines, i, end));
                    Matcher m = LITERAL_SOURCE.matcher(block);
                    while (m.find()) {
                        found.add(m.group(1));
                    }
                }
            }
        }
        return found;
    }

    /** 消耗行的构造点数 —— 阳性对照用: 一个都找不到就说明扫描器坏了。 */
    private static int constructionSiteCount() throws IOException {
        int n = 0;
        try (Stream<Path> paths = Files.walk(MAIN_ROOT)) {
            for (Path p : (Iterable<Path>) paths.filter(Files::isRegularFile)
                    .filter(x -> x.toString().endsWith(".java"))::iterator) {
                for (String line : stripped(p).split("\n", -1)) {
                    if (line.contains(CONSTRUCTION_MARKER)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * 逐行剔注释。
     *
     * <p>🔴 不剔就是假闸：{@link VoucherConsumptionSourcePolicy} 的 javadoc 里
     * 逐字列着那张实测分布表，里面每一个来源名都会被扫进来。</p>
     */
    private static String stripped(Path p) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readString(p, StandardCharsets.UTF_8).split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;
            }
            int slash = line.indexOf("//");
            out.append(slash >= 0 ? line.substring(0, slash) : line).append('\n');
        }
        return out.toString();
    }

    @Test
    @DisplayName("阳性对照: 扫描器确实找得到消耗行的构造点")
    void scannerActuallyFindsConstructionSites() throws IOException {
        // 2026-08-27 实测: 全 src/main 有 11 个 new MaterialConsumption() 构造点。
        // ⚠️ 断下界不断等号 —— 新增构造点是常事, 不该因此变红。
        assertThat(constructionSiteCount())
                .as("一个构造点都扫不到 —— 扫描器坏了(路径? 剔注释?), 后面的绿不作数")
                .isGreaterThanOrEqualTo(8);
        assertThat(literalSourcesInMain())
                .as("""
                        构造点扫到了, 但一个字面量来源都没提出来 —— 正则漂了。
                        MATERIAL_RETURN 是已知的裸字面量写入点(FactoryMaterialRequisitionServiceImpl)。""")
                .contains("MATERIAL_RETURN");
    }

    @Test
    @DisplayName("🔴 源码里每个字面量来源都必须被裁定过")
    void everyLiteralSourceIsClassified() throws IOException {
        Set<String> unclassified = new TreeSet<>();
        for (String src : literalSourcesInMain()) {
            if (!VoucherConsumptionSourcePolicy.IN_VOUCHER.contains(src)
                    && !VoucherConsumptionSourcePolicy.EXCLUDED.containsKey(src)) {
                unclassified.add(src);
            }
        }
        assertThat(unclassified)
                .as("""
                        这些消耗来源没人裁定过它进不进财务凭证。
                        ⛔ 不裁定的后果是二选一的静默错误: 计入 -> 凭空造成本; 不计入 -> 成本悄悄少算。
                        请在 VoucherConsumptionSourcePolicy 的 IN_VOUCHER 或 EXCLUDED 里登记(并写理由)。""")
                .isEmpty();
    }

    @Test
    @DisplayName("🔴 生产上真出现过的来源也必须被裁定过 —— 兜住扫描器的变量参数盲区")
    void productionObservedSourcesAreAllClassified() {
        Set<String> unclassified = new TreeSet<>();
        for (String src : OBSERVED_IN_PRODUCTION) {
            if (!VoucherConsumptionSourcePolicy.IN_VOUCHER.contains(src)
                    && !VoucherConsumptionSourcePolicy.EXCLUDED.containsKey(src)) {
                unclassified.add(src);
            }
        }
        assertThat(unclassified)
                .as("这些来源**生产库里真的有行**, 却没被裁定 —— 它们大多由变量实参写入, 源码扫不到")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 同一个来源不许同时在「进」和「不进」两张表里")
    void classificationIsUnambiguous() {
        Set<String> both = new TreeSet<>(VoucherConsumptionSourcePolicy.IN_VOUCHER);
        both.retainAll(VoucherConsumptionSourcePolicy.EXCLUDED.keySet());
        assertThat(both)
                .as("同时登记在两边 —— 读的人无法判断它到底进不进")
                .isEmpty();
    }

    @Test
    @DisplayName("MATERIAL_RETURN 必须在「进」那一侧 —— 它是生产领用的冲减, 金额为负")
    void productionReturnStaysInTheVoucher() {
        // 实测: 生产库里这一项 23 行 -631.40 元。领用进而退料不进, 成本会虚高。
        assertThat(VoucherConsumptionSourcePolicy.isProductionConsumption("MATERIAL_RETURN"))
                .as("把退料排除掉会让成本虚高 —— 「生产领用」是个会计科目, 不是一个动作名")
                .isTrue();
    }

    @Test
    @DisplayName("阴性对照: 移库与手工出库确实被挡在凭证外")
    void movesAndManualIssuesStayOut() {
        assertThat(VoucherConsumptionSourcePolicy.isProductionConsumption("TRANSFER_OUT")).isFalse();
        assertThat(VoucherConsumptionSourcePolicy.isProductionConsumption("MANUAL_ISSUE")).isFalse();
        // null = 说不出这行怎么来的, 不该记进账
        assertThat(VoucherConsumptionSourcePolicy.isProductionConsumption(null)).isFalse();
        // 但 null 不是「已知排除」—— 它该被当成需要有人看一眼的信号
        assertThat(VoucherConsumptionSourcePolicy.isKnownExclusion(null)).isFalse();
    }
}
