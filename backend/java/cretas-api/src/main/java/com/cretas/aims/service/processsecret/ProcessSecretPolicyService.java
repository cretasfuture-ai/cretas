package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.processsecret.ProcessSecretAccessEvent;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.entity.processsecret.ProcessSecretGrant;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretAccessEventRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.security.AuthenticatedActor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 保密策略、授权与审计查询。
 *
 * <p>权力边界 (方案 §2.2):</p>
 * <ul>
 *   <li>首次启用: 本厂在职的 factory_super_admin 发起, 并<b>指定</b>保密负责人 (可以是自己)。
 *       启用前该产品人人可见, 所以这一步不扩大任何人的知密范围。</li>
 *   <li>启用后: 改档位、换负责人、关闭、签发/撤销授权、看审计 —— 只有当前负责人本人。
 *       其他超管、权限管理员、平台角色一律不行, 改自己的角色也绕不过去。</li>
 *   <li>负责人不能给自己签授权 (本来就有); 授权必须写用途和到期时间, 平台支持人员最长 72 小时且不能导出。</li>
 * </ul>
 */
@Service
public class ProcessSecretPolicyService {

    static final Duration MAX_FACTORY_GRANT = Duration.ofDays(366);
    static final Duration MAX_PLATFORM_GRANT = Duration.ofHours(72);

    private final ProcessSecretPolicyRepository policyRepository;
    private final ProcessSecretGrantRepository grantRepository;
    private final ProcessSecretAccessEventRepository eventRepository;
    private final UserRepository userRepository;
    private final ProcessSecretAccessService accessService;

    public ProcessSecretPolicyService(ProcessSecretPolicyRepository policyRepository,
                                      ProcessSecretGrantRepository grantRepository,
                                      ProcessSecretAccessEventRepository eventRepository,
                                      UserRepository userRepository,
                                      ProcessSecretAccessService accessService) {
        this.policyRepository = policyRepository;
        this.grantRepository = grantRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.accessService = accessService;
    }

    public record PolicyView(String productTypeId, ProcessSecretStorageMode storageMode, Long ownerUserId,
                             Long policyVersion, boolean currentUserIsOwner, LocalDateTime updatedAt) { }

    public record GrantRequest(ProcessSecretGranteeType granteeType, Long granteeId, boolean canViewFull,
                               boolean canExport, boolean canEdit, String purpose, LocalDateTime expiresAt) { }

    @Transactional(readOnly = true)
    public List<PolicyView> listPolicies(AuthenticatedActor actor, String factoryId) {
        requireFactoryMember(actor, factoryId);
        return policyRepository.findByFactoryIdOrderByProductTypeIdAsc(factoryId).stream()
                .map(p -> view(actor, factoryId, p))
                .toList();
    }

    /**
     * 启用或修改一个产品的保密策略。{@code expectedPolicyVersion} 对已存在的策略必填 (乐观并发)。
     */
    @Transactional
    public PolicyView upsertPolicy(AuthenticatedActor actor, String factoryId, String productTypeId,
                                   ProcessSecretStorageMode mode, Long ownerUserId, Long expectedPolicyVersion) {
        requireText(productTypeId, "productTypeId");
        if (mode == null) throw new BusinessException(400, "storageMode 不能为空");
        if (ownerUserId == null) throw new BusinessException(400, "必须指定保密负责人 ownerUserId");
        requireActiveFactoryUser(ownerUserId, factoryId, "保密负责人");

        ProcessSecretPolicy policy = policyRepository.findForUpdate(factoryId, productTypeId).orElse(null);
        if (policy == null) {
            requireFactorySuperAdmin(actor, factoryId);
            policy = policyRepository.save(ProcessSecretPolicy.builder()
                    .factoryId(factoryId).productTypeId(productTypeId)
                    .storageMode(mode).ownerUserId(ownerUserId).createdBy(actor.id())
                    .build());
            audit(factoryId, productTypeId, actor, "ENABLE " + mode + " owner=" + ownerUserId);
        } else {
            requireOwner(actor, factoryId, policy);
            requireVersion(policy, expectedPolicyVersion);
            ProcessSecretStorageMode before = policy.getStorageMode();
            Long ownerBefore = policy.getOwnerUserId();
            policy.setStorageMode(mode);
            policy.setOwnerUserId(ownerUserId);
            policy.setPolicyVersion(policy.getPolicyVersion() + 1);
            policyRepository.save(policy);
            if (mode == ProcessSecretStorageMode.CUSTOMER_HELD && before != mode) {
                // 云端不再有可授予的完整工艺, 旧授权一并失效
                grantRepository.revokeAllForProduct(factoryId, productTypeId, LocalDateTime.now(), actor.id());
            }
            audit(factoryId, productTypeId, actor, "UPDATE " + before + "->" + mode
                    + " owner " + ownerBefore + "->" + ownerUserId);
        }
        accessService.invalidate(factoryId);
        return view(actor, factoryId, policy);
    }

