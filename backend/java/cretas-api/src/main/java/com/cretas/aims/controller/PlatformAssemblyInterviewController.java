package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.template.IndustryTemplatePackageDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.FactoryAssemblyConversationService;
import com.cretas.aims.service.FactoryAssemblyConversationService.ConversationActor;

import com.cretas.aims.blueprint.interview.FactoryBuildApplyService;
import com.cretas.aims.blueprint.interview.FactoryBuildInterviewService;
import com.cretas.aims.blueprint.interview.FactoryBuildPlanService;
import com.cretas.aims.blueprint.interview.FactoryAssemblyDraftIntent;
import com.cretas.aims.blueprint.interview.FactoryDecisionPacket;
import com.cretas.aims.blueprint.interview.InterviewDefaults;
import com.cretas.aims.utils.JwtUtil;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行业模板包 / 对话建厂 控制器。
 *
 * <h2>本类只做三件事: 收请求 → 鉴权 → 调 service → 包响应</h2>
 *
 * 2026-08-12 之前这里是 <b>3536 行 / 14 个端点</b>, 校验、计划计算、十几段落库写入、
 * 访谈题目表、模板包 CRUD 全在一个文件里。问题不在「模块边界」而在
 * <b>单文件瓶颈</b>: 所有建厂相关的活都要动这一个文件, 于是只能串行 ——
 * 2026-08-11 有 6 张欠账表被迫排队, 2026-08-12 一轮里又有两项撞在这里。
 * 现在按职责落到:
 * <ul>
 *   <li>{@link TemplatePackageService} —— 模板包 CRUD + 导入工厂</li>
 *   <li>{@link FactoryBuildInterviewService} —— 访谈题目表 / 会话进度 / 结构化答题 / 描述拼装</li>
 *   <li>{@link com.cretas.aims.blueprint.interview.FactoryBuildApplyValidator} —— 落库前的全量校验</li>
 *   <li>{@link FactoryBuildPlanService} —— 「确认之后会发生什么」(预览与落库共用)</li>
 *   <li>{@link FactoryBuildApplyService} —— 真正的落库写入</li>
 * </ul>
 *
 * <h2>⛔ 请求/响应 DTO 仍然留在本类里</h2>
 *
 * {@code ApplyAIInitRequest} 等是<b>对外契约的一部分</b>, 而且已经被闸与调用方按
 * {@code PlatformAssemblyInterviewController.ApplyAIInitRequest} 引用。搬走等于改公开类型名,
 * 那不属于「纯结构拆分」。
 *
 * <h2>事务</h2>
 *
 * 拆分前 {@code @Transactional} 挂在本类的两个方法上
 * ({@code applyAIInitializedConfig} / {@code submitStructuredAnswers})。
 * 现在挂在对应的 service 方法上, 而本类在调它们之前<b>只做不碰库的事</b>
 * (解析 token / 查角色) —— 事务的起点与终点没有移动。
 * ⛔ 别把 {@code @Transactional} 再加回本类: 两处都挂着虽然不出错(REQUIRED 会合并),
 *    但会让「事务边界到底在哪」变成两处口径。
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-12-29
 */
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@RestController
@RequestMapping("/api/platform/factory-assembly")
@Tag(name = "行业模板包", description = "行业模板包管理相关接口，包括模板包列表查询、详情获取、创建/更新/删除、设为默认、使用情况统计、工厂模板初始化等功能。用于平台管理员创建行业标准配置模板，工厂可快速导入使用")
@Validated
public class PlatformAssemblyInterviewController {



    @Autowired
    private FactoryBuildInterviewService interviewService;

    @Autowired
    private FactoryBuildPlanService planService;

    @Autowired
    private FactoryBuildApplyService applyService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private FactoryAssemblyConversationService assemblyConversations;

    /**
     * 能对这个工厂做建厂类操作的角色。
     *
     * ⛔ 这两个角色名此前在本文件里**手写了四遍**(初始化模板/对话建厂/落库/…)。
     *    手写清单的问题不是重复, 是<b>改的时候只会改到你正在看的那一处</b> ——
     *    漏掉的那处会变成一个安静的权限口子(或一个安静的误拒)。
     *    加第五处时收成一份。
     */
    private static final Set<String> PLATFORM_BUILD_ROLES = Set.of("super_admin");

    /** ⛔ 模板包 CRUD 只放平台管理员, 与建厂类操作<b>不是同一档</b>, 别合并。 */
    private static final String PLATFORM_ADMIN_ROLE = "super_admin";

    /** 这些角色**只能动自己那家厂** —— 平台角色不受此限(它们本来就是跨厂的)。
     *  ⚠️ 与 JwtAuthInterceptor.FACTORY_SELF_SERVICE_ROLES 是同一件事的两层,
     *  由 scripts/tests/check-platform-role-gates-agree.py 钉住两边一致。 */
    private static final Set<String> FACTORY_SCOPED_BUILD_ROLES = Set.of();
    /**
     * 建厂类操作的准入：角色对 <b>且</b>（工厂超管时）只能动自己那家厂。
     *
     * <p>⚠️ 2026-08-18 之前这里<b>只判角色、不比对 factoryId</b>。当时没出事，
     * 只是因为 {@code JwtAuthInterceptor} 把整个 {@code /api/platform/**} 一刀切给了
     * 平台角色 —— 工厂超管<b>根本进不来</b>，这段判断是到不了的代码。
     * 现在拦截器按路径放行工厂超管了，这一层就必须自己把厂号钉死，
     * ⛔ 否则 A 厂超管能复核 B 厂。
     *
     * <p>⛔ 六个入口都走这一个方法 —— 之前是六处各写一遍 {@code contains}，
     * 那正是「改一处漏五处」的形状（本仓硬约束 8）。
     *
     * @param what 出错时告诉用户他刚才想做什么（「初始化模板」「落库 AI 配置」…）
     */
    private void requireBuildAccess(String factoryId, HttpServletRequest request, String what) {
        authenticatedActorOf(request);
        if (factoryId == null || factoryId.isBlank()) throw new BusinessException(400, "目标工厂不能为空");
    }

    private AuthenticatedActor authenticatedActorOf(HttpServletRequest request) {
        return AuthenticatedActor.current(request)
                .filter(actor -> actor.isPlatformAdmin() && "super_admin".equals(actor.role()))
                .orElseThrow(() -> new BusinessException(403, "仅已验证的平台管理员可以使用建厂能力"));
    }

