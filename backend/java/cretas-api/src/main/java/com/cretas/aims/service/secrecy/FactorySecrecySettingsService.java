package com.cretas.aims.service.secrecy;

import com.cretas.aims.entity.secrecy.FactorySecrecySetting;
import com.cretas.aims.entity.secrecy.FactorySecrecySettingAudit;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingAuditRepository;
import com.cretas.aims.repository.secrecy.FactorySecrecySettingRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.platform.PlatformFactoryControlAuthority;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 平台改工厂保密配置 (唯一写入口; 工厂端没有任何写路径)。
 *
 * <ul>
 *   <li>权限: 控制器先过 {@link PlatformFactoryControlAuthority#require} (平台令牌、非查看模式),
 *       这里再 {@link PlatformFactoryControlAuthority#lock} (库里必须是有效的平台超级管理员 + 锁工厂行)。</li>
 *   <li>改 a / b / c / e 必须填理由 (空白 = 400); 关 e 还必须 {@code confirmed=true} (否则 400)。</li>
 *   <li>改设置和写留痕在同一个事务里; 每改一项写一行留痕 (「全开 / 全关」也是逐项, 本身不存值)。值没变的项不写。</li>
 *   <li>提交后清缓存: 配置缓存、工艺保密名单缓存 ({@link ProcessSecretAccessService#invalidate})、保密名缓存。</li>
 * </ul>
 */
@Service
public class FactorySecrecySettingsService {

    static final int MAX_REASON = 500;
    static final int AUDIT_LIMIT = 20;

    public record Update(Boolean enabled, Boolean followProcessSecret, String reason, Boolean confirmed) { }

    public record BulkUpdate(Boolean enabled, String reason, Boolean confirmed) { }

    public record ItemView(String code, String letter, String label, String stored, boolean effective,
                           boolean inEffect) { }

    public record AuditView(Long id, String itemCode, String oldValue, String newValue, Long actorId,
                            String actorRole, String reason, boolean confirmed, boolean bulk,
                            LocalDateTime changedAt) { }

    public record SettingsView(String factoryId, List<ItemView> items, List<AuditView> recentAudit) { }

    public record ItemDetailView(String factoryId, ItemView item, List<AuditView> recentAudit) { }

    private final FactorySecrecySettingRepository settings;
    private final FactorySecrecySettingAuditRepository audits;
    private final PlatformFactoryControlAuthority authority;
    private final FactorySecrecyConfig config;
    private final ProcessSecretAccessService accessService;
    private final ProcessSecretSampleFilter sampleFilter;

    public FactorySecrecySettingsService(FactorySecrecySettingRepository settings,
                                         FactorySecrecySettingAuditRepository audits,
                                         PlatformFactoryControlAuthority authority,
                                         FactorySecrecyConfig config,
                                         ProcessSecretAccessService accessService,
                                         ProcessSecretSampleFilter sampleFilter) {
        this.settings = settings;
        this.audits = audits;
        this.authority = authority;
        this.config = config;
        this.accessService = accessService;
        this.sampleFilter = sampleFilter;
    }

    @Transactional(readOnly = true)
    public SettingsView view(String factoryId, ActorRef actor) {
        authority.requireReadRef(actor, factoryId);
        Map<SecrecyItem, FactorySecrecyConfig.Stored> stored = config.storedFresh(factoryId);
        List<ItemView> items = new ArrayList<>();
        for (SecrecyItem item : SecrecyItem.values()) items.add(itemView(item, stored));
        return new SettingsView(factoryId, items,
                audits.findTop20ByFactoryIdOrderByChangedAtDescIdDesc(factoryId).stream()
                        .map(FactorySecrecySettingsService::auditView).toList());
    }

    @Transactional(readOnly = true)
    public ItemDetailView item(String factoryId, String itemCode, ActorRef actor) {
        SecrecyItem item = requireItem(itemCode);
        authority.requireReadRef(actor, factoryId);
        Map<SecrecyItem, FactorySecrecyConfig.Stored> stored = config.storedFresh(factoryId);
        return new ItemDetailView(factoryId, itemView(item, stored),
                audits.findTop20ByFactoryIdAndItemCodeOrderByChangedAtDescIdDesc(factoryId, item.name()).stream()
                        .map(FactorySecrecySettingsService::auditView).toList());
    }

    @Transactional
    public ItemDetailView update(String factoryId, String itemCode, Update body, ActorRef actor, String actorRole) {
        SecrecyItem item = requireItem(itemCode);
        if (body == null) throw new BusinessException(400, "请求体不能为空");
        FactorySecrecyConfig.Stored target = target(item, body);
        String reason = normalizeReason(body.reason());
        boolean confirmed = Boolean.TRUE.equals(body.confirmed());
        validate(item, target, reason, confirmed);
        authority.lock(actor, factoryId);
        Map<SecrecyItem, FactorySecrecyConfig.Stored> before = config.storedFresh(factoryId);
        write(factoryId, item, before.getOrDefault(item, FactorySecrecyConfig.Stored.UNSET), target, reason,
                confirmed, false, actor, actorRole);
        invalidate(factoryId);
        return item(factoryId, item.name(), actor);
    }

    /**
     * 「全开 / 全关」: a–e 逐项设为开 / 关, f 设为跟随工艺保密 (于是 f 的生效值与 a 一致)。逐项写、逐项留痕,
     * 同一事务; 本身不存值。校验与逐项改一致 (要理由; 关掉开着的 e 要确认)。
     */
    @Transactional
    public SettingsView bulk(String factoryId, BulkUpdate body, ActorRef actor, String actorRole) {
        if (body == null || body.enabled() == null) throw new BusinessException(400, "必须指定全开还是全关");
        String reason = normalizeReason(body.reason());
        boolean confirmed = Boolean.TRUE.equals(body.confirmed());
        authority.lock(actor, factoryId);
        Map<SecrecyItem, FactorySecrecyConfig.Stored> before = config.storedFresh(factoryId);
        FactorySecrecyConfig.Stored onOff = body.enabled() ? FactorySecrecyConfig.Stored.ON : FactorySecrecyConfig.Stored.OFF;
        for (SecrecyItem item : SecrecyItem.values()) {
            FactorySecrecyConfig.Stored target = item.triState() ? FactorySecrecyConfig.Stored.FOLLOW : onOff;
            FactorySecrecyConfig.Stored old = before.getOrDefault(item, FactorySecrecyConfig.Stored.UNSET);
            if (sameEffectiveStorage(old, target)) continue;
            validate(item, target, reason, confirmed);
        }
        for (SecrecyItem item : SecrecyItem.values()) {
            FactorySecrecyConfig.Stored target = item.triState() ? FactorySecrecyConfig.Stored.FOLLOW : onOff;
            write(factoryId, item, before.getOrDefault(item, FactorySecrecyConfig.Stored.UNSET), target, reason,
                    confirmed, true, actor, actorRole);
        }
        invalidate(factoryId);
        return view(factoryId, actor);
    }

    // ------------------------------------------------------------------

    private void write(String factoryId, SecrecyItem item, FactorySecrecyConfig.Stored old,
                       FactorySecrecyConfig.Stored target, String reason, boolean confirmed, boolean bulk,
                       ActorRef actor, String actorRole) {
        if (sameEffectiveStorage(old, target)) return;
        LocalDateTime now = LocalDateTime.now();
        settings.save(FactorySecrecySetting.builder()
                .factoryId(factoryId).itemCode(item.name())
                .enabled(target == FactorySecrecyConfig.Stored.FOLLOW ? null : target == FactorySecrecyConfig.Stored.ON)
                .updatedAt(now).updatedBy(actor.id())
                .build());
        audits.save(FactorySecrecySettingAudit.builder()
                .factoryId(factoryId).itemCode(item.name())
                .oldValue(old.name()).newValue(target.name())
                .actorId(actor.id()).actorRole(actorRole == null || actorRole.isBlank() ? "UNKNOWN" : actorRole)
                .reason(reason).confirmed(confirmed).bulk(bulk)
                .changedAt(now)
                .build());
    }

    /** 没有行 (UNSET) 与「跟随」对 f 是同一个生效来源, 但存的状态不同 —— 只有字面相同才算没变。 */
    private static boolean sameEffectiveStorage(FactorySecrecyConfig.Stored old, FactorySecrecyConfig.Stored target) {
        return old == target;
    }

    private void invalidate(String factoryId) {
        config.invalidate(factoryId);
        accessService.invalidate(factoryId);
        if (sampleFilter != null) sampleFilter.invalidateNameCache();
    }

    static SecrecyItem requireItem(String itemCode) {
        return SecrecyItem.fromCode(itemCode)
                .orElseThrow(() -> new BusinessException(400, "未知的保密配置项"));
    }

    static FactorySecrecyConfig.Stored target(SecrecyItem item, Update body) {
        if (Boolean.TRUE.equals(body.followProcessSecret())) {
            if (!item.triState()) throw new BusinessException(400, "「" + item.label() + "」不能设为跟随工艺保密");
            return FactorySecrecyConfig.Stored.FOLLOW;
        }
        if (body.enabled() == null) throw new BusinessException(400, "必须指定开启还是关闭");
        return body.enabled() ? FactorySecrecyConfig.Stored.ON : FactorySecrecyConfig.Stored.OFF;
    }

    static String normalizeReason(String reason) {
        if (reason == null) return null;
        String trimmed = reason.strip();
        if (trimmed.length() > MAX_REASON) throw new BusinessException(400, "理由不能超过 " + MAX_REASON + " 个字");
        return trimmed.isEmpty() ? null : trimmed;
    }

    static void validate(SecrecyItem item, FactorySecrecyConfig.Stored target, String reason, boolean confirmed) {
        if (item.requiresReason() && reason == null) {
            throw new BusinessException(400, "修改「" + item.label() + "」必须填写理由");
        }
        if (item.offRequiresConfirmation() && target == FactorySecrecyConfig.Stored.OFF && !confirmed) {
            throw new BusinessException(400, "关闭「" + item.label() + "」需要二次确认：" + item.offConfirmWarning());
        }
    }

    private ItemView itemView(SecrecyItem item, Map<SecrecyItem, FactorySecrecyConfig.Stored> stored) {
        boolean effective = FactorySecrecyConfig.resolve(stored, item);
        boolean inEffect = effective;
        if (item.effectiveOnlyWhen() != null) {
            inEffect = effective && SecrecyItem.fromCode(item.effectiveOnlyWhen())
                    .map(dep -> FactorySecrecyConfig.resolve(stored, dep)).orElse(true);
        }
        return new ItemView(item.name(), item.letter(), item.label(),
                stored.getOrDefault(item, FactorySecrecyConfig.Stored.UNSET).name(), effective, inEffect);
    }

    private static AuditView auditView(FactorySecrecySettingAudit a) {
        return new AuditView(a.getId(), a.getItemCode(), a.getOldValue(), a.getNewValue(), a.getActorId(),
                a.getActorRole(), a.getReason(), a.isConfirmed(), a.isBulk(), a.getChangedAt());
    }
}