    /** 关闭保密: 产品恢复改造前的可见性。只有负责人可做, 所有授权同时撤销。 */
    @Transactional
    public void disablePolicy(AuthenticatedActor actor, String factoryId, String productTypeId,
                              Long expectedPolicyVersion) {
        ProcessSecretPolicy policy = policyRepository.findForUpdate(factoryId, productTypeId)
                .orElseThrow(() -> new BusinessException(404, "该产品没有保密策略"));
        requireOwner(actor, factoryId, policy);
        requireVersion(policy, expectedPolicyVersion);
        grantRepository.revokeAllForProduct(factoryId, productTypeId, LocalDateTime.now(), actor.id());
        policyRepository.delete(policy);
        audit(factoryId, productTypeId, actor, "DISABLE " + policy.getStorageMode());
        accessService.invalidate(factoryId);
    }

    @Transactional
    public ProcessSecretGrant createGrant(AuthenticatedActor actor, String factoryId, String productTypeId,
                                          GrantRequest request) {
        ProcessSecretPolicy policy = policyRepository.findForUpdate(factoryId, productTypeId)
                .orElseThrow(() -> new BusinessException(404, "该产品没有保密策略"));
        requireOwner(actor, factoryId, policy);
        if (policy.getStorageMode() == ProcessSecretStorageMode.CUSTOMER_HELD) {
            throw new BusinessException(409, "该产品核心工艺由客户侧保存，云端没有可授予的完整工艺");
        }
        Objects.requireNonNull(request, "grant request");
        if (request.granteeType() == null || request.granteeId() == null || request.granteeId() <= 0) {
            throw new BusinessException(400, "必须指定被授权人");
        }
        if (!request.canViewFull() && !request.canExport() && !request.canEdit()) {
            throw new BusinessException(400, "至少选择一项：查看完整工艺 / 导出 / 编辑");
        }
        requireText(request.purpose(), "purpose");
        if (request.purpose().length() > 200) throw new BusinessException(400, "用途说明不超过 200 字");
        LocalDateTime now = LocalDateTime.now();
        if (request.expiresAt() == null || !request.expiresAt().isAfter(now)) {
            throw new BusinessException(400, "必须设置晚于当前时间的到期时间");
        }
        if (request.granteeType() == ProcessSecretGranteeType.FACTORY_USER) {
            if (request.granteeId().equals(policy.getOwnerUserId())) {
                throw new BusinessException(400, "保密负责人本人无需授权");
            }
            requireActiveFactoryUser(request.granteeId(), factoryId, "被授权人");
            if (request.expiresAt().isAfter(now.plus(MAX_FACTORY_GRANT))) {
                throw new BusinessException(400, "授权有效期最长一年");
            }
        } else {
            if (request.canExport()) throw new BusinessException(400, "平台支持授权不允许导出");
            if (request.expiresAt().isAfter(now.plus(MAX_PLATFORM_GRANT))) {
                throw new BusinessException(400, "平台支持授权最长 72 小时");
            }
        }
        ProcessSecretGrant grant = grantRepository.save(ProcessSecretGrant.builder()
                .factoryId(factoryId).productTypeId(productTypeId)
                .granteeType(request.granteeType()).granteeId(request.granteeId())
                .canViewFull(request.canViewFull()).canExport(request.canExport()).canEdit(request.canEdit())
                .purpose(request.purpose().trim()).expiresAt(request.expiresAt()).grantedBy(actor.id())
                .build());
        audit(factoryId, productTypeId, actor, "GRANT #" + grant.getId() + " to " + request.granteeType()
                + ":" + request.granteeId());
        return grant;
    }

