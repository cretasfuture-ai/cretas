package com.cretas.aims.codequality;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1551-11 红线 1: 待审批 / 已驳回的强制插单不能经任何开工入口开工 —— 结构闸, 走 javac AST (形态 C⁸), 不是逐行正则。
 *
 * <ol>
 *   <li>把<b>批次</b>设成 IN_PROGRESS 的每一处 ({@code setStatus(..)} / builder {@code .status(..)}, 限定名或静态导入,
 *       跨行都算) 必须登记在 {@link #BATCH_SITES}, 多一处少一处都红;</li>
 *   <li>登记为开工的入口方法里必须有 {@code PendingApprovalStartGuard.check(..)} 调用 (注释里的不算), 且同一方法内
 *       调用位置在设状态之前;</li>
 *   <li>「待审批」只在守卫里判: 入口不许另写 {@code isPendingApproval()} 或引用 {@code ProductionPlan.ApprovalStatus.PENDING}
 *       (含 {@code getApprovalStatus() == PENDING} 写法)。</li>
 * </ol>
 * 已知盲区: 只解析不做类型归因, {@code "PENDING".equals(x.getApprovalStatus().name())} 这类字符串比较认不出。
 */
class BatchStartApprovalGuardAstContractTest {

    private static final String GUARD = "PendingApprovalStartGuard";

    /** 「类#方法」→ 处置。GATED 的方法 (或它的全部调用方, 见 GUARDED_BY) 必须过守卫。 */
    private static final Map<String, String> BATCH_SITES = Map.of(
            "ProcessingServiceImpl#startProduction", "GATED: 批次开工 (REST /batches/{id}/start、AI 两个工具)",
            "ClerkProcessEntryServiceImpl#createProductionBatch", "GATED: 文员录入",
            "ProductionPlanServiceImpl#materializeBatchForPlan",
            "GATED_BY_CALLERS: 计划开工 startProduction 与 createBatchFromPlan 共用的批次物化",
            "ProcessingServiceImpl#resumeProduction", "NOT_GATED: 已暂停 → 生产中, 早已开过工",
            "ReportReversalServiceImpl#reopenPlanAndBatchAfterSettlementReversal", "NOT_GATED: 结算冲销后重开, 不是开工");

    /** 必须自己调用守卫的方法。 */
    private static final List<String> GUARD_HOLDERS = List.of(
            "ProcessingServiceImpl#startProduction",
            "ClerkProcessEntryServiceImpl#createProductionBatch",
            "ProductionPlanServiceImpl#startProduction",
            "ProductionPlanServiceImpl#createBatchFromPlan");

    /** 允许出现「待审批」判定的文件: 实体定义、守卫本身、审批流 (批准 / 驳回前看计划还在不在待审批)。 */
    private static final List<String> JUDGE_ALLOWED = List.of(
            "ProductionPlan", GUARD, "UrgentInsertServiceImpl");

    // ------------------------------------------------------------------ 主代码

    @Test
    void everyBatchInProgressSiteIsRegisteredAndEveryStartEntryCallsTheGuardBeforeWriting() throws IOException {
        Scan scan = scan(mainSources());

        assertThat(scan.batchInProgress.keySet())
                .as("新增或挪动了把批次设成 IN_PROGRESS 的地方: 判断它是不是开工, 是就接上 PendingApprovalStartGuard.check, 再登记进 BATCH_SITES")
                .containsExactlyInAnyOrderElementsOf(BATCH_SITES.keySet());

        for (String holder : GUARD_HOLDERS) {
            assertThat(scan.guardCalls).as(holder + " 是开工入口, 必须调用 PendingApprovalStartGuard.check(..)")
                    .containsKey(holder);
            Long write = scan.batchInProgress.get(holder);
            if (write != null) {
                assertThat(scan.guardCalls.get(holder))
                        .as(holder + ": 守卫必须在把批次设成生产中之前调用 (被拦不留半截状态)")
                        .isLessThan(write);
            }
        }
    }

    @Test
    void pendingApprovalIsJudgedOnlyInTheGuard() throws IOException {
        Scan scan = scan(mainSources());
        assertThat(scan.pendingJudges).as("阳性对照: 守卫自己的判定都没找到 = AST 没跑起来").contains(GUARD);
        List<String> offenders = new ArrayList<>(scan.pendingJudges);
        offenders.removeAll(JUDGE_ALLOWED);
        assertThat(offenders)
                .as("开工入口请调用 PendingApprovalStartGuard.check(plan, describer), 不要自己判 isPendingApproval() / ApprovalStatus.PENDING")
                .isEmpty();
    }

    // ------------------------------------------------------------------ 仪器自检 (证明能抓到各种写法)

    @Test
    void detectorCatchesBuilderStaticImportMultiLineAndIgnoresComments() throws IOException {
        String builderStatic = """
                package x;
                import static com.cretas.aims.entity.enums.ProductionBatchStatus.IN_PROGRESS;
                class BuilderStatic {
                    Object make() {
                        return ProductionBatch.builder()
                                .status(
                                        IN_PROGRESS)
                                .build();
                    }
                }
                """;
        String commentOnly = """
                package x;
                class CommentOnly {
                    void start(Object batch) {
                        // PendingApprovalStartGuard.check(plan, describer);
                        /* PendingApprovalStartGuard.check(plan, null); */
                        batch.setStatus(com.cretas.aims.entity.enums.ProductionBatchStatus
                                .IN_PROGRESS);
                    }
                }
                """;
        String realCall = """
                package x;
                class RealCall {
                    void start(Object batch, Object plan) {
                        batch.setStatus(ProductionBatchStatus.IN_PROGRESS);
                        com.cretas.aims.service.production.PendingApprovalStartGuard.check(plan, null);
                    }
                }
                """;
        String planOnly = """
                package x;
                import static com.cretas.aims.entity.enums.ProductionPlanStatus.IN_PROGRESS;
                class PlanOnly {
                    void start(Object plan) { plan.setStatus(IN_PROGRESS); }
                }
                """;
        String judges = """
                package x;
                import com.cretas.aims.entity.ProductionPlan.ApprovalStatus;
                class Judges {
                    boolean a(ProductionPlan p) { return p.getApprovalStatus() == ApprovalStatus.PENDING; }
                }
                """;
        String judges2 = """
                package x;
                class Judges2 {
                    boolean a(ProductionPlan p) { return p.getApprovalStatus() == ProductionPlan.ApprovalStatus.PENDING; }
                    boolean b(ProductionPlan p) { return p.isPendingApproval(); }
                }
                """;
        String otherPending = """
                package x;
                class OtherPending {
                    boolean a(Txn t) { return t.getApprovalStatus() == ArApApprovalStatus.PENDING; }
                }
                """;

        Scan s = scan(List.of(src("BuilderStatic", builderStatic), src("CommentOnly", commentOnly),
                src("RealCall", realCall), src("PlanOnly", planOnly), src("Judges", judges),
                src("Judges2", judges2), src("OtherPending", otherPending)));

        assertThat(s.batchInProgress.keySet())
                .containsExactlyInAnyOrder("BuilderStatic#make", "CommentOnly#start", "RealCall#start");
        assertThat(s.guardCalls.keySet()).as("注释里的守卫调用不算").containsExactly("RealCall#start");
        assertThat(s.guardCalls.get("RealCall#start"))
                .as("守卫写在设状态之后 ⇒ 位置比较能发现").isGreaterThan(s.batchInProgress.get("RealCall#start"));
        assertThat(s.pendingJudges).containsExactlyInAnyOrder("Judges", "Judges2");
    }

    // ------------------------------------------------------------------ 扫描器

    private static final class Scan {
        /** 类#方法 → 首个「批次设成生产中」的源码位置。 */
        final Map<String, Long> batchInProgress = new TreeMap<>();
        /** 类#方法 → 首个守卫调用的源码位置。 */
        final Map<String, Long> guardCalls = new TreeMap<>();
        /** 出现「待审批」判定的类 (文件名)。 */
        final TreeSet<String> pendingJudges = new TreeSet<>();
    }

    private static List<JavaFileObject> mainSources() throws IOException {
        JavaCompiler compiler = compiler();
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java"))) {
            paths = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        List<JavaFileObject> out = new ArrayList<>();
        fm.getJavaFileObjectsFromPaths(paths).forEach(out::add);
        assertThat(out).as("阳性对照: 一个源文件都没读到 = 工作目录不对").hasSizeGreaterThan(100);
        return out;
    }

    private static JavaCompiler compiler() {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler).as("拿不到 JDK 编译器 —— 仪器坏了, 这一轮读数作废").isNotNull();
        return compiler;
    }

    private static JavaFileObject src(String name, String code) {
        return new SimpleJavaFileObject(URI.create("string:///x/" + name + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
    }

    private static Scan scan(List<JavaFileObject> files) throws IOException {
        JavacTask task = (JavacTask) compiler().getTask(null, null, d -> { }, List.of("-proc:none"), null, files);
        SourcePositions positions = Trees.instance(task).getSourcePositions();
        Scan scan = new Scan();
        for (CompilationUnitTree cu : task.parse()) {
            String path = cu.getSourceFile().toUri().getPath();
            String file = path.substring(path.lastIndexOf('/') + 1).replace(".java", "");
            boolean batchStatic = false;
            boolean planStatic = false;
            boolean planApprovalImported = false;
            for (ImportTree imp : cu.getImports()) {
                String q = imp.getQualifiedIdentifier().toString();
                if (imp.isStatic() && (q.endsWith("ProductionBatchStatus.IN_PROGRESS") || q.endsWith("ProductionBatchStatus.*"))) {
                    batchStatic = true;
                }
                if (imp.isStatic() && (q.endsWith("ProductionPlanStatus.IN_PROGRESS") || q.endsWith("ProductionPlanStatus.*"))) {
                    planStatic = true;
                }
                if (q.endsWith("ProductionPlan.ApprovalStatus") || q.endsWith("ProductionPlan.*")) {
                    planApprovalImported = true;
                }
            }
            final boolean bareInProgressIsBatch = batchStatic && !planStatic;
            final boolean bareApprovalStatusIsPlan = planApprovalImported;
            cu.accept(new TreeScanner<Void, String>() {
                @Override
                public Void visitMethod(MethodTree node, String enclosing) {
                    return super.visitMethod(node, file + "#" + node.getName());
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, String method) {
                    String name = simpleName(node.getMethodSelect());
                    long pos = positions.getStartPosition(cu, node);
                    if (method != null && ("setStatus".equals(name) || "status".equals(name))
                            && node.getArguments().size() == 1
                            && isBatchInProgress(node.getArguments().get(0), bareInProgressIsBatch)) {
                        scan.batchInProgress.merge(method, pos, Math::min);
                    }
                    if (method != null && "check".equals(name)
                            && node.getMethodSelect() instanceof MemberSelectTree ms
                            && simpleName(ms.getExpression()).equals(GUARD)) {
                        scan.guardCalls.merge(method, pos, Math::min);
                    }
                    if ("isPendingApproval".equals(name)) {
                        scan.pendingJudges.add(file);
                    }
                    return super.visitMethodInvocation(node, method);
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree node, String method) {
                    if (node.getIdentifier().contentEquals("PENDING")) {
                        String owner = node.getExpression().toString();
                        if (owner.endsWith("ProductionPlan.ApprovalStatus")
                                || (owner.equals("ApprovalStatus") && bareApprovalStatusIsPlan)) {
                            scan.pendingJudges.add(file);
                        }
                    }
                    return super.visitMemberSelect(node, method);
                }
            }, null);
        }
        return scan;
    }

    private static boolean isBatchInProgress(ExpressionTree arg, boolean bareInProgressIsBatch) {
        if (arg instanceof MemberSelectTree ms) {
            return ms.getIdentifier().contentEquals("IN_PROGRESS")
                    && simpleName(ms.getExpression()).equals("ProductionBatchStatus");
        }
        return arg instanceof IdentifierTree id && id.getName().contentEquals("IN_PROGRESS") && bareInProgressIsBatch;
    }

    private static String simpleName(ExpressionTree t) {
        if (t instanceof MemberSelectTree ms) {
            return ms.getIdentifier().toString();
        }
        if (t instanceof IdentifierTree id) {
            return id.getName().toString();
        }
        return "";
    }
}
