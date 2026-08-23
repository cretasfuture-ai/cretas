package com.cretas.aims.gate;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「谁干的」这一列不许写死一个数字。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>{@code created_by} / {@code updated_by} 存的是<b>一个具体的人</b>，而且多张表上
 * 这一列是 {@code NOT NULL} 且带外键指向 {@code users}。
 * 把它写成常量 = 断言「这个 id 永远存在」，而<b>写死的 user id 有保质期</b>：
 * 租户清理、账号停用、建库重来都会让它失效。
 *
 * <h2>prod 实证：两个写死的 id，两次静默失败（2026-08-19 报错普查，error 流全量）</h2>
 *
 * <table>
 *   <caption>口径：DETAIL 行 = 同时含约束名与 {@code Key (created_by)=(N)} 的行；
 *            「提及行」= 含约束名的所有行（含堆栈重复）</caption>
 *   <tr><th>写死的值</th><th>撞的外键</th><th>DETAIL / 提及</th><th>日期</th><th>来源</th></tr>
 *   <tr><td>{@code 1L}</td><td>{@code fksdgf6ekee4yaptmucrps183qt}<br>(production_plans)</td>
 *       <td>13 / 104</td><td>08-07 … 08-19</td>
 *       <td>{@code SupplyChainOrchestrator}（已于 {@code 76ffe5fa72} 修）</td></tr>
 *   <tr><td>{@code 0L}</td><td>{@code fk_po_created_by}<br>(purchase_orders)</td>
 *       <td>13 / 104</td><td>06-21 … 07-14</td>
 *       <td>{@code ProcurementSuggestionService.SYSTEM_USER_ID}</td></tr>
 * </table>
 *
 * <p>那两个「系统账号」在 prod 都不存在：{@code users} 79 行，{@code min(id) = 1309}，
 * {@code id <= 10} 的行数是 <b>0</b>。{@code id=1 / factory_admin1 / F001} 是
 * 2026-08-07 12:20:45 被 {@code V20261029_68__purge_nonkept_factory_tenants} 删掉的，
 * 3 小时 57 分后开始报外键。
 *
 * <h2>🔴 为什么必须走 AST，而且必须解析常量</h2>
 *
 * <p>本仓在「用文本数东西」上栽过三次。这一条尤其明显：
 * 直接对源码 {@code grep 'setCreatedBy([0-9]'} 在建闸当天会数出 <b>9</b> 条，
 * 而其中 <b>3</b> 条是注释和 javadoc（{@code RawMaterialType} 的用法示例、
 * {@code SupplyChainOrchestrator} 里描述这次修复的两段注释）——
 * 真实代码点是 <b>6</b> 条。文本闸会把描述问题的文字当成问题本身。
 *
 * <p>更要命的是反方向：{@code po.setCreatedBy(SYSTEM_USER_ID)} <b>不含任何数字</b>，
 * 纯字面量闸完全看不见它 —— 而它正是撞了 13 次的那一个。
 * ⇒ 所以检测器要把参数是「同文件内 {@code static final} 数字常量」的那一档也算上。
 *
 * <h2>为什么是棘轮</h2>
 *
 * <p>建闸当天（setter 档）存量 8 处，其中 5 处在同一个 PR 里修掉，剩 3 处
 * <b>全部经代码可达性核实为死代码</b>（见 {@link #KNOWN_HARDCODED} 逐条理由）。
 * 死代码里的地雷不改动它本身，但冻结登记。
 *
 * <p>🔴 <b>2026-08-19 订正：那句「存量 8 处」当时就是错的，因为这道闸只认方法名。</b>
 * 补上 {@link #ACTOR_PARAM_NAMES} 那一档（常量当位置参数传给 actor 形参）之后，
 * 同一天又找出 <b>6 处</b>：
 *
 * <ul>
 *   <li>{@code ReverseTransferService} / {@code ProductionPlanTransferLifecycleListener}
 *       的 {@code SYSTEM_USER_ID = 0L} —— <b>已修</b>（prod 上已经写出 2 行
 *       {@code internal_transfers.requested_by = 0}）</li>
 *   <li>{@code CanvasApplyTemplateTool} / {@code CanvasUpdateFieldTool} /
 *       {@code CanvasToggleModuleTool} 的 {@code 0L} → {@code operatorId} —— <b>已修</b>
 *       （改成 {@code getUserId(context)}，与同族 AI 工具一致）</li>
 *   <li>{@code SmartBIServiceImpl#executeIntent} —— 冻结登记，见
 *       {@link #KNOWN_ACTOR_ARG_SITES}</li>
 * </ul>
 *
 * <p>⇒ 真实存量是 <b>14</b> 不是 8。而这道闸的旧注释写着
 * 「『还有几处没改』这件事从此由闸回答，不再由注释回答（注释会过期）」——
 * <b>闸也会过期</b>，方式是它看不见的那种形状。
 * ⇒ 一道闸<b>看不见什么</b>要和它<b>看得见什么</b>一样写在脸上。
 *
 * <p>⛔ 两份冻结名单都只许变短。
 *
 * @see com.cretas.aims.service.orchestration.ProcurementSuggestionService
 */
@DisplayName("写死的 user id：created_by / updated_by 不许是常量")
class HardcodedActorIdContractTest {

    private static final Path MAIN_ROOT = Paths.get("src/main/java");
    private static final Path FIXTURE =
            Paths.get("src/test/resources/test-fixtures/HardcodedActorIdFixture.java.txt");

    /** 只认这几个方法名 —— 形态 E: 宁可窄而可信，不要宽而被关掉。 */
    private static final Set<String> ACTOR_SETTERS = Set.of(
            "setcreatedby", "setupdatedby", "createdby", "updatedby");

    /**
     * 第二种形状：<b>把常量当位置参数传给一个 actor 形参</b>。
     *
     * <h3>🔴 为什么补这一档（2026-08-19，本闸自己漏掉过两处）</h3>
     *
     * <p>上面那份 {@link #ACTOR_SETTERS} 只认<b>方法名</b>。于是这两处它一个字都看不见：
     *
     * <pre>{@code
     * transferService.createTransfer(factoryId, request, SYSTEM_USER_ID);
     * transferService.closeOpenTransfersForProductionPlan(factoryId, planId, SYSTEM_USER_ID, reason);
     * }</pre>
     *
     * <p>方法名是 {@code createTransfer} / {@code closeOpenTransfersForProductionPlan}，
     * 与 {@code created_by} 毫无字面关系；那个 0 是在<b>下游</b>才被写进
     * {@code transfer.setRequestedBy(userId)} 的。
     *
     * <p>⚠️ 而本闸的类注释当时写着「『还有几处没改』这件事从此由闸回答，不再由注释回答」——
     * <b>它答错了</b>：建闸当天说存量 8 处、5 改 3 留，真实存量是 10 处。
     * prod 因此在 {@code internal_transfers.requested_by} 上留下 2 行 0
     * （F006 的 D1 自动反向调拨草稿，调拨详情页「申请人」空白）。
     *
     * <p>⇒ 一道闸<b>看不见的形状</b>要和它<b>看得见的形状</b>一样写清楚，
     * 否则「闸是绿的」会被读成「这一类没有了」。
     */
    private static final Set<String> ACTOR_PARAM_NAMES = Set.of(
            "userid", "createdby", "updatedby", "operatorid", "actorid",
            "completedby", "requestedby", "approvedby", "cancelleruserid",
            "settledby", "submittedby", "confirmedby", "reviewedby", "deletedby");

    /**
     * 存量欠账，<b>冻结于 2026-08-19，只许变短</b>。
     *
     * <p>这 3 处都<b>核实过不可达</b>（不是「日志里没报错」——那只说明没人走到，
     * 分不清「不可达」和「还没人点」）：
     *
     * <ul>
     *   <li>{@code RawMaterialTypeServiceImpl#initializeDefaults} ——
     *       方法体第一句是 {@code if (!DEFAULT_MATERIAL_TYPES.isEmpty()) throw ...}，
     *       而那个静态列表在 static 块里被塞了 8 条（带鱼 / 黄花鱼 / …）。
     *       ⇒ 非空必抛，抛在循环<b>之前</b>；若为空则循环零次。<b>两条分支都到不了那行</b>。</li>
     *   <li>{@code RawMaterialTypeServiceImpl#convertFromExportDTO} ——
     *       唯一调用点在 Excel 导入的循环里，而那个循环<b>前面</b>有一段
     *       {@code if (!excelData.isEmpty()) { 逐行 addFailure; return result; }}
     *       ⇒ 循环只在 {@code excelData} 为空时才跑，迭代 0 次。</li>
     *   <li>{@code SupplierServiceImpl#convertFromExportDTO} ——
     *       唯一调用点是 {@code importSuppliersFromExcel}，而<b>没有任何调用方</b>：
     *       同名的 controller 方法直接 {@code throw new BusinessException(409, "供应商导入必须先预览再确认")}，
     *       从不调服务层。真正在跑的是 {@code SupplierImportService#confirm(factoryId, request, userId)}，
     *       它<b>已经</b>拿的是 token 里的 userId。</li>
     * </ul>
     *
     * <p>⇒ 三处都是 <b>legacy 死双胞胎</b>。删它们是另一件事（会动到接口和一批测试），
     * 本次只冻结登记：⛔ 名单只许变短，往里加一条 = 让这个洞第三次长出来。
     */
    private static final Set<String> KNOWN_HARDCODED = Set.of(
            "RawMaterialTypeServiceImpl#initializeDefaults",
            "RawMaterialTypeServiceImpl#convertFromExportDTO",
            "SupplierServiceImpl#convertFromExportDTO");

    /**
     * 第二档（常量当位置参数传给 actor 形参）的存量欠账，<b>冻结于 2026-08-19，只许变短</b>。
     *
     * <p>⚠️ 它是<b>另一份名单</b>，不是往 {@link #KNOWN_HARDCODED} 里加 ——
     * 那份名单的规则是「只许变短」，而这一档是<b>今天才长出来的检测器</b>，
     * 它自带存量。混进去会把「旧账还清了没有」和「新检测器发现了什么」搅成一个数。
     * （同 {@code GateSelectorCoverageContractTest} 的棘轮做法：新闸不倒进存量硬红，
     *   否则就是做一道当天被关掉的闸。）
     *
     * <p>唯一一条：
     * <ul>
     *   <li>{@code SmartBIServiceImpl#executeIntent} 第 1561 行
     *       {@code intentExecutorService.execute(factoryId, fallbackReq, 0L, "restaurant_manager")}
     *       —— 同一族的「0 = 没有人」，但<b>不写任何 FK 列</b>：这个 userId 只进
     *       {@code IntentExecutorService} 的权限判定（同文件 738 行有一条注释把
     *       {@code userId=0L} 定义成「匿名/系统调用」约定）。
     *       ⚠️ 与它相邻的另一处（740 行）写的是 {@code userId != null ? userId : 0L}，
     *       至少还先试了真人；这一处是<b>硬写</b>的。
     *       <b>没有当场改</b>：改它要先弄清 0 在低敏意图免审批里到底意味着什么，
     *       那是另一件事。⇒ 登记留痕，不硬改（登记是留痕，不是豁免）。</li>
     * </ul>
     */
    private static final Set<String> KNOWN_ACTOR_ARG_SITES = Set.of(
            "SmartBIServiceImpl#executeIntent -> execute(arg2)");

    /** 两份冻结名单的并集 —— 主断言与防烂断言都用它。 */
    private static Set<String> frozen() {
        Set<String> all = new HashSet<>(KNOWN_HARDCODED);
        all.addAll(KNOWN_ACTOR_ARG_SITES);
        return all;
    }

    /** 一处「把写死的数字塞进 created_by/updated_by」的位置。 */
    private record Site(String klass, String method, String arg) {
        @Override
        public String toString() {
            return klass + "#" + method;
        }
    }

    // ───────────────────────── 检测器 ─────────────────────────

    /** 取调用的方法名（{@code a.b.setCreatedBy} → {@code setCreatedBy}）。 */
    private static String calleeName(MethodInvocationTree call) {
        ExpressionTree select = call.getMethodSelect();
        if (select instanceof MemberSelectTree member) {
            return member.getIdentifier().toString();
        }
        if (select instanceof IdentifierTree ident) {
            return ident.getName().toString();
        }
        return "";
    }

    private static boolean isNumericLiteral(Tree node) {
        if (!(node instanceof LiteralTree literal)) {
            return false;
        }
        // ⛔ 只认数字。字符串字面量不算 —— 「AST 扫字面量仍然是字符串匹配」那条教训。
        return literal.getValue() instanceof Number;
    }

    /**
     * 扫一棵编译单元，返回其中的违例。
     *
     * <p>两档都要认：
     * <ol>
     *   <li>参数直接是数字字面量 —— {@code setCreatedBy(1L)}</li>
     *   <li>参数是<b>同文件内</b>初始化为数字字面量的 {@code static final} 常量 ——
     *       {@code setCreatedBy(SYSTEM_USER_ID)}。<b>第二档是这道闸存在的主要理由</b>：
     *       它不含任何数字，纯字面量闸看不见它，而它撞了 13 次。</li>
     * </ol>
     *
     * <p>⚠️ 它<b>看不见</b>跨文件的常量引用（{@code SomeConstants.SYSTEM_USER}）——
     * 那需要符号解析（要 classpath、要编译顺序）。这是本闸<b>已知的盲区</b>，
     * 写在这里而不是假装没有。本仓当前没有那种形状。
     */
    /**
     * 能接住一个数字字面量的形参类型。
     *
     * <p>只有这些类型的形参才可能是「那个 {@code 0L} 传进去的地方」——
     * 一个 {@code CreateTransferRequest} 形参<b>接不住</b> {@code 0L}，
     * 所以带这种形参的重载根本不可能是本次调用的目标。
     */
    private static final Set<String> NUMERIC_PARAM_TYPES = Set.of(
            "Long", "long", "Integer", "int", "Short", "short",
            "Byte", "byte", "Number", "Object");

    /**
     * 建「方法名/参数个数 → 哪些位置是 actor 形参」的索引。
     *
     * <h3>为什么不是「所有同名同参数个数的声明都必须同意」</h3>
     *
     * <p>第一版就是那么写的，结果<b>它把自己关掉了</b>：变异把
     * {@code createTransfer(factoryId, request, event.getCompletedBy())} 改回
     * {@code (…, 0L)}，闸<b>纹丝不动</b>。诊断出来的原因是仓里有第三个
     * {@code createTransfer/3}——{@code TransferController} 的
     * {@code (String factoryId, String authorization, CreateTransferRequest request)}，
     * 它的 2 号位是 {@code request}，于是「所有声明都同意」不成立，2 号位被整体剔除。
     *
     * <p>⇒ 判据换成：<b>某个声明在这一位是 actor 形参</b>，
     * <b>且没有任何声明在这一位放了一个「接得住数字」的非 actor 形参</b>。
     * {@code CreateTransferRequest} 接不住 {@code 0L} ⇒ 那个重载不可能是目标 ⇒ 不构成歧义。
     * 真正的歧义（{@code foo(Long userId)} vs {@code foo(Long count)}）照样被排除。
     *
     * <p>⚠️ 这一条是被变异抓出来的，不是想出来的 —— 闸绿了但变异没红，
     * 而变异<b>已经证明落盘</b>（形态 C″：先证明变异生效，再下「断言没用」的结论）。
     */
    private static void indexActorParams(CompilationUnitTree cu,
                                         Map<String, Set<Integer>> actorIdx,
                                         Map<String, Set<Integer>> numericNonActorIdx) {
        cu.accept(new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethod(MethodTree node, Void unused) {
                List<? extends VariableTree> params = node.getParameters();
                String key = node.getName().toString() + "/" + params.size();
                for (int i = 0; i < params.size(); i++) {
                    String name = params.get(i).getName().toString().toLowerCase(Locale.ROOT);
                    String type = params.get(i).getType().toString();
                    // ⛔ actor 档要求类型是**装箱 Long** —— 本仓 users.id 一律 Long。
                    //    实测这一条卡掉了唯一一个误报: IsapiDeviceService#buildChangePasswordXml
                    //    的 `int userId` 是海康 ISAPI **设备本地的用户槽位号**(管理员槽 = 1),
                    //    与 users 表毫无关系, 传字面量 1 完全正确。
                    if (ACTOR_PARAM_NAMES.contains(name) && "Long".equals(type)) {
                        actorIdx.computeIfAbsent(key, k -> new HashSet<>()).add(i);
                    } else if (NUMERIC_PARAM_TYPES.contains(type)) {
                        numericNonActorIdx.computeIfAbsent(key, k -> new HashSet<>()).add(i);
                    }
                }
                return super.visitMethod(node, unused);
            }
        }, null);
    }

    /** actor 位 减去 有歧义的位 —— 见 {@link #indexActorParams} 的判据说明。 */
    private static Map<String, Set<Integer>> resolveActorParamIndex(List<CompilationUnitTree> units) {
        Map<String, Set<Integer>> actorIdx = new HashMap<>();
        Map<String, Set<Integer>> numericNonActorIdx = new HashMap<>();
        for (CompilationUnitTree cu : units) {
            indexActorParams(cu, actorIdx, numericNonActorIdx);
        }
        numericNonActorIdx.forEach((k, ambiguous) -> {
            Set<Integer> s = actorIdx.get(k);
            if (s != null) {
                s.removeAll(ambiguous);
            }
        });
        return actorIdx;
    }

    private static List<Site> scanUnit(CompilationUnitTree cu) {
        return scanUnit(cu, Map.of());
    }

    private static List<Site> scanUnit(CompilationUnitTree cu,
                                       Map<String, Set<Integer>> actorParamIndex) {
        // ⚠️ 不能用 Paths.get(uri) —— fixture 是内存里的 `string:///…` URI,
        //    那个 scheme 没有 FileSystemProvider, 会抛 FileSystemNotFoundException。
        //    取路径末段就够了, 两种来源通用。
        String uri = cu.getSourceFile().toUri().toString();
        String file = uri.substring(uri.lastIndexOf('/') + 1);
        String klass = file.replaceAll("\\.java(\\.txt)?$", "");

        // 第一遍: 收集本文件里初始化为数字字面量的 static final 常量
        Map<String, String> numericConstants = new HashMap<>();
        cu.accept(new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                boolean isStaticFinal = node.getModifiers().getFlags().stream()
                        .map(f -> f.toString().toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet())
                        .containsAll(Set.of("static", "final"));
                if (isStaticFinal && isNumericLiteral(node.getInitializer())) {
                    numericConstants.put(node.getName().toString(), node.getInitializer().toString());
                }
                return super.visitVariable(node, unused);
            }
        }, null);

        // 第二遍: 找 actor setter 的调用, 看它的参数
        List<Site> sites = new ArrayList<>();
        cu.accept(new TreeScanner<Void, String>() {
            @Override
            public Void visitMethod(MethodTree node, String unused) {
                // 把「当前在哪个方法里」带下去 —— 站点身份用 类#方法, 不用行号(行号会漂)
                return super.visitMethod(node, node.getName().toString());
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, String enclosingMethod) {
                String callee = calleeName(node).toLowerCase(Locale.ROOT);
                if (ACTOR_SETTERS.contains(callee) && node.getArguments().size() == 1) {
                    Tree arg = node.getArguments().get(0);
                    if (isNumericLiteral(arg)) {
                        sites.add(new Site(klass,
                                enclosingMethod == null ? "<unknown>" : enclosingMethod,
                                arg.toString()));
                    } else if (arg instanceof IdentifierTree ident
                            && numericConstants.containsKey(ident.getName().toString())) {
                        sites.add(new Site(klass,
                                enclosingMethod == null ? "<unknown>" : enclosingMethod,
                                ident.getName() + " = " + numericConstants.get(ident.getName().toString())));
                    }
                }

                // ── 第二档: 常量被当**位置参数**传给一个 actor 形参 ──
                // 这一档是 2026-08-19 补的: 上面那档只认方法名, 所以
                // createTransfer(factoryId, request, SYSTEM_USER_ID) 它一个字都看不见,
                // 而那正是 prod 上 internal_transfers.requested_by = 0 的来源。
                // ⛔ setter 那一档已经由上面管了 —— 不去重的话同一处会被报两次,
                //    而第二条的 key 不在 KNOWN_HARDCODED 里, 三个已冻结的欠账会集体"复活"。
                //    (setCreatedBy(Long createdBy) 的形参名恰好也叫 createdBy。)
                Set<Integer> actorPositions = ACTOR_SETTERS.contains(callee)
                        ? null
                        : actorParamIndex.get(calleeName(node) + "/" + node.getArguments().size());
                if (actorPositions != null) {
                    for (int i : actorPositions) {
                        Tree a = node.getArguments().get(i);
                        String what = null;
                        if (isNumericLiteral(a)) {
                            what = a.toString();
                        } else if (a instanceof IdentifierTree id
                                && numericConstants.containsKey(id.getName().toString())) {
                            what = id.getName() + " = " + numericConstants.get(id.getName().toString());
                        }
                        if (what != null) {
                            sites.add(new Site(klass,
                                    (enclosingMethod == null ? "<unknown>" : enclosingMethod)
                                            + " -> " + calleeName(node) + "(arg" + i + ")",
                                    what));
                        }
                    }
                }
                return super.visitMethodInvocation(node, enclosingMethod);
            }
        }, null);
        return sites;
    }

    private static List<CompilationUnitTree> parse(Iterable<? extends JavaFileObject> units,
                                                   StandardJavaFileManager fm) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertThat(compiler)
                .as("拿不到 JDK 编译器 —— 仪器坏了, 不是仓里没有违例")
                .isNotNull();
        // 只 parse, 不做符号解析 —— 不需要 classpath, 也就不会被编译顺序影响。
        JavacTask task = (JavacTask) compiler.getTask(null, fm, d -> { }, List.of(), null, units);
        List<CompilationUnitTree> out = new ArrayList<>();
        task.parse().forEach(out::add);
        return out;
    }

    /**
     * 全树 parse 一次约 15s，四条测试都要用它 —— 缓存，别跑四遍。
     * ⚠️ 缓存的是<b>读数</b>不是结论：每条测试仍各自判各自的断言。
     */
    private static List<Site> productionSitesCache;

    private static synchronized List<Site> productionSites() throws IOException {
        if (productionSitesCache == null) {
            productionSitesCache = scanProductionSources();
        }
        return productionSitesCache;
    }

    private static List<Site> scanProductionSources() throws IOException {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(MAIN_ROOT)) {
            sources = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }
        assertThat(sources)
                .as("src/main/java 下一个 .java 都没走到 —— 仪器坏了")
                .hasSizeGreaterThan(500);

        List<Site> all = new ArrayList<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            List<CompilationUnitTree> units = parse(fm.getJavaFileObjectsFromPaths(sources), fm);
            // 两遍: 先建「哪些形参是 actor」的全树索引, 再扫调用。
            Map<String, Set<Integer>> actorParamIndex = resolveActorParamIndex(units);
            for (CompilationUnitTree cu : units) {
                all.addAll(scanUnit(cu, actorParamIndex));
            }
        }
        return all;
    }

    private List<Site> scanFixture() throws IOException {
        String src = Files.readString(FIXTURE, StandardCharsets.UTF_8);
        JavaFileObject inMemory = new SimpleJavaFileObject(
                URI.create("string:///HardcodedActorIdFixture.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return src;
            }
        };
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        List<Site> all = new ArrayList<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            // 与产品树同样两遍 —— fixture 自己声明 recordAction(String, Long userId, String),
            // 第二档检测器才有形参名可查。
            List<CompilationUnitTree> units = parse(List.of(inMemory), fm);
            Map<String, Set<Integer>> actorParamIndex = resolveActorParamIndex(units);
            for (CompilationUnitTree cu : units) {
                all.addAll(scanUnit(cu, actorParamIndex));
            }
        }
        return all;
    }

    // ───────────────────── 仪器自检（阳性 + 阴性对照） ─────────────────────

    /**
     * 🔴 本类最重要的一条。它证明检测器<b>两个方向都活着</b>：
     * 该报的报得出来（含那个不含任何数字的常量档），不该报的不误报。
     *
     * <p>为什么要用 fixture 而不是拿产品源码当对照：修完之后产品树里<b>没有</b>
     * 常量档的实例了（{@code SYSTEM_USER_ID} 已被删）。
     * 一道在验收环境里没有输入能让它红的闸，在那个环境里就是恒真式（形态 B⁴）。
     */
    @Test
    @DisplayName("仪器自检: 字面量档 + 常量档都报得出来, 参数/方法调用档不误报")
    void detectorFiresOnBothShapesAndStaysQuietOnRealActors() throws IOException {
        Set<String> flagged = new TreeSet<>();
        for (Site s : scanFixture()) {
            // 第二档的 method 形如 "外层方法 -> callee(argN)" —— 只取外层方法名对照。
            flagged.add(s.method().split(" -> ", 2)[0]);
        }

        assertThat(flagged)
                .as("阳性对照: 这六种形状必须被报出来。少任何一种, 主断言的「0 违例」就没有意义")
                .contains("mustBeFlagged_literalLong",
                        "mustBeFlagged_literalInt",
                        "mustBeFlagged_namedConstant",
                        "mustBeFlagged_builderLiteral",
                        // 第二档 (2026-08-19 新增): 常量当位置参数传给 actor 形参。
                        // 产品树上这一档只剩 1 处且已冻结, 所以没有这两条 fixture,
                        // 这个分支在验收环境里就是恒真式 (形态 B⁴)。
                        "mustBeFlagged_actorArgLiteral",
                        "mustBeFlagged_actorArgConstant");

        assertThat(flagged)
                .as("""
                        阴性对照: 这四种形状**不该**被报。
                        误报是让闸被关掉的那个东西(形态 E) —— 一道天天误报的闸最终覆盖率归零。
                        mustNotBeFlagged_unrelatedSetterWithLiteral 尤其重要:
                        它证明闸只管 created_by/updated_by, 不是「所有 setter 都不许传数字」。""")
                .doesNotContain("mustNotBeFlagged_parameter",
                        "mustNotBeFlagged_methodCall",
                        "mustNotBeFlagged_builderParameter",
                        "mustNotBeFlagged_unrelatedSetterWithLiteral",
                        "mustNotBeFlagged_actorArgRealValue",
                        // 🔴 这一条是实测出来的: 它对应 IsapiDeviceService#buildChangePasswordXml,
                        //    那里的 `int userId` 是海康设备本地的用户槽位号(管理员槽=1),
                        //    与 users 表毫无关系。第二档检测器上线时它是**唯一**的误报,
                        //    靠「形参类型必须是装箱 Long」卡掉。
                        "mustNotBeFlagged_primitiveIntUserIdIsNotAnActor");
    }

    /**
     * 阳性对照之二：产品树上确实还找得到冻结的那几处。
     *
     * <p>如果这条红了，只有两种可能：欠账真的还清了（那就把 {@link #KNOWN_HARDCODED}
     * 一起改短，这是好事），或者<b>扫描根本没跑起来</b>。
     * 后者会让主断言变成「0 违例 ✅」—— 而「找不到」最像「一切正常」。
     */
    @Test
    @DisplayName("阳性对照: 冻结名单上的站点在产品树里真的存在(否则主断言恒绿)")
    void frozenSitesAreActuallyPresentInProductionSources() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Site s : productionSites()) {
            found.add(s.toString());
        }

        assertThat(found)
                .as("""
                        冻结名单里的站点在产品树里找不到了。
                        如果你刚把它们修掉/删掉 —— 很好, 把 KNOWN_HARDCODED 里对应的行也删掉。
                        如果你没动过它们 —— 那是扫描没跑起来, 主断言此刻是恒真式。""")
                .containsAll(frozen());
    }

    // ───────────────────────── 主断言 ─────────────────────────

    @Test
    @DisplayName("🔴 created_by / updated_by 不许收一个写死的数字(冻结名单外一律红)")
    void noNewHardcodedActorIds() throws IOException {
        List<Site> sites = productionSites();
        Set<String> violations = new TreeSet<>();
        Map<String, String> detail = new HashMap<>();
        Set<String> allowed = frozen();
        for (Site s : sites) {
            if (!allowed.contains(s.toString())) {
                violations.add(s.toString());
                detail.put(s.toString(), s.arg());
            }
        }

        assertThat(violations)
                .as("""
                        这些地方把一个写死的数字塞进了 created_by / updated_by:
                          %s

                        写死的 user id 有保质期。prod 上两个「系统账号」都已失效:
                          1L (factory_admin1/F001) 2026-08-07 随租户被 V20261029_68 删除
                          0L 从来就不存在 (users 79 行, min(id)=1309, id<=10 的行数为 0)
                        而 production_plans / purchase_orders / product_types /
                        raw_material_types / suppliers 的 created_by 都是 NOT NULL + 外键 → users。
                        失效方式是**异步线程里的静默失败**: 接口返回 200, 库里没有行。

                        修法: 把「这一步是谁触发的」一路传进来 ——
                          · HTTP 入口: mobileService.getUserFromToken(TokenUtils.extractToken(authorization)).getId()
                            ⛔ 不要用 SecurityUtils.getCurrentUserId(), 本项目它**永远返回 null**
                               (全仓没有任何地方调 SecurityContextHolder.setAuthentication,
                                身份由 JwtAuthInterceptor 写进 request attribute)
                          · 事件驱动: 用事件自带的字段(如 SalesOrderFinanceApprovedEvent.approvedBy)
                          · 取不到就显式失败, ⛔ 不要兜底一个假 id(形态 A¹⁰:
                            兜底会把「不知道是谁」写成「就是那个人」)
                        列可空且无外键时(如 drools_rules/finished_goods_batches), null 是诚实的答案。

                        ⛔ 不要往 KNOWN_HARDCODED 里加 —— 那份名单只许变短。""".formatted(detail))
                .isEmpty();
    }

    /**
     * 名单不许烂 —— 它同时守两个方向。
     *
     * <p>与 {@code GateSelectorCoverageContractTest} 的 {@code debtListMustNotRot} 同源：
     * 已经还清的条目留在名单里，会让闸对那个位置永久失明。
     * （「名单里的站点还在不在」由 {@link #frozenSitesAreActuallyPresentInProductionSources()} 钉。）
     */
    @Test
    @DisplayName("⛔ 冻结名单只许变短: 不许出现名单里有、代码里没有的僵尸条目")
    void frozenListMustNotRot() throws IOException {
        Set<String> found = new HashSet<>();
        for (Site s : productionSites()) {
            found.add(s.toString());
        }
        Set<String> zombies = new TreeSet<>(frozen());
        zombies.removeAll(found);

        assertThat(zombies)
                .as("这些条目已经不在代码里了 —— 把它们从 KNOWN_HARDCODED 删掉, "
                        + "否则闸对那几个位置永久失明")
                .isEmpty();
    }
}