    private ConversationActor conversationActorOf(HttpServletRequest request) {
        AuthenticatedActor actor = authenticatedActorOf(request);
        return new ConversationActor(actor.type(), actor.id());
    }

    private String roleOf(HttpServletRequest httpRequest) {
        return authenticatedActorOf(httpRequest).role();
    }

    private Long userIdOf(HttpServletRequest httpRequest) {
        return authenticatedActorOf(httpRequest).id();
    }

    // ==================== DTO定义 ====================

    /**
     * 初始化模板请求
     */
    @Data
    public static class InitializeTemplateRequest {
        @NotBlank(message = "模板ID不能为空")
        private String templatePackageId;

        /**
         * 是否覆盖已有配置
         */
        private Boolean overwrite = false;
    }

    /**
     * 初始化结果
     */
    @Data
    public static class InitializeTemplateResult {
        private boolean success;
        private int templatesImported;
        private List<String> entityTypes;
        private String message;
    }

    /**
     * 创建模板包请求
     */
    @Data
    public static class CreateTemplatePackageRequest {
        @NotBlank(message = "行业代码不能为空")
        private String industryCode;

        @NotBlank(message = "行业名称不能为空")
        private String industryName;

        private String description;

        @NotBlank(message = "模板JSON不能为空")
        private String templatesJson;

        private Boolean isDefault = false;
    }

    /**
     * 更新模板包请求
     */
    @Data
    public static class UpdateTemplatePackageRequest {
        private String industryName;
        private String description;
        private String templatesJson;
        private Boolean isDefault;
    }

    /**
     * 模板使用情况DTO
     */
    @Data
    public static class TemplateUsageInfo {
        private String templateId;
        private int usageCount;
        private List<FactoryUsageInfo> factories;
    }

    @Data
    public static class FactoryUsageInfo {
        private String factoryId;
        private String factoryName;
        private String initializedAt;
    }

    @Data
    public static class FactoryBuildProgress {
        private String sessionId;
        private String factoryId;
        private String status;
        private int currentRound;
        private int maxRounds;
        /** 还剩几项没问到 —— 收敛判据读这个 */
        private int remaining;
        private List<String> pending = new ArrayList<>();
        private List<String> pendingLabels = new ArrayList<>();
        private List<String> answered = new ArrayList<>();
        private Map<String, String> collected = new LinkedHashMap<>();
        /** Safe transcript for recovery; includes typed receipts but excludes fingerprints and raw provider output. */
        private List<FactoryAssemblyConversationService.ConversationHistoryMessage> history = new ArrayList<>();
        /** 收齐后才有: 可直接喂给 POST /ai-initialize 的 factoryDescription */
        private String assembledDescription;
        private boolean readyForGenerate;
        /** 平台拥有的行业建议包；不含客户、租户、来源工厂或相似度。 */
        private Object platformRecommendation;
        /** 逐题建议值, key 与 pending 里的题目名一一对应; 没有建议的题不出现在这里 */
        private Map<String, String> suggestions = new LinkedHashMap<>();
        /** 没有平台建议、或客户确认与建议不一致时的说明。 */
        private String recommendationNote;

        /** 当前唯一需要处理的强引导决策包；服务端权威，前端不解析聊天文案。 */
        private FactoryDecisionPacket currentDecision;

        /** 用户明确选择“暂时不确定”的项目；继续访谈但阻止完整方案确认。 */
        private List<FactoryDecisionPacket> unresolvedDecisions = new ArrayList<>();

        /**
         * 已确认事实的证据账本。
         *
         * <p>这不是第二份答案存储：{@link #collected} 仍是值的唯一真值，本列表只从会话
         * typed receipt 和消息时间派生来源。旧会话没有逐字段回执时必须标为
         * {@code LEGACY_UNATTRIBUTED}，不得伪造来源或时间。</p>
         */
        private List<FactEvidenceItem> factEvidence = new ArrayList<>();

        /**
         * 从 {@link #collected} 实时派生的只读路线编译预览。
         *
         * <p>纯顺序路线可确定性形成图；复杂路线仅从用户确认的起点、终点和逐条边事实编译，
         * 缺少显式端点/条件时 fail-closed。
         * 该字段不落库、不调用模型，且所有条目固定标记 {@code NOT_PUBLISHED}。</p>
         */
        private List<com.cretas.aims.blueprint.canonical.FactoryRouteIntentCompiler.RouteCompilation>
                routePreviews = new ArrayList<>();

        /**
         * 待答题的完整渲染信息 —— 前端按这个画界面, 不再自己维护一份题目表。
         *
         * ⛔ 2026-08-10 实测: 页面里写死了 5 个题目 key, 其中 3 个
         *    (processChain / keyProcessParams / qualityChecks) **后端早就不问了** ——
         *    界面在展示不存在的题, 而每一层都不报错。
         *    题目是服务端定义的, 就该由服务端一次给全: 名字/标签/题型/选项/建议。
         */
        private List<PendingItem> pendingItems = new ArrayList<>();

        /**
         * 问过两次没拿到、<b>已经放弃</b>的题 —— 带原因。
         *
         * <p>⛔ 它们不在 {@link #pendingItems} 里: 那一份是「还问得动的题」,
         * 界面拿它的第一项当「当前这一题」。放弃的项留在里面, 界面就会一直
         * 指着一道系统早就不问了的题(2026-08-18 真实浏览器实测, 客户会被卡死)。
         *
         * <p>⚠️ 但也<b>不能就这么消失</b> —— 那是「被系统悄悄放弃」。
         * 它们在这里带着原因出现, 客户看得到、也知道为什么。
         */
        private List<SkippedItem> skippedItems = new ArrayList<>();

        @Data
        public static class SkippedItem {
            private String name;
            private String label;
            /** 为什么放弃 —— 直接用会话给的原话(「问了 N 次没拿到确切答案」) */
            private String why;
        }

        @Data
        public static class FactEvidenceItem {
            private String field;
            private String label;
            /** 与 collected 当前值逐字一致，不从历史消息反推。 */
            private String value;
            /** USER_STRUCTURED / USER_NARRATIVE / LEGACY_SESSION。 */
            private String sourceKind;
            /** CONFIRMED / LEGACY_UNATTRIBUTED。 */
            private String evidenceStatus;
            private String reasonCode;
            private LocalDateTime capturedAt;
            /** 稳定但不包含原始输入的会话证据引用。 */
            private String evidenceRef;
            /** 当前值是否与行业参考值一致；一致仍表示用户确认，不表示参考值自动写入。 */
            private boolean referenceMatched;
        }

