package com.cretas.aims.service.intent;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.config.AiFrontlineIntentGrant;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 一线角色 AI 写操作开放名单 (owner 2026-10-06: 先收窄, 管理端可开放)。
 *
 * <p>一线角色在 AI 里<b>查询不受限</b>; <b>会改数据的操作</b> (与 W0 写确认同一判据:
 * {@link WriteGuardService#isWriteTool} 或 {@link WriteGuardService#isWriteIntent}) 只能办理:
 * <ol>
 *   <li>{@link #DEFAULT_OPEN} 里的日常工作 (写在代码里, 管理端关不掉), 或</li>
 *   <li>本工厂管理员在 web-admin「一线角色 AI 操作权限」开放过的 (表 ai_frontline_intent_grants)。</li>
 * </ol>
 * 其它角色不受本类影响。本类只<b>收窄</b>: 放行仍要过原有的意图权限、工具权限、写确认。
 *
 * <p>读开放记录失败时按「只有默认清单开放」处理 (fail-closed), 不会因为库抖一下就放行。
 */
@Slf4j
@Service
public class FrontlineAiWriteGrantService {

    /** 一线角色及其显示名 (管理端列顺序)。 */
    public static final Map<String, String> FRONTLINE_ROLES;
    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("operator", "操作员");
        m.put("yield_operator", "出成率录入员");
        m.put("warehouse_worker", "仓库员");
        m.put("quality_inspector", "质检员");
        FRONTLINE_ROLES = java.util.Collections.unmodifiableMap(m);
    }

    private static final Set<String> SELF_SERVICE = Set.of("CLOCK_IN", "CLOCK_OUT", "HR_LEAVE_SUBMIT");
    private static final Set<String> PRODUCTION_LINE = union(SELF_SERVICE,
            Set.of("WORK_PROCESS_TASK_START", "WORK_PROCESS_TASK_COMPLETE"));

    /**
     * 默认开放的日常工作 —— 一线角色每天本来就在做、App 里本来就能做的写操作。
     * MATERIAL_BATCH_CREATE 不在此列: 它的执行不带来源单据, 仓库「无单不可建库存」必拒, 开放了也办不成 (收货走 RECEIVE_CONFIRM_CREATE)。
     * ⚠️ 改这里等于改所有工厂的默认权限; 想给某个工厂多开, 用管理端, 不要改这里。
     */
    public static final Map<String, Set<String>> DEFAULT_OPEN = Map.of(
            "operator", PRODUCTION_LINE,
            "yield_operator", PRODUCTION_LINE,
            "warehouse_worker", union(SELF_SERVICE, Set.of(
                    "RECEIVE_CONFIRM_CREATE", "RECEIVE_WITH_LIMIT",
                    "MATERIAL_BATCH_USE", "REUSABLE_CONTAINER_SHIP_OUT", "REUSABLE_CONTAINER_RETURN_IN")),
            "quality_inspector", union(SELF_SERVICE, Set.of(
                    "QUALITY_CHECK_CREATE", "QUALITY_DEFECT_RECORD", "SSOP_EXECUTE_COMPLETE", "FOOD_SAMPLE_CREATE")));

    /** 开放记录的缓存有效期。管理端改动会立即清本工厂的缓存, 这个时长只兜「别的进程改了库」。 */
    static final long CACHE_TTL_MILLIS = 60_000L;

    private final AiFrontlineIntentGrantRepository repository;
    private final WriteGuardService writeGuardService;
    private final IntentConfigManagementService configService;
    private final ToolRegistry toolRegistry;

    private final Map<String, CachedGrants> cache = new ConcurrentHashMap<>();

    private record CachedGrants(Set<String> keys, long loadedAt) {}

    @Autowired
    public FrontlineAiWriteGrantService(AiFrontlineIntentGrantRepository repository,
                                        WriteGuardService writeGuardService,
                                        @Lazy IntentConfigManagementService configService,
                                        @Lazy ToolRegistry toolRegistry) {
        this.repository = repository;
        this.writeGuardService = writeGuardService;
        this.configService = configService;
        this.toolRegistry = toolRegistry;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        java.util.Set<String> out = new java.util.HashSet<>(a);
        out.addAll(b);
        return Set.copyOf(out);
    }

    private static String key(String role, String intentCode) {
        return role + "|" + intentCode;
    }

    public static boolean isFrontline(String role) {
        return role != null && FRONTLINE_ROLES.containsKey(role);
    }

    public static boolean isDefaultOpen(String role, String intentCode) {
        return intentCode != null && DEFAULT_OPEN.getOrDefault(role, Set.of()).contains(intentCode);
    }

    /** 与 ToolDispatchService 里 W0 写确认同一判据。tool 为 null 时只看意图。 */
    public boolean isWrite(ToolExecutor tool, AIIntentConfig intent) {
        return (tool != null && writeGuardService.isWriteTool(tool))
                || (intent != null && writeGuardService.isWriteIntent(intent));
    }

    /**
     * 执行前的判定。
     *
     * @return 空 = 放行 (不是一线角色 / 不是写操作 / 已开放); 非空 = 拒绝, 值是给用户看的说明
     */
    public Optional<String> denyReason(String factoryId, String userRole, ToolExecutor tool, AIIntentConfig intent) {
        if (!isFrontline(userRole) || !isWrite(tool, intent)) {
            return Optional.empty();
        }
        String intentCode = intent != null ? intent.getIntentCode() : null;
        if (isDefaultOpen(userRole, intentCode) || isGranted(factoryId, userRole, intentCode)) {
            return Optional.empty();
        }
        String name = intent != null && intent.getIntentName() != null ? intent.getIntentName()
                : (tool != null ? tool.getToolName() : "这个操作");
        log.info("一线 AI 写操作未开放: factoryId={}, role={}, intent={}", factoryId, userRole, intentCode);
        return Optional.of(String.format(
                "「%s」还没有对%s开放 AI 办理。你可以在 App 对应页面办理；"
                        + "如需在 AI 里办理，请工厂管理员在管理端「一线角色 AI 操作权限」里开放。",
                name, FRONTLINE_ROLES.get(userRole)));
    }

    /** 本工厂是否开放过 (不含默认清单)。读失败按未开放处理。 */
    public boolean isGranted(String factoryId, String role, String intentCode) {
        if (factoryId == null || role == null || intentCode == null) {
            return false;
        }
        return grantKeys(factoryId).contains(key(role, intentCode));
    }

    private Set<String> grantKeys(String factoryId) {
        long now = System.currentTimeMillis();
        CachedGrants cached = cache.get(factoryId);
        if (cached != null && now - cached.loadedAt() < CACHE_TTL_MILLIS) {
            return cached.keys();
        }
        try {
            Set<String> keys = repository.findByFactoryIdAndRevokedAtIsNull(factoryId).stream()
                    .map(g -> key(g.getRoleCode(), g.getIntentCode()))
                    .collect(Collectors.toUnmodifiableSet());
            cache.put(factoryId, new CachedGrants(keys, now));
            return keys;
        } catch (Exception e) {
            log.warn("一线 AI 开放记录读取失败, 本次只按默认清单放行: factoryId={}, err={}", factoryId, e.getMessage());
            return Set.of();
        }
    }

    void evict(String factoryId) {
        cache.remove(factoryId);
    }

    // ==================== 管理端 ====================

    /** 管理端列表的一行。 */
    public record GrantItem(String intentCode, String intentName, String category, String sensitivityLevel,
                            String description, List<String> defaultOpenRoles, List<String> grantedRoles) {}

    public record RoleItem(String code, String name) {}

    public record GrantView(List<RoleItem> roles, List<GrantItem> intents) {}

    /** 本工厂可见、绑定了已注册工具、且判为写操作的意图, 按名称排序。 */
    public GrantView list(String factoryId) {
        Set<String> keys = loadFresh(factoryId);
        List<GrantItem> items = new ArrayList<>();
        for (AIIntentConfig intent : writeIntents(factoryId)) {
            items.add(item(intent, keys));
        }
        items.sort(Comparator.comparing(GrantItem::intentName, Comparator.nullsLast(Comparator.naturalOrder())));
        List<RoleItem> roles = FRONTLINE_ROLES.entrySet().stream()
                .map(e -> new RoleItem(e.getKey(), e.getValue())).toList();
        return new GrantView(roles, items);
    }

    public GrantItem grant(String factoryId, String role, String intentCode, Long operatorUserId) {
        AIIntentConfig intent = requireListable(factoryId, role, intentCode);
        if (!isDefaultOpen(role, intentCode)
                && repository.findByFactoryIdAndRoleCodeAndIntentCodeAndRevokedAtIsNull(factoryId, role, intentCode).isEmpty()) {
            repository.save(AiFrontlineIntentGrant.builder()
                    .factoryId(factoryId).roleCode(role).intentCode(intentCode)
                    .grantedBy(operatorUserId).grantedAt(LocalDateTime.now()).build());
            log.info("一线 AI 写操作开放: factoryId={}, role={}, intent={}, by={}", factoryId, role, intentCode, operatorUserId);
        }
        evict(factoryId);
        return item(intent, loadFresh(factoryId));
    }

    public GrantItem revoke(String factoryId, String role, String intentCode, Long operatorUserId) {
        AIIntentConfig intent = requireListable(factoryId, role, intentCode);
        if (isDefaultOpen(role, intentCode)) {
            throw new BusinessException(400, "这是" + FRONTLINE_ROLES.get(role) + "的日常工作，默认开放，不能在这里关闭。");
        }
        repository.findByFactoryIdAndRoleCodeAndIntentCodeAndRevokedAtIsNull(factoryId, role, intentCode)
                .ifPresent(g -> {
                    g.setRevokedAt(LocalDateTime.now());
                    g.setRevokedBy(operatorUserId);
                    repository.save(g);
                    log.info("一线 AI 写操作关闭: factoryId={}, role={}, intent={}, by={}", factoryId, role, intentCode, operatorUserId);
                });
        evict(factoryId);
        return item(intent, loadFresh(factoryId));
    }

    private AIIntentConfig requireListable(String factoryId, String role, String intentCode) {
        if (!isFrontline(role)) {
            throw new BusinessException(400, "只能为一线角色 (操作员 / 出成率录入员 / 仓库员 / 质检员) 设置 AI 操作权限。");
        }
        return writeIntents(factoryId).stream()
                .filter(i -> intentCode != null && intentCode.equals(i.getIntentCode()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(404, "找不到这个 AI 写操作: " + intentCode));
    }

    private Set<String> loadFresh(String factoryId) {
        evict(factoryId);
        return grantKeys(factoryId);
    }

    private List<AIIntentConfig> writeIntents(String factoryId) {
        List<AIIntentConfig> out = new ArrayList<>();
        for (AIIntentConfig intent : configService.getAllIntents(factoryId)) {
            if (intent == null || intent.getIntentCode() == null
                    || !Boolean.TRUE.equals(intent.getIsActive()) || intent.getDeletedAt() != null
                    || "RESTAURANT".equalsIgnoreCase(intent.getBusinessType())
                    || intent.getToolName() == null || intent.getToolName().isBlank()) {
                continue;
            }
            Optional<ToolExecutor> tool = toolRegistry.getExecutor(intent.getToolName());
            if (tool.isPresent() && isWrite(tool.get(), intent)) {
                out.add(intent);
            }
        }
        return out;
    }

    private static GrantItem item(AIIntentConfig intent, Set<String> grantKeys) {
        List<String> defaults = new ArrayList<>();
        List<String> granted = new ArrayList<>();
        for (String role : FRONTLINE_ROLES.keySet()) {
            if (isDefaultOpen(role, intent.getIntentCode())) {
                defaults.add(role);
            } else if (grantKeys.contains(key(role, intent.getIntentCode()))) {
                granted.add(role);
            }
        }
        return new GrantItem(intent.getIntentCode(), intent.getIntentName(), intent.getIntentCategory(),
                intent.getSensitivityLevel(), intent.getDescription(), defaults, granted);
    }
}
