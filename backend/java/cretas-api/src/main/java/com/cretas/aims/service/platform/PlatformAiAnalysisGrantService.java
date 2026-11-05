package com.cretas.aims.service.platform;

import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.entity.platform.PlatformAiAnalysisGrant;
import com.cretas.aims.entity.platform.PlatformAiAnalysisGrantAudit;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.platform.PlatformAiAnalysisGrantAuditRepository;
import com.cretas.aims.repository.platform.PlatformAiAnalysisGrantRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.PlatformFactoryViewReadPolicy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 谁能用平台 AI 分析入口 (Steve 2026-11-04): 有效的平台超级管理员本人可用; 其余平台账号要由超级管理员开通,
 * 开给一个平台角色或一个平台账号。
 *
 * <ul>
 *   <li>判定 ({@link #requireEntry}) 每次现查库, ⛔ 不加缓存: 撤销下一次调用就生效, 账号停用同理;</li>
 *   <li>按库里的角色判, 不按令牌里的角色: 令牌最长活 24 小时, 被降级的人不能靠旧令牌用;</li>
 *   <li>开通 / 撤销与留痕在同一个事务里, 每次一行, 必须写理由; 没有变化 (已开通 / 本来没开通) 不写留痕;</li>
 *   <li>超级管理员的账号不能开通 (本来就不需要)。这只挡账号授权: 超级管理员仍能开通一个角色, 之后被降级到这个角色就能用,
 *       所以降级时要连角色授权一起看;</li>
 *   <li>账号授权在账号后来改角色时保留, 直到撤销; 角色授权跟着库里当前的角色走;</li>
 *   <li>存在与角色只在开通时校验, 撤销只看格式: 账号后来升成超级管理员或行被删了, 那条授权照样撤得掉,
 *       否则它留在表里, 账号再被降级时授权就回来了。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PlatformAiAnalysisGrantService {

    static final int MAX_REASON = 500;
    static final String NOT_GRANTED_MESSAGE = "该平台账号未开通 AI 分析入口，请联系平台超级管理员开通";
    static final String INACTIVE_MESSAGE = "仅当前有效的平台账号可使用 AI 分析入口";
    static final String NOT_MANAGER_MESSAGE = "仅当前有效的平台超级管理员可管理 AI 分析入口授权";
    static final String ALREADY_GRANTED_MESSAGE = "该角色或账号已经开通";
    static final String ALREADY_GRANTED_HINT = "不需要再开通；要改理由或开通人，先在授权列表里撤销这一条，再重新开通";
    private static final String IDENTITY_MESSAGE = "无法确认平台登录身份";

    public record GrantRequest(String granteeType, String grantee, String reason) { }

    public record GrantView(String granteeType, String grantee, Long grantedBy, LocalDateTime grantedAt, String reason) { }

    public record AuditView(Long id, String action, String granteeType, String grantee, Long actorId, String actorRole,
                            String reason, LocalDateTime changedAt) { }

    public record GrantsView(List<GrantView> grants, List<AuditView> recentAudit) { }

    private record Grantee(String type, String ref) { }

    private final PlatformAiAnalysisGrantRepository grants;
    private final PlatformAiAnalysisGrantAuditRepository audits;
    private final PlatformAdminRepository admins;
    private final PlatformFactoryControlAuthority authority;

    /** 入口的身份闸: 平台令牌、非查看模式、库里有效、超级管理员或已开通。其余 403。 */
    public AuthenticatedActor requireEntry(HttpServletRequest request) {
        AuthenticatedActor actor = platformActor(request);
        PlatformAdmin admin = admins.findById(actor.id()).filter(PlatformAdmin::isActive)
                .orElseThrow(() -> new BusinessException(403, INACTIVE_MESSAGE));
        if (!admin.isSuperAdmin() && !granted(admin)) {
            throw new BusinessException(403, NOT_GRANTED_MESSAGE);
        }
        return actor;
    }

    private boolean granted(PlatformAdmin admin) {
        return grants.existsByGranteeTypeAndGranteeRef(PlatformAiAnalysisGrant.ACCOUNT, String.valueOf(admin.getId()))
                || (admin.getPlatformRole() != null && grants.existsByGranteeTypeAndGranteeRef(
                        PlatformAiAnalysisGrant.ROLE, admin.getPlatformRole().name()));
    }

    /** 授权接口的身份闸: 平台令牌、非查看模式、库里有效的超级管理员。 */
    public AuthenticatedActor requireManager(HttpServletRequest request) {
        AuthenticatedActor actor = platformActor(request);
        requireManager(actor);
        return actor;
    }

    private void requireManager(AuthenticatedActor actor) {
        if (!authority.canManage(new ActorRef(actor.type(), actor.id()))) {
            throw new BusinessException(403, NOT_MANAGER_MESSAGE);
        }
    }

    private static AuthenticatedActor platformActor(HttpServletRequest request) {
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(403, IDENTITY_MESSAGE));
        if (actor.type() != AuthenticatedActorType.PLATFORM_ADMIN || !"PLATFORM".equals(actor.factoryId())
                || PlatformFactoryViewReadPolicy.isView(request)) {
            throw new BusinessException(403, IDENTITY_MESSAGE);
        }
        return actor;
    }

    @Transactional(readOnly = true)
    public GrantsView list(AuthenticatedActor actor) {
        requireManager(actor);
        return new GrantsView(
                grants.findAllByOrderByGrantedAtDescGranteeRefAsc().stream().map(PlatformAiAnalysisGrantService::view).toList(),
                audits.findTop50ByOrderByChangedAtDescIdDesc().stream().map(PlatformAiAnalysisGrantService::view).toList());
    }

    @Transactional
    public GrantView grant(AuthenticatedActor actor, GrantRequest body) {
        requireManager(actor);
        Grantee grantee = grantable(body);
        String reason = reason(body);
        if (grants.existsByGranteeTypeAndGranteeRef(grantee.type(), grantee.ref())) {
            throw alreadyGranted();
        }
        LocalDateTime now = LocalDateTime.now();
        PlatformAiAnalysisGrant saved;
        try {
            saved = grants.saveAndFlush(PlatformAiAnalysisGrant.builder().granteeType(grantee.type())
                    .granteeRef(grantee.ref()).grantedBy(actor.id()).grantedAt(now).reason(reason).build());
        } catch (DataIntegrityViolationException lostTheRace) {
            // 上面查的时候还没有, 写的时候别人先写了: 只有一方成功, 另一方不留痕、不改已有的行
            throw alreadyGranted();
        }
        record(PlatformAiAnalysisGrantAudit.GRANT, grantee, actor, reason, now);
        return view(saved);
    }

    @Transactional
    public void revoke(AuthenticatedActor actor, GrantRequest body) {
        requireManager(actor);
        Grantee grantee = parsed(body);
        String reason = reason(body);
        if (grants.revoke(grantee.type(), grantee.ref()) == 0) {
            throw new BusinessException(404, "该角色或账号没有开通");
        }
        record(PlatformAiAnalysisGrantAudit.REVOKE, grantee, actor, reason, LocalDateTime.now());
    }

    private static BusinessException alreadyGranted() {
        return new BusinessException(409, ALREADY_GRANTED_MESSAGE).withHint(ALREADY_GRANTED_HINT);
    }

    private void record(String action, Grantee grantee, AuthenticatedActor actor, String reason, LocalDateTime at) {
        audits.save(PlatformAiAnalysisGrantAudit.builder().action(action).granteeType(grantee.type())
                .granteeRef(grantee.ref()).actorId(actor.id()).actorRole(PlatformRole.super_admin.name())
                .reason(reason).changedAt(at).build());
    }

    /** 只看格式, 不查库: 撤销走这一步就够了, 表里没有这一行时由撤销本身给 404。 */
    private static Grantee parsed(GrantRequest body) {
        if (body == null || body.granteeType() == null || body.grantee() == null) {
            throw new BusinessException(400, "必须指定开通给哪个平台角色或哪个平台账号");
        }
        String value = body.grantee().strip();
        return switch (body.granteeType().strip()) {
            case PlatformAiAnalysisGrant.ROLE -> {
                if (value.isEmpty()) throw new BusinessException(400, "必须指定开通给哪个平台角色或哪个平台账号");
                yield new Grantee(PlatformAiAnalysisGrant.ROLE, value);
            }
            case PlatformAiAnalysisGrant.ACCOUNT -> {
                if (!value.matches("[1-9][0-9]{0,18}")) throw new BusinessException(400, "平台账号编号无效");
                yield new Grantee(PlatformAiAnalysisGrant.ACCOUNT, value);
            }
            default -> throw new BusinessException(400, "授权对象类型只能是 ROLE 或 ACCOUNT");
        };
    }

    /** 开通才查: 角色要是可开通的, 账号要存在且不是超级管理员。 */
    private Grantee grantable(GrantRequest body) {
        Grantee grantee = parsed(body);
        if (PlatformAiAnalysisGrant.ROLE.equals(grantee.type())) {
            requireGrantableRole(grantee.ref());
        } else {
            requireGrantableAccount(grantee.ref());
        }
        return grantee;
    }

    private static void requireGrantableRole(String value) {
        for (PlatformRole role : PlatformRole.values()) {
            if (role != PlatformRole.super_admin && role.name().equals(value)) return;
        }
        throw new BusinessException(400, "不是可开通的平台角色（超级管理员不需要开通）");
    }

    private void requireGrantableAccount(String value) {
        PlatformAdmin target = admins.findById(Long.valueOf(value))
                .orElseThrow(() -> new BusinessException(404, "平台账号不存在"));
        if (target.isSuperAdmin()) {
            throw new BusinessException(400, "超级管理员不需要开通");
        }
    }

    private static String reason(GrantRequest body) {
        String reason = body.reason() == null ? "" : body.reason().strip();
        if (reason.isEmpty()) throw new BusinessException(400, "必须填写理由");
        if (reason.length() > MAX_REASON) throw new BusinessException(400, "理由不能超过 " + MAX_REASON + " 字");
        return reason;
    }

    private static GrantView view(PlatformAiAnalysisGrant row) {
        return new GrantView(row.getGranteeType(), row.getGranteeRef(), row.getGrantedBy(), row.getGrantedAt(), row.getReason());
    }

    private static AuditView view(PlatformAiAnalysisGrantAudit row) {
        return new AuditView(row.getId(), row.getAction(), row.getGranteeType(), row.getGranteeRef(), row.getActorId(),
                row.getActorRole(), row.getReason(), row.getChangedAt());
    }
}