        /**
         * 已答题的中文标签 —— key 是题目名, value 是标签。
         *
         * ⚠️ 界面上原先直接把题目名当标签显示, 于是已答那栏出现的是
         * 「businessType」而不是「做什么产品」。标签同样是服务端定义的,
         * 前端不该也没法自己补一份(展开出来的 step3.auxMaterials 更没法猜)。
         */
        private Map<String, String> answeredLabels = new LinkedHashMap<>();

        @Data
        public static class PendingItem {
            private String name;
            private String label;
            /** CHOICE = 给按钮; NARRATIVE = 给输入框 */
            private String inputType;
            private List<String> options;
            private boolean multiSelect;
            private boolean allowNone;
            /** 提示语(原 validationHint) —— 例子写在这里, 客户才知道要说到多细 */
            private String hint;
            /** 案例给的建议值; 没有就是 null。⛔ 建议不是答案, 要客户点了才算 */
            private String suggestion;
            /** 第几道/第几个(重复段展开项才有) */
            private Integer repeatIndex;
        }
    }

    /**
     * 把 AI 生成的工厂配置真正写进库(生成与落库分离的「确认」那一步)。
     *
     * ## 为什么需要这个端点
     *
     * `POST /api/platform/factory-assembly/factories/{id}/ai-initialize` **只生成不落库** —— 方法体里
     * 一次写入调用都没有, 拿到 AI 结果直接构造响应返回。而唯一的落库路径
     * `initialize-templates` 只接**预制模板包 ID**, 接不了 AI 产出。
     * 于是「对话建厂」到生成为止就断了。
     *
     * 旁证: `FormTemplateRepository#countAIGeneratedByFactoryId` 统计
     * `source='AI_ASSISTANT'` 的模板, 而 2026-08-10 全仓 `setSource(...)` 只出现过
     * `CLASSIFIER` / `IMPORT` —— 计数器早就有, 喂它的人从来没有过。
     *
     * ## 为什么是「回传配置」而不是「按草稿 ID 落库」
     *
     * 平台管理员**应该**能在落库前改 AI 给的配置(那正是预览的意义)。所以入参是
     * 客户端回传的配置, 但服务端用与生成侧同一套规则**重新校验**, 客户端改坏了照样拒。
     *
     * ## 全量校验先于任何写入
     *
     * ⛔ 不做「能写的先写、坏的跳过」—— 那会留下一个配了一半的工厂, 而且没人知道
     *    差哪儿。任何一条不合规 → 整个请求 400 并逐条列出原因, 一行都不写。
     *    加 `@Transactional` 是第二重保险(写入中途失败也整体回滚)。
     */
    /**
     * 一条计划中的改动 —— 预览与落库<b>看的是同一份</b>。
     */
    @Data
    public static class PlannedChange {
        /** 分组: 表单模板 / 产品类型 / 工序 / 存放区 … */
        private String group;
        /** 幂等键(code 或名字) */
        private String key;
        /** 给人看的一句话 */
        private String detail;
        /** 新建 / 覆盖 / 跳过 */
        private String action;
        /** 库里已经有了吗 */
        private boolean exists;
    }

    @Data
    public static class ApplyAIInitRequest {
        private String industryCode;
        private String industryName;
        private List<SchemaItem> schemas;
        private SuggestedData suggestedData;
        /** 已存在同 entityType/code 时是否覆盖; 默认 false = 跳过并如实报告。 */
        private Boolean overwrite;
        /** Explicit allowlisted capability lanes to open as empty drafts. */
        private List<FactoryAssemblyDraftIntent> draftCapabilityIntents;

        public void setDraftCapabilityIntents(List<FactoryAssemblyDraftIntent> intents) {
            FactoryAssemblyDraftIntent.requireValid(intents);
            this.draftCapabilityIntents = intents;
        }
        /**
         * 客户说了、而生成端**没能配出来**的那些东西。
         *
         * <h3>为什么它要跟着 apply 走一趟</h3>
         *
         * 生成端本来就知道这些(存放区没认出来的名字、工序链对不上的成品、
         * 缺阈值的指标、方向不对的换算), 而它此前**只拼成一句话放进 aiSummary**。
         * aiSummary 只活在建厂那一屏 —— 客户第二天来复核时那一屏早关了,
         * 「我说的三个存放区怎么只建了一个」在复核界面上<b>一个字都没有</b>。
         *
         * ⇒ 让它随 apply 一起存进快照(`result_json`, 已有的 JSON 列, ⛔ 不需要迁移),
         *    复核向导逐类显示。这是目标③ 的后半句:「对不上的地方系统自己指出来」。
         *
         * ⚠️ 服务端<b>不产生也不校验</b>它 —— 它是生成端的产物, 这里只负责原样带过去。
         */
        private List<NotBuiltItem> notBuilt;

        /**
         * 一条「说了没配上」。
         *
         * @param group     哪一类(与预览分组同名, 复核按它归屏)
         * @param item      客户说的那个东西(⛔ 用他说的名字, 不是机器编码)
         * @param why       为什么没配上(客户话)
         * @param howToFix  他现在能怎么补
         */
        @Data
        public static class NotBuiltItem {
            private String group;
            private String item;
            private String why;
            private String howToFix;
            /**
             * 哪一种对不上：{@code missing}=他说了没配上；{@code extra}=配出来了他没说过。
             *
             * <h3>为什么共用一条通道而不是新加一个字段</h3>
             *
             * 「多配」原先只有 aiSummary 里一句话 —— 那段文字只活在建厂那一屏，
             * 客户第二天来复核时它早关了，复核的三份对账里<b>一个字都没有</b>。
             *
             * <p>而新开一个字段要穿 python → DTO → apply → 前端<b>四层</b>，
             * 本仓刚记过「三处缺一不可」那次漏层。⇒ 复用这条已经打通的通道，
             * 只多带一个 kind，渲染时分两块。
             *
             * <p>⚠️ 老数据没有这个字段（快照里存的旧条目 kind 为 null）——
             * 消费端一律按 {@code missing} 处理，⛔ 不许因为它是 null 就不显示。
             */
            private String kind;
        }

        @Data
        public static class SchemaItem {
            private String entityType;
            private String entityName;
            private String description;
            private List<Map<String, Object>> fields;
        }

