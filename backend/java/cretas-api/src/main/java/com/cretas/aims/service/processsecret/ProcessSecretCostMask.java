package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.security.AuthenticatedActor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 不走 JSON 裁剪器的出口 (Excel / CSV 导出、拼进文字的 AI 提示词) 用的成本遮蔽判定:
 * 给一组产品, 返回当前调用者看不到成本的那几个 (受保护且没有完整查看权)。
 *
 * <p>保密产品的成本合计 (原料 / 调料 / 包材小计、总成本、单位成本、毛利) 对无授权账号一律「已保密」
 * (Steve 2026-10-28 选 A), 与 {@link ProcessSecretPayloadScrubber} 对 JSON 的口径一致。
 * 判定与审计都交给 {@link ProcessSecretAccessService#filterPermitted}: 逐个记 ALLOW / DENY,
 * ALLOW 写不进去按无权处理。识别不出调用者 (定时任务等) 时按无权处理。</p>
 */
@Component
public class ProcessSecretCostMask {

    public static final String LABEL = "已保密";

    private final ProcessSecretAccessService accessService;

    public ProcessSecretCostMask(ProcessSecretAccessService accessService) {
        this.accessService = accessService;
    }

    /** 当前调用者看不到成本的产品; 没有受保护产品时为空集合 (不写审计)。 */
    public Set<String> hiddenFor(String factoryId, Collection<String> productTypeIds, String resourceRef) {
        return hiddenFor(AuthenticatedActor.fromCurrentExecution().orElse(null), factoryId, productTypeIds,
                resourceRef);
    }

    public Set<String> hiddenFor(AuthenticatedActor actor, String factoryId, Collection<String> productTypeIds,
                                 String resourceRef) {
        if (factoryId == null || productTypeIds == null || productTypeIds.isEmpty()) return Set.of();
        Set<String> protectedIds = accessService.protectedProductTypeIds(factoryId);
        if (protectedIds.isEmpty()) return Set.of();
        List<String> candidates = productTypeIds.stream()
                .filter(Objects::nonNull)
                .filter(protectedIds::contains)
                .distinct()
                .toList();
        if (candidates.isEmpty()) return Set.of();
        List<String> permitted = accessService.filterPermitted(actor, factoryId, candidates,
                ProcessSecretAction.VIEW_FULL, false, resourceRef);
        Set<String> hidden = new LinkedHashSet<>(candidates);
        permitted.forEach(hidden::remove);
        return hidden;
    }
}
