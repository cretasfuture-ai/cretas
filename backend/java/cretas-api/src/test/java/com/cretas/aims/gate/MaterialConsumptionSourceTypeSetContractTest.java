package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸：每个 {@code new MaterialConsumption()} 都必须设 {@code sourceType}。
 *
 * <h4>它守的是什么</h4>
 *
 * <p>{@code VoucherConsumptionSourcePolicy.isProductionConsumption(null)} 返回 {@code false}
 * —— 「说不出这行是怎么来的，就不该记进账」。这条规则本身是对的，但它把
 * <b>「漏设 sourceType」翻译成了「这笔成本不进凭证」</b>，而且是<b>静默</b>的：
 * 不报错、不 500、不留信号，只是某个月的成本悄悄少算。</p>
 *
 * <p>兄弟闸 {@link VoucherConsumptionSourceCoverageContractTest} 守的是
 * <b>「每一种来源都被裁定过」</b>；本闸守的是它的前一步 ——
 * <b>「每一行都有来源」</b>。两者都需要，缺一个都会漏。</p>
 *
 * <h4>2026-08-27 建闸时的实测</h4>
 *
 * <pre>
 *   构造点 11 处，其中 3 处从不设 sourceType，且 3 处全部会 save() 落库：
 *     MaterialBatchServiceImpl#consumeBatchMaterial      (REST + AI 工具两条活路径)
 *     ProcessingServiceImpl#recordMaterialConsumption    (REST 活端点)
 *     ProductionPlanServiceImpl#recordMaterialConsumption(REST 活端点)
 * </pre>
 *
 * <p>⚠️ 当时生产库 {@code material_consumptions} 恰好 0 行 NULL —— 但那三个都是
 * <b>活的对外端点</b>，0 行是巧合不是保证。本闸把那个巧合换成结构保证。</p>
 *
 * <h4>⚠️ 代理判据，写明它看不见什么</h4>
 *
 * <p>本闸按<b>文本块</b>判定（构造点起 {@value #BLOCK_LINES} 行内出现 {@code setSourceType}），
 * 与兄弟闸同一形状 —— 本仓 test scope 没有 JavaParser，⛔ 不为这一道闸引依赖。</p>
 *
 * <ul>
 *   <li>看不见：把赋值拆到 {@value #BLOCK_LINES} 行以外、或经 helper 间接设置的写法；</li>
 *   <li>看不见：{@code setSourceType(someNullableVar)} —— 设了但值可能为 null
 *       （这一类靠兄弟闸的<b>生产实测来源表</b>兜住）。</li>
 * </ul>
 *
 * <p>⛔ 不要靠堆正则去逼近。要真判准只有一条路：把 {@code sourceType} 收敛成枚举
 * 并在实体上标 {@code nullable = false}。</p>
 */
@DisplayName("每个消耗行构造点都必须设 sourceType")
class MaterialConsumptionSourceTypeSetContractTest {

    private static final Path MAIN_ROOT = Paths.get("src/main/java");

    private static final String CONSTRUCTION_MARKER = "new MaterialConsumption()";

    /** 构造点之后这么多行内的 setSourceType 算它的（与兄弟闸取同一口径）。 */
    private static final int BLOCK_LINES = 35;

    /**
     * 显式登记的豁免 —— <b>留痕，不是备注</b>。
     *
     * <p>格式 {@code 文件名:方法或用途}。⛔ 往这里加条目必须同时写清
     * 「为什么这一处不落库 / 落库了也不需要来源」，⛔ 不许为了让闸变绿而加。</p>
     *
     * <p>2026-08-27 建闸时为空：11 处构造点全部设了 sourceType。</p>
     *
     * <p>⚠️ 一个差点被登进来的反例：{@code OrderCostBreakdownService} 曾被上一棒的交接件
     * 列为「第 4 个不设 sourceType 的写入点」，实测它<b>根本不是写入点</b> ——
     * 只 {@code findBy...} 读、从不 {@code save}，构造出来的对象是内存合并的成本行。
     * 它确实设了 {@code sourceType}（透传上游行的值），所以既不需要豁免也不该被算成缺口。
     * 见 {@link #costBreakdownMergeIsNotAPersistenceSite}。</p>
     */
    private static final Set<String> EXEMPT = Set.of();

    /** 一个构造点 = 文件相对路径 + 行号 + 该块文本。 */
    private record Site(String where, String block) { }

    private static List<Site> constructionSites() throws IOException {
        List<Site> sites = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(MAIN_ROOT)) {
            for (Path p : (Iterable<Path>) paths.filter(Files::isRegularFile)
                    .filter(x -> x.toString().endsWith(".java"))::iterator) {
                String[] lines = stripped(p).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (!lines[i].contains(CONSTRUCTION_MARKER)) {
                        continue;
                    }
                    int end = Math.min(lines.length, i + BLOCK_LINES);
                    sites.add(new Site(
                            p.getFileName().toString() + ":" + (i + 1),
                            String.join("\n", Arrays.copyOfRange(lines, i, end))));
                }
            }
        }
        return sites;
    }

    /**
     * 逐行剔注释。
     *
     * <p>🔴 不剔就是假闸 —— 本仓已有先例：三处新写的说明性注释里逐字引用了
     * {@code setSourceType} 与来源名，不剔的话闸会把<b>自己要求写的那句注释</b>
     * 当成被测代码数进去，于是漏设的地方照样绿。</p>
     *
     * <p>⚠️ 注释行<b>置空而不是删掉</b> —— 必须保持行号与原文件一一对应。
     * 建闸时第一版是 {@code continue}(整行丢弃)，变异对照当场把后果照出来了：
     * 闸报的是 {@code ProductionPlanServiceImpl.java:6354}，而真实位置是
     * <b>7607</b>，差了一千两百多行。文件名对、行号错，
     * 照着去看的人会打开一段毫不相干的代码。</p>
     */
    private static String stripped(Path p) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : Files.readString(p, StandardCharsets.UTF_8).split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                out.append('\n');   // ⛔ 不要改成 continue: 行号会整体前移, 报出来的位置就是错的
                continue;
            }
            int slash = line.indexOf("//");
            out.append(slash >= 0 ? line.substring(0, slash) : line).append('\n');
        }
        return out.toString();
    }

    @Test
    @DisplayName("阳性对照: 扫描器确实找得到构造点, 且剔注释之后仍找得到")
    void scannerActuallyFindsConstructionSites() throws IOException {
        List<Site> sites = constructionSites();
        // 2026-08-27 实测 11 处。断下界不断等号 —— 新增构造点是常事, 不该因此变红。
        assertThat(sites)
                .as("一个构造点都扫不到 —— 扫描器坏了(路径? 剔注释剔过头?), 后面的绿不作数")
                .hasSizeGreaterThanOrEqualTo(8);
        // ⚠️ 这一条钉的是「变异之后仍然成立」的东西: 不管哪一处漏设 sourceType,
        //    「至少有一处设了」都还成立 ⇒ 它红了就是仪器坏了, 而不是发现本身。
        assertThat(sites.stream().anyMatch(s -> s.block().contains("setSourceType")))
                .as("11 个构造点里一处 setSourceType 都提不出来 —— 块窗口或剔注释坏了")
                .isTrue();
    }

    @Test
    @DisplayName("🔴 每个构造点都必须设 sourceType —— 漏设会让这笔成本静默不进凭证")
    void everyConstructionSiteSetsSourceType() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Site s : constructionSites()) {
            if (EXEMPT.contains(s.where()) || s.block().contains("setSourceType")) {
                continue;
            }
            missing.add(s.where());
        }
        assertThat(missing)
                .as("""
                        这些消耗行构造点没有设 sourceType。
                        后果不是报错, 是**静默少算成本**: VoucherConsumptionSourcePolicy 对 null 返回 false
                        ⇒ 这条领用不进财务凭证, 没有任何人会收到信号。
                        ⛔ 不要靠「生产库目前 0 行 NULL」放过它 —— 那是巧合, 不是保证。
                        修法: 在构造点设一个已被 VoucherConsumptionSourcePolicy 裁定过的来源;
                        确实不该有来源的, 到 EXEMPT 里显式登记并写明理由。""")
                .isEmpty();
    }

    @Test
    @DisplayName("留痕: 成本拆解里的合并行不是落库写入点 —— 上一棒在这里判错过")
    void costBreakdownMergeIsNotAPersistenceSite() throws IOException {
        Path f = MAIN_ROOT.resolve("com/cretas/aims/service/yield/OrderCostBreakdownService.java");
        assertThat(Files.exists(f))
                .as("阳性对照: 文件都不在了, 这条断言无从谈起 —— 路径变了就来改这里")
                .isTrue();
        String src = stripped(f);
        assertThat(src)
                .as("阳性对照: 这个文件确实在构造消耗行, 否则本条断言是空转")
                .contains(CONSTRUCTION_MARKER);
        assertThat(src)
                .as("""
                        它开始落库了 —— 那么它构造的那些行也会带着**透传来的** sourceType 进库,
                        而透传的上游值可能为 null。这条断言变红不代表你做错了, 但请先确认:
                        透传的那个来源在落库前是不是一定非空。""")
                .doesNotContain("consumptionRepository.save(");
    }
}