        @Data
        public static class SuggestedData {
            private List<TypeItem> productTypes;
            private List<TypeItem> materialTypes;
            private List<ConversionItem> conversionRates;
            private List<WorkProcessItem> workProcesses;
            /** 关键控制点(HACCP)—— 2026-08-15 段② 判据一补 */
            private List<HaccpItem> haccpCheckpoints;
            /** 模块开关 —— key 取自 factory_settings 的 enableXxx 字段名 */
            private Map<String, Boolean> modules;
            /** 预警阈值 —— key 必须在 ThresholdKeyCatalog 的合法集合里 */
            private List<ThresholdItem> thresholds;
            /** 存放区(仓库/库位) —— 没有它就无法入库出库 */
            private List<WarehouseItem> warehouses;
            /**
             * S4 产线与每小时产能 (访谈「产线产能」)。单位由客户说出, 只认 PIECE / KG / BATCH;
             * 插单可行性只读这些配置, 不换算、不套默认。按产线名字幂等: 同名产线复用, 产能按线+产品替换。
             */
            private List<ProductionLineItem> productionLines;
            /**
             * 产品×工序绑定。
             *
             * <p>⚠️ 这是 2026-08-10 对抗性审计抓出来的缺口: 访谈问了一条工序链,
             * 而真实工厂**每个产品走的链不一样** —— F006 实测掌中宝走
             * 化冻→焯水→油炸→熟制伴汁→气调, 牛腱肉走 修油→滚揉→焯水→…
             * 只问一条全局链, 拼出来的配置对多产品工厂根本不成立。
             */
            private List<ProductProcessItem> productProcesses;
            /**
             * 是否建一套默认账号。
             *
             * ⚠️ 这是对抗性审计里最重的一条缺口: 配完的工厂**没有任何人能登录**
             * (F006 有 28 个账号, 新厂 0 个)。
             *
             * ⛔ 默认 null = 不建。建账号是有副作用的动作, 不能因为"顺手"就做 ——
             * 而且必须先出现在预览里(见 planChanges), 客户点确认时要知道自己在建账号。
             */
            private Boolean provisionDefaultUsers;
            /**
             * 物料编码 L1 分段(如 原料/包材/辅料)。
             *
             * ⚠️ 只做 L1: L1 是**结构性**的(任何加工厂都分这三类), 而 F006 的
             * L2/L3(牛肉部位/牛腱…) 是那家厂的业务, ⛔ 不许当默认发给别人 ——
             * 与「indicators 不拿 F006 的 21 条当推荐」同一条判据。
             * L2/L3 由客户自己在物料建档时补。
             */
            private List<CodeSegmentItem> codeSegments;

            /**
             * 要开哪些告警。
             *
             * ⚠️ 2026-08-11 实测 alert_rules 全局 0 行 —— 整个告警子系统(SpEL 求值/
             * 去重/通知派发全都实现了)因为 triggerAlert 开头那句
             * {@code if (rules.isEmpty()) return emptyList()} 而<b>一条都产生不了</b>。
             * 这一段就是补那个入口。可选项见 InterviewAlertCatalog。
             */
            private List<AlertItem> alerts;

            /**
             * 要盯哪些生产指标(落 alert_thresholds)。
             *
             * ⚠️ 与上面的 alerts 不是一回事: alerts 是事件/定时告警(alert_rules),
             * 这一段是**按最近若干批的均值**判异常(AnomalyDetectionScheduler 在跑,
             * 实测那张表 0 行 —— 每次都在遍历空表)。
             */
            private List<MetricWatchItem> metricWatches;

            /**
             * 每个模块怎么配(开关 / 分析维度 / 隐藏哪些屏) —— 落 {@code factory_feature_config}。
             *
             * <p>⚠️ 与上面的 {@code modules} <b>不是一回事</b>, 两者都叫「模块」但落<b>不同的表</b>:
             * <ul>
             *   <li>{@code modules}(Map&lt;String,Boolean&gt;) → {@code factory_settings}
             *       的 {@code enableXxx} 列, 是后端功能位</li>
             *   <li>本字段 → {@code factory_feature_config} 行, 是<b>前端</b>按工厂裁剪
             *       屏幕/维度的依据(RN {@code factoryFeatureStore} + web-admin
             *       「功能模块配置」页在读)</li>
             * </ul>
             *
             * <p>⛔ 这一段是 2026-08-12 从 ②({@code /api/internal/onboarding/create-factory})
             * 迁过来的 —— ② 是应用层<b>唯一</b>能建这张表的行的地方, 而它要被淘汰。
             * 不填 = 不写, 工厂继续跑「全部模块启用」的兜底(见
             * {@link com.cretas.aims.blueprint.interview.InterviewModuleConfigCatalog})。
             */
            private List<ModuleConfigItem> moduleConfigs;

        /**
         * 客户说「不用看」的指标 code —— apply 会把它们置 {@code is_active=false}。
         *
         * <p>⛔ 不是「不种」: 那 11 行在建厂时(访谈之前)就种好了。
         * ⚠️ 认不出的 code 直接跳过并记日志, 不猜也不报错 —— 与模块那边同口径。
         */
            private List<String> indicatorOptOut;
        }

        /**
         * 一个模块的配置。
         *
         * <p>结构对齐 ② 原先收的 {@code moduleConfigs}(moduleId/moduleName/enabled/config),
         * 这样 web-admin 那页与 RN store 不用改一行就能读到。
         */
        @Data
        public static class ModuleConfigItem {
            /** ⛔ 必须在 {@code InterviewModuleConfigCatalog} 里 —— 读者查不到的名字写了等于没写 */
            private String moduleId;
            /** 展示名; 不填用目录里的 label */
            private String moduleName;
            /** 不填按 true 处理 —— 会出现在这个列表里通常就是要开 */
            private Boolean enabled;
            /**
             * 自由配置块, 原样落 jsonb。
             * 读者认得的键: {@code analysisDimensions} / {@code disabledScreens} /
             * {@code quickActions} / {@code disabledReports} 等(见建表注释)。
             */
            private Map<String, Object> config;
        }

        @Data
        public static class MetricWatchItem {
            /** 指标名, 必须是 InterviewAnomalyMetricCatalog 里的(= 评估器认得的那 10 个) */
            private String metricName;
            private Boolean enabled;
            /** 阈值。⛔ 必填 —— 没有阈值的「盯着」不是配置, 是空转 */
            private java.math.BigDecimal threshold;
        }

