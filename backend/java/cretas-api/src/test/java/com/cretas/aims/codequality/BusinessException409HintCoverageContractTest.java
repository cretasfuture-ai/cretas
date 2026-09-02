package com.cretas.aims.codequality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIUSHANMEN 走查 (2026-09-02)：车间/仓管操作被 409 拦下, 但一批
 * {@code BusinessException(409, …)} 没配 {@code .withHint(...)} —— web-admin
 * 拦截器（见 {@code request.ts}）曾把"没有 actionHint"当成"这是调用方自己处理的
 * 乐观锁冲突", 整条提示被吞掉; 修好拦截器之后(见同一批 PR), 用户至少能看到裸的
 * 后端 message, 但**没有下一步动作**——「所选批次不在可投料的生产库中」不说要先去
 * 领料。
 *
 * <h2>两层判据</h2>
 * <ol>
 *   <li><b>硬闸</b>：本次走查触达的 7 个文件（报工投料分配 / 发货批次分配 / 过程单 /
 *       调拨 / 采购 / 应付结算 / 销售发货）——{@code new BusinessException(409, …)}
 *       <b>必须</b>带 {@code .withHint(}。新增一处不带 hint 的 409 会让本闸<b>直接红</b>，
 *       不经棘轮。</li>
 *   <li><b>棘轮</b>：其余 409 抛出点当天普查存量 {@value #REPO_WIDE_FROZEN_HINTLESS}
 *       处（普查方式见 {@link #scanRepoWide()}），<b>只许变少不许变多</b>。刻意不要求
 *       一次改完全仓——同一批 41 处 hint 都是"该把用户导到哪个页面做什么"的产品判断，
 *       批量编文案而不经业务确认，产出的是看着像话、实际指错地方的提示（同源判据见
 *       {@link BlockingErrorsCarryActionHintContractTest}）。</li>
 * </ol>
 *
 * <h2>🔴 代理判据 —— 本闸只量「有没有 hint」，量不出「说得对不对」（形态 E）</h2>
 *
 * <p>{@code .withHint(} 的字面存在是代理，不是终审。<b>2026-09-02 当天就应验了</b>：
 * 首批 41 条写完、本闸五颗绿灯全亮之后，终审抽 10 条查出 4 条不成立——同一形状：守卫读的是
 * <b>计划/批次开工时钉住的那一份</b>快照（BOM 修订 / Workflow 修订），hint 却让用户去改
 * <b>活的那一份</b>（BOM成本管理 / Workflow 画布）；改完发布 = 新版本，在途计划/已开工批次
 * 仍钉着旧版本，用户照做完还是同一个 409——比没有 hint 更糟，因为它让人误以为"照着做就好了"。
 * 详细案例与判据见 {@link BlockingErrorsCarryActionHintContractTest} 顶部注释；已在同一批 PR
 * 里改正（8 处：3 条 BOM 相关 + 2 条 BOM 自动投料相关 + 3 条 Workflow 端口快照相关），
 * 逐条核对本 PR 其余 39 处 hint 不涉及"读钉住值、指活的源"这个形状，故不受影响。
 * <b>本闸对这一类假 hint 是瞎的</b>——它红不了，因为它只查字符串存不存在，不查语义；
 * 能查出来的只有走查/终审。
 */
class BusinessException409HintCoverageContractTest {

    /**
     * 本次 LIUSHANMEN 走查触达、逐条读过 guard 再配 hint 的文件——不是整个包。
     * 同包内其它文件可能仍有历史欠账，留给棘轮。
     */
    private static final List<Path> TOUCHED_FILES = List.of(
            Path.of("src/main/java/com/cretas/aims/service/processentry/impl"
                    + "/ProductionStockAllocationServiceImpl.java"),
            Path.of("src/main/java/com/cretas/aims/service/sales/impl"
                    + "/SalesDeliveryBatchAllocationServiceImpl.java"),
            Path.of("src/main/java/com/cretas/aims/service/processentry/impl"
                    + "/ProcessSheetServiceImpl.java"),
            Path.of("src/main/java/com/cretas/aims/service/inventory/impl"
                    + "/TransferServiceImpl.java"),
            Path.of("src/main/java/com/cretas/aims/service/inventory/impl"
                    + "/PurchaseServiceImpl.java"),
            Path.of("src/main/java/com/cretas/aims/service/finance/impl"
                    + "/PayableSettlementServiceImpl.java"),
            Path.of("src/main/java/com/cretas/aims/service/inventory/impl"
                    + "/SalesServiceImpl.java"));

    private static final Path MAIN = Path.of("src/main/java");

    /**
     * 全仓其余 409 抛出点当天(2026-09-02)普查存量。<b>只许调小。</b>
     *
     * <p>修好一批之后把这个数往下调；调大之前先问一句：新增的那处 409，用户被拦下时
     * 知道下一步该去哪、做什么吗？不知道就该给 hint，不该把 FROZEN 数字改大来放行。
     */
    private static final int REPO_WIDE_FROZEN_HINTLESS = 197;

    /** 一条 {@code throw}/{@code orElseThrow} 语句里从 {@code new BusinessException(409} 到分号为止的链式调用。 */
    private static final Pattern THROW_CHAIN_409 = Pattern.compile(
            "new\\s+BusinessException\\s*\\(\\s*409\\b(?:[^;]*?)\\)(?:\\s*\\.\\w+\\s*\\([^;]*?\\))*\\s*;",
            Pattern.DOTALL);

    private record Scan(int total, List<String> hintless) {
    }

    private static Scan scanFiles(List<Path> files) throws IOException {
        int total = 0;
        List<String> hintless = new ArrayList<>();
        for (Path file : files) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String source = Files.readString(file);
            Matcher m = THROW_CHAIN_409.matcher(source);
            while (m.find()) {
                total++;
                String chain = m.group();
                if (chain.contains(".withHint(")) {
                    continue;
                }
                int line = (int) source.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
                hintless.add(file + ":" + line);
            }
        }
        return new Scan(total, hintless);
    }

    private static Scan scanRepoWide() throws IOException {
        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            javaFiles.addAll(files.filter(p -> p.toString().endsWith(".java")).toList());
        }
        return scanFiles(javaFiles);
    }

    @Test
    @DisplayName("阳性对照: 触达文件里确实扫得到 BusinessException(409 —— 否则下面的「全部带 hint」是恒真式")
    void touchedFilesScannerFindsSites() throws IOException {
        Scan scan = scanFiles(TOUCHED_FILES);
        assertThat(scan.total())
                .as("触达的 7 个文件里一个 BusinessException(409 都扫不到 —— 正则失配或路径不对, "
                        + "下面「零 hint 缺口」的断言无效")
                .isGreaterThanOrEqualTo(41);
    }

    @Test
    @DisplayName("硬闸: 本次走查触达的 7 个文件, 每一处 BusinessException(409 都必须带 .withHint(")
    void touchedFilesEveryBusinessException409CarriesHint() throws IOException {
        Scan scan = scanFiles(TOUCHED_FILES);

        assertThat(scan.hintless())
                .as("这些文件已经逐条读过 guard 配好 hint —— 新增一处不带 hint 的 409 会让用户"
                        + "被拦下却不知道下一步做什么。修法: 链上补 .withHint(\"请前往「X → Y」…\")")
                .isEmpty();
    }

    @Test
    @DisplayName("阳性对照: 全仓普查确实扫得到超过冻结值的候选 —— 证明棘轮不是在数空气")
    void repoWideScannerFindsMoreThanFrozen() throws IOException {
        Scan scan = scanRepoWide();
        assertThat(scan.total())
                .as("全仓扫到的 BusinessException(409 总数少于冻结的缺口值, 说明扫描器没跑对目录")
                .isGreaterThan(REPO_WIDE_FROZEN_HINTLESS);
    }

    @Test
    @DisplayName("🔴 棘轮: 全仓没配 hint 的 BusinessException(409 只许变少, 不许变多")
    void repoWideHintlessCountDoesNotGrow() throws IOException {
        Scan scan = scanRepoWide();
        assertThat(scan.hintless().size())
                .as("没配 hint 的 BusinessException(409 从 " + REPO_WIDE_FROZEN_HINTLESS
                        + " 涨到了 " + scan.hintless().size() + "。新写的 409 guard 请配 .withHint(...); "
                        + "确有理由不给提示的, 把 REPO_WIDE_FROZEN_HINTLESS 往上调之前先说明理由。")
                .isLessThanOrEqualTo(REPO_WIDE_FROZEN_HINTLESS);
    }

    @Test
    @DisplayName("⛔ 冻结值别忘了往下调 —— 修完一批要把 REPO_WIDE_FROZEN_HINTLESS 跟着降, 否则棘轮松了")
    void frozenValueTracksReality() throws IOException {
        int hintless = scanRepoWide().hintless().size();
        assertThat(REPO_WIDE_FROZEN_HINTLESS - hintless)
                .as("已经修到 " + hintless + " 处, 而冻结值还停在 " + REPO_WIDE_FROZEN_HINTLESS
                        + " —— 中间这段空隙让棘轮不再守任何东西, 请把 REPO_WIDE_FROZEN_HINTLESS 调到 " + hintless)
                .isLessThanOrEqualTo(5);
    }
}
