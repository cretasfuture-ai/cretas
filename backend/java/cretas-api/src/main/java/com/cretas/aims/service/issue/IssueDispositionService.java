package com.cretas.aims.service.issue;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.issue.IssueDisposition;
import com.cretas.aims.entity.issue.IssueDisposition.Conclusion;
import com.cretas.aims.entity.issue.IssueDisposition.SourceType;
import com.cretas.aims.entity.issue.IssueDisposition.Status;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.foodsafety.ColdChainAlertRepository;
import com.cretas.aims.repository.issue.IssueDispositionRepository;
import com.cretas.aims.repository.shortage.DailyReconcileRunRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.shortage.CostDeviationCaseWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 「发现的问题」处置: 关闭 (必须选结论并写一句) / 转成我的待办 / 恢复。
 *
 * <p>每次写入都校验: ① 来源对象确实属于本厂 (跨厂 id 一律 404, 不靠前端); ② 操作人对该来源有处理权限
 * ({@link #WRITE_PERMISSIONS}, 与来源页处理动作的 @RequirePermission 逐条相同, 由
 * IssueDispositionPermissionParityContractTest 钉住)。读处置也按来源的读权限过滤 ({@link #READ_PERMISSIONS})。
 * ⛔ 不改来源单据。
 *
 * <p>状态规则: 已关闭的不能再关 (要改结论先恢复, 409); 已转给别人的待办, 只有被指派人或本厂管理员能关 / 恢复,
 * 也不能再转给自己; 从「已关闭」恢复时把上一次的结论、说明、关闭人、关闭时间留在 previous_* 字段里。
 */
@Service
@RequiredArgsConstructor
public class IssueDispositionService {

    public static final int REASON_MIN = 2;
    public static final int REASON_MAX = 200;
    public static final int TITLE_MAX = 200;

    /** 本厂管理员 (原「异常中心」的可见角色): 可以处置已转给别人的待办。 */
    static final Set<String> ADMIN_ROLES = Set.of("factory_super_admin", "permission_admin");

    /** 各来源的处理权限 (任一即可) = 来源页处理动作 @RequirePermission 的并集。 */
    public static final Map<SourceType, List<String>> WRITE_PERMISSIONS = new EnumMap<>(Map.of(
            SourceType.SHORTAGE_CASE, List.of("procurement:read_write", "production:read_write", "scheduling:read_write"),
            SourceType.DELIVERY_RISK, List.of("production:read_write", "sales:read_write"),
            SourceType.PROCESS_PARAM_DEVIATION, List.of("quality:read_write", "production:read_write"),
            SourceType.COST_DEVIATION, List.of("finance:read_write", "production:read_write"),
            SourceType.COLD_CHAIN_ALERT, List.of("quality:read_write"),
            SourceType.DAILY_RECONCILE_RUN, List.of("finance:read_write", "production:read_write")));

    /** 各来源的读权限 (任一即可) = 来源列表接口的 @RequirePermission。读处置时据此过滤。 */
    public static final Map<SourceType, List<String>> READ_PERMISSIONS = new EnumMap<>(Map.of(
            SourceType.SHORTAGE_CASE, List.of("procurement:read", "procurement:read_write", "production:read",
                    "production:read_write", "warehouse:read", "warehouse:read_write", "quality:read",
                    "quality:read_write", "sales:read", "sales:read_write"),
            SourceType.DELIVERY_RISK, List.of("production:read", "production:read_write", "sales:read",
                    "sales:read_write", "procurement:read", "procurement:read_write"),
            SourceType.PROCESS_PARAM_DEVIATION, List.of("quality:read", "quality:read_write", "production:read",
                    "production:read_write"),
            SourceType.COST_DEVIATION, List.of("finance:read", "finance:read_write", "procurement:price:view",
                    "production:read", "production:read_write"),
            SourceType.COLD_CHAIN_ALERT, List.of("quality:read_write", "quality:read", "warehouse:read",
                    "warehouse:read_write"),
            SourceType.DAILY_RECONCILE_RUN, List.of("finance:read", "finance:read_write", "procurement:price:view",
                    "production:read", "production:read_write")));

    private final IssueDispositionRepository repository;
    private final ShortageCaseRepository caseRepository;
    private final ColdChainAlertRepository coldChainAlertRepository;
    private final DailyReconcileRunRepository dailyRunRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;

    public record SourceRef(String sourceType, String sourceId, String title, String linkPath) {
    }

    /** 本厂已关闭 / 已转待办的处置, 只返回操作人有读权限的来源类型。 */
    @Transactional(readOnly = true)
    public List<IssueDisposition> listActive(String factoryId, ActorRef actor) {
        Set<SourceType> readable = readableTypes(actor);
        return repository.findByFactoryIdAndStatusInOrderByActedAtDesc(factoryId,
                        EnumSet.of(Status.CLOSED, Status.CONVERTED), PageRequest.of(0, 500))
                .stream().filter(d -> readable.contains(d.getSourceType())).toList();
    }

    /** 转给本人的待办 (同样按读权限过滤: 权限被收回后不再显示)。 */
    @Transactional(readOnly = true)
    public List<IssueDisposition> assignedTo(String factoryId, ActorRef actor) {
        Set<SourceType> readable = readableTypes(actor);
        return repository.findByFactoryIdAndAssigneeUserIdAndStatusOrderByActedAtDesc(factoryId, actor.id(),
                        Status.CONVERTED, PageRequest.of(0, 200))
                .stream().filter(d -> readable.contains(d.getSourceType())).toList();
    }

    /** 关闭: 结论必须是原型清单之一, 说明去空白 (含全角空格) 后 ≥ 2 字。已关闭的不能再关。 */
    @Transactional
    public IssueDisposition close(String factoryId, ActorRef actor, SourceRef ref, String conclusionCode, String reason) {
        Conclusion conclusion = parseConclusion(conclusionCode);
        String why = clean(reason);
        if (why.length() < REASON_MIN) {
            throw new BusinessException(400, "关闭要写一句说明 (至少 " + REASON_MIN + " 个字)");
        }
        if (why.length() > REASON_MAX) {
            throw new BusinessException(400, "说明最多 " + REASON_MAX + " 个字");
        }
        Prepared p = prepare(factoryId, actor, ref);
        IssueDisposition d = p.row();
        if (d.getStatus() == Status.CLOSED) {
            throw new BusinessException(409, "这条问题已经关闭; 要改结论, 先恢复再关闭")
                    .withHint("该问题已关闭，要改结论请先点「恢复」，再重新关闭并选结论");
        }
        requireAssigneeOrAdmin(d, actor, p.user(), "关闭");
        applyRef(d, p, actor);
        d.setStatus(Status.CLOSED);
        d.setConclusion(conclusion);
        d.setReason(why);
        d.setAssigneeUserId(null);
        return repository.save(d);
    }

    /** 转成待办: 指派给操作人本人, 出现在「待办 › 指派给我」。平台账号不能转 (没有工厂待办)。 */
    @Transactional
    public IssueDisposition convertToTodo(String factoryId, ActorRef actor, SourceRef ref) {
        if (actor.type() != AuthenticatedActorType.FACTORY_USER) {
            throw new BusinessException(403, "只有本厂账号能把问题转成自己的待办");
        }
        Prepared p = prepare(factoryId, actor, ref);
        IssueDisposition d = p.row();
        if (d.getStatus() == Status.CLOSED) {
            throw new BusinessException(409, "这条问题已关闭, 先恢复再转待办")
                    .withHint("该问题已关闭，要转成待办请先点「恢复」");
        }
        if (d.getStatus() == Status.CONVERTED) {
            boolean mine = Objects.equals(d.getAssigneeUserId(), actor.id());
            throw new BusinessException(409, mine ? "这条问题已经是你的待办" : "这条问题已经转给别人做待办")
                    .withHint(mine ? "这条已在你的「待办 › 指派给我」里，去那里处理即可"
                            : "这条待办已转给别人，请联系被指派的人或管理员处理");
        }
        applyRef(d, p, actor);
        d.setStatus(Status.CONVERTED);
        d.setConclusion(null);
        d.setReason(null);
        d.setAssigneeUserId(actor.id());
        return repository.save(d);
    }

    /** 恢复: 回到未处置。从「已关闭」恢复时, 上一次的结论、说明、关闭人、关闭时间留在 previous_*。 */
    @Transactional
    public IssueDisposition reopen(String factoryId, ActorRef actor, SourceRef ref) {
        SourceType type = parseSourceType(ref.sourceType());
        String sourceId = requireId(ref.sourceId());
        // 先查权限再判状态: 没有处理权限的人一律 403, 不从 404 / 409 里看出处置状态
        User user = requireWritePermission(actor, type);
        requireSourceInFactory(factoryId, type, sourceId);
        IssueDisposition d = repository.findByFactoryIdAndSourceTypeAndSourceId(factoryId, type, sourceId)
                .orElseThrow(() -> new BusinessException(404, "这条问题没有处置记录, 不用恢复"));
        if (d.getStatus() == Status.OPEN) {
            throw new BusinessException(409, "这条问题没有关闭或转待办, 不用恢复")
                    .withHint("这条问题现在就是未处理状态，刷新页面即可看到最新状态");
        }
        requireAssigneeOrAdmin(d, actor, user, "恢复");
        if (d.getStatus() == Status.CLOSED) {
            d.setPreviousConclusion(d.getConclusion());
            d.setPreviousReason(d.getReason());
            d.setPreviousClosedByType(d.getActorType());
            d.setPreviousClosedById(d.getActorId());
            d.setPreviousClosedAt(d.getActedAt());
        }
        d.setStatus(Status.OPEN);
        d.setConclusion(null);
        d.setReason(null);
        d.setAssigneeUserId(null);
        stamp(d, actor);
        return repository.save(d);
    }

    private record Prepared(IssueDisposition row, User user, String title, String link) {
    }

    private Prepared prepare(String factoryId, ActorRef actor, SourceRef ref) {
        SourceType type = parseSourceType(ref.sourceType());
        String sourceId = requireId(ref.sourceId());
        requireSourceInFactory(factoryId, type, sourceId);
        User user = requireWritePermission(actor, type);
        String title = clean(ref.title());
        if (title.isEmpty()) throw new BusinessException(400, "缺少问题标题");
        if (title.length() > TITLE_MAX) title = title.substring(0, TITLE_MAX);
        String link = clean(ref.linkPath());
        if (!link.isEmpty() && (!link.startsWith("/") || link.startsWith("//") || link.length() > 255)) {
            throw new BusinessException(400, "链接只能是站内路径");
        }
        IssueDisposition d = repository.findByFactoryIdAndSourceTypeAndSourceId(factoryId, type, sourceId)
                .orElseGet(() -> {
                    IssueDisposition n = new IssueDisposition();
                    n.setFactoryId(factoryId);
                    n.setSourceType(type);
                    n.setSourceId(sourceId);
                    n.setStatus(Status.OPEN);
                    return n;
                });
        return new Prepared(d, user, title, link.isEmpty() ? null : link);
    }

    private static void applyRef(IssueDisposition d, Prepared p, ActorRef actor) {
        d.setTitle(p.title());
        d.setLinkPath(p.link());
        stamp(d, actor);
    }

    private static void stamp(IssueDisposition d, ActorRef actor) {
        d.setActorType(actor.type().name());
        d.setActorId(actor.id());
        d.setActedAt(LocalDateTime.now());
    }

    /** 去掉首尾空白, 含全角空格 (U+3000) 与不换行空格, 与前端 String.trim() 一致。 */
    static String clean(String s) {
        return s == null ? "" : s.replace(' ', ' ').strip();
    }

    /** 已转给别人的待办: 只有被指派人或本厂管理员 (平台旁路账号同) 能处置。 */
    private static void requireAssigneeOrAdmin(IssueDisposition d, ActorRef actor, User user, String action) {
        if (d.getStatus() != Status.CONVERTED) return;
        if (actor.type() != AuthenticatedActorType.FACTORY_USER) return;
        if (Objects.equals(d.getAssigneeUserId(), actor.id())) return;
        // 角色取值与 User#getRole 一致: roleCode 为空就退回 position
        if (user != null && user.getRole() != null && ADMIN_ROLES.contains(user.getRole())) return;
        throw new BusinessException(409, "这条问题已转给别人做待办, 只有被指派的人或管理员能" + action)
                .withHint("这条待办已转给别人，请联系被指派的人或工厂管理员" + action);
    }

    static Conclusion parseConclusion(String code) {
        String c0 = clean(code);
        if (c0.isEmpty()) {
            throw new BusinessException(400, "关闭要先选结论 (已处理 / 误报 / 接受偏差)");
        }
        for (Conclusion c : Conclusion.values()) {
            if (c.name().equals(c0)) return c;
        }
        throw new BusinessException(400, "结论只能是 已处理 / 误报 / 接受偏差");
    }

    static SourceType parseSourceType(String code) {
        String c0 = clean(code);
        for (SourceType t : SourceType.values()) {
            if (t.name().equals(c0)) return t;
        }
        throw new BusinessException(400, "不认识的问题来源");
    }

    private static String requireId(String id) {
        String s = clean(id);
        if (s.isEmpty() || s.length() > 191) throw new BusinessException(400, "缺少来源编号");
        return s;
    }

    private void requireSourceInFactory(String factoryId, SourceType type, String sourceId) {
        boolean found = switch (type) {
            case SHORTAGE_CASE -> caseRepository.findByIdAndFactoryIdAndSourceTypeIn(sourceId, factoryId,
                    List.of(ShortageCase.SourceType.SALES_ORDER)).isPresent();
            case DELIVERY_RISK -> caseRepository.findByIdAndFactoryIdAndSourceTypeIn(sourceId, factoryId,
                    List.of(ShortageCase.SourceType.DELIVERY_RISK)).isPresent();
            case PROCESS_PARAM_DEVIATION -> caseRepository.findByIdAndFactoryIdAndSourceTypeIn(sourceId, factoryId,
                    List.of(ShortageCase.SourceType.PROCESS_PARAM_DEVIATION)).isPresent();
            case COST_DEVIATION -> caseRepository.findByIdAndFactoryIdAndSourceTypeIn(sourceId, factoryId,
                    CostDeviationCaseWriter.SOURCES).isPresent();
            case COLD_CHAIN_ALERT -> parseLong(sourceId) != null
                    && coldChainAlertRepository.findByIdAndFactoryId(parseLong(sourceId), factoryId).isPresent();
            case DAILY_RECONCILE_RUN -> dailyRunRepository.findById(sourceId)
                    .filter(r -> factoryId.equals(r.getFactoryId())).isPresent();
        };
        if (!found) throw new BusinessException(404, "本厂没有这条问题的来源记录");
    }

    private static Long parseLong(String s) {
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 返回操作人 (本厂账号); 平台旁路账号返回 null (控制器已按工厂旁路校验过)。 */
    private User requireWritePermission(ActorRef actor, SourceType type) {
        if (actor.type() != AuthenticatedActorType.FACTORY_USER) {
            return null;
        }
        User user = userRepository.findById(actor.id())
                .orElseThrow(() -> new BusinessException(401, "无法识别当前操作人"));
        if (!permissionService.hasAnyPermission(user, WRITE_PERMISSIONS.get(type).toArray(String[]::new))) {
            throw new BusinessException(403, "你没有处理这类问题的权限");
        }
        return user;
    }

    private Set<SourceType> readableTypes(ActorRef actor) {
        if (actor.type() != AuthenticatedActorType.FACTORY_USER) {
            return EnumSet.allOf(SourceType.class);
        }
        User user = userRepository.findById(actor.id())
                .orElseThrow(() -> new BusinessException(401, "无法识别当前操作人"));
        Set<SourceType> out = EnumSet.noneOf(SourceType.class);
        for (SourceType t : SourceType.values()) {
            if (permissionService.hasAnyPermission(user, READ_PERMISSIONS.get(t).toArray(String[]::new))) out.add(t);
        }
        return out;
    }
}