        @Data
        public static class AlertItem {
            /** AlertType 枚举名, 如 INVENTORY_LOW */
            private String alertType;
            /** 不填按 true 处理 —— 会出现在这个列表里就是要开 */
            private Boolean enabled;
            /**
             * 金额阈值, 只有 PO/SO 两个金额类要填。
             * ⛔ 它们不填就开 = 每张单子一条告警(内置逻辑里没有金额比较), 见 InterviewAlertCatalog。
             */
            private java.math.BigDecimal amountThreshold;
        }

        @Data
        public static class CodeSegmentItem {
            /** 段码, L1 是 3 位数字(F006 实测 001/002/003) */
            private String code;
            /** 段名, 如 原料 / 包材 / 辅料 */
            private String label;
        }

        @Data
        public static class ProductionLineItem {
            /** 产线名, 也是幂等键 (本厂同名产线复用, 不重复建) */
            private String name;
            private List<LineCapacityItem> capacities;
        }

        @Data
        public static class LineCapacityItem {
            /** 产品 code (本次 productTypes 或库里); 为空 = 该线所有产品通用 */
            private String productCode;
            private java.math.BigDecimal capacityPerHour;
            /** PIECE / KG / BATCH */
            private String capacityUnit;
        }

        @Data
        public static class WarehouseItem {
            /** 工厂内唯一编号 */
            private String code;
            private String name;
            /** WarehouseType 枚举名, 如 RAW / WIP / FINISHED */
            private String type;
            private String remarks;
        }

        @Data
        public static class ProductProcessItem {
            /** 产品 code, 要能在本次 productTypes 或库里解析到 */
            private String productCode;
            /**
             * 兼容既有落库链路的工序清单；线性路线中顺序就是 process_order。
             *
             * <p>复杂路线仍保留这一份去重后的节点名称清单，供蓝图预览和将来的 Workflow
             * 草稿入口使用；分支、合流、返工与多终点的权威拓扑只读 routeNodes/routeEdges。
             * 当前旧 apply 会在任何业务写入前明确拒绝显式路线，禁止静默退化成这份线性清单。
             */
            private List<String> processNames;
            /**
             * 显式路线节点。字段缺失时按既有 processNames 生成线性路线；字段存在（即使为空）
             * 时禁止静默回退，由 canonical validate 如实报告结构问题。
             */
            private List<RouteNodeItem> routeNodes;
            /** 显式路线连线；type 只接受 FORWARD / BRANCH / MERGE / REWORK。 */
            private List<RouteEdgeItem> routeEdges;
        }

        @Data
        public static class RouteNodeItem {
            /** 产品路线内稳定且唯一的节点 ID。 */
            private String id;
            /** 对应工序名称。 */
            private String label;
        }

        @Data
        public static class RouteEdgeItem {
            private String fromNodeId;
            private String toNodeId;
            private com.cretas.aims.blueprint.canonical.FactoryRouteGraphPrototype.EdgeType type;
        }

        /**
         * 一条阈值。
         *
         * <p>⚠️ 只带 key 和 value: 分类由 {@code ThresholdKeyCatalog} 按前缀推,
         * 不让提交方自己报 —— 报错了会让同一个 key 在不同入口归到不同类,
         * 而按类查询的地方就再也查不全。
         */
        @Data
        public static class ThresholdItem {
            private String key;
            private String value;
        }

        /**
         * 一道工序。访谈里每道问 8 项, 这里是它们的落库形状。
         *
         * <p>⚠️ {@code customFields} 与 {@code byproducts} 对应 {@code work_processes} 的
         * {@code custom_field_schema} / {@code expected_byproducts} 两列 ——
         * F006 库里 16 道真实工序这两列<b>16/16 全空</b>。原因不是客户不填,
         * 是<b>此前没有任何一个入口去问</b>。
         */
        @Data
        public static class WorkProcessItem {
            /** 工序名, 幂等键(factoryId + processName) */
            private String name;
            /** 前处理 / 加工 / 包装 —— 取自真实库里已有的三档, 不新造 */
            private String category;
            /** 第几道, 1 起 */
            private Integer sortOrder;
            /** 投入单位, NOT NULL */
            private String unit;
            /** 产出单位; 包装工序常与 unit 不同(kg → 盒/箱) */
            private String outputUnit;
            /** 出成率区间; 注水/加汁类会 >1, 包装类通常不填 */
            private BigDecimal yieldMin;
            private BigDecimal yieldMax;
            /** SEMI_FINISHED / FINISHED_GOOD */
            private String outputKind;
            private String description;
            /** 工艺参数 → custom_field_schema, 形状由 WorkProcessCustomFieldSchema 定 */
            private List<Map<String, Object>> customFields;
            /** 副产物 → expected_byproducts */
            private List<Map<String, Object>> byproducts;
            /** 辅料 → process_material_recipes(SEASONING) */
            private List<RecipeItem> auxMaterials;
            /** 包材 → process_material_recipes(PACKAGING) */
            private List<RecipeItem> packMaterials;
        }

        @Data
        public static class RecipeItem {
            private String name;
            private BigDecimal quantity;
            private String unit;
            private BigDecimal unitPrice;
            private String remark;
        }

        /**
         * 转换率。AI 给的是 code, 而 material_product_conversions 的
         * material_type_id / product_type_id 都是 **NOT NULL 的 id** ——
         * 所以落库前必须把 code 解析成 id, 解析不了就整体拒绝(见 validateApplyRequest)。
         */
        @Data
        public static class ConversionItem {
            /** 原料 code */
            private String fromCode;
            /** 成品 code */
            private String toCode;
            /** 出成率 */
            private BigDecimal rate;
            private String note;
        }

        /**
         * 关键控制点(HACCP)。
         *
         * 🔴 `criticalLimitMin` / `criticalLimitMax` / `unit` 在库里**三列都非空**
         * (`haccp_checkpoints`), 所以缺任一个的整条**不写** —— apply 计入 skipped
         * 并在结果里逐条列出。⛔ 不许补默认值: 食品安全的限值编错不是误报, 是安全风险。
         */
        @Data
        public static class HaccpItem {
            /** 控制点名, 如「杀菌」 */
            private String name;
            /** BIOLOGICAL | CHEMICAL | PHYSICAL */
            private String hazardType;
            private BigDecimal criticalLimitMin;
            private BigDecimal criticalLimitMax;
            private String unit;
            private String monitoringProcedure;
        }

