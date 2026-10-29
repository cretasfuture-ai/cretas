package com.cretas.aims.blueprint.interview;

import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 建厂访谈 —— 重复段展开（唯一实现）。
 *
 * <h2>为什么要有这个类</h2>
 *
 * 「问几道工序」的答案会决定后面还有多少题：答 4 就要再问 4×7 项。触发点有两个 ——
 * 结构化答题端点（{@code .../answers}）和自由文本回复（{@code continueConversation}），
 * 一个在 controller、一个在 service。
 *
 * ⛔ 我第一版在两处各写了一份子项模板和一份触发逻辑。那是本仓库里反复出事的那类缺陷：
 *    <b>同一口径写在两处，早晚会有人只改一处</b>。真出问题时的表现是「用点选答的能展开、
 *    用打字答的展不开」，而两条路都返回 200 —— 从状态码上完全看不出来。
 *    所以模板与触发条件在这里只有一份，两个调用点都调 {@link #expandIfReady}。
 *
 * <h2>不猜</h2>
 *
 * 答案里找不到数字（「说不好」「看情况」）时<b>不展开也不报错</b>，让追问继续。
 * 把一个猜的工序数固化下来，会一路错到 BOM 和成本；而多问一遍只花一轮。
 */
@Slf4j
public final class FactoryInterviewExpansion {

    private FactoryInterviewExpansion() {
    }

    /** 建厂访谈的意图代码。⛔ 展开只对这个意图生效 —— 别的意图恰好有个叫 stepCount 的参数不该被牵连。 */
    public static final String INTENT = "FACTORY_BUILD_INTERVIEW";

    /** 重复段的 key，展开后子项名形如 {@code step1.name} / {@code step3.yieldRate}。 */
    public static final String STEP_TEMPLATE_KEY = "step";

    /** 决定展开几份的那一问。 */
    public static final String STEP_COUNT_KEY = "stepCount";

    /** 产品重复段 —— 每个成品自己走哪几道工序。 */
    public static final String PRODUCT_TEMPLATE_KEY = "product";
    public static final String PRODUCT_COUNT_KEY = "productCount";

    /** 合理工序道数上限。超过基本是答错了（把产量当成道数之类），不展开、留给人看。 */
    public static final int MAX_STEPS = 30;

    /**
     * 每道工序要问的 8 项。
     *
     * ⚠️ 其中 <b>辅料 / 包材 / 工艺参数 / 副产物</b> 是 v1 访谈<b>一项都没问</b>的 ——
     * 而 F006 库里 16 道真实工序的 {@code custom_field_schema} 与
     * {@code expected_byproducts} 也是 16/16 全空。两件事互为因果:
     * <b>没有任何一个入口去问, 所以那两列永远是空的</b>。
     * BOM 与成本归集因此缺一块(下脚料还带回收收入, 漏了连收入都少一块)。
     *
     * <p>每一项都有落库去处, 见 {@code TemplatePackageController#applyWorkProcesses}:
     * 工艺参数 → {@code work_processes.custom_field_schema};
     * 副产物 → {@code work_processes.expected_byproducts};
     * 辅料/包材 → {@code process_material_recipes}(SEASONING / PACKAGING)。
     */
    private static final List<FactoryAssemblyConversationSession.RequiredParameter> STEP_SUB_ITEMS = List.of(
            item("name", "工序名称", "第{i}道叫什么？比如「滚揉（注射保水）」「焯水」", false),
            item("inputMaterial", "投入主原料", "第{i}道投入的是什么？（上一道的产出，或新领的原料）", false),
            item("auxMaterials", "辅料", "第{i}道要加什么辅料？如卤料包、保水剂、盐。没有就说没有", true),
            item("packMaterials", "包材", "第{i}道用什么包材？如气调盒、顶膜、标签、外箱。没有就说没有", true),
            item("output", "产出", "第{i}道产出什么？叫什么名字、按什么单位计（kg / 盒 / 箱）", false),
            item("params", "工艺参数", "第{i}道要控哪些参数？每个带单位，如「中心温度 ≥75℃」「滚揉 40 分钟」", true),
            item("yieldRate", "出成率", "第{i}道大概出成多少？注水/加汁类会大于 1（实测见过 1.22–1.36），"
                    + "包装工序通常不计出成率", true),
            item("byproducts", "副产物", "第{i}道会出什么下脚料？如鱼头、内脏、脂肪、边角料。"
                    + "有回收价值的要说，没有就说没有", true));

    /**
     * 每个成品要问的 7 项。
     *
     * ⚠️ 2026-08-10 对抗性审计抓出来的缺口: 访谈只问了<b>一条</b>工序链,
     * 而真实工厂**每个产品走的链不一样** —— F006 实测掌中宝走
     * 化冻→焯水→油炸→熟制伴汁→气调, 牛腱肉走 修油→滚揉→焯水→…
     * 只问一条全局链, 对多产品工厂拼出来的配置根本不成立,
     * 而 product_work_processes 那 16 行永远是空的。
     */
    private static final List<FactoryAssemblyConversationSession.RequiredParameter> PRODUCT_SUB_ITEMS = List.of(
            item("name", "成品名称", "第{i}个成品叫什么？（规格也说一下，如「纸片牛腱肉 80g」）", false),
            item("steps", "经过哪几道", "第{i}个成品依次经过哪几道工序？按顺序说，"
                    + "不走的就不说（不同产品走的链可以不一样）", false),
            multiChoiceItem("routeShape", "路线形态",
                    "第{i}个成品除了顺序前进，还有没有并行、条件分支、合流、返工或质检不合格处置？"
                            + "可以多选；只有顺序也必须明确确认。",
                    List.of("仅顺序", "有并行", "有条件分支", "有合流", "有返工", "有质检/不合格处置")),
            item("routeDetails", "路线例外说明",
                    "把第{i}个成品的复杂路线说清：从哪道分出去、什么条件、回到哪里或怎么处置。"
                            + "只有顺序就明确说“没有”。这一步只记录事实，不会自动发布 Workflow。",
                    true),
            item("routeEntry", "路线起点",
                    "第{i}个成品从哪道已确认工序开始？复杂路线必须明确选择；仅顺序由确认卡自动确定。", true),
            item("routeTerminal", "路线终点",
                    "第{i}个成品在哪道已确认工序完成？复杂路线必须明确选择；仅顺序由确认卡自动确定。", true),
            item("routeTopology", "逐条路线连线",
                    "请在拓扑确认卡逐条选择起点、终点、边类型和条件；返工还要选择放行出口。"
                            + "它只生成未发布预览，不会写入 Workflow。", true));

    private static FactoryAssemblyConversationSession.RequiredParameter item(
            String name, String label, String hint, boolean allowNone) {
        return FactoryAssemblyConversationSession.RequiredParameter.builder()
                .name(name).label(label).type("string").validationHint(hint)
                .inputType("NARRATIVE").allowNone(allowNone).collected(false)
                .build();
    }

    private static FactoryAssemblyConversationSession.RequiredParameter multiChoiceItem(
            String name, String label, String hint, List<String> options) {
        return FactoryAssemblyConversationSession.RequiredParameter.builder()
                .name(name).label(label).type("string").validationHint(hint)
                .inputType("CHOICE").options(options).multiSelect(true).collected(false)
                .build();
    }

    /** 仅顺序路线的「逐条路线连线」: 与 FactoryRouteIntentCompiler 里 NO_TOPOLOGY 的同一个词。 */
    public static final String SEQUENTIAL_TOPOLOGY_ANSWER = "按顺序自动确定";
    public static final String DERIVED_SEQUENTIAL_TOPOLOGY_REASON = "DERIVED_SEQUENTIAL_TOPOLOGY";

    /**
     * 用户已确认某个成品「路线形态 = 仅顺序」时, 它的逐条路线连线是确定的 (没有额外的边, 顺序就是工序顺序),
     * 题面也写着「仅顺序由确认卡自动确定」—— 而页面对仅顺序路线并不出确认卡, 这一项就只能靠用户恰好说出
     * 「按顺序自动确定」或点「暂时不确定」(后者把它卡成永远的待补)。所以在这里确定性地填上, 并留回执。
     *
     * <p>只在 routeShape 恰好是单个「仅顺序」且这一项还没有值时才填; 复杂路线 (并行/分支/合流/返工) 一概不动,
     * 必须由用户在确认卡里逐条确认。已有的值不会被覆盖; 用户之前选的「暂时不确定」由这次明确的值解除。
     *
     * @return 被填上的字段名 (没有则为空)
     */
    public static List<String> deriveSequentialTopology(FactoryAssemblyConversationSession session) {
        if (session == null || !INTENT.equals(session.getKnownIntentCode())) return List.of();
        java.util.Map<String, String> collected = session.getCollectedParameters();
        List<String> derived = new java.util.ArrayList<>();
        for (FactoryAssemblyConversationSession.RequiredParameter parameter : session.getRequiredParameters()) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("^product(\\d+)\\.routeTopology$").matcher(String.valueOf(parameter.getName()));
            if (!matcher.matches() || parameter.isCollected()) continue;
            String shape = collected.get("product" + matcher.group(1) + ".routeShape");
            if (shape == null || !"仅顺序".equals(shape.trim())) continue;
            session.addCollectedParameter(parameter.getName(), SEQUENTIAL_TOPOLOGY_ANSWER);
            derived.add(parameter.getName());
        }
        if (derived.isEmpty()) return derived;
        List<FactoryAssemblyConversationSession.ReplyFieldReceipt> receipts = new java.util.ArrayList<>();
        for (String field : derived) {
            receipts.add(FactoryAssemblyConversationSession.ReplyFieldReceipt.builder()
                    .field(field).resolution("ACCEPTED").canonicalValue(SEQUENTIAL_TOPOLOGY_ANSWER)
                    .reasonCode(DERIVED_SEQUENTIAL_TOPOLOGY_REASON).build());
        }
        FactoryAssemblyConversationSession.Message note = FactoryAssemblyConversationSession.Message.user(
                "路线形态是「仅顺序」，逐条路线连线按工序顺序自动确定（" + derived.size() + " 个成品）");
        note.setReplyReceipt(FactoryAssemblyConversationSession.ReplyReceipt.builder()
                .extractionOutcome("SUCCESS").currentRound(session.getCurrentRound())
                .maxRounds(session.getMaxRounds())
                .status(session.getStatus() == null ? null : session.getStatus().name())
                .completed(false).message("逐条路线连线按顺序自动确定").intentCode(session.getKnownIntentCode())
                .progressMutated(true).correctionApplied(false).fieldReceipts(receipts).build());
        session.addMessage(note);
        return derived;
    }

    /** 供闸与调用方核对条数用，不暴露可变引用。 */
    public static int subItemCount() {
        return STEP_SUB_ITEMS.size();
    }

    /**
     * 从一句话里读出计数 —— <b>全仓唯一一处</b>「怎么把『3 道』读成 3」。
     *
     * <p>⛔ 展开与「这答案算不算数」必须用<b>同一个</b>判定:
     * 两处各写一份的话,会出现「判定说这答案有效、展开却读不出数」的静默夹缝,
     * 而那正是 2026-08-16 实测到的那个缺陷的形状。
     */
    public static java.util.OptionalInt countIn(String raw) {
        if (raw == null) {
            return java.util.OptionalInt.empty();
        }
        Matcher m = Pattern.compile("\\d+").matcher(raw);
        return m.find()
                ? java.util.OptionalInt.of(Integer.parseInt(m.group()))
                : java.util.OptionalInt.empty();
    }

    /** 计数题的 key —— 只有这两问的答案决定后面还有多少题。 */
    private static final List<String> COUNT_KEYS = List.of(STEP_COUNT_KEY, PRODUCT_COUNT_KEY);

    /**
     * 计数题收到<b>数不出数</b>的答案时,退回一次,让追问继续。
     *
     * <h2>🔴 治的是什么(2026-08-16 本地起服务实测)</h2>
     *
     * 本类的类注释一直写着:「答案里找不到数字时<b>不展开也不报错,让追问继续</b>」。
     * 前半句成立,<b>后半句从来没有人实现</b> —— 答案已经被标记为「已收集」,
     * 于是追问永远不会继续。实测(「其他」行业,真实端点):
     *
     * <pre>
     * collected[stepCount]    = "厂名叫宠物冻干零食加工厂。"   ← 客户说的是厂名
     * collected[productCount] = "就按常规做法。"
     * pending = 0   readyForGenerate = true                    ← 访谈报告「问齐了」
     * </pre>
     *
     * 后果不止少问几题:
     * <ul>
     *   <li>逐道工序的 8 个子项<b>一次都不会问</b>(展开永不触发);</li>
     *   <li>{@code assembledDescription} 里赫然写着「工序道数:厂名叫…」,
     *       而那段会原样喂给 LLM 去生成整厂配置。</li>
     * </ul>
     *
     * <h2>为什么只退一次</h2>
     *
     * {@code getNextPendingParameter()} 取的是<b>声明序第一个</b>未收集项。
     * 无条件退回会把整场访谈卡死在同一题上(客户答不出数字就永远问它),
     * 那与目标一「任何行业的客户都能被引导着答完」直接冲突。
     *
     * <p>⇒ 只退一次,标记在 {@code RequiredParameter#reAskedOnce}。
     * 第二次仍数不出数就收下 —— 但那时必须<b>明说后果</b>(调用方负责),
     * ⛔ 不许再静默地当成问齐了。
     *
     * <p>🔴 第一版想省掉那个字段,用「{@code !collected && value != null}」当标记 ——
     * 而 {@code addCollectedParameter} <b>正常收集时就会 setValue</b>,
     * 于是那个条件恒为真,退回分支<b>一次都没进过</b>。
     * 实测日志里全是「第二次仍数不出数」,一条「退回重问一次」都没有:
     * <b>修复到达了代码,只是标记选错了。</b>
     *
     * @return 这次被退回的 key(空表示没有需要退的)
     */
    public static List<String> rejectUnparsableCounts(FactoryAssemblyConversationSession session) {
        if (session == null || !INTENT.equals(session.getKnownIntentCode())) {
            return List.of();
        }
        java.util.Map<String, String> collected = session.getCollectedParameters();
        if (collected == null || collected.isEmpty()) {
            return List.of();
        }
        List<FactoryAssemblyConversationSession.RequiredParameter> params = session.getRequiredParameters();
        if (params == null) {
            return List.of();
        }
        List<String> rejected = new java.util.ArrayList<>();
        for (String key : COUNT_KEYS) {
            String raw = collected.get(key);
            if (raw == null || countIn(raw).isPresent()) {
                continue;
            }
            FactoryAssemblyConversationSession.RequiredParameter p = params.stream()
                    .filter(x -> key.equals(x.getName()))
                    .findFirst().orElse(null);
            if (p == null) {
                continue;
            }
            if (p.isReAskedOnce()) {
                // 已经退回过一次 —— 不再退, 免得卡死。调用方要把后果说清楚。
                log.info("{} 第二次仍数不出数, 收下但后面的细节问不了: session={}, raw={}",
                        key, session.getSessionId(), raw);
                continue;
            }
            collected.remove(key);
            p.setCollected(false);
            p.setValue(null);         // ⛔ 清掉, 否则下一轮的问题里会回显一个错答案
            p.setReAskedOnce(true);
            rejected.add(key);
            log.info("{} 的答案数不出数, 退回重问一次: session={}, raw={}",
                    key, session.getSessionId(), raw);
        }
        if (!rejected.isEmpty()) {
            session.setCollectedParameters(collected);
            session.setRequiredParameters(params);
        }
        return rejected;
    }

    /**
     * 若 {@code stepCount} 已答且是个合理数字，就展开 N×当前工序子项模板。
     *
     * <p>幂等且可纠正：相同数量不变；数量增大只补新项；数量缩小时在同一会话
     * JSON 变更里删除超出范围的题与答案。
     *
     * <p>⚠️ 展开会把 {@code maxRounds} 一并抬到「参数数+1」——
     * 这两件事必须同时做。分开做就一定会有人只做一半，
     * 结果是访谈问到一半静默停住（remaining 停在正数、status 仍是 ACTIVE）。
     *
     * @return 新增的子项数，0 表示这次没展开
     */
    public static int expandIfReady(FactoryAssemblyConversationSession session) {
        if (session == null || !INTENT.equals(session.getKnownIntentCode())) {
            return 0;
        }
        String raw = session.getCollectedParameters() == null
                ? null : session.getCollectedParameters().get(STEP_COUNT_KEY);
        if (raw == null || raw.trim().isEmpty()) {
            return 0;
        }
        java.util.OptionalInt parsed = countIn(raw);
        if (parsed.isEmpty()) {
            log.info("stepCount 答案里没有数字, 暂不展开(继续追问): session={}, raw={}",
                    session.getSessionId(), raw);
            return 0;
        }
        int n = parsed.getAsInt();
        if (n <= 0 || n > MAX_STEPS) {
            log.warn("stepCount={} 超出 1..{} , 不展开: session={}",
                    n, MAX_STEPS, session.getSessionId());
            return 0;
        }
        int changed = session.reconcileRepeat(STEP_TEMPLATE_KEY, n, STEP_SUB_ITEMS);
        if (changed > 0) {
            log.info("按 stepCount={} 对齐 {} 个工序子项变更, maxRounds={} : session={}",
                    n, changed, session.getMaxRounds(), session.getSessionId());
        }
        return changed;
    }

    /**
     * 按 {@code productCount} 展开每个成品的工序链问题。
     *
     * <p>与工序展开同一套机制(幂等 + 同时抬高 maxRounds), 但**独立触发** ——
     * 客户可能先说产品再说工序, 也可能反过来。
     */
    public static int expandProductsIfReady(FactoryAssemblyConversationSession session) {
        if (session == null || !INTENT.equals(session.getKnownIntentCode())) {
            return 0;
        }
        String raw = session.getCollectedParameters() == null
                ? null : session.getCollectedParameters().get(PRODUCT_COUNT_KEY);
        if (raw == null || raw.trim().isEmpty()) {
            return 0;
        }
        java.util.OptionalInt parsedP = countIn(raw);
        if (parsedP.isEmpty()) {
            log.info("productCount 答案里没有数字, 暂不展开: session={}, raw={}",
                    session.getSessionId(), raw);
            return 0;
        }
        int n = parsedP.getAsInt();
        if (n <= 0 || n > MAX_STEPS) {
            log.warn("productCount={} 超出 1..{}, 不展开: session={}", n, MAX_STEPS, session.getSessionId());
            return 0;
        }
        int changed = session.reconcileRepeat(PRODUCT_TEMPLATE_KEY, n, PRODUCT_SUB_ITEMS);
        if (changed > 0) {
            log.info("按 productCount={} 对齐 {} 个成品子项变更: session={}",
                    n, changed, session.getSessionId());
        }
        return changed;
    }
}
