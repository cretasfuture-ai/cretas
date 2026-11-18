package com.cretas.aims.service.crew;

import com.cretas.aims.ai.capability.FactoryBusinessTypeResolver;
import com.cretas.aims.ai.capability.FactoryCapabilityPack;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.crew.DigitalCrewMemberSetting;
import com.cretas.aims.entity.crew.DigitalCrewRoleMapping;
import com.cretas.aims.entity.crew.DigitalCrewSettingLog;
import com.cretas.aims.entity.crew.DigitalCrewWorkItem;
import com.cretas.aims.entity.crew.DigitalCrewWorkItem.Status;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.entity.crew.DigitalCrewDelegationLock;
import com.cretas.aims.repository.crew.DigitalCrewDelegationLockRepository;
import com.cretas.aims.repository.crew.DigitalCrewMemberSettingRepository;
import com.cretas.aims.repository.crew.DigitalCrewRoleMappingRepository;
import com.cretas.aims.repository.crew.DigitalCrewSettingLogRepository;
import com.cretas.aims.repository.crew.DigitalCrewWorkItemRepository;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.crew.DigitalCrewCatalog.Member;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;

/**
 * 迁移 P8「数字班组」(MIGRATION-PLAN 2.7): 按厂启用 / 岗位映射 / 自由对话 / 等我确认 / 额度四态。
 *
 * <p>守住的四条 (2.7):
 * <ol>
 *   <li><b>确认在服务端按登录人校验</b>: 确认人从会话取 ({@link Actor}), 必须持有事项声明的写权限码
 *       ({@link #requireConfirmPermission}); ⛔ 不信页面传的权限或姓名。平台账号不能确认工厂的事。</li>
 *   <li><b>额度四态 (R29)</b>: 交办不扣; 确认执行后回读预览凭据为 CONFIRMED 才记已办成、扣 1, 日期是北京时间真实日期;
 *       「不办」记没办成; 异常 / 确认链路说结果不明 记结果不明。</li>
 *   <li><b>写操作复用 #1632</b>: 预览 / 确认都走 {@link IntentExecutorService#preview} / {@link IntentExecutorService#confirm}
 *       (确认前比对改前值、异常记结果不明都在那条路径里), 这里不另做一套写入。凭据绑定确认人自己
 *       (预览令牌认领要求 userId 一致), 所以确认人要先按自己身份预览, 事项只存凭据摘要。</li>
 *   <li><b>自由对话</b>: 走同一个意图识别 (READ 模式); 能力包匹配 ({@link FactoryCapabilityPackSelector}) 结果随回答返回;
 *       识别出的意图按其权限码的模块过滤 —— 不归这位员工管就不返回数据, 指给该管的那位。</li>
 * </ol>
 */