        @Data
        public static class TypeItem {
            private String code;
            private String name;
            private String unit;
            private String description;
            /** Material storage choice explicitly confirmed by the user. */
            private String storageType;

            /**
             * 产品业务类别。产品缺省按 FINISHED_PRODUCT；物料项不使用此字段。
             * 该字段决定 SKU 是否能进入 Workflow、销售等下游选择器，不能只靠名称猜测。
             */
            private String productCategory;

            /**
             * 一级(包装)单位原文, 如「箱」。与 boxConversionCoefficient 联用:
             * <b>1 个 level1Unit = boxConversionCoefficient 个 unit</b>
             * —— 与 {@code ProductType#level1Unit} 的注释逐字一致, ⛔ 方向不许反。
             *
             * <p>访谈里「一箱20斤」就是 level1Unit=箱 / unit=斤 / coefficient=20。
             *
             * <p>⚠️ 方向反了的代价是 <b>400 倍</b>: 换算图给正向边挂 factor、反向边挂 1/factor,
             * 写成 jin→case×20 会让「1 箱」被算成 0.05 斤而不是 20 斤, 采购金额与投料量同倍错。
             *
             * <p>⛔ 不要在这里对它调 storageUnit, 也不要手工推单位码:
             * 「箱」归一到 {@code case} 而不是 {@code box}(实测 alias("case","case","箱","carton")),
             * 手工推会撞上词典里遗留的 box|箱 那一行。归一交给 synchronize 内部做。
             */
            private String level1Unit;

            /**
             * 1 个 level1Unit 等于多少个 unit。
             *
             * <p>必须 &gt; 0, 且与 level1Unit <b>成对出现</b>; 两个单位还都得是权威表认得的写法。
             * 不合规<b>整体拒绝</b>(400), ⛔ 不静默丢弃 —— 见 {@code checkPackaging}。
             */
            private BigDecimal boxConversionCoefficient;

            /**
             * 物料的 L1 分类。不填就<b>从 workProcesses 的结构位置推</b>(见 deriveMaterialRoles) ——
             * 名字出现在某道工序的 packMaterials 里就是包材, auxMaterials 里就是辅料, 其余是原料。
             *
             * <p>⚠️ 这个信息访谈本来就收了(每道工序分开问原料/辅料/包材), 只是
             * suggestedData.materialTypes 拍平成一个列表时丢掉了。所以不必新问, 是接回来。
             *
             * <p>⛔ 不交给 LLM 生成: 它是结构位置的函数, 推得出来就不该让模型猜。
             */
            private String category;
        }
    }

    @Data
    public static class ApplyAIInitResult {
        private boolean success;
        private String factoryId;
        private String message;
        private List<String> writtenTemplates = new ArrayList<>();
        /** 关键控制点(HACCP)—— 写入的 */
        private List<String> writtenHaccpCheckpoints = new ArrayList<>();
        /**
         * 🔴 **因为没给临界限值而没写入的**控制点。
         * 库里 critical_limit_min/max/unit 三列都非空 ⇒ 缺任一个整条写不进去。
         * ⛔ 不补默认值(食品安全限值编错是安全风险)⇒ 只能跳过, 但**必须告诉客户**,
         * 否则就成了「他说了而系统装作没听见」。
         */
        private List<String> skippedHaccpCheckpoints = new ArrayList<>();
        private List<String> writtenProductTypes = new ArrayList<>();
        private List<String> writtenMaterialTypes = new ArrayList<>();
        /** 判定不出 L1 分类、只能直写的物料码 —— ⛔ 它们的 category/primary_code 是空的。 */
        private List<String> unclassifiedMaterialTypes = new ArrayList<>();
        private List<String> skippedTemplates = new ArrayList<>();
        private List<String> skippedProductTypes = new ArrayList<>();
        private List<String> skippedMaterialTypes = new ArrayList<>();
        private List<String> writtenConversions = new ArrayList<>();
        private List<String> skippedConversions = new ArrayList<>();
        private List<String> writtenWorkProcesses = new ArrayList<>();
        private List<String> skippedWorkProcesses = new ArrayList<>();
        private List<String> writtenModules = new ArrayList<>();
        private List<String> writtenThresholds = new ArrayList<>();
        private List<String> writtenWarehouses = new ArrayList<>();
        private List<String> skippedWarehouses = new ArrayList<>();
        /** S4: 新建的产线与写下的产能 (键与预览「产线与设备产能」分组同形) */
        private List<String> writtenProductionLines = new ArrayList<>();
        /** S4: 同名已存在而复用的产线 */
        private List<String> skippedProductionLines = new ArrayList<>();
        private List<String> writtenProductProcesses = new ArrayList<>();
        /** 新建的登录账号用户名。⛔ 初始密码不在这里, 也不在任何响应里 —— 走重置流程。 */
        private List<String> writtenUsers = new ArrayList<>();
        /**
         * 没建的登录账号, <b>带原因</b>。
         *
         * <p>🔴 2026-08-17 实测补的缺口: 预览会说「跳过(本厂已有 N 个账号, 不会重复建)」,
         * 而回执这一侧<b>连一个字段都没有</b> —— 客户在回执里看不到这一条的结局,
         * 而目标③ 要求「预览 = 实际写入 = 完成回执, 三者对得上」。
         *
         * <p>{@code UserServiceImpl#provisionDefaultUsers} 返回空<b>只有一个原因</b>:
         * 本厂已有 users(幂等跳过)。其余情况要么建 3 个、要么抛 EntityNotFoundException。
         * ⇒ 「返回空」可以确定性地翻译成这一条, 不用猜。
         */
        private List<String> skippedUsers = new ArrayList<>();
        private List<String> writtenCodeSegments = new ArrayList<>();
        private List<String> skippedCodeSegments = new ArrayList<>();
        private List<String> writtenAlerts = new ArrayList<>();
        private List<String> writtenPackagingConversions = new ArrayList<>();
        /** 换算图冲突等原因没同步成功的 —— ⛔ 不阻断建厂, 但必须看得见。 */
        private List<String> failedPackagingConversions = new ArrayList<>();
        private List<String> writtenMetricWatches = new ArrayList<>();
        private List<String> skippedMetricWatches = new ArrayList<>();
        private List<String> writtenModuleConfigs = new ArrayList<>();
        private List<String> skippedModuleConfigs = new ArrayList<>();
        /** 落 factory_feature_config 时出错的模块 —— ⛔ 不阻断建厂, 但必须看得见。 */
        private List<String> failedModuleConfigs = new ArrayList<>();
        /**
         * 后端 API 门禁开关(factory_module_configs)—— 上面那三个是**前端可见性**
         * (factory_feature_config), 两层是分开的。
         * ⛔ 值是 {@code @RequireModule} 的码, 不是前端的 moduleId。
         */
        private List<String> writtenModuleGates = new ArrayList<>();
        private List<String> skippedModuleGates = new ArrayList<>();
        /**
         * 后端门禁这一层为什么没写 —— ⛔ **不能只留空列表**: 空列表分不清
         * 「没什么要写」和「整段被跳过了」。工厂已经在走 Canvas 配置流程时本段会整体跳过。
         */
        private String moduleGateNote;

