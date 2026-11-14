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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * 「发现的问题」处置: 关闭 (必须选结论并写一句) / 转成我的待办 / 恢复。
 *
 * <p>每次写入都校验: ① 来源对象确实属于本厂 (跨厂 id 一律 404, 不靠前端); ② 操作人对该来源有处理权限
 * (与来源页处理动作同一组 read_write 码)。⛔ 不改来源单据。
 */
@Service
@RequiredArgsConstructor
public class IssueDispositionService {

    public static final int REASON_MIN = 2;
    public static final int REASON_MAX = 200;
    public static final int TITLE_MAX = 200;

    /** 各来源的处理权限 (任一即可), 与来源页处理动作的 @RequirePermission 一致。 */
    static final Map<SourceType, String[]> WRITE_PERMISSIONS = Map.of(
            SourceType.SHORTAGE_CASE, new String[]{"procurement:read_write", "production:read_write", "warehouse:read_write", "quality:read_write"},
            SourceType.DELIVERY_RISK, new String[]{"production:read_write", "sales:read_write"},
            SourceType.PROCESS_PARAM_DEVIATION, new String[]{"quality:read_write", "production:read_write"},
            SourceType.COST_DEVIATION, new String[]{"finance:read_write", "production:read_write"},
            SourceType.COLD_CHAIN_ALERT, new String[]{"quality:read_write"},
            SourceType.DAILY_RECONCILE_RUN, new String[]{"finance:read_write", "production:read_write"});

    private final IssueDispositionRepository repository;
    private final ShortageCaseRepository caseRepository;
    private final ColdChainAlertRepository coldChainAlertRepository;
    private final DailyReconcileRunRepository dailyRunRepository;
    private final UserRepository userRepository;
    private final PermissionService permissionService;

    public record SourceRef(String sourceType, String sourceId, String title, String linkPath) {
    }

    @Transactional(readOnly = true)
    public List<IssueDisposition> listActive(String factoryId) {
        return repository.findByFactoryIdAndStatusInOrderByActedAtDesc(factoryId,
                EnumSet.of(Status.CLOSED, Status.CONVERTED), PageRequest.of(0, 500));
    }

    @Transactional(readOnly = true)
    public List<IssueDisposition> assignedTo(String factoryId, Long userId) {
        return repository.findByFactoryIdAndAssigneeUserIdAndStatusOrderByActedAtDesc(factoryId, userId,
                Status.CONVERTED, PageRequest.of(0, 200));
    }

    /** 关闭: 结论必须是原型清单之一, 说明去空白后 ≥ 2 字。 */
    @Transactional
    public IssueDisposition close(String factoryId, ActorRef actor, SourceRef ref, String conclusionCode, String reason) {
        Conclusion conclusion = parseConclusion(conclusionCode);
        String why = reason == null ? "" : reason.trim();
        if (why.length() < REASON_MIN) {
            throw new BusinessException(400, "关闭要写一句说明 (至少 " + REASON_MIN + " 个字)");
        }
        if (why.length() > REASON_MAX) {
            throw new BusinessException(400, "说明最多 " + REASON_MAX + " 个字");
        }
        IssueDisposition d = prepare(factoryId, actor, ref);
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
        IssueDisposition d = prepare(factoryId, actor, ref);
        if (d.getStatus() == Status.CLOSED) {
            throw new BusinessException(409, "这条问题已关闭, 先恢复再转待办");
        }
        d.setStatus(Status.CONVERTED);
        d.setConclusion(null);
        d.setReason(null);
        d.setAssigneeUserId(actor.id());
        return repository.save(d);
    }

    /** 恢复: 回到未处置, 清空结论与指派。 */
    @Transactional
    public IssueDisposition reopen(String factoryId, ActorRef actor, SourceRef ref) {
        SourceType type = parseSourceType(ref.sourceType());
        IssueDisposition d = repository.findByFactoryIdAndSourceTypeAndSourceId(factoryId, type, requireId(ref.sourceId()))
                .orElseThrow(() -> new BusinessException(404, "这条问题没有处置记录, 不用恢复"));
        requireSourceInFactory(factoryId, type, d.getSourceId());
        requireWritePermission(actor, type);
        d.setStatus(Status.OPEN);
        d.setConclusion(null);
        d.setReason(null);
        d.setAssigneeUserId(null);
        stamp(d, actor);
        return repository.save(d);
    }

    private IssueDisposition prepare(String factoryId, ActorRef actor, SourceRef ref) {
        SourceType type = parseSourceType(ref.sourceType());
        String sourceId = requireId(ref.sourceId());
        requireSourceInFactory(factoryId, type, sourceId);
        requireWritePermission(actor, type);
        String title = ref.title() == null ? "" : ref.title().trim();
        if (title.isEmpty()) throw new BusinessException(400, "缺少问题标题");
        if (title.length() > TITLE_MAX) title = title.substring(0, TITLE_MAX);
        String link = ref.linkPath() == null ? null : ref.linkPath().trim();
        if (link != null && !link.isEmpty() && (!link.startsWith("/") || link.startsWith("//") || link.length() > 255)) {
            throw new BusinessException(400, "链接只能是站内路径");
        }
        IssueDisposition d = repository.findByFactoryIdAndSourceTypeAndSourceId(factoryId, type, sourceId)
                .orElseGet(() -> {
                    IssueDisposition n = new IssueDisposition();
                    n.setFactoryId(factoryId);
                    n.setSourceType(type);
                    n.setSourceId(sourceId);
                    return n;
                });
        d.setTitle(title);
        d.setLinkPath(link == null || link.isEmpty() ? null : link);
        stamp(d, actor);
        return d;
    }

    private static void stamp(IssueDisposition d, ActorRef actor) {
        d.setActorType(actor.type().name());
        d.setActorId(actor.id());
        d.setActedAt(LocalDateTime.now());
    }

    static Conclusion parseConclusion(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(400, "关闭要先选结论 (已处理 / 误报 / 接受偏差)");
        }
        for (Conclusion c : Conclusion.values()) {
            if (c.name().equals(code.trim())) return c;
        }
        throw new BusinessException(400, "结论只能是 已处理 / 误报 / 接受偏差");
    }

    static SourceType parseSourceType(String code) {
        if (code != null) {
            for (SourceType t : SourceType.values()) {
                if (t.name().equals(code.trim())) return t;
            }
        }
        throw new BusinessException(400, "不认识的问题来源");
    }

    private static String requireId(String id) {
        String s = id == null ? "" : id.trim();
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

    private void requireWritePermission(ActorRef actor, SourceType type) {
        if (actor.type() != AuthenticatedActorType.FACTORY_USER) {
            return; // 平台账号已在控制器按工厂旁路校验过
        }
        User user = userRepository.findById(actor.id())
                .orElseThrow(() -> new BusinessException(401, "无法识别当前操作人"));
        if (!permissionService.hasAnyPermission(user, WRITE_PERMISSIONS.get(type))) {
            throw new BusinessException(403, "你没有处理这类问题的权限");
        }
    }
}
