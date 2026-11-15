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
    private final UserRepository users;
    private final PermissionService permissions;
    private final IntentExecutorService intents;
    private final AIIntentService intentConfigs;
    private final PreviewTokenService previewTokens;
    private final FactoryCapabilityPackSelector packSelector;
    private final FactoryBusinessTypeResolver businessTypes;
    private final TransactionTemplate tx;
    private final Supplier<LocalDateTime> clock;

    public DigitalCrewService(DigitalCrewWorkItemRepository items,
                              DigitalCrewMemberSettingRepository memberSettings,
                              DigitalCrewRoleMappingRepository roleMappings,
                              DigitalCrewSettingLogRepository settingLogs,
                              UserRepository users,
                              PermissionService permissions,
                              IntentExecutorService intents,
                              AIIntentService intentConfigs,
                              PreviewTokenService previewTokens,
                              FactoryCapabilityPackSelector packSelector,
                              FactoryBusinessTypeResolver businessTypes,
                              PlatformTransactionManager transactionManager) {
        this.items = items;
        this.memberSettings = memberSettings;
        this.roleMappings = roleMappings;
        this.settingLogs = settingLogs;
        this.users = users;
        this.permissions = permissions;
        this.intents = intents;
        this.intentConfigs = intentConfigs;
        this.previewTokens = previewTokens;
        this.packSelector = packSelector;
        this.businessTypes = businessTypes;
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

    public record InboxView(List<ItemView> mine, int hiddenCount, List<String> hiddenModules) {
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
                .mode("READ").build();
        IntentExecuteResponse resp = intents.execute(factoryId, req, actor.id(), actor.role());
        if (resp == null) throw new BusinessException(502, "没有收到识别结果，没有执行任何写操作，请重试");
        com.cretas.aims.service.execution.CustomerTextSanitizer.sanitize(resp);

        AIIntentConfig intent = resp.getIntentCode() == null ? null
                : intentConfigs.getIntentByCode(factoryId, resp.getIntentCode()).orElse(null);
        String requiredPermission = intent == null ? null : intent.getRequiredPermission();
        String module = DigitalCrewCatalog.moduleOf(requiredPermission);
        if (module != null && !member.modules().contains(module)) {
            String owner = DigitalCrewCatalog.ownerOf(module).map(Member::key).orElse(null);
            String ownerName = owner == null ? "其他岗位" : DigitalCrewCatalog.member(owner).map(Member::name).orElse(owner);
            return new AskView("OUT_OF_SCOPE", memberKey, resp.getStatus(),
                    "这件事（" + nz(resp.getIntentName(), resp.getIntentCode()) + "）不归" + member.name() + "管，请找「"
                            + ownerName + "」。这次没有查数据，也没有执行任何写操作。",
                    resp.getIntentCode(), resp.getIntentName(), pack, owner, null, List.of(), resp.getSessionId());
        }

        if ("READ_MODE_WRITE_BLOCKED".equals(resp.getStatus())) {
            if (requiredPermission == null || requiredPermission.isBlank()) {
                return new AskView("REFUSED", memberKey, resp.getStatus(),
                        "这件事是写操作，但系统里没有登记谁有权确认，所以不交办。没有执行任何写操作。",
                        resp.getIntentCode(), resp.getIntentName(), pack, null, null, List.of(), resp.getSessionId());
            }
            ItemView item = delegate(factoryId, actor, member, input, intent, requiredPermission, module);
            return new AskView("DELEGATED", memberKey, resp.getStatus(),
                    member.name() + "把这件事整理好了，记为「已交办」，放进「待办 › 等我确认」。"
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
        DigitalCrewWorkItem saved = tx.execute(s -> {
            Optional<DigitalCrewWorkItem> dup = items.findFirstByFactoryIdAndMemberKeyAndCreatedByUserIdAndUserInputAndStatus(
                    factoryId, member.key(), actor.id(), input, Status.DELEGATED);
            if (dup.isPresent()) return dup.get();
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
            return items.save(w);
        });
        return view(saved, user);
    }

    // ==================== 等我确认 ====================

    public InboxView inbox(String factoryId, Actor actor) {
        User user = requireUser(actor);
        List<DigitalCrewWorkItem> open = items.findByFactoryIdAndStatusInOrderByCreatedAtDesc(
                factoryId, List.of(Status.DELEGATED), PageRequest.of(0, 200));
        List<ItemView> mine = new ArrayList<>();
        Set<String> hiddenModules = new TreeSet<>();
        int hidden = 0;
        for (DigitalCrewWorkItem w : open) {
            if (permissions.hasPermission(user, w.getRequiredPermission())) {
                mine.add(view(w, user));
            } else {
                hidden++;
                hiddenModules.add(w.getModule());
            }
        }
        return new InboxView(mine, hidden, List.copyOf(hiddenModules));
    }

    /** 确认人按自己身份预览 (#1632 路径)。凭据只回给确认人, 本表只记摘要。 */
    public PreviewView preview(String factoryId, Actor actor, Long id) {
        User user = requireUser(actor);
        DigitalCrewWorkItem w = requireItem(factoryId, id);
        requireConfirmPermission(user, w);
        if (w.getStatus() != Status.DELEGATED) {
            throw new BusinessException(409, "这件事已经" + w.getStatus().label() + "，不能再预览");
        }
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .userInput(w.getUserInput()).intentCode(w.getIntentCode()).build();
        IntentExecuteResponse resp = intents.preview(factoryId, req, actor.id(), actor.role());
        if (resp == null) throw new BusinessException(502, "没有收到预览，没有执行任何写操作，请重试");
        IntentExecuteResponse.ConfirmableAction action = resp.getConfirmableAction();
        if (action != null && action.getCommandDigest() != null && action.getConfirmToken() != null) {
            DigitalCrewWorkItem stamped = tx.execute(s -> {
                DigitalCrewWorkItem cur = requireItem(factoryId, id);
                if (cur.getStatus() != Status.DELEGATED) {
                    throw new BusinessException(409, "这件事刚被别人处理了（" + cur.getStatus().label() + "）");
                }
                cur.setPreviewedByUserId(actor.id());
                cur.setPreviewedAt(clock.get());
                cur.setCommandDigest(action.getCommandDigest());
                return items.save(cur);
            });
            return new PreviewView(view(stamped, user), resp);
        }
        return new PreviewView(view(w, user), resp);
    }

    /**
     * 确认执行。服务端: 会话里的人有写权限 → 事项仍已交办、预览人是本人、摘要一致 → 原子认领 →
     * 走 #1632 确认 → 回读凭据定四态。
     */
    public ItemView confirm(String factoryId, Actor actor, Long id, ConfirmationProof proof) {
        User user = requireUser(actor);
        DigitalCrewWorkItem w = requireItem(factoryId, id);
        requireConfirmPermission(user, w);
        if (proof == null) throw new BusinessException(400, "缺少确认凭据，请先预览");
        String name = displayName(user);
        Integer claimed = tx.execute(s -> items.claimForConfirm(id, factoryId, actor.id(), name,
                proof.commandDigest(), clock.get(), Status.DELEGATED, Status.CONFIRMING));
        if (claimed == null || claimed != 1) {
            DigitalCrewWorkItem cur = requireItem(factoryId, id);
            if (cur.getStatus() != Status.DELEGATED) {
                throw new BusinessException(409, "这件事已经" + cur.getStatus().label() + "，没有再执行");
            }
            throw new BusinessException(409, "确认凭据与你的预览对不上（预览不是你做的，或已重新预览），请重新预览后再确认。没有执行任何写操作");
        }

        IntentExecuteResponse resp;
        try {
            resp = intents.confirm(factoryId, proof, actor.id(), actor.role());
        } catch (RuntimeException e) {
            log.error("[DigitalCrew] confirm threw, outcome unknown: item={}, error={}", id, e.toString());
            return finish(factoryId, id, user, Status.OUTCOME_UNKNOWN, "EXCEPTION",
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
                return finish(factoryId, id, user, Status.DONE, status, message,
                        "回读预览凭据：已确认执行" + affected(resp));
            }
            return finish(factoryId, id, user, Status.OUTCOME_UNKNOWN, status,
                    "接口说办成了，但回读凭据状态是「" + (tokenStatus == null ? "读不到" : tokenStatus.name())
                            + "」，对不上，按结果不明记录、不扣额度。请到业务页面核对。", null);
        }
        if (tokenStatus != null && DEFINITELY_NOT_WRITTEN.contains(tokenStatus)) {
            // 确定没写入 (改前值已变 / 被拒 / 失效): 回到已交办, 重新预览即可
            return finish(factoryId, id, user, Status.DELEGATED, status,
                    "没有办成，也没有写入：" + message + "。可以重新预览后再确认。", null);
        }
        return finish(factoryId, id, user, Status.OUTCOME_UNKNOWN, status,
                "结果不明：" + message + "。写入可能已经提交，请先到业务页面核对，勿重复办理。", null);
    }

    /** 不办 (记没办成)。只有有权确认的人, 或交办人自己, 能不办。 */
    public ItemView reject(String factoryId, Actor actor, Long id, String reason) {
        User user = requireUser(actor);
        String why = requireReason(reason);
        DigitalCrewWorkItem saved = tx.execute(s -> {
            DigitalCrewWorkItem w = requireItem(factoryId, id);
            boolean creator = Objects.equals(w.getCreatedByUserId(), actor.id());
            if (!creator && !permissions.hasPermission(user, w.getRequiredPermission())) {
                throw new BusinessException(403, "你没有处理这件事的权限（需要 " + w.getRequiredPermission() + "）");
            }
            if (w.getStatus() != Status.DELEGATED) {
                throw new BusinessException(409, "这件事已经" + w.getStatus().label() + "，不能再改成不办");
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
            return items.save(w);
        });
        return view(saved, user);
    }

    // ==================== 工作记录与额度 ====================

    public LedgerView ledger(String factoryId, Actor actor, String memberKey) {
        User user = actor.factoryUser() ? requireUser(actor) : null;
        Map<String, String> levels = levels(actor);
        LocalDate today = clock.get().toLocalDate();
        List<DigitalCrewWorkItem> all = items.findByFactoryIdOrderByCreatedAtDesc(factoryId, PageRequest.of(0, 2000))
                .stream().filter(w -> readable(levels, w.getModule())).toList();
        List<MemberCount> per = new ArrayList<>();
        for (Member m : DigitalCrewCatalog.MEMBERS) {
            List<DigitalCrewWorkItem> mine = all.stream().filter(w -> w.getMemberKey().equals(m.key())).toList();
            per.add(new MemberCount(m.key(), m.name(), count(mine, Status.DELEGATED), count(mine, Status.CONFIRMING),
                    count(mine, Status.DONE), count(mine, Status.NOT_DONE), count(mine, Status.OUTCOME_UNKNOWN),
                    mine.stream().mapToLong(DigitalCrewWorkItem::getQuotaCost).sum()));
        }
        List<DigitalCrewWorkItem> scoped = memberKey == null || memberKey.isBlank() ? all
                : all.stream().filter(w -> w.getMemberKey().equals(memberKey)).toList();
        long quotaToday = scoped.stream().filter(w -> today.equals(w.getResolvedDate()))
                .mapToLong(DigitalCrewWorkItem::getQuotaCost).sum();
        long quotaMonth = scoped.stream().filter(w -> w.getResolvedDate() != null
                        && w.getResolvedDate().getYear() == today.getYear() && w.getResolvedDate().getMonth() == today.getMonth())
                .mapToLong(DigitalCrewWorkItem::getQuotaCost).sum();
        long quotaTotal = scoped.stream().mapToLong(DigitalCrewWorkItem::getQuotaCost).sum();
        return new LedgerView(today, quotaToday, quotaMonth, quotaTotal, count(scoped, Status.DELEGATED),
                count(scoped, Status.DONE), per, scoped.stream().limit(300).map(w -> view(w, user)).toList());
    }

    // ==================== 内部 ====================

    private ItemView finish(String factoryId, Long id, User user, Status to, String resultStatus, String note,
                            String evidence) {
        DigitalCrewWorkItem saved = tx.execute(s -> {
            DigitalCrewWorkItem w = requireItem(factoryId, id);
            LocalDateTime now = clock.get();
            w.setStatus(to);
            w.setResultStatus(clip(resultStatus, 40));
            w.setResultNote(clip(note, 500));
            if (to == Status.DELEGATED) {
                // 确定没写入: 清掉这次预览, 回到等确认
                w.setQuotaCost(0);
                w.setCommandDigest(null);
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
            return items.save(w);
        });
        return view(saved, user);
    }

    private static String affected(IntentExecuteResponse resp) {
        if (resp.getAffectedEntities() == null || resp.getAffectedEntities().isEmpty()) return "";
        StringBuilder sb = new StringBuilder("；业务对象：");
        resp.getAffectedEntities().stream().limit(3).forEach(e -> sb.append(nz(e.getEntityType(), "对象")).append(' ')
                .append(nz(e.getEntityName(), e.getEntityId())).append(e.getAction() == null ? "" : "（" + e.getAction() + "）").append(' '));
        return sb.toString().strip();
    }

    private void requireConfirmPermission(User user, DigitalCrewWorkItem w) {
        if (!permissions.hasPermission(user, w.getRequiredPermission())) {
            throw new BusinessException(403, "你没有确认这件事的权限（需要 " + w.getRequiredPermission() + "），服务端已拒绝，没有执行任何写操作");
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
            throw new BusinessException(409, "本厂没有启用" + m.name() + "，请到「设置 › 数字班组配置」启用");
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
                && permissions.hasPermission(viewer, w.getRequiredPermission());
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

    private static long count(List<DigitalCrewWorkItem> list, Status s) {
        return list.stream().filter(w -> w.getStatus() == s).count();
    }

    private static String nz(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    static String clip(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