    @Transactional
    public ProcessSecretGrant revokeGrant(AuthenticatedActor actor, String factoryId, Long grantId) {
        ProcessSecretGrant grant = grantRepository.findByIdAndFactoryId(grantId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "授权不存在"));
        ProcessSecretPolicy policy = policyRepository.findForUpdate(factoryId, grant.getProductTypeId())
                .orElseThrow(() -> new BusinessException(404, "该产品没有保密策略"));
        requireOwner(actor, factoryId, policy);
        if (grant.getRevokedAt() == null) {
            grant.setRevokedAt(LocalDateTime.now());
            grant.setRevokedBy(actor.id());
            grantRepository.save(grant);
            audit(factoryId, grant.getProductTypeId(), actor, "REVOKE #" + grant.getId());
        }
        return grant;
    }

    @Transactional(readOnly = true)
    public List<ProcessSecretGrant> listGrants(AuthenticatedActor actor, String factoryId, String productTypeId) {
        requireOwner(actor, factoryId, requirePolicy(factoryId, productTypeId));
        return grantRepository.findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(factoryId, productTypeId);
    }

    @Transactional(readOnly = true)
    public List<ProcessSecretAccessEvent> listEvents(AuthenticatedActor actor, String factoryId,
                                                     String productTypeId, int limit) {
        requireOwner(actor, factoryId, requirePolicy(factoryId, productTypeId));
        int size = Math.max(1, Math.min(limit, 500));
        return eventRepository.findByFactoryIdAndProductTypeIdOrderByCreatedAtDesc(factoryId, productTypeId,
                PageRequest.of(0, size));
    }

    // ---------------------------------------------------------------- helpers

    ProcessSecretPolicy requirePolicy(String factoryId, String productTypeId) {
        return policyRepository.findByFactoryIdAndProductTypeId(factoryId, productTypeId)
                .orElseThrow(() -> new BusinessException(404, "该产品没有保密策略"));
    }

    void requireOwner(AuthenticatedActor actor, String factoryId, ProcessSecretPolicy policy) {
        if (!ProcessSecretAccessService.isOwner(actor, factoryId, policy)) {
            if (actor != null) {
                accessService.audit(factoryId, policy.getProductTypeId(), actor, ProcessSecretAction.MANAGE_POLICY,
                        "DENY", "非保密负责人", null);
            }
            throw new BusinessException(403, "只有该产品的保密负责人可以管理保密设置");
        }
    }

    private void requireFactoryMember(AuthenticatedActor actor, String factoryId) {
        if (actor == null || actor.isPlatformAdmin() || !factoryId.equals(actor.factoryId())) {
            throw new BusinessException(403, "只有本厂用户可以查看保密设置");
        }
    }

    private void requireFactorySuperAdmin(AuthenticatedActor actor, String factoryId) {
        requireFactoryMember(actor, factoryId);
        User user = userRepository.findByIdAndFactoryId(actor.id(), factoryId)
                .orElseThrow(() -> new BusinessException(403, "无法确认当前用户"));
        if (!Boolean.TRUE.equals(user.getIsActive()) || user.getRoleEnum() != FactoryUserRole.factory_super_admin) {
            throw new BusinessException(403, "首次启用工艺保密需要本厂工厂总监发起");
        }
    }

    private void requireActiveFactoryUser(Long userId, String factoryId, String label) {
        User user = userRepository.findByIdAndFactoryId(userId, factoryId)
                .orElseThrow(() -> new BusinessException(400, label + "必须是本厂用户"));
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new BusinessException(400, label + "账号已停用");
        }
    }

    private static void requireVersion(ProcessSecretPolicy policy, Long expected) {
        if (expected == null || !expected.equals(policy.getPolicyVersion())) {
            throw new BusinessException(409, "保密设置已被修改，请刷新后重试");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new BusinessException(400, field + " 不能为空");
    }

    private PolicyView view(AuthenticatedActor actor, String factoryId, ProcessSecretPolicy p) {
        return new PolicyView(p.getProductTypeId(), p.getStorageMode(), p.getOwnerUserId(), p.getPolicyVersion(),
                ProcessSecretAccessService.isOwner(actor, factoryId, p), p.getUpdatedAt());
    }

    private void audit(String factoryId, String productTypeId, AuthenticatedActor actor, String what) {
        // 管理动作与业务写入同事务提交; 审计走独立事务, 写不进去时整个管理动作回滚
        if (!accessService.audit(factoryId, productTypeId, actor, ProcessSecretAction.MANAGE_POLICY,
                "ALLOW", what, null)) {
            throw new BusinessException(503, "保密审计暂不可用，本次修改未生效");
        }
    }
}