        /**
         * 被客户说「不用看」而**停用**的指标(展示名)。
         *
         * ⚠️ 是 {@code is_active=false} 不是删除 —— 那 11 行在建厂时就种好了,
         * 客户以后想看回来在 Canvas 里打开即可。
         */
        private List<String> disabledIndicators = new ArrayList<>();
        /** 认不出的 code —— ⛔ 必须看得见, 空列表分不清「没要减」和「减了但没认出来」。 */
        private List<String> unknownIndicatorCodes = new ArrayList<>();

        /**
         * 客户说了、而**生成端就没能配出来**的那些东西 —— 原样来自请求。
         *
         * <h3>⛔ 它与 {@code skipped*} 不是一回事</h3>
         *
         * {@code skipped*} 是「载荷里有这一条, 但落库时跳过了(多半是库里已有)」;
         * 这一份是「客户说过, 而**载荷里根本就没有它**」——
         * 存放区认不出的那两个名字, 在 planned / written / skipped 里**一处都不出现**,
         * 于是复核的三份对账里它们是隐形的: 三个数完全对得上, 而客户说的东西少了两件。
         *
         * <p>🔴 2026-08-17 实测就是这个形状: 客户说「原料冷库、车间暂存、成品仓」三个,
         * 系统建 1 个, 复核界面上另外两个一个字都没有(它们只在建厂那一屏的说明里,
         * 而那一屏关掉就没了)。
         *
         * <p>⚠️ 存进快照(`result_json`)才能隔天还看得到 —— 这是目标③ 的后半句。
         */
        private List<ApplyAIInitRequest.NotBuiltItem> notBuilt = new ArrayList<>();
        private List<String> skippedAlerts = new ArrayList<>();
        /** 本次落库对应的计划(与预览端点同一份计算) */
        private List<PlannedChange> planned = new ArrayList<>();
    }

    // ==================== API端点 ====================

    /**
     * 获取所有可用的行业模板包
     */
    @PostMapping("/factories/{factoryId}/ai-initialize/converse")
    @Operation(summary = "开始对话建厂",
               description = "开一个多轮参数收集会话, 逐项问齐建厂所需信息。"
                           + "回复使用平台建厂专用会话接口")
    public ApiResponse<FactoryAssemblyConversationService.ConversationResponse> startFactoryBuildConversation(
            @PathVariable @Parameter(description = "目标工厂ID") String factoryId,
            @RequestParam(defaultValue = "false")
            @Parameter(description = "仅用户显式点击重新开始时为 true") boolean forceRestart,
            HttpServletRequest httpRequest) {

        requireBuildAccess(factoryId, httpRequest, "对话建厂");
        ConversationActor actor = conversationActorOf(httpRequest);
        return ApiResponse.success(
                interviewService.startFactoryBuildConversation(factoryId, actor, forceRestart));
    }

