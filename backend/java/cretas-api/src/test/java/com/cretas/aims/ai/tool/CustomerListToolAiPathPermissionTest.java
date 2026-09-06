package com.cretas.aims.ai.tool;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * owner 裁定 (2026-09-06): 「客户名单只有<b>销售、财务、工厂管理员、运营</b>能看。」
 *
 * <p>🔴 这条裁定有<b>两个</b>执行点，不是一个:</p>
 * <ol>
 *   <li>HTTP 面 —— {@code CustomerController#getCustomerList} 上的 {@code @RequirePermission}
 *       (见 {@code CustomerControllerGetCustomerListPermissionTest});</li>
 *   <li><b>AI 面</b> —— {@code CustomerListTool} 直接调 {@code CustomerService#getCustomerList}，
 *       <b>⛔ 完全不经过那个 controller</b>。它继承 {@code AbstractTool} 的默认实现
 *       ({@code requiresPermission()=false} / {@code hasPermission()=true})，
 *       所以在把 {@code customer_list} 加进 {@link ToolRbacEnforcer#TOOL_PERMISSION_MAP} 之前，
 *       生产经理在 HTTP 面拿 403、<b>转头问一句 AI 就能拿到全厂客户名单</b>。</li>
 * </ol>
 *
 * <p>本测试守的就是第 2 个执行点。⚠️ 这几个 customer 只读工具是
 * {@code TOOL_PERMISSION_MAP} 里<b>仅有的只读条目</b> —— 该表原本只收「敏感写工具」，
 * 所以它们很容易在后续清理中被当成「放错了」删掉。删之前请读 owner 裁定:
 * 客户名单本身就是受控数据，与读写无关。</p>
 *
 * <h3>2026-09-06 第二票 ({@code SEC-CUSTOMER-ROSTER-REMAINING-PATHS}) 扩到 6 个工具</h3>
 * <p>#615 只映射了 {@code customer_list}。而 CRM 下另有 5 个只读工具调<b>同样的
 * {@code CustomerService} 方法</b>、同样绕过 controller —— 只关一个等于把绕行入口
 * 从 1 个减到 5 个。本轮把它们一起映射，并且<b>把口径的唯一来源钉死在 HTTP 那一侧</b>:
 * {@link #aiPathCodesMatchTheHttpGateVerbatim} 逐条<b>反射读</b>
 * {@code CustomerController} 上对应方法的 {@code @RequirePermission}，
 * ⛔ 测试里不再手抄第二份权限码 (形态 D: 同一个东西有两份，它一定会漂)。</p>
 *
 * @see com.cretas.aims.controller.CustomerControllerRemainingReadPathsPermissionTest HTTP 那一侧的 11 条
 * @see ToolRbacEnforcerTest 中央 enforcer 的通用行为 (低权拒绝 / 只读不受影响 / fail-closed)
 */
@DisplayName("owner 裁定的 AI 执行点: customer_list 也要过 sales|finance 读权限")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerListToolAiPathPermissionTest {

    private static final String FACTORY = "F006";
    /** production_manager: L1 prod 实测 sales='-' finance='-' —— 裁定四类之外。 */
    private static final long PRODUCTION_MANAGER_UID = 9401L;
    /** sales_manager: L1 prod 实测 sales='rw'。 */
    private static final long SALES_MANAGER_UID = 9402L;

    @Mock private UserRepository userRepository;
    @Mock private PermissionService permissionService;

    private ToolRbacEnforcer enforcer;

    @BeforeEach
    void setUp() {
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", userRepository);
        ReflectionTestUtils.setField(guard, "permissionService", permissionService);
        enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);

        when(userRepository.findById(PRODUCTION_MANAGER_UID))
                .thenReturn(Optional.of(user(PRODUCTION_MANAGER_UID, "production_manager")));
        when(userRepository.findById(SALES_MANAGER_UID))
                .thenReturn(Optional.of(user(SALES_MANAGER_UID, "sales_manager")));
    }

    private static User user(long id, String roleCode) {
        User u = new User();
        u.setId(id);
        u.setFactoryId(FACTORY);
        u.setRoleCode(roleCode);
        return u;
    }

    private static Map<String, Object> ctx(long userId, String role) {
        Map<String, Object> c = new HashMap<>();
        c.put("factoryId", FACTORY);
        c.put("userId", userId);
        c.put("userRole", role);
        return c;
    }

    /** 最小桩工具: 只提供名字, 不自声明权限 —— 与真实 CustomerListTool 一致 (它没 override)。 */
    private static ToolExecutor tool(String name) {
        return new ToolExecutor() {
            @Override public String getToolName() { return name; }
            @Override public String getDescription() { return name; }
            @Override public Map<String, Object> getParametersSchema() { return Map.of(); }
            @Override public String execute(ToolCall c, Map<String, Object> ctx) { return "{}"; }
            @Override public Set<String> getRequiredPermissions() { return Set.of(); }
        };
    }

    /**
     * 受同一条裁定管的 AI 工具 → 它在 HTTP 面的对位方法。
     *
     * <p>🔴 这张表是<b>成对</b>的，不是一份权限码清单: 右边那一列是<b>唯一口径来源</b>，
     * 测试反射去读它。⇒ 有人改了 controller 上的注解而忘了改映射表 (或反过来)，
     * {@link #aiPathCodesMatchTheHttpGateVerbatim} 当场红。</p>
     *
     * <p>⛔ 表里<b>没有</b> {@code customer_stats}: 它不传 {@code customerId} 时走的是
     * {@code getOverallCustomerStatistics} + 两张分布表 —— 那三个 HTTP 端点按档 C
     * (「一个客户身份都不吐」) 显式留开。给它挂四个码会让 AI 面比 HTTP 面更严，
     * 正好破坏本条要守的「两面逐字相同」。⚠️ 残余风险已在
     * {@code ToolRbacEnforcer.TOOL_PERMISSION_MAP} 的注释里登记 (登记不是豁免)。</p>
     */
    private static final Map<String, String> TOOL_TO_HTTP_METHOD = new LinkedHashMap<>();
    static {
        TOOL_TO_HTTP_METHOD.put("customer_list", "getCustomerList");                       // GET /customers (#615)
        TOOL_TO_HTTP_METHOD.put("customer_active", "getActiveCustomers");                  // /active
        TOOL_TO_HTTP_METHOD.put("customer_search", "searchCustomers");                     // /search
        TOOL_TO_HTTP_METHOD.put("customer_by_type", "getCustomersByType");                 // /by-type
        TOOL_TO_HTTP_METHOD.put("customer_purchase_history", "getCustomerPurchaseHistory");// /{id}/purchase-history
        TOOL_TO_HTTP_METHOD.put("customer_price_memory_query", "getCustomerPriceMemory");  // /{id}/price-memory
        // 🔴 这条不在派活的清单里 —— 按硬约束 8 grep「谁调 CustomerService 的名单方法」才捞出来的。
        //    它名字像「查单个客户的 CRM 状态」, 但传 customerName 走 searchCustomersByName,
        //    多条命中时把整份 candidates(完整 CustomerDTO 列表) 原样返回 ⇒ 一次名单导出。
        //    对位取 getCustomerById (它的 customerId 分支); 与 /search 的码逐字相同, 取哪个都一样。
        TOOL_TO_HTTP_METHOD.put("customer_crm_status_query", "getCustomerById");           // /{id} + /search
    }

    @ParameterizedTest(name = "🔴 裁定后果 (AI 面): production_manager 用 {0} 要客户数据 → 拒绝")
    @MethodSource("rosterTools")
    void productionManagerDeniedOnAiPath(String toolName) {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);

        ToolRbacEnforcer.Decision d =
                enforcer.check(tool(toolName), ctx(PRODUCTION_MANAGER_UID, "production_manager"));

        assertThat(d.isAllowed())
                .as("%s 必须在 TOOL_PERMISSION_MAP 里 —— 否则 controller 的门是摆设, "
                        + "AI 路径直接绕过它拿到全厂客户名单", toolName)
                .isFalse();
    }

    static Stream<String> rosterTools() {
        return TOOL_TO_HTTP_METHOD.keySet().stream();
    }

    /**
     * 阴性对照的阴性对照: 一个<b>没被映射</b>的 CRM 只读工具照旧放行。
     *
     * <p>没有这条，上面那 6 条 {@code isAllowed()==false} 分不清是
     * 「映射生效了」还是「这个 enforcer 对任何只读工具都拒」。</p>
     */
    @Test
    @DisplayName("阴性对照: 未映射的只读工具 (customer_stats) 仍然放行 —— 证明拒绝来自映射表而不是 enforcer 一律拒")
    void unmappedReadOnlyToolStillAllowed() {
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(false);

        assertThat(enforcer.check(tool("customer_stats"),
                ctx(PRODUCTION_MANAGER_UID, "production_manager")).isAllowed())
                .as("customer_stats 故意未映射 (见 TOOL_PERMISSION_MAP 注释)。"
                        + "它若变成 false, 说明有人顺手映射了它 —— 那会让 AI 面比 HTTP 面更严")
                .isTrue();
    }

    @Test
    @DisplayName("销售: 持 sales:read 的角色经 AI 查客户名单 → 放行")
    void salesManagerAllowedOnAiPath() {
        // 只在被问到「sales:read(_write)」时才给 true —— 避免 any() 恒真让断言失去意义。
        // ⚠️ Mockito varargs 坑 (沿用本仓既有先例): getArguments() 把 varargs 按位置摊平,
        //    index 0 是 user, index 1.. 才是各个权限码 —— ⛔ 不能把 args[1] 当 String[] 强转。
        when(permissionService.hasAnyPermission(any(), any(String[].class)))
                .thenAnswer(inv -> {
                    Object[] args = inv.getArguments();
                    for (int i = 1; i < args.length; i++) {
                        String code = String.valueOf(args[i]);
                        if ("sales:read".equals(code) || "sales:read_write".equals(code)) {
                            return true;
                        }
                    }
                    return false;
                });

        ToolRbacEnforcer.Decision d =
                enforcer.check(tool("customer_list"), ctx(SALES_MANAGER_UID, "sales_manager"));

        assertThat(d.isAllowed()).isTrue();
    }

    /**
     * 阴性对照 —— 上面那些都经过 mock 出来的 {@code PermissionService}，
     * 所以它们证明的是「enforcer 按映射表里的码放行/拒绝」；
     * <b>映射表里到底写了哪几个码</b>由这条钉住，且必须与 HTTP 面逐字相同 (形态 D: 同一个
     * 裁定有两份执行点, 不钉一致性它一定会漂)。
     *
     * <p>🔴 <b>期望值不是写死在这里的</b> —— 它反射读 {@code CustomerController} 上对应方法
     * 的 {@code @RequirePermission}。把期望值抄一份进测试等于制造第三份会漂的副本;
     * 这样写则是「一份是源，另一份对它校验」。</p>
     */
    @ParameterizedTest(name = "两个执行点逐字相同: AI {0} == HTTP CustomerController#{1}")
    @MethodSource("toolToHttpMethodPairs")
    void aiPathCodesMatchTheHttpGateVerbatim(String toolName, String httpMethodName) {
        Method[] all = com.cretas.aims.controller.CustomerController.class.getDeclaredMethods();
        List<Method> found = Arrays.stream(all)
                .filter(m -> m.getName().equals(httpMethodName))
                .toList();
        assertThat(found)
                .as("控制器上叫 %s 的方法应当恰好一个 (重载会让反射瞄错目标)", httpMethodName)
                .hasSize(1);

        RequirePermission ann = found.get(0).getAnnotation(RequirePermission.class);
        assertThat(ann)
                .as("HTTP 面 %s 必须带 @RequirePermission —— 它是本条断言的口径来源, "
                        + "它没了这条断言就没有意义 (⛔ 不要因为它是 null 就把期望值抄进测试)",
                        httpMethodName)
                .isNotNull();

        assertThat(ToolRbacEnforcer.TOOL_PERMISSION_MAP.get(toolName))
                .as("AI 工具 %s 的权限码必须与 CustomerController#%s 的 @RequirePermission "
                        + "完全一致 —— 同一条 owner 裁定的两个执行点", toolName, httpMethodName)
                .isEqualTo(new LinkedHashSet<>(Arrays.asList(ann.value())));
    }

    static Stream<Arguments> toolToHttpMethodPairs() {
        return TOOL_TO_HTTP_METHOD.entrySet().stream()
                .map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    /**
     * 硬约束 8 的收尾: 受裁定管的 AI 工具<b>恰好 7 个</b>。
     *
     * <p>这条不是重复计数 —— 它守的是「有人新加了一个 CRM 只读工具却没登记」:
     * 那种工具会安静地成为第 7 条绕行路，而上面每一条断言都不会红 (它们只看表里已有的)。</p>
     */
    /**
     * 挂满四码、但<b>没有 HTTP 对位方法</b>的工具 (workdesk / CRM 记录域专用, controller 上没有同源端点)。
     * 它们进不了 {@link #TOOL_TO_HTTP_METHOD} 的反射对钉，所以在这里单独登记。
     *
     * <p>🔴 全部是第二把尺子量出来的 —— 它们走各自的 CRM 记录仓库再逐行反查客户名，
     * 第一把尺子 (「调 CustomerService 名单方法」) 一个都看不见，而它们都是<b>全厂枚举</b>。</p>
     */
    private static final Set<String> RULING_CODED_WITHOUT_HTTP_COUNTERPART = Set.of(
            "customer_priority_query",        // 200 条/次: name + code + contactPerson + contactPhone
            "call_record_followup_pending",   // 全厂通话记录 -> 每行 customerName
            "opportunity_stage_alert",        // 全厂商机     -> 每行 customerName
            "wechat_record_recent_query");    // 全厂微信记录 -> 每行 customerName

    @Test
    @DisplayName("硬约束 8: TOOL_PERMISSION_MAP 里挂满裁定四码的工具 = 有 HTTP 对位的 ∪ 无对位的, 一个不多一个不少")
    void exactlySevenToolsCarryTheRulingCodes() {
        List<String> carriers = ToolRbacEnforcer.TOOL_PERMISSION_MAP.entrySet().stream()
                .filter(e -> e.getValue().equals(RULING_CODES))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();

        Set<String> expected = new java.util.LinkedHashSet<>(TOOL_TO_HTTP_METHOD.keySet());
        expected.addAll(RULING_CODED_WITHOUT_HTTP_COUNTERPART);

        assertThat(carriers)
                .as("⛔ 多出来的那个是谁? 少了的那个又被谁删了? 两种都得回去读 owner 裁定, "
                        + "⛔ 不要直接改这条断言的数字")
                .containsExactlyInAnyOrderElementsOf(expected);
    }
    // ================================================================ 第二把尺子 (完备性)

    /**
     * <b>登记不挂门</b>的工具 → 理由。⛔ 这不是豁免清单，是<b>要求写理由</b>的清单:
     * 下面那条完备性断言只接受「挂了裁定四码」或「在这张表里且有理由」两种状态。
     *
     * <p>🔴 为什么需要它: 第一把尺子 (「谁调 {@code CustomerService} 的名单方法」) 漏掉了
     * 三个直接走 {@code CustomerRepository} 的工具 —— 其中 {@code customer_priority_query}
     * 一次能拉 200 个客户的<b>姓名 + 编码 + 联系人 + 联系电话</b>。
     * 一把尺子不够，所以完备性断言用<b>两把尺子的并集</b>。</p>
     */
    /** owner 裁定的四个权限码。⛔ 期望值仍从 controller 反射读 (见 aiPathCodesMatchTheHttpGateVerbatim); 这里只用于分类判据。 */
    private static final Set<String> RULING_CODES =
            Set.of("sales:read_write", "sales:read", "finance:read_write", "finance:read");

    private static final Map<String, String> REGISTERED_NOT_GATED = Map.of(
            "customer_stats",
            "默认分支是 getOverallCustomerStatistics + 两张分布表 —— 那三个 HTTP 端点按档 C 留开; "
                    + "挂四码会让 AI 面比 HTTP 面更严, 破坏「两面逐字相同」不变式",
            "customer_tracking_recent_query",
            "必须先有 customerId 才能反查出一个名字 (⛔ 不能枚举); 主载荷是跟进记录, 属另一个域",
            "customer_quality_standard",
            "必须先有 customerId (⛔ 不能枚举), 只回该客户 name/type/industry + 质检标准; "
                    + "使用者是 quality_inspector (L1 sales='-' finance='-'), 挂四码会打断质检取标准这条业务链, "
                    + "而没有任何裁定支持那个收紧",
            "customer_revenue_trend",
            "不传 customerId 时只回按月聚合的 series 且 customerName=null (⛔ 不能枚举); "
                    + "传了 customerId 才反查出那一个名字 —— 与 customer_stats 同形",
            "batch_trace_by_customer_date",
            "必须先有 customerId (findByIdAndFactoryId 单条, ⛔ 不能枚举); 食安追溯域, "
                    + "使用者是质量角色, 挂销售/财务码会打断追溯链",
            "customer_notify_batch",
            "召回通知: 客户集合来自**该批次的出货记录**而不是客户表枚举, 只回 customerCount + sampleNames; "
                    + "⛔ 挂销售/财务码会打断食安召回这条安全关键链",
            "sku_assemble",
            "泄漏本身已在工具里改掉 (原 availableCustomers=全部活跃客户名字 -> 现 activeCustomerCount); "
                    + "给整个工具挂销售/财务码会改变「谁能建 SKU」, 裁定不管那件事");

    /**
     * 🔴🔴 完备性闸 —— <b>两把尺子的并集</b>。
     *
     * <p>尺子二用<b>字节码</b>而不是源码文本: 一个工具想拿到客户数据，必须声明一个
     * {@code CustomerService} 或 {@code CustomerRepository} 字段。这比 grep
     * {@code CustomerDTO} 更宽 (能抓到只用 {@code Customer} 实体的那三个),
     * 也不受源码路径 / 行尾 / 编码影响。</p>
     *
     * <p>⚠️ 这条断言自己会不会瞎: 会 —— 如果扫描扫到 0 个类, 或某个类实例化失败拿不到
     * 工具名, 它就悄悄少量了一部分。所以下面**先断言扫描到的类数量 &gt; 0**、
     * **实例化失败清单为空**, 再断言分类结果 (硬约束 9: 「没发生」要配一个「一定会发生」的读数)。</p>
     */
    @Test
    @DisplayName("🔴 完备性(两把尺子并集): 凡声明 CustomerService/CustomerRepository 字段的 AI 工具, 要么挂裁定四码, 要么登记并写理由")
    void everyToolTouchingCustomerDataIsGatedOrRegistered() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ToolExecutor.class));

        List<Class<?>> toolClasses = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.cretas.aims.ai.tool")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (!Modifier.isAbstract(c.getModifiers())) {
                toolClasses.add(c);
            }
        }

        // 「一定会发生」的读数 ①: 扫描本身得扫到东西, 否则下面全是空集恒真。
        assertThat(toolClasses)
                .as("classpath 扫描一个 ToolExecutor 都没找到 ⇒ 这条闸在守空气, 先修仪器")
                .hasSizeGreaterThan(100);

        List<String> touchesCustomerData = new ArrayList<>();
        List<String> uninstantiable = new ArrayList<>();
        for (Class<?> c : toolClasses) {
            boolean touches = false;
            for (Field f : c.getDeclaredFields()) {
                String t = f.getType().getSimpleName();
                if ("CustomerService".equals(t) || "CustomerRepository".equals(t)) {
                    touches = true;
                    break;
                }
            }
            if (!touches) {
                continue;
            }
            try {
                Object instance = c.getDeclaredConstructor().newInstance();
                touchesCustomerData.add(((ToolExecutor) instance).getToolName());
            } catch (ReflectiveOperationException e) {
                // ⛔ 不 continue 了事: 拿不到名字 = 这一个没量到, 必须计数并贴出来 (形态 C′)
                uninstantiable.add(c.getSimpleName() + " (" + e.getClass().getSimpleName() + ")");
            }
        }

        // 「一定会发生」的读数 ②: 拿不到名字的类必须为 0, 否则这条闸对它们是瞎的。
        assertThat(uninstantiable)
                .as("这些工具类无法用无参构造实例化 ⇒ 取不到 getToolName(), 本闸对它们失明。"
                        + "请改成字段注入, 或在本测试里为它们补一条取名路径")
                .isEmpty();

        // 「一定会发生」的读数 ③: 尺子二至少要量出已知的那几个, 否则说明字段判据写错了。
        assertThat(touchesCustomerData)
                .as("尺子二 (声明 CustomerService/CustomerRepository 字段) 必须至少覆盖这些已知工具; "
                        + "量不到它们说明判据本身坏了, 后面的「全都登记了」是假绿")
                .contains("customer_list", "customer_active", "customer_priority_query",
                        "customer_quality_standard", "sku_assemble",
                        "call_record_followup_pending", "wechat_record_recent_query",
                        "customer_notify_batch");

        List<String> unaccounted = new ArrayList<>();
        for (String toolName : touchesCustomerData) {
            Set<String> codes = ToolRbacEnforcer.TOOL_PERMISSION_MAP.get(toolName);
            // ⚠️ 判据是「码集是裁定四码的**非空子集**」而不是「恰好等于」——
            //    写工具挂的是 {sales:read_write} / {sales:read_write, finance:read_write},
            //    它们比四码**更窄**, 同样保证只有裁定那几类角色过得去。
            //    ⛔ 反过来, 挂 production:* 之类的码不算「已登记」: 那种门 production_manager 照样过。
            boolean gated = codes != null && !codes.isEmpty() && RULING_CODES.containsAll(codes);
            boolean registered = REGISTERED_NOT_GATED.containsKey(toolName);
            if (!gated && !registered) {
                unaccounted.add(toolName);
            }
        }

        assertThat(unaccounted)
                .as("这些 AI 工具能拿到客户数据, 却既没挂 owner 裁定的四个权限码, "
                        + "也没在 REGISTERED_NOT_GATED 里登记理由。⛔ 二选一, 不许留空 —— "
                        + "「登记」也要写清楚它为什么不构成名单泄漏")
                .isEmpty();
    }

    /** 登记清单里的理由不许是空话 —— 空字符串等于没登记。 */
    @Test
    @DisplayName("登记不挂门的每一条都必须带理由 (⛔ 空理由 = 没登记)")
    void everyRegisteredExemptionCarriesAReason() {
        assertThat(REGISTERED_NOT_GATED.values())
                .allSatisfy(reason -> assertThat(reason).isNotBlank().hasSizeGreaterThan(20));
        assertThat(REGISTERED_NOT_GATED.keySet())
                .as("⛔ 登记清单与挂门清单不许有交集 —— 一个工具只能是其中一种状态")
                .doesNotContainAnyElementsOf(TOOL_TO_HTTP_METHOD.keySet());
        assertThat(REGISTERED_NOT_GATED.keySet())
                .as("⛔ 同上, 对没有 HTTP 对位的那几个也成立")
                .doesNotContainAnyElementsOf(RULING_CODED_WITHOUT_HTTP_COUNTERPART);
    }

    /**
     * {@code customer_priority_query} 没有 HTTP 对位方法 (workdesk 专用)，
     * 所以它不在 {@link #TOOL_TO_HTTP_METHOD} 里 —— 单独钉它的权限码。
     *
     * <p>🔴 它是第二把尺子量出来的: 直接走 {@code CustomerRepository}，一次最多 200 条，
     * 每条含 customerName + customerCode + contactPerson + <b>contactPhone</b>。</p>
     */
    @Test
    @DisplayName("🔴 customer_priority_query (无 HTTP 对位, 第二把尺子量出) 挂裁定四码")
    void workdeskPriorityQueryCarriesTheRulingCodes() {
        assertThat(ToolRbacEnforcer.TOOL_PERMISSION_MAP.get("customer_priority_query"))
                .as("它一次能拉 200 个客户的姓名/编码/联系人/联系电话 —— 比 /active 还多两列联系方式")
                .isEqualTo(Set.of("sales:read_write", "sales:read",
                        "finance:read_write", "finance:read"));
    }
}