@Slf4j
@Service
public class DigitalCrewService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final int REASON_MIN = 2;
    public static final int REASON_MAX = 200;
    public static final int INPUT_MAX = 256;
    static final Set<String> SUCCESS = Set.of("SUCCESS", "COMPLETED");
    /** 凭据处于这些状态 = 确定没写入, 事项回到已交办可重新预览。 */
    static final Set<TokenStatus> DEFINITELY_NOT_WRITTEN = EnumSet.of(
            TokenStatus.PENDING, TokenStatus.FAILED, TokenStatus.CANCELLED, TokenStatus.EXPIRED);
    /** 凭据处于这些状态 = 再也不会被认领执行 (终态且没写入)。 */
    static final Set<TokenStatus> TERMINAL_NOT_WRITTEN = EnumSet.of(
            TokenStatus.FAILED, TokenStatus.CANCELLED, TokenStatus.EXPIRED);
    /**
     * 「办理中」超过这个时长还没有结果 = 确认请求已经断了 (进程重启 / 超时 / 结算事务失败), 对账回收。
     * 必须比预览凭据的在途对账窗口 ({@link PreviewTokenService#executingStaleMinutes()}, 可配置) 长, 先让凭据自己定态:
     * 取「凭据窗口 + 5 分钟」, 且不少于 15 分钟 ({@link #confirmingStale()})。
     */
    public static final long CONFIRMING_STALE_MARGIN_MINUTES = 5;
    public static final long CONFIRMING_STALE_FLOOR_MINUTES = 15;
    static final int RECORDS_LIMIT = 300;
    /**
     * 每人同时挂着的「已交办」上限: 交办门槛是任一读权限 (#1771 复审 中-B), 不设上限时一个人就能把真正的待确认挤出去。
     * 超了先处理 (确认 / 不办) 再交办新的。
     */
    public static final int OPEN_DELEGATIONS_PER_USER = 20;
    public static final int INBOX_PAGE_SIZE_DEFAULT = 50;
    /**
     * 测试接缝 (默认什么都不做): 并发上限测试在「数完件数」之后插一段等待, 把竞态窗口放宽到看得见,
     * 证明交办锁真的在起作用 (#1771 复审 低-1)。⛔ 生产代码不设置它。
     * 包级可见 (#1771 第 3 轮 N1): 不作为 public 接缝暴露给任何调用方; 测试经同包的
     * {@code DigitalCrewServiceTestSeams} (只在 src/test) 设置。
     */
    volatile Runnable afterOpenCountHook = () -> { };
    public static final int INBOX_PAGE_SIZE_MAX = 100;

    /** 会话里取出的操作人: 类型 / id / 角色都来自 JWT, ⛔ 不来自请求体。 */
    public record Actor(AuthenticatedActorType type, Long id, String role) {
        boolean factoryUser() {
            return type == AuthenticatedActorType.FACTORY_USER;
        }
    }

    private final DigitalCrewWorkItemRepository items;
    private final DigitalCrewMemberSettingRepository memberSettings;
    private final DigitalCrewRoleMappingRepository roleMappings;
    private final DigitalCrewSettingLogRepository settingLogs;
    private final DigitalCrewDelegationLockRepository delegationLocks;
    private final UserRepository users;
    private final PermissionService permissions;
    private final IntentExecutorService intents;
    private final AIIntentService intentConfigs;
    private final PreviewTokenService previewTokens;
    private final FactoryCapabilityPackSelector packSelector;
    private final FactoryBusinessTypeResolver businessTypes;
    private final DigitalCrewExecutionGate executionGate;
    private final TransactionTemplate tx;
    private final Supplier<LocalDateTime> clock;

    public DigitalCrewService(DigitalCrewWorkItemRepository items,
                              DigitalCrewMemberSettingRepository memberSettings,
                              DigitalCrewRoleMappingRepository roleMappings,
                              DigitalCrewSettingLogRepository settingLogs,
                              DigitalCrewDelegationLockRepository delegationLocks,
                              UserRepository users,
                              PermissionService permissions,
                              IntentExecutorService intents,
                              AIIntentService intentConfigs,
                              PreviewTokenService previewTokens,
                              FactoryCapabilityPackSelector packSelector,
                              FactoryBusinessTypeResolver businessTypes,
                              DigitalCrewExecutionGate executionGate,
                              PlatformTransactionManager transactionManager) {
        this.items = items;
        this.memberSettings = memberSettings;
        this.roleMappings = roleMappings;
        this.settingLogs = settingLogs;
        this.delegationLocks = delegationLocks;
        this.users = users;
        this.permissions = permissions;
        this.intents = intents;
        this.intentConfigs = intentConfigs;
        this.previewTokens = previewTokens;
        this.packSelector = packSelector;
        this.businessTypes = businessTypes;
        this.executionGate = executionGate;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = () -> LocalDateTime.now(ZONE);
    }

    // ==================== 视图 ====================

    public record MemberView(String key, String name, String shortName, String duty, boolean enabled,
                             List<String> modules, Map<String, String> moduleLevels, String level,
                             List<DigitalCrewCatalog.Capability> capabilities, List<String> workdesks) {
    }

    public record ItemView(Long id, String memberKey, String memberName, String intentCode, String intentName,
                           String title, String userInput, String module, String requiredPermission,
                           String status, String statusLabel, int quotaCost, String previewSummary,
                           String createdByName, LocalDateTime createdAt, String confirmedByName,
                           LocalDateTime confirmedAt, LocalDateTime resolvedAt, LocalDate resolvedDate,
                           String resultStatus, String resultNote, String evidence,
                           boolean previewedByMe, boolean canConfirm) {
    }

    /** 等我确认: mine 是这一页; totalMine = 我能确认的总件数; hiddenCount = 按权限不显示的总件数 (都在库里计数, 不截断)。 */
    public record InboxView(List<ItemView> mine, int hiddenCount, List<String> hiddenModules, long totalMine,
                            int page, int size) {
    }

    public record SettingLogView(LocalDateTime at, String by, String kind, String reason, String before, String after) {
    }

    public record SettingsView(List<MemberView> members, Map<String, String> roleMapping,
                               Map<String, String> roleLabels, List<SettingLogView> logs, boolean canEdit) {
    }

    public record AskView(String kind, String memberKey, String status, String text, String intentCode,
                          String intentName, String capabilityPack, String ownerKey, ItemView item,
                          List<String> clarificationChoices, String sessionId) {
    }

    public record PreviewView(ItemView item, IntentExecuteResponse preview) {
    }

    public record MemberCount(String memberKey, String memberName, long delegated, long confirming, long done,
                              long notDone, long outcomeUnknown, long quota) {
    }

    public record LedgerView(LocalDate today, long quotaToday, long quotaThisMonth, long quotaTotal,
                             long delegated, long done, List<MemberCount> perMember, List<ItemView> records) {
    }

    // ==================== 员工与配置 ====================

    public List<MemberView> members(String factoryId, Actor actor) {
        Map<String, Boolean> enabled = enabledMap(factoryId);
        Map<String, String> levels = levels(actor);
        return DigitalCrewCatalog.MEMBERS.stream().map(m -> memberView(m, enabled, levels)).toList();
    }

    public SettingsView settings(String factoryId, Actor actor) {
        Map<String, String> mapping = new LinkedHashMap<>(DigitalCrewCatalog.DEFAULT_ROLE_MAPPING);
        for (DigitalCrewRoleMapping r : roleMappings.findByFactoryId(factoryId)) {
            mapping.put(r.getFrontlineRole(), r.getMemberKey());
        }
        List<SettingLogView> logs = settingLogs.findByFactoryIdOrderByCreatedAtDescIdDesc(factoryId, PageRequest.of(0, 50))
                .stream().map(l -> new SettingLogView(l.getCreatedAt(), l.getActorName(), l.getChangeKind().name(),
                        l.getReason(), l.getBeforeJson(), l.getAfterJson())).toList();
        return new SettingsView(members(factoryId, actor), mapping, DigitalCrewCatalog.FRONTLINE_ROLE_LABELS, logs,
                canEditSettings(actor));
    }

    /** 改本厂启用哪几位。要写原因; 保存后回读并留调整记录。 */
    public SettingsView updateMembers(String factoryId, Actor actor, Map<String, Boolean> enabled, String reason) {
        requireSettingsEditor(actor);
        String why = requireReason(reason);
        if (enabled == null || enabled.isEmpty()) throw new BusinessException(400, "没有要保存的启用设置");
        for (String key : enabled.keySet()) {
            if (DigitalCrewCatalog.member(key).isEmpty()) throw new BusinessException(400, "不认识的数字员工: " + key);
        }
        tx.executeWithoutResult(s -> {
            Map<String, Boolean> before = enabledMap(factoryId);
            Map<String, DigitalCrewMemberSetting> rows = new HashMap<>();
            memberSettings.findByFactoryId(factoryId).forEach(r -> rows.put(r.getMemberKey(), r));
            String name = actorName(actor);
            for (Map.Entry<String, Boolean> e : enabled.entrySet()) {
                DigitalCrewMemberSetting row = rows.computeIfAbsent(e.getKey(), k -> {
                    DigitalCrewMemberSetting n = new DigitalCrewMemberSetting();
                    n.setFactoryId(factoryId);
                    n.setMemberKey(k);
                    return n;
                });
                row.setEnabled(Boolean.TRUE.equals(e.getValue()));
                row.setUpdatedByUserId(actor.id());
                row.setUpdatedByName(name);
                memberSettings.save(row);
            }
            Map<String, Boolean> after = new LinkedHashMap<>(before);
            enabled.forEach((k, v) -> after.put(k, Boolean.TRUE.equals(v)));
            log(factoryId, actor, DigitalCrewSettingLog.ChangeKind.MEMBERS, why, before.toString(), after.toString());
        });
        return settings(factoryId, actor);
    }

    /** 改一线岗位 → 数字员工的对应 (空 = 该岗位手机端不给入口)。 */
    public SettingsView updateRoleMapping(String factoryId, Actor actor, Map<String, String> mapping, String reason) {
        requireSettingsEditor(actor);
        String why = requireReason(reason);
        if (mapping == null || mapping.isEmpty()) throw new BusinessException(400, "没有要保存的岗位对应");
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            if (!DigitalCrewCatalog.FRONTLINE_ROLE_LABELS.containsKey(e.getKey())) {
                throw new BusinessException(400, "不是一线岗位: " + e.getKey());
            }
            String v = e.getValue();
            if (v != null && !v.isBlank() && DigitalCrewCatalog.member(v).isEmpty()) {
                throw new BusinessException(400, "不认识的数字员工: " + v);
            }
        }
        tx.executeWithoutResult(s -> {
            Map<String, String> before = new LinkedHashMap<>(settings(factoryId, actor).roleMapping());
            Map<String, DigitalCrewRoleMapping> rows = new HashMap<>();
            roleMappings.findByFactoryId(factoryId).forEach(r -> rows.put(r.getFrontlineRole(), r));
            String name = actorName(actor);
            Map<String, String> after = new LinkedHashMap<>(before);
            for (Map.Entry<String, String> e : mapping.entrySet()) {
                DigitalCrewRoleMapping row = rows.computeIfAbsent(e.getKey(), k -> {
                    DigitalCrewRoleMapping n = new DigitalCrewRoleMapping();
                    n.setFactoryId(factoryId);
                    n.setFrontlineRole(k);
                    return n;
                });
                String v = e.getValue() == null || e.getValue().isBlank() ? null : e.getValue();
                row.setMemberKey(v);
                row.setUpdatedByUserId(actor.id());
                row.setUpdatedByName(name);
                roleMappings.save(row);
                after.put(e.getKey(), v);
            }
            log(factoryId, actor, DigitalCrewSettingLog.ChangeKind.ROLE_MAPPING, why, before.toString(), after.toString());
        });
        return settings(factoryId, actor);
    }

    // ==================== 自由对话 ====================

    /**
     * 对一位数字员工说一句话。查询直接返回; 写操作不执行, 记一件「已交办」进等我确认 (DECISIONS 第十四节)。
     */
    public AskView ask(String factoryId, Actor actor, String memberKey, String text, String sessionId) {
        Member member = requireEnabledMember(factoryId, memberKey);
        String input = text == null ? "" : text.strip();
        if (input.isEmpty()) throw new BusinessException(400, "先说要办什么或查什么");
        if (input.length() > INPUT_MAX || input.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(400, "一句话最多 " + INPUT_MAX + " 个字，且不能含控制字符");
        }
        if (!actor.factoryUser()) throw new BusinessException(403, "平台账号不能以工厂身份和数字员工对话");
        String pack = capabilityPack(factoryId, actor, input);

        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .userInput(input).sessionId(sessionId == null || sessionId.isBlank() ? null : sessionId)
                .intentCode(DigitalCrewCatalog.quickIntentFor(memberKey, input).orElse(null))
                .mode("READ").build();
        IntentExecuteResponse resp = intents.execute(factoryId, req, actor.id(), actor.role());
        if (resp == null) throw new BusinessException(502, "没有收到识别结果，没有执行任何写操作，请重试");
        com.cretas.aims.service.execution.CustomerTextSanitizer.sanitize(resp);

        AIIntentConfig intent = resp.getIntentCode() == null ? null
                : intentConfigs.getIntentByCode(factoryId, resp.getIntentCode()).orElse(null);
        String requiredPermission = intent == null ? null : intent.getRequiredPermission();
        // 逗号 = 任一即可 (与意图权限门 IntentPermissionGate 同一拆法); 模块取这位员工管得着的那一个
        String module = DigitalCrewCatalog.moduleOf(requiredPermission, member);
        if (module != null && !member.modules().contains(module)) {
            String owner = DigitalCrewCatalog.ownerOf(module).map(Member::key).orElse(null);
            String ownerName = owner == null ? "其他岗位" : DigitalCrewCatalog.member(owner).map(Member::name).orElse(owner);
            return new AskView("OUT_OF_SCOPE", memberKey, resp.getStatus(),
                    "这件事（" + nz(resp.getIntentName(), resp.getIntentCode()) + "）不归" + member.name() + "管，请找「"
                            + ownerName + "」。这次没有查数据，也没有执行任何写操作。",
                    resp.getIntentCode(), resp.getIntentName(), pack, owner, null, List.of(), resp.getSessionId());
        }

        // 两种形状都是「写操作、这次不执行」, 都交办:
        //  · READ_MODE_WRITE_BLOCKED: 说话人自己有写权限, 编排器在 READ 模式下拦了写;
        //  · NO_PERMISSION + 写级权限码: 编排器先查权限、后做 READ 写拦截, 没写权限的人 (如操作员) 拿到的是这个。
        //    交给有权限的人确认正是数字班组要做的事 (DECISIONS 第二十一节 2/3); 确认仍只限有权限的人。
        boolean writeBlocked = "READ_MODE_WRITE_BLOCKED".equals(resp.getStatus());
        boolean deniedWrite = "NO_PERMISSION".equals(resp.getStatus()) && intent != null && isWriteLevel(requiredPermission);
        if (writeBlocked || deniedWrite) {
            if (requiredPermission == null || requiredPermission.isBlank()) {
                return new AskView("REFUSED", memberKey, resp.getStatus(),
                        "这件事是写操作，但系统里没有登记谁有权确认，所以不交办。没有执行任何写操作。",
                        resp.getIntentCode(), resp.getIntentName(), pack, null, null, List.of(), resp.getSessionId());
            }
            if (!isWriteLevel(requiredPermission) && hasReadLevelCode(requiredPermission)) {
                // 读写混合码 (如请假提交 hr:read,hr:read_write): 本人有读权限就能办的个人自助操作, 不代办 (#1816 L2)
                return new AskView("REFUSED", memberKey, resp.getStatus(),
                        "这件事（" + nz(resp.getIntentName(), resp.getIntentCode()) + "）是个人自助操作，请自己办，数字班组不代办。"
                                + "没有执行任何写操作。",
                        resp.getIntentCode(), resp.getIntentName(), pack, null, null, List.of(), resp.getSessionId());
            }
            if (!isWriteLevel(requiredPermission) || module == null) {
                return new AskView("REFUSED", memberKey, resp.getStatus(),
                        "这件事是写操作，但登记的权限「" + requiredPermission + "」不是写权限，没法判断谁有权确认，所以不交办。"
                                + "没有执行任何写操作。请管理员在意图配置里登记写权限。",
                        resp.getIntentCode(), resp.getIntentName(), pack, null, null, List.of(), resp.getSessionId());
            }
            ItemView item = delegate(factoryId, actor, member, input, intent, requiredPermission, module);
            String who = deniedWrite ? "你没有这项写权限，" : "";
            return new AskView("DELEGATED", memberKey, resp.getStatus(),
                    who + member.name() + "把这件事整理好了，记为「已交办」，放进「待办 › 等我确认」。"
                            + "有权限的人先看改前改后的预览、再确认才会执行。现在没有执行任何写操作，也不扣额度。",
                    resp.getIntentCode(), resp.getIntentName(), pack, null, item, List.of(), resp.getSessionId());
        }

        List<String> choices = "NEED_CLARIFICATION".equals(resp.getStatus()) && resp.getSuggestedActions() != null
                ? resp.getSuggestedActions().stream()
                .filter(a -> "SELECT_INTENT".equals(a.getActionCode()) && a.getActionName() != null)
                .map(IntentExecuteResponse.SuggestedAction::getActionName).limit(8).toList()
                : List.of();
        String answer = nz(resp.getFormattedText(), resp.getMessage());
        return new AskView("ANSWER", memberKey, resp.getStatus(), answer == null ? "" : answer, resp.getIntentCode(),
                resp.getIntentName(), pack, null, null, choices, resp.getSessionId());
    }

    private ItemView delegate(String factoryId, Actor actor, Member member, String input, AIIntentConfig intent,
                              String requiredPermission, String module) {
        User user = requireUser(actor);
        DigitalCrewWorkItem saved;
        try {
            saved = insertDelegated(factoryId, actor, member, input, intent, requiredPermission, module, user);
        } catch (DataIntegrityViolationException raced) {
            // 双击 / 重放: 库里的部分唯一索引 (同一人同一句话只能有一件已交办) 挡住了第二件, 返回先建的那件
            saved = tx.execute(s -> items.findFirstByFactoryIdAndMemberKeyAndCreatedByUserIdAndUserInputAndStatus(
                    factoryId, member.key(), actor.id(), input, Status.DELEGATED).orElseThrow(() -> raced));
        }
        return view(saved, user);
    }

    private DigitalCrewWorkItem insertDelegated(String factoryId, Actor actor, Member member, String input,
                                                AIIntentConfig intent, String requiredPermission, String module, User user) {
        String lockKey = DigitalCrewDelegationLock.key(factoryId, actor.id());
        ensureDelegationLockRow(lockKey, factoryId, actor.id());
        return tx.execute(s -> {
            // 同一个人的并发交办在这里串行: 锁到本事务结束, 后面的「数件数 → 插入」不会被别的请求插队 (#1771 复审 低-1)
            delegationLocks.lockForUpdate(lockKey, factoryId)
                    .orElseThrow(() -> new BusinessException(503, "交办锁没建上，这件没有交办，请重试"));
            Optional<DigitalCrewWorkItem> dup = items.findFirstByFactoryIdAndMemberKeyAndCreatedByUserIdAndUserInputAndStatus(
                    factoryId, member.key(), actor.id(), input, Status.DELEGATED);
            if (dup.isPresent()) return dup.get();
            long open = items.countOpenByUser(factoryId, actor.id(), Status.DELEGATED);
            afterOpenCountHook.run();
            if (open >= OPEN_DELEGATIONS_PER_USER) {
                throw new BusinessException(409, "你已经有 " + open + " 件交办还在等确认（每人最多 " + OPEN_DELEGATIONS_PER_USER
                        + " 件），这件没有交办，也没有执行任何写操作")
                        .withHint("到「数字班组 › 工作记录」看还在等确认的事，请有权限的人确认，或把不需要的点「不办」，再交办新的");
            }
            DigitalCrewWorkItem w = new DigitalCrewWorkItem();
            w.setFactoryId(factoryId);
            w.setMemberKey(member.key());
            w.setIntentCode(intent.getIntentCode());
            w.setIntentName(intent.getIntentName());
            w.setTitle(clip(nz(intent.getIntentName(), intent.getIntentCode()) + " · " + input, 200));
            w.setUserInput(input);
            w.setRequiredPermission(requiredPermission.strip());
            w.setModule(module);
            w.setStatus(Status.DELEGATED);
            w.setQuotaCost(0);
            w.setPreviewSummary(member.name() + "准备办：" + nz(intent.getIntentName(), intent.getIntentCode())
                    + "。原话：「" + input + "」。确认前会按确认人的权限重新预览改前 / 改后，看过再确认。");
            w.setCreatedByUserId(actor.id());
            w.setCreatedByName(displayName(user));
            return items.saveAndFlush(w);
        });
    }

    /** 第一次交办时建本人本厂的锁行 (单独提交); 两个请求同时建, 撞主键的那个什么都不做。 */
    private void ensureDelegationLockRow(String lockKey, String factoryId, Long userId) {
        if (delegationLocks.existsById(lockKey)) return;
        try {
            tx.executeWithoutResult(s -> {
                DigitalCrewDelegationLock l = new DigitalCrewDelegationLock();
                l.setLockKey(lockKey);
                l.setFactoryId(factoryId);
                l.setUserId(userId);
                delegationLocks.saveAndFlush(l);
            });
        } catch (DataIntegrityViolationException alreadyThere) {
            // 别的请求刚建好: 正好, 下面锁它
        }
    }

    // ==================== 等我确认 ====================

    public InboxView inbox(String factoryId, Actor actor) {
        return inbox(factoryId, actor, 0, INBOX_PAGE_SIZE_DEFAULT);
    }

    /**
     * 等我确认 (分页)。先列出本厂已交办事项里出现过的权限码, 逐个按登录人判能不能确认, 再在库里按「我能确认的码」
     * 分页取、计数 —— 件数再多也不会把我的待办挤出去 (#1771 复审 中-B), 隐藏件数也是库里的总数。
     */
    public InboxView inbox(String factoryId, Actor actor, int page, int size) {
        User user = requireUser(actor);
        reconcile(factoryId, actor);
        int p = Math.max(0, page);
        int sz = size <= 0 ? INBOX_PAGE_SIZE_DEFAULT : Math.min(size, INBOX_PAGE_SIZE_MAX);
        // 按 (权限码, 意图) 分组判: 登记码 + 执行时真生效的工具门都过得了才算「我能确认」(#1816 终审 M1)
        List<String> keys = items.findDistinctPermissionIntentKeys(factoryId, Status.DELEGATED);
        List<String> allowed = keys.stream().filter(k -> canHandle(factoryId, user, permissionOfKey(k), intentOfKey(k))).toList();
        List<String> denied = keys.stream().filter(k -> !allowed.contains(k)).toList();
        org.springframework.data.domain.Page<DigitalCrewWorkItem> mine = allowed.isEmpty()
                ? org.springframework.data.domain.Page.empty(PageRequest.of(p, sz))
                : items.findInboxPage(factoryId, Status.DELEGATED, allowed, PageRequest.of(p, sz));
        long hidden = denied.isEmpty() ? 0
                : items.countByKeys(factoryId, Status.DELEGATED, denied);
        List<String> hiddenModules = denied.isEmpty() ? List.of()
                : items.findDistinctModules(factoryId, Status.DELEGATED, denied).stream().sorted().toList();
        return new InboxView(mine.getContent().stream().map(w -> view(w, user)).toList(),
                (int) Math.min(Integer.MAX_VALUE, hidden), hiddenModules, mine.getTotalElements(), p, sz);
    }

    /** 确认人按自己身份预览 (#1632 路径)。凭据只回给确认人, 本表只记摘要和凭据行 id (不是凭据本身)。 */
    public PreviewView preview(String factoryId, Actor actor, Long id) {
        User user = requireUser(actor);
        DigitalCrewWorkItem w = requireItem(factoryId, id);
        requireConfirmPermission(user, w);
        requireNotUsedElsewhere(factoryId, id);
        w = requireItem(factoryId, id);
        if (w.getStatus() != Status.DELEGATED) {
            throw new BusinessException(409, "这件事已经" + w.getStatus().label() + "，不能再预览")
                    .withHint("到「数字班组 › 工作记录」看这件事的结果，不用再预览");
        }
        // 作废时看到的那张 (null = 没有): 落库时库里还得是它, 否则说明别人在我等预览的这段时间里预览过了
        Long expectedOld = retirePreviousToken(factoryId, w);
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .userInput(w.getUserInput()).intentCode(w.getIntentCode()).build();
        IntentExecuteResponse resp = intents.preview(factoryId, req, actor.id(), actor.role());
        if (resp == null) throw new BusinessException(502, "没有收到预览，没有执行任何写操作，请重试");
        IntentExecuteResponse.ConfirmableAction action = resp.getConfirmableAction();
        if (action != null && action.getCommandDigest() != null && action.getConfirmToken() != null) {
            Long tokenId = previewTokens.readBoundTokenId(action.getConfirmToken(), factoryId, actor.id()).orElse(null);
            if (tokenId == null) {
                // 刚铸的凭据读不回来 = 没法把它绑到这件事上, 后面的绑定校验和对账都会落空: 不放行 (fail closed)
                previewTokens.cancelToken(action.getConfirmToken(), factoryId, actor.id(), "数字班组没登记上这张凭据");
                throw new BusinessException(502, "预览没有登记上，没有执行任何写操作，请重新预览");
            }
            LocalDateTime now = clock.get();
            Integer stampedRows = tx.execute(s -> expectedOld == null
                    ? items.stampPreviewIfNoToken(id, factoryId, actor.id(), action.getCommandDigest(), tokenId, now,
                    Status.DELEGATED)
                    : items.stampPreviewReplacingToken(id, factoryId, actor.id(), action.getCommandDigest(), tokenId,
                    expectedOld, now, Status.DELEGATED));
            if (stampedRows == null || stampedRows != 1) {
                // 别人在我等预览的时候预览过 (或处理掉了) 这件事: 我这张不登记、当场作废, 前一张不被覆盖、仍被追踪 (#1771 复审 中-C)
                previewTokens.cancelPendingById(tokenId, factoryId, actor.id(), "数字班组事项 " + id + " 被别人同时预览，这张作废");
                DigitalCrewWorkItem cur = requireItem(factoryId, id);
                throw new BusinessException(409, cur.getStatus() == Status.DELEGATED
                        ? "别人刚预览过这件事，你这次的预览已作废，没有执行任何写操作"
                        : "这件事刚被别人处理了（" + cur.getStatus().label() + "），你这次的预览已作废")
                        .withHint("刷新「待办 › 等我确认」，再按你的权限重新预览");
            }
            return new PreviewView(view(requireItem(factoryId, id), user), resp);
        }
        return new PreviewView(view(w, user), resp);
    }

    /**
     * 确认执行。服务端: 会话里的人有写权限 → 事项仍已交办、凭据没在别处用掉、预览人是本人、凭据是这件事的、
     * 摘要一致 → 原子认领 → 走 #1632 确认 → 回读凭据定四态。
     */
    public ItemView confirm(String factoryId, Actor actor, Long id, ConfirmationProof proof) {
        User user = requireUser(actor);
        DigitalCrewWorkItem w = requireItem(factoryId, id);
        requireConfirmPermission(user, w);
        if (proof == null) throw new BusinessException(400, "缺少确认凭据，请先预览");
        requireNotUsedElsewhere(factoryId, id);
        w = requireItem(factoryId, id);
        if (w.getPreviewTokenId() == null) {
            // 没有登记的凭据 (没预览过, 或对账发现上一张已失效 / 作废): 拿什么凭据来都认领不到, 快速拒绝, 不进「办理中」
            throw new BusinessException(409, "这件事还没有有效的预览（或上次的预览已失效），没有执行任何写操作")
                    .withHint("在这件事上点「预览」，看过改前改后再确认");
        }
        Long presented = previewTokens.readBoundTokenId(proof.proofToken(), factoryId, actor.id()).orElse(null);
        if (!w.getPreviewTokenId().equals(presented)) {
            throw new BusinessException(409, "这张确认凭据不是这件事的预览凭据，没有执行任何写操作")
                    .withHint("在这件事上点「预览」重新生成凭据，再点确认");
        }
        String name = displayName(user);
        Long claimedTokenId = presented;
        // 认领时库里还得是我出示的这张凭据 (#1771 第 3 轮 N3): 上面的比对和认领之间若被重新预览换了凭据,
        // 认领影响 0 行、当场 409; 不比对的话会认领成功、拿旧凭据去确认, finish 又因凭据行 id 对不上不落结果,
        // 事项卡在「办理中」直到超时对账 (≥15 分钟)。
        Integer claimed = tx.execute(s -> items.claimForConfirm(id, factoryId, actor.id(), name,
                proof.commandDigest(), claimedTokenId, clock.get(), Status.DELEGATED, Status.CONFIRMING));
        if (claimed == null || claimed != 1) {
            DigitalCrewWorkItem cur = requireItem(factoryId, id);
            if (cur.getStatus() != Status.DELEGATED) {
                throw new BusinessException(409, "这件事已经" + cur.getStatus().label() + "，没有再执行")
                        .withHint("到「数字班组 › 工作记录」看这件事的结果，勿重复办理");
            }
            throw new BusinessException(409, "确认凭据与你的预览对不上（预览不是你做的，或已重新预览），请重新预览后再确认。没有执行任何写操作")
                    .withHint("在这件事上点「预览」，看过改前改后再确认");
        }

        IntentExecuteResponse resp;
        try {
            resp = intents.confirm(factoryId, proof, actor.id(), actor.role());
        } catch (RuntimeException e) {
            log.error("[DigitalCrew] confirm threw, outcome unknown: item={}, error={}", id, e.toString());
            return finish(factoryId, id, user, claimedTokenId, Status.OUTCOME_UNKNOWN, "EXCEPTION",
                    "确认执行时出错，写入可能已经提交：请先到业务页面核对，勿重复办理。", null);
        }
        String status = resp == null ? null : resp.getStatus();
        String message = resp == null ? "确认没有返回结果" : clip(nz(resp.getMessage(), status), 300);
        Optional<PreviewTokenService.BoundTokenSnapshot> snap;
        try {
            snap = previewTokens.readBoundSnapshot(proof.proofToken(), factoryId, actor.id());
        } catch (RuntimeException e) {
            snap = Optional.empty();
        }
        TokenStatus tokenStatus = snap.filter(x -> proof.commandDigest().equals(x.commandDigest()))
                .map(PreviewTokenService.BoundTokenSnapshot::status).orElse(null);

        if (status != null && SUCCESS.contains(status)) {
            if (tokenStatus == TokenStatus.CONFIRMED) {
                return finish(factoryId, id, user, claimedTokenId, Status.DONE, status, message,
                        "回读预览凭据：已确认执行" + affected(resp));
            }
            return finish(factoryId, id, user, claimedTokenId, Status.OUTCOME_UNKNOWN, status,
                    "接口说办成了，但回读凭据状态是「" + (tokenStatus == null ? "读不到" : tokenStatus.name())
                            + "」，对不上，按结果不明记录、不扣额度。请到业务页面核对。", null);
        }
        if (tokenStatus != null && DEFINITELY_NOT_WRITTEN.contains(tokenStatus)) {
            // 确定没写入 (改前值已变 / 被拒 / 失效): 回到已交办, 重新预览即可
            return finish(factoryId, id, user, claimedTokenId, Status.DELEGATED, status,
                    "没有办成，也没有写入：" + message + "。可以重新预览后再确认。", null);
        }
        return finish(factoryId, id, user, claimedTokenId, Status.OUTCOME_UNKNOWN, status,
                "结果不明：" + message + "。写入可能已经提交，请先到业务页面核对，勿重复办理。", null);
    }

    /** 不办 (记没办成)。只有有权确认的人, 或交办人自己, 能不办。凭据已在别处用掉的不能再记成没办成。 */
    public ItemView reject(String factoryId, Actor actor, Long id, String reason) {
        User user = requireUser(actor);
        String why = requireReason(reason);
        DigitalCrewWorkItem first = requireItem(factoryId, id);
        requireRejecter(user, actor, first);
        requireNotUsedElsewhere(factoryId, id);
        DigitalCrewWorkItem saved;
        try {
            saved = tx.execute(s -> {
                DigitalCrewWorkItem w = requireItem(factoryId, id);
                requireRejecter(user, actor, w);
                if (w.getStatus() != Status.DELEGATED) {
                    throw new BusinessException(409, "这件事已经" + w.getStatus().label() + "，不能再改成不办")
                            .withHint("到「数字班组 › 工作记录」看这件事的结果");
                }
                LocalDateTime now = clock.get();
                w.setStatus(Status.NOT_DONE);
                w.setQuotaCost(0);
                w.setConfirmedByUserId(actor.id());
                w.setConfirmedByName(displayName(user));
                w.setConfirmedAt(now);
                w.setResolvedAt(now);
                w.setResolvedDate(now.toLocalDate());
                w.setResultStatus("REJECTED");
                w.setResultNote(clip("不办：" + why, 500));
                return items.saveAndFlush(w);
            });
        } catch (OptimisticLockingFailureException raced) {
            throw new BusinessException(409, "这件事刚被别人处理了，没有改成不办")
                    .withHint("刷新「待办 › 等我确认」，到工作记录里看是谁处理的");
        }
        return view(saved, user);
    }

    private void requireRejecter(User user, Actor actor, DigitalCrewWorkItem w) {
        boolean creator = Objects.equals(w.getCreatedByUserId(), actor.id());
        if (!creator && !holds(user, w.getRequiredPermission())) {
            throw new BusinessException(403, "你没有处理这件事的权限（需要 " + w.getRequiredPermission() + "）");
        }
    }

    // ==================== 工作记录与额度 ====================

    /**
     * 工作记录与额度。件数与额度在库里按 factory_id 聚合 (不截断、不按查看人过滤: 所有查看人看到同一个数);
     * 明细列表 (最近 {@value #RECORDS_LIMIT} 件) 按查看人的模块读权限过滤 —— 原话里可能有别的模块的业务内容。
     */
    public LedgerView ledger(String factoryId, Actor actor, String memberKey) {
        User user = actor.factoryUser() ? requireUser(actor) : null;
        reconcile(factoryId, actor);
        Map<String, String> levels = levels(actor);
        LocalDate today = clock.get().toLocalDate();
        String scope = memberKey == null || memberKey.isBlank() ? null : memberKey;

        Map<String, Map<Status, long[]>> agg = new HashMap<>();
        for (Object[] r : items.countByMemberAndStatus(factoryId)) {
            agg.computeIfAbsent((String) r[0], k -> new EnumMap<>(Status.class))
                    .put((Status) r[1], new long[]{((Number) r[2]).longValue(), ((Number) r[3]).longValue()});
        }
        List<MemberCount> per = new ArrayList<>();
        long scopedDelegated = 0, scopedDone = 0, scopedQuota = 0;
        for (Member m : DigitalCrewCatalog.MEMBERS) {
            Map<Status, long[]> c = agg.getOrDefault(m.key(), Map.of());
            long quota = c.values().stream().mapToLong(v -> v[1]).sum();
            per.add(new MemberCount(m.key(), m.name(), n(c, Status.DELEGATED), n(c, Status.CONFIRMING),
                    n(c, Status.DONE), n(c, Status.NOT_DONE), n(c, Status.OUTCOME_UNKNOWN), quota));
            if (scope == null || scope.equals(m.key())) {
                scopedDelegated += n(c, Status.DELEGATED);
                scopedDone += n(c, Status.DONE);
                scopedQuota += quota;
            }
        }
        long quotaToday = items.sumQuota(factoryId, today, today, scope);
        long quotaMonth = items.sumQuota(factoryId, today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()), scope);

        PageRequest page = PageRequest.of(0, RECORDS_LIMIT);
        List<DigitalCrewWorkItem> recent = scope == null
                ? items.findByFactoryIdOrderByCreatedAtDesc(factoryId, page)
                : items.findByFactoryIdAndMemberKeyOrderByCreatedAtDesc(factoryId, scope, page);
        List<ItemView> records = recent.stream().filter(w -> readable(levels, w.getModule())).map(w -> view(w, user)).toList();
        return new LedgerView(today, quotaToday, quotaMonth, scopedQuota, scopedDelegated, scopedDone, per, records);
    }

    private static long n(Map<Status, long[]> c, Status s) {
        long[] v = c.get(s);
        return v == null ? 0 : v[0];
    }

    // ==================== 对账 ====================

    /**
     * 读的时候顺手对账 (等我确认 / 工作记录都会调):
     * <ol>
     *   <li>「办理中」超过 {@link #CONFIRMING_STALE} 的 (确认请求断了: 进程重启 / 超时 / 结算事务失败),
     *       按凭据行 id + 预览人回读凭据定四态 ({@link #reconcileStaleConfirming});</li>
     *   <li>「已交办」但预览过的, 凭据可能被别的入口 (通用 AI 写入确认) 用掉了, 回读后回写 ({@link #reconcileDelegated})。</li>
     * </ol>
     * 单件失败 (并发被别人改了 / 回读异常) 跳过, 下次读再对, 不挡读。
     */
    void reconcile(String factoryId, Actor trigger) {
        LocalDateTime cutoff = clock.get().minus(confirmingStale());
        for (DigitalCrewWorkItem w : items.findByFactoryIdAndStatusAndConfirmedAtBefore(factoryId, Status.CONFIRMING, cutoff)) {
            log.info("[DigitalCrew] reconcile stale confirming: item={}, triggeredBy={}:{}", w.getId(),
                    trigger == null ? null : trigger.type(), trigger == null ? null : trigger.id());
            safely(w.getId(), () -> reconcileStaleConfirming(factoryId, w.getId(), cutoff));
        }
        for (DigitalCrewWorkItem w : items.findByFactoryIdAndStatusAndPreviewTokenIdIsNotNull(factoryId, Status.DELEGATED)) {
            log.debug("[DigitalCrew] reconcile delegated token: item={}, triggeredBy={}:{}", w.getId(),
                    trigger == null ? null : trigger.type(), trigger == null ? null : trigger.id());
            safely(w.getId(), () -> reconcileDelegated(factoryId, w.getId()));
        }
    }

    /** 「办理中」超时窗口: 凭据在途窗口 + 5 分钟, 不少于 15 分钟。 */
    java.time.Duration confirmingStale() {
        long tokenWindow = previewTokens.executingStaleMinutes();
        return java.time.Duration.ofMinutes(Math.max(CONFIRMING_STALE_FLOOR_MINUTES, tokenWindow + CONFIRMING_STALE_MARGIN_MINUTES));
    }

    private void safely(Long id, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.warn("[DigitalCrew] reconcile skipped: item={}, error={}", id, e.toString());
        }
    }

    /**
     * 再次预览前: 作废这件事上一次登记的凭据 (不论是谁预览的)。凭据只作废同一个人的旧凭据
     * (PreviewTokenServiceImpl.createBoundToken), A 预览后 B 再预览, A 那张还能拿去通用确认入口写入 (#1771 复审 中-A)。
     * 作废是原子的: 已被认领 / 已终态的作废不了, 这时回读并按结果回写, 拒绝这次预览。
     */
    /**
     * @return 落库时库里应当还是的那个凭据行 id (= 我作废掉 / 确认已终态的那张; 被对账清掉后是 null)。
     *         落库用它做比较后再写, 期间别人改过就 409 (中-C)。
     */
    private Long retirePreviousToken(String factoryId, DigitalCrewWorkItem w) {
        Long seen = w.getPreviewTokenId();
        if (seen == null || w.getPreviewedByUserId() == null) return seen;
        boolean cancelled = previewTokens.cancelPendingById(seen, factoryId, w.getPreviewedByUserId(),
                "数字班组事项 " + w.getId() + " 已重新预览，旧凭据作废");
        if (cancelled) return seen;
        // 没作废掉: 要么早已终态没写入 (对账清掉行 id), 要么刚被拿去用了 (对账回写 + 409)
        requireNotUsedElsewhere(factoryId, w.getId());
        Long now = requireItem(factoryId, w.getId()).getPreviewTokenId();
        if (now != null && !now.equals(seen)) {
            throw new BusinessException(409, "别人刚预览过这件事，这次没有预览，也没有执行任何写操作")
                    .withHint("刷新「待办 › 等我确认」，再按你的权限重新预览");
        }
        return now;
    }

    /** 预览 / 确认 / 不办之前: 这件事的凭据要是已经在别处用掉了, 先回写结果, 再拒绝这次操作。 */
    private void requireNotUsedElsewhere(String factoryId, Long id) {
        Status after;
        try {
            after = reconcileDelegated(factoryId, id);
        } catch (OptimisticLockingFailureException raced) {
            throw new BusinessException(409, "这件事刚被别人处理了，这次没有执行任何操作")
                    .withHint("刷新「待办 › 等我确认」，到工作记录里看是谁处理的");
        }
        if (after != null) {
            throw new BusinessException(409, "这件事的确认凭据已经在数字班组之外用掉了，已按回读结果记为「" + after.label()
                    + "」，这次没有执行任何操作")
                    .withHint("到「数字班组 › 工作记录」核对这件事；结果不明的请先到业务页面核对，勿重复办理");
        }
    }

    /**
     * 已交办的事项, 凭据回读为已确认 ⇒ 已办成 (扣 1, 确认人 = 预览人); 在途 / 结果不明 ⇒ 结果不明;
     * 终态且没写入 ⇒ 清掉凭据行 id (再也不会被执行, 不用再对)。返回改成的状态, 没改返回 null。
     */
    Status reconcileDelegated(String factoryId, Long id) {
        return tx.execute(s -> {
            DigitalCrewWorkItem w = requireItem(factoryId, id);
            if (w.getStatus() != Status.DELEGATED || w.getPreviewTokenId() == null || w.getPreviewedByUserId() == null) {
                return null;
            }
            TokenStatus ts = previewTokens.readBoundStatusById(w.getPreviewTokenId(), factoryId, w.getPreviewedByUserId())
                    .orElse(null);
            if (ts == null || ts == TokenStatus.PENDING) return null;
            if (TERMINAL_NOT_WRITTEN.contains(ts)) {
                w.setPreviewTokenId(null);
                items.saveAndFlush(w);
                return null;
            }
            Long previewer = w.getPreviewedByUserId();
            String previewerName = users.findById(previewer).map(DigitalCrewService::displayName).orElse("用户 " + previewer);
            w.setConfirmedByUserId(previewer);
            w.setConfirmedByName(previewerName);
            w.setConfirmedAt(clock.get());
            if (ts == TokenStatus.CONFIRMED) {
                resolve(w, Status.DONE, "CONFIRMED_ELSEWHERE",
                        "这件事的预览凭据在数字班组之外（如通用 AI 写入确认）被确认执行了，按回读结果记已办成。",
                        "回读预览凭据：已确认执行（凭据行 " + w.getPreviewTokenId() + "，预览人 " + previewerName + "）");
            } else {
                resolve(w, Status.OUTCOME_UNKNOWN, "TOKEN_" + ts.name(),
                        "这件事的预览凭据在数字班组之外被拿去确认了，回读凭据状态是「" + ts.name()
                                + "」，写入可能已经提交，按结果不明记录、不扣额度。请到业务页面核对，勿重复办理。", null);
            }
            items.saveAndFlush(w);
            return w.getStatus();
        });
    }

    /** 「办理中」超时: 回读凭据 —— 已确认 ⇒ 已办成; 终态没写入 ⇒ 回到已交办; 其余 (在途 / 结果不明 / 读不到) ⇒ 结果不明。 */
    void reconcileStaleConfirming(String factoryId, Long id, LocalDateTime cutoff) {
        tx.executeWithoutResult(s -> {
            DigitalCrewWorkItem w = requireItem(factoryId, id);
            if (w.getStatus() != Status.CONFIRMING || w.getConfirmedAt() == null || w.getConfirmedAt().isAfter(cutoff)) {
                return;
            }
            TokenStatus ts = w.getPreviewTokenId() == null || w.getPreviewedByUserId() == null ? null
                    : previewTokens.readBoundStatusById(w.getPreviewTokenId(), factoryId, w.getPreviewedByUserId()).orElse(null);
            if (ts == TokenStatus.CONFIRMED) {
                resolve(w, Status.DONE, "RECONCILED_CONFIRMED", "确认请求在途中断，对账回读凭据为已确认执行，记已办成。",
                        "对账回读预览凭据：已确认执行（凭据行 " + w.getPreviewTokenId() + "）");
            } else if (ts != null && DEFINITELY_NOT_WRITTEN.contains(ts)) {
                resolve(w, Status.DELEGATED, "RECONCILED_" + ts.name(),
                        "确认请求在途中断，对账回读凭据「" + ts.name() + "」，确定没有写入，回到已交办，可以重新预览后再确认。", null);
            } else {
                resolve(w, Status.OUTCOME_UNKNOWN, "RECONCILED_" + (ts == null ? "UNREADABLE" : ts.name()),
                        "确认请求超过 " + confirmingStale().toMinutes() + " 分钟没有结果，对账回读凭据「"
                                + (ts == null ? "读不到" : ts.name()) + "」，写入可能已经提交，按结果不明记录、不扣额度。"
                                + "请到业务页面核对，勿重复办理。", null);
            }
            items.saveAndFlush(w);
        });
    }

    // ==================== 内部 ====================

    private ItemView finish(String factoryId, Long id, User user, Long claimedTokenId, Status to, String resultStatus, String note,
                            String evidence) {
        DigitalCrewWorkItem saved = tx.execute(s -> {
            DigitalCrewWorkItem w = requireItem(factoryId, id);
            if (w.getStatus() != Status.CONFIRMING || !Objects.equals(w.getConfirmedByUserId(), user.getId())
                    || !Objects.equals(w.getPreviewTokenId(), claimedTokenId)) {
                // 这次确认回来晚了: 对账已经定了结果 (或已回到已交办、被别人重新认领), 不覆盖
                log.warn("[DigitalCrew] late confirm result ignored: item={}, now={}, wanted={}, resultStatus={}",
                        id, w.getStatus(), to, resultStatus);
                return w;
            }
            resolve(w, to, resultStatus, note, evidence);
            return items.save(w);
        });
        return view(saved, user);
    }

    /** 定一件事的状态 (四态 + 回到已交办), 额度只有已办成扣 1; 日期是北京时间真实日期。 */
    private void resolve(DigitalCrewWorkItem w, Status to, String resultStatus, String note, String evidence) {
        LocalDateTime now = clock.get();
        w.setStatus(to);
        w.setResultStatus(clip(resultStatus, 40));
        w.setResultNote(clip(note, 500));
        if (to == Status.DELEGATED) {
            // 确定没写入: 清掉这次预览, 回到等确认
            w.setQuotaCost(0);
            w.setCommandDigest(null);
            w.setPreviewTokenId(null);
            w.setPreviewedByUserId(null);
            w.setPreviewedAt(null);
            w.setConfirmedByUserId(null);
            w.setConfirmedByName(null);
            w.setConfirmedAt(null);
        } else {
            w.setQuotaCost(to == Status.DONE ? 1 : 0);
            w.setResolvedAt(now);
            w.setResolvedDate(now.toLocalDate());
            w.setEvidence(clip(evidence, 500));
        }
    }

    private static String affected(IntentExecuteResponse resp) {
        if (resp.getAffectedEntities() == null || resp.getAffectedEntities().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("；业务对象：");
        resp.getAffectedEntities().stream().limit(3).forEach(e -> sb.append(nz(e.getEntityType(), "对象")).append(' ')
                .append(nz(e.getEntityName(), e.getEntityId())).append(e.getAction() == null ? "" : "（" + e.getAction() + "）").append(' '));
        return sb.toString().strip();
    }

    private void requireConfirmPermission(User user, DigitalCrewWorkItem w) {
        if (!holds(user, w.getRequiredPermission())) {
            throw new BusinessException(403, "你没有确认这件事的权限（需要 " + w.getRequiredPermission() + "），服务端已拒绝，没有执行任何写操作");
        }
        Optional<String> gate = executionGate.denyReason(w.getFactoryId(), user, w.getIntentCode());
        if (gate.isPresent()) {
            throw new BusinessException(403, "你确认了执行时也会被拒（" + gate.get() + "），服务端已拒绝，没有执行任何写操作");
        }
    }

    private User requireUser(Actor actor) {
        if (actor == null || !actor.factoryUser()) {
            throw new BusinessException(403, "平台账号不能确认或交办工厂的事，请用本厂账号");
        }
        return users.findById(actor.id()).orElseThrow(() -> new BusinessException(401, "无法识别当前操作人"));
    }

    private DigitalCrewWorkItem requireItem(String factoryId, Long id) {
        if (id == null) throw new BusinessException(400, "缺少事项编号");
        return items.findByIdAndFactoryId(id, factoryId).orElseThrow(() -> new BusinessException(404, "本厂没有这件事"));
    }

    private Member requireEnabledMember(String factoryId, String key) {
        Member m = DigitalCrewCatalog.member(key).orElseThrow(() -> new BusinessException(404, "不认识的数字员工"));
        if (!enabledMap(factoryId).getOrDefault(key, true)) {
            throw new BusinessException(409, "本厂没有启用" + m.name() + "，请到「设置 › 数字班组配置」启用")
                    .withHint("到「设置 › 数字班组配置」启用" + m.name() + "，或换一位数字员工");
        }
        return m;
    }

    private Map<String, Boolean> enabledMap(String factoryId) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        DigitalCrewCatalog.MEMBERS.forEach(m -> out.put(m.key(), true));
        memberSettings.findByFactoryId(factoryId).forEach(r -> out.put(r.getMemberKey(), r.isEnabled()));
        return out;
    }

    private Map<String, String> levels(Actor actor) {
        if (actor == null) return Map.of();
        if (!actor.factoryUser()) return Map.of("*", "rw");
        return users.findById(actor.id()).map(permissions::resolveModuleLevels).orElse(Map.of());
    }

    private static boolean readable(Map<String, String> levels, String module) {
        String l = levels.getOrDefault(module, levels.getOrDefault("*", "-"));
        return l != null && (l.contains("r") || l.contains("w"));
    }

    private MemberView memberView(Member m, Map<String, Boolean> enabled, Map<String, String> levels) {
        Map<String, String> ml = new LinkedHashMap<>();
        for (String mod : m.modules()) ml.put(mod, levels.getOrDefault(mod, levels.getOrDefault("*", "-")));
        String level = ml.values().stream().anyMatch("rw"::equals) ? "rw"
                : ml.values().stream().anyMatch(v -> v != null && v.contains("r")) ? "r" : "-";
        return new MemberView(m.key(), m.name(), m.shortName(), m.duty(), enabled.getOrDefault(m.key(), true),
                m.modules(), ml, level, m.capabilities(), m.workdesks());
    }

    private ItemView view(DigitalCrewWorkItem w, User viewer) {
        String memberName = DigitalCrewCatalog.member(w.getMemberKey()).map(Member::name).orElse(w.getMemberKey());
        boolean canConfirm = viewer != null && w.getStatus() == Status.DELEGATED
                && canHandle(w.getFactoryId(), viewer, w.getRequiredPermission(), w.getIntentCode());
        boolean previewedByMe = viewer != null && Objects.equals(w.getPreviewedByUserId(), viewer.getId());
        return new ItemView(w.getId(), w.getMemberKey(), memberName, w.getIntentCode(), w.getIntentName(), w.getTitle(),
                w.getUserInput(), w.getModule(), w.getRequiredPermission(), w.getStatus().name(), w.getStatus().label(),
                w.getQuotaCost(), w.getPreviewSummary(), w.getCreatedByName(), w.getCreatedAt(), w.getConfirmedByName(),
                w.getConfirmedAt(), w.getResolvedAt(), w.getResolvedDate(), w.getResultStatus(), w.getResultNote(),
                w.getEvidence(), previewedByMe, canConfirm);
    }

    private String capabilityPack(String factoryId, Actor actor, String input) {
        try {
            String r = "production_manager".equals(actor.role()) ? "dispatcher" : actor.role();
            FactoryUserRole role = FactoryUserRole.valueOf(r);
            Optional<FactoryType> type = businessTypes.resolveActive(factoryId);
            if (type.isEmpty()) return null;
            return packSelector.match(role, type.get(), input).map(FactoryCapabilityPack::packId).orElse(null);
        } catch (RuntimeException e) {
            return null; // 能力包只是标注: 选不到不挡对话
        }
    }

    private boolean canEditSettings(Actor actor) {
        if (actor == null || !actor.factoryUser()) return false;
        return users.findById(actor.id()).map(u -> permissions.hasPermission(u, "system:read_write")).orElse(false);
    }

    private void requireSettingsEditor(Actor actor) {
        if (!canEditSettings(actor)) throw new BusinessException(403, "只有有系统设置权限的本厂账号能改数字班组配置");
    }

    private void log(String factoryId, Actor actor, DigitalCrewSettingLog.ChangeKind kind, String reason,
                     String before, String after) {
        DigitalCrewSettingLog l = new DigitalCrewSettingLog();
        l.setFactoryId(factoryId);
        l.setChangeKind(kind);
        l.setActorUserId(actor.id());
        l.setActorName(actorName(actor));
        l.setReason(reason);
        l.setBeforeJson(before);
        l.setAfterJson(after);
        settingLogs.save(l);
    }

    private String actorName(Actor actor) {
        return users.findById(actor.id()).map(DigitalCrewService::displayName).orElse("用户 " + actor.id());
    }

    static String displayName(User u) {
        String n = u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername();
        return clip(n == null ? "用户 " + u.getId() : n, 100);
    }

    static String requireReason(String reason) {
        String r = reason == null ? "" : reason.replace('　', ' ').strip();
        if (r.length() < REASON_MIN) throw new BusinessException(400, "请写一句原因（至少 " + REASON_MIN + " 个字）");
        if (r.length() > REASON_MAX) throw new BusinessException(400, "原因最多 " + REASON_MAX + " 个字");
        return r;
    }

    /** 写级别权限码: 只有这种码才交办 (确认人必须持有它)。逗号分隔的「任一」码要每一个都是写级别。 */
    static boolean isWriteLevel(String permission) {
        List<String> codes = com.cretas.aims.service.execution.IntentPermissionGate.splitCodes(permission);
        return !codes.isEmpty() && codes.stream().allMatch(p -> p.endsWith(":read_write") || p.endsWith(":write"));
    }

    /** 登记的码里有读级别的 (模块:read) —— 说明本人有读权限就能办, 是个人自助操作。 */
    static boolean hasReadLevelCode(String permission) {
        return com.cretas.aims.service.execution.IntentPermissionGate.splitCodes(permission).stream()
                .anyMatch(p -> p.endsWith(":read"));
    }

    /** 能确认 = 持有登记码 且 执行时的工具门 (一线开放 / 旧角色串 / 工具权限表) 也过得了。 */
    private boolean canHandle(String factoryId, User user, String requiredPermission, String intentCode) {
        return holds(user, requiredPermission) && executionGate.denyReason(factoryId, user, intentCode).isEmpty();
    }

    /** 收件箱分组键 = 权限码 + '|' + 意图码 (与 DigitalCrewWorkItemRepository 的 CONCAT 同一拼法)。 */
    static String permissionOfKey(String key) {
        int i = key.lastIndexOf('|');
        return i < 0 ? key : key.substring(0, i);
    }

    static String intentOfKey(String key) {
        int i = key.lastIndexOf('|');
        return i < 0 ? null : key.substring(i + 1);
    }

    /** 事项登记的权限码 (逗号 = 任一即可, 与意图权限门同口径): 持有其中任一个即算有权限。 */
    private boolean holds(User user, String requiredPermission) {
        return com.cretas.aims.service.execution.IntentPermissionGate.splitCodes(requiredPermission).stream()
                .anyMatch(c -> permissions.hasPermission(user, c));
    }

    private static String nz(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