    public record FactoryBuildReplyRequest(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 10000) String userReply,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 100) String requestId) { }

    @PostMapping("/factories/{factoryId}/ai-initialize/converse/{sessionId}/reply")
    public ApiResponse<FactoryAssemblyConversationService.ConversationResponse> replyFactoryBuildConversation(
            @PathVariable String factoryId, @PathVariable String sessionId,
            @jakarta.validation.Valid @RequestBody FactoryBuildReplyRequest reply,
            HttpServletRequest request) {
        requireBuildAccess(factoryId, request, "回复建厂访谈");
        return ApiResponse.success(assemblyConversations.continueConversation(factoryId,
                conversationActorOf(request), sessionId, reply.userReply(), reply.requestId()));
    }

    /**
     * 访谈里表格题的现值与默认值。
     *
     * <p>表格题的问法是「只改跟你们不一样的格子」, 前提是<b>预填的东西有出处</b>。
     * 每段都带 {@code defaultsFrom}:
     * {@code GLOBAL_ROW}(factory_id='*' 的通用行) / {@code ENTITY_DEFAULT}(实体默认) /
     * {@code NONE}(没有默认可给, 从零问)。
     *
     * <p>⛔ {@code NONE} 时前端不许预填, 更不许去找一家像的厂抄一份 ——
     * 实测 indicators 只有 F006 一家有 21 条, 拿它当"推荐"等于把一个租户的配置
     * 当成系统建议发给另一个租户, 而客户看不出是抄来的。
     */
    @GetMapping("/factories/{factoryId}/ai-initialize/interview-defaults")
    @Operation(summary = "访谈表格题的现值与默认值",
               description = "返回模块开关/预警阈值/关注指标三段的当前值与默认值来源。"
                           + "defaultsFrom=NONE 表示没有默认可给, 必须从零问")
    public ApiResponse<List<InterviewDefaults.Section>> getInterviewDefaults(
            @PathVariable String factoryId,
            HttpServletRequest httpRequest) {
        requireBuildAccess(factoryId, httpRequest, "查看访谈默认值");
        return ApiResponse.success(interviewService.interviewDefaults(factoryId));
    }

    /**
     * 会话进度 —— 收敛判据从这里读。
     *
     * `remaining` 必须**单调不增且有界归零**: 每答一轮少一项, 答不上来就不动。
     * ⛔ 别拿模型的措辞当判据。
     */
    /**
     * 段③ 复核：把「当时说的」与「当时做的」取回来。
     *
     * <h3>⛔ 为什么不是「重新预览一次」</h3>
     *
     * 库已经被那次 apply 改过了，重算出来的每一条都会变成「已存在/跳过」，
     * 与客户当时确认的那份对不上。⇒ 读快照，⛔ 不重算。
     *
     * <h3>⚠️ 三份里这里只给两份</h3>
     *
     * 「现在库里的」那一份要六类各自去查，属于复核的每一屏自己的事
     * （设计卡 §二）。⛔ 这里不假装三份都齐了。
     *
     * <p>⚠️ {@code result} 为 null 有两种情况，调用方要分开说：
     * 这个工厂还没成功建过厂；或者那次快照是在补 {@code result_json} 这一列
     * <b>之前</b>存的（老行）。两者都不是「建厂什么都没写」。
     */
    @GetMapping("/factories/{factoryId}/ai-initialize/review")
    @Operation(summary = "建厂复核:当时说的 + 当时做的",
               description = "读最后一次建厂的快照。⛔ 不重算预览 —— 库已被改过, 重算与当时对不上。")
    public ApiResponse<Map<String, Object>> getFactoryBuildReview(
            @PathVariable String factoryId,
            HttpServletRequest httpRequest) {
        // ⛔ 与建厂入口同一道闸(PLATFORM_BUILD_ROLES): 这份快照会把这家厂
        //    「配了什么、跳过了什么」整份摊开, 不是任何登录用户都该看的。
        // ⚠️ 第一版我写了个 requirePlatformAdmin(...) —— **那个方法不存在**,
        //    是我照着印象编的名字。⇒ 鉴权照抄邻居的写法, ⛔ 不自创。
        requireBuildAccess(factoryId, httpRequest, "查看建厂复核");
        return ApiResponse.success(applyService.reviewSnapshot(factoryId));
    }

    @GetMapping("/factories/{factoryId}/ai-initialize/converse/{sessionId}")
    @Operation(summary = "对话建厂进度",
               description = "返回还剩几项没问到、已收集什么、以及收齐后可直接喂给 ai-initialize 的描述")
    public ApiResponse<FactoryBuildProgress> getFactoryBuildProgress(
            @PathVariable String factoryId,
            @PathVariable String sessionId,
            HttpServletRequest httpRequest) {
        ConversationActor actor = conversationActorOf(httpRequest);
        return ApiResponse.success(interviewService.progress(factoryId, sessionId, actor));
    }

    @GetMapping("/factories/{factoryId}/ai-initialize/converse/recoverable")
    @Operation(summary = "读取可恢复的建厂访谈",
               description = "只读返回当前操作者最新的 ACTIVE 或 COMPLETED 建厂访谈；不会创建或重启会话")
    public ApiResponse<FactoryBuildProgress> getRecoverableFactoryBuildConversation(
            @PathVariable String factoryId,
            HttpServletRequest httpRequest) {
        ConversationActor actor = conversationActorOf(httpRequest);
        return ApiResponse.success(interviewService.recoverableProgress(factoryId, actor).orElse(null));
    }

    /**
     * 结构化答题 —— 枚举类题目不走 LLM 抽取。
     *
     * ## 为什么单独开一个端点
     *
     * 访谈规格 v2 的判据: **能枚举的不要用问答**。
     * 「有没有客供料」这种二选一, 勾选一秒钟; 走对话则要来回三轮还可能答偏。
     *
     * ⛔ 更要紧的是: 对枚举项跑 LLM 抽取是**负作用** —— 它会把「差不多」的说法
     *    归一到某个选项, 而那正是我们不想要的猜测。
     *
     * ⛔ 全量校验先于任何写入: 任一项不合规 → 整个提交 400, 一个都不写。
     */
    @PostMapping("/factories/{factoryId}/ai-initialize/converse/{sessionId}/answers")
    @Operation(summary = "结构化答题(勾选/表格/矩阵)",
               description = "枚举类题目直接按 key 提交, 不经 LLM 抽取。逐项校验后整体写入。")
    public ApiResponse<FactoryBuildProgress> submitStructuredAnswers(
            @PathVariable String factoryId,
            @PathVariable String sessionId,
            @RequestBody Map<String, Object> answers,
            HttpServletRequest httpRequest) {
        ConversationActor actor = conversationActorOf(httpRequest);
        return ApiResponse.success(interviewService.submitStructuredAnswers(
                factoryId, sessionId, actor, answers));
    }

    // ==================== AI 生成配置 → 落库 ====================

    /**
     * 预览: 只算不写。
     *
     * <p>先跑与落库<b>同一份</b>校验 —— 不合规的在这一步就 400,
     * 而不是等客户点了确认才报错(那正是预览要消灭的东西)。
     */
    @PostMapping("/factories/{factoryId}/ai-initialize/preview")
    @Operation(summary = "落库前预览", description = "与 apply 共用校验与计划计算, 只算不写")
    public ApiResponse<List<PlannedChange>> previewAIInitializedConfig(
            @PathVariable String factoryId,
            @RequestBody ApplyAIInitRequest request,
            HttpServletRequest httpRequest) {
        requireBuildAccess(factoryId, httpRequest, "预览");
        return ApiResponse.success(planService.preview(factoryId, request));
    }

    /**
     * 把 AI 生成的工厂配置真正写进库(生成与落库分离的「确认」那一步)。
     *
     * <p>⛔ {@code @Transactional} 在 {@link FactoryBuildApplyService#apply} 上,
     * 不在这里 —— 本方法在调它之前只解析 token 与查角色, 都不碰库,
     * 所以事务跨度与拆分前逐字相同。别把注解加回来变成两处口径。
     *
     * <p>⛔ {@code operatorId} 可能为 null, <b>照传</b>: 拆分前的语句顺序是
     * 「先跑全量校验(400) 再判令牌里有没有用户标识(401)」, 在这里提前判会让
     * 「载荷不合规 + 令牌没有用户标识」的请求从 400 变成 401。
     */
    @PostMapping("/factories/{factoryId}/ai-initialize/apply")
    @Operation(summary = "落库 AI 生成的工厂配置",
               description = "把 ai-initialize 生成(并经人工确认/修改)的配置写入工厂: "
                           + "schemas→form_templates, productTypes→product_types, "
                           + "materialTypes→raw_material_types。仅平台管理员与工厂超级管理员")
    public ApiResponse<ApplyAIInitResult> applyAIInitializedConfig(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @Valid @RequestBody ApplyAIInitRequest request,
            HttpServletRequest httpRequest) {

        // 权限 —— 与 initialize-templates 同一档: 平台管理员 或 工厂超级管理员
        requireBuildAccess(factoryId, httpRequest, "落库 AI 配置");
        AuthenticatedActor actor = authenticatedActorOf(httpRequest);
        return ApiResponse.success(
                applyService.applyAsActor(factoryId, request, new ActorRef(actor.type(), actor.id())));
    }

    /**
     * 创建新的行业模板包
     */

}
