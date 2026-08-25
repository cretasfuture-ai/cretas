package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新建 {@code MaterialBatch} 的地方必须写来源单据 —— 棘轮，只禁增长。
 *
 * <h2>为什么需要这一条</h2>
 *
 * <p>「入库必须有发起单」这道 fail-closed 守卫写在
 * {@code MaterialBatchServiceImpl.validateSourceDoc} 里，而它<b>只在
 * {@code createMaterialBatch} 这一条路上跑</b>。其余写入点是直接
 * {@code new MaterialBatch()} + {@code repository.save()}，<b>绕开整道守卫</b>。</p>
 *
 * <p>这不是假设。2026-08-16 的 {@code PurchaseServiceImpl} 就因此漏过一次：
 * 采购收货这条主路径「一个字都不写」，prod 实测建出来的批次
 * {@code source_doc_type}/{@code source_doc_id} 全空，从批次追不回采购单与供应商。
 * 当时的修法是<b>在那一个文件里手工补一行</b> —— 守卫本身没有被接上，
 * 所以下一个新写入点还会漏。2026-08-25 复查发现 {@code TransferServiceImpl}
 * 正是「下一个」：调入方建的批次同样一个来源字段都不写。</p>
 *
 * <p>生产实测（2026-08-25，只读）：{@code material_batches} 全库 177 行里
 * {@code source_doc_type} 为空的有 <b>37 行</b>，时间窗 08-09 → <b>08-18</b>；
 * 而 {@code PURCHASE_RECEIVE} 从 08-17 开始、最新批次是 08-25 ——
 * 即那次手工补写之后<b>不再产生新的无来源批次</b>，37 行是历史残留。
 * 本闸守的是<b>不要再长出下一个</b>，不是去修那 37 行。</p>
 *
 * <h2>判据与它的代理性（必须写明，见 measurement-and-wiring 形态 E）</h2>
 *
 * <p>真正想守的是「<b>任何会被持久化的</b> MaterialBatch 都带来源单」。
 * 静态扫描判不出「这个对象最终有没有进 save()」，所以用一个<b>代理判据</b>：
 * <em>凡是出现 {@code new MaterialBatch()} 的文件，同一文件里必须出现
 * {@code setSourceDocType}；否则必须登记在 {@link #KNOWN_WITHOUT_SOURCE_DOC} 里并写明理由。</em></p>
 *
 * <p>这个代理有两个已知盲区，<b>不打算靠加启发式去逼近</b>：</p>
 * <ol>
 *   <li><b>文件级粒度</b>：同一文件里若有两处构造、只有一处设了来源，本闸看不出来。
 *       当前全仓只有 11 处构造点，粒度够用；真要更细得做 Java AST，收益不抵成本。</li>
 *   <li><b>临时对象会被误判</b>：为拼 DTO/报表而 new 出来、从不落库的对象，
 *       本闸同样要求它「设来源或登记」。这类已登记在下面，登记项即是说明。</li>
 * </ol>
 *
 * <h2>为什么是棘轮</h2>
 *
 * <p>建闸当天有 1 个真实缺口（见下），硬红会让它当天被 {@code @Disabled} 掉，
 * 那时覆盖率归零。所以冻结存量、只禁增长：<b>名单是债务登记，不是豁免</b>，
 * 还清了必须从名单里划掉（本闸会检查已消失的条目，防止名单烂掉）。</p>
 */
@DisplayName("新建 MaterialBatch 必须写来源单据（棘轮）")
class MaterialBatchSourceDocContractTest {

    private static final Path MAIN_ROOT = Paths.get("src/main/java");

    /** 构造点的机械信号。 */
    private static final String CONSTRUCTION_MARKER = "new MaterialBatch()";

    /** 写了来源单据的机械信号。 */
    private static final String SOURCE_DOC_MARKER = "setSourceDocType";

    /**
     * 存量豁免 —— <b>债务登记</b>，键是文件的仓库相对路径，值是理由。
     *
     * <p>⛔ 不许往这里加新条目。新写入点要么设来源，要么这道闸当场红。</p>
     */
    private static final Map<String, String> KNOWN_WITHOUT_SOURCE_DOC = Map.of(
            "service/impl/ProcessingServiceImpl.java",
            "非写入点: 该处 new MaterialBatch() 只是为拼报表 materialDetail 的临时占位对象, 从不落库");

    private static Set<String> filesConstructingMaterialBatch() throws IOException {
        Set<String> hits = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(MAIN_ROOT)) {
            for (Path p : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (src.contains(CONSTRUCTION_MARKER)) {
                    hits.add(relative(p));
                }
            }
        }
        return hits;
    }

    /** 统一成 {@code com/cretas/aims/...} 之后的相对路径，跨平台分隔符归一。 */
    private static String relative(Path p) {
        String s = MAIN_ROOT.relativize(p).toString().replace('\\', '/');
        String pkg = "com/cretas/aims/";
        int i = s.indexOf(pkg);
        return i >= 0 ? s.substring(i + pkg.length()) : s;
    }

    private static boolean writesSourceDoc(String relPath) throws IOException {
        Path p = MAIN_ROOT.resolve("com/cretas/aims/" + relPath);
        return Files.readString(p, StandardCharsets.UTF_8).contains(SOURCE_DOC_MARKER);
    }

    @Test
    @DisplayName("阳性对照: 扫描确实找得到构造点 (仪器活着)")
    void scannerActuallyFindsConstructionSites() throws IOException {
        Set<String> sites = filesConstructingMaterialBatch();

        // 若哪天重构把 `new MaterialBatch()` 全换成 builder, 本闸会静默扫到 0 个而"全绿",
        // 那时这条会红, 提醒去改 CONSTRUCTION_MARKER —— 否则本闸变成恒真式。
        assertThat(sites)
                .as("扫不到任何构造点 ⇒ 是 CONSTRUCTION_MARKER 过期了, 不是代码变干净了")
                .isNotEmpty();
        assertThat(sites).contains("service/inventory/impl/PurchaseServiceImpl.java");
    }

    @Test
    @DisplayName("每个构造 MaterialBatch 的文件都写来源单据, 或已登记在债务名单里")
    void everyConstructionSiteWritesSourceDocOrIsRegistered() throws IOException {
        Set<String> offenders = new TreeSet<>();
        for (String site : filesConstructingMaterialBatch()) {
            if (writesSourceDoc(site)) continue;
            if (KNOWN_WITHOUT_SOURCE_DOC.containsKey(site)) continue;
            offenders.add(site);
        }

        assertThat(offenders)
                .as("这些文件 new 了 MaterialBatch 却没写 sourceDocType。"
                        + "入库必须有发起单的守卫只在 createMaterialBatch 那条路上跑, "
                        + "直接 new + save 会绕开它 —— 请写来源, 或在 KNOWN_WITHOUT_SOURCE_DOC 里登记理由")
                .isEmpty();
    }

    @Test
    @DisplayName("债务名单不许烂掉: 已经不再构造 MaterialBatch 的条目必须划掉")
    void registryHasNoStaleEntries() throws IOException {
        Set<String> sites = filesConstructingMaterialBatch();
        Set<String> stale = new TreeSet<>(KNOWN_WITHOUT_SOURCE_DOC.keySet());
        stale.removeAll(sites);

        assertThat(stale)
                .as("这些条目已经不再 new MaterialBatch, 请从 KNOWN_WITHOUT_SOURCE_DOC 里删掉 "
                        + "—— 名单是债务登记, 留着过期条目会让下一个人以为债还没还")
                .isEmpty();
    }

    /**
     * 债务只许变短。当前 1 条, 且是【非写入点】—— 即真实缺口已清零。
     * 还清一条就把这个数字改小 —— 改大等于新增绕过守卫的写入点。
     */
    @Test
    @DisplayName("棘轮: 债务条目数只许减少, 当前 1")
    void debtRatchetOnlyShrinks() {
        assertThat(KNOWN_WITHOUT_SOURCE_DOC)
                .as("⛔ 不许新增豁免。新写入点必须写来源单据")
                .hasSizeLessThanOrEqualTo(1);
    }
}
