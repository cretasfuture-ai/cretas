package com.cretas.aims.gate;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴🔒 <b>「结不掉就不算待扣」这条谓词只许有一份</b>
 * (BUG-GHOST-PENDING-FEED-DELETED-PLANS, 2026-09-06)。
 *
 * <h2>为什么需要这道闸</h2>
 *
 * <p>{@code ProcessSheetRowRepository#findUnsettledStockFeedRows} 的谓词只问「这一行结了没有」,
 * <b>不问「这一行还结不结得掉」</b> —— 计划被硬删/软删/取消之后, 它照样把那些行吐出来,
 * 而它们永远盖不上戳 ⇒ 谁直接用它的原始结果, 谁就复制一份<b>永久幽灵预扣</b>。
 *
 * <p>排除谓词与留痕<b>只写在一个地方</b>: {@code PendingInterimFeedServiceImpl#pendingSemiFeedSnapshot}。
 * 三个消费方 (投料下拉 ×2 {@code WipInventoryServiceImpl} / 半成品盘点
 * {@code SemiFinishedStocktakeServiceImpl}) 都经它取数, 所以修一处就够 —— <b>前提是它保持唯一</b>。
 *
 * <p>⚠️ 这正是「同一个东西有两份, 它一定会漂」的预防位: 同一个聚合口径此前已经漂过一次
 * ({@code stockConsumedAtSubmit} 在两条扣减路径上跳过、唯独聚合器没跳)。⛔ 不要靠注释拦,
 * 注释拦不住 —— 落成闸。
 *
 * <h2>红了怎么办</h2>
 *
 * <p><b>不是</b>「把新调用方加进白名单」。要么让新调用方改走
 * {@code PendingInterimFeedService#pendingSemiFeedSnapshot}, 要么把排除谓词下沉进仓库层
 * <b>并同时</b>把计数留痕一起搬过去 —— ⛔ 静默剔除与「本来就没有这些行」完全同形, 不许只搬一半。
 */
@DisplayName("幽灵预扣: findUnsettledStockFeedRows 的排除谓词只许有一个落点")
class PendingFeedGhostPlanSingleConsumerContractTest {

    private static final Path MAIN_ROOT = Paths.get("src/main/java");

    /** 原始查询: 只按 interim_settled_at 过滤, 不管计划死活。 */
    private static final String RAW_QUERY = "findUnsettledStockFeedRows";

    /** 唯一允许直接消费它的类 —— 排除谓词与留痕都在这里。 */
    private static final String SOLE_CONSUMER = "PendingInterimFeedServiceImpl";

    private Set<String> callersOf(String method) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("拿不到 JDK 编译器 —— 仪器坏了, 这一轮读数作废").isNotNull();

        List<Path> sources;
        try (Stream<Path> walk = Files.walk(MAIN_ROOT)) {
            sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }
        Set<String> callers = new TreeSet<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fm, d -> { }, List.of(), null,
                    fm.getJavaFileObjectsFromPaths(sources));
            for (CompilationUnitTree cu : task.parse()) {
                String file = Paths.get(cu.getSourceFile().toUri()).getFileName().toString();
                String klass = file.substring(0, file.length() - ".java".length());
                cu.accept(new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                        String sel = node.getMethodSelect().toString();
                        if (sel.equals(method) || sel.endsWith("." + method)) {
                            callers.add(klass);
                        }
                        return super.visitMethodInvocation(node, unused);
                    }
                }, null);
            }
        }
        return callers;
    }

    @Test
    @DisplayName("阳性对照: AST 真的解析到了调用 (数出 0 个最像「一切正常」)")
    void positiveControl() throws IOException {
        assertThat(callersOf(RAW_QUERY))
                .as("一个 " + RAW_QUERY + " 调用都没找到 —— 那是 AST 没跑起来, 不是没人调它。"
                        + "⛔ 别把这个 0 读成「闸通过了」")
                .isNotEmpty();
    }

    @Test
    @DisplayName("🔴 只有 PendingInterimFeedServiceImpl 能直接用原始查询 (AST 数结构, 不是 grep 数文本)")
    void rawUnsettledQueryHasExactlyOneConsumer() throws IOException {
        assertThat(callersOf(RAW_QUERY))
                .as("""
                        直接消费 findUnsettledStockFeedRows 的类多了一个。它拿到的是【没有剔除
                        幽灵行】的原始结果: 计划被硬删/软删/取消之后那些行永远盖不上戳, 贡献的待扣量
                        永远不会清, 也没有任何操作能释放 ⇒ 半成品批次可用量被永久压低 / 盘点永久多减。

                        ⛔ 不要把新类加进这道闸的白名单。正确做法二选一:
                          ① 新调用方改走 PendingInterimFeedService#pendingSemiFeedSnapshot;
                          ② 把排除谓词下沉进仓库层, 【并且】把 orphanRowCount/cancelledRowCount
                             那套计数留痕一起搬过去 —— 静默剔除与「本来就没有这些行」长得一模一样。""")
                .containsExactly(SOLE_CONSUMER);
    }
}
