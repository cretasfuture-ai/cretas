package com.cretas.aims.service.platform;

import com.cretas.aims.dto.AIRequestDTO;
import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.AIEnterpriseService;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretPlatformAiScope;
import com.cretas.aims.service.processsecret.ProcessSecretReadTracker;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 平台专用 AI 分析入口 (Steve 2026-11-04: 「平台 ai 能 access 所有的, 不管是什么配置」)。
 *
 * <p>入口不读 a / b: 配置怎么设都能用。留下来的约束: 必须点名工厂、一次一家、谁能用看授权
 * ({@link PlatformAiAnalysisGrantService})、关停的工厂不给、每次使用都留痕。
 *
 * <p>顺序不能调: 身份与授权 → 工厂存在 → 工厂开着 → 同步留痕 (写不进去就拒绝) → 打开只属于这次分析的读取作用域
 * ({@link ProcessSecretPlatformAiScope}) 和外发绑定 → 逐个记下可读的保密产品 → 取数和分析。
 * 留痕都在取数之前: 没有记录就不碰这家工厂的数据。保密产品名单来自保密策略, 不依赖批次, 所以能排在读批次之前。
 *
 * <p>出外脱敏 (c) 不归这里管: 它决定什么内容能发给外部模型, 不决定谁能读, 由 {@link LlmEgressGuard} 按点名的工厂判。
 *
 * <p>⛔ 不开事务: 留痕走独立事务 ({@code ProcessSecretAuditRecorder}), 分析里有一次模型调用, 不占着连接等模型。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformFactoryAiAnalysisService {

    /** 留痕的 resource_ref。⛔ 只放入口, 不放问题原文。 */
    static final String AUDIT_RESOURCE = "ai:platform-analysis";

    static final String FACTORY_CLOSED_MESSAGE = "该工厂已关停，不能做 AI 分析";
    static final String AUDIT_UNAVAILABLE_MESSAGE = "保密访问记录暂时写不进去，已暂停平台 AI 分析";

    private final PlatformAiAnalysisGrantService grants;
    private final PlatformFactoryControlAuthority authority;
    private final FactoryRepository factories;
    private final ProcessSecretAccessService access;
    private final AIEnterpriseService analysis;

    /** {@code factoryId} 只来自路径 (服务端认定的工厂), 请求体里没有工厂字段。 */
    public MobileDTO.AICostAnalysisResponse analyzeTimeRange(HttpServletRequest request, String factoryId,
                                                             AIRequestDTO.TimeRangeAnalysisRequest body) {
        AuthenticatedActor actor = grants.requireEntry(request);
        authority.checkFactory(factoryId);
        requireOpen(factoryId);
        recordUse(actor, factoryId, body.getStartDate(), body.getEndDate());
        LocalDateTime start = body.getStartDate().atStartOfDay();
        LocalDateTime end = body.getEndDate().atTime(23, 59, 59);
        try (LlmEgressGuard.AnalysedFactoryScope egress = LlmEgressGuard.analysing(factoryId);
             ProcessSecretPlatformAiScope read = ProcessSecretPlatformAiScope.open(actor, factoryId)) {
            access.recordPlatformAiReads(actor, factoryId);
            // 不打标: 这次的输出已经按入口裁过了, 打了标响应裁剪会把数字字段藏掉, 还写一条「平台被拒」的假记录
            return ProcessSecretReadTracker.untracked(() -> analysis.analyzeTimeRangeForPlatform(
                    factoryId, start, end, body.getDimension(), body.getQuestion()));
        }
    }

    /** 关停的工厂不给分析 (工厂拦截器的关停闸对平台账号不生效)。擦除的前提是先关停, 所以擦除中的工厂也落在这里。 */
    private void requireOpen(String factoryId) {
        boolean open = factories.findById(factoryId).map(Factory::getIsActive).map(Boolean.TRUE::equals).orElse(false);
        if (!open) {
            throw new BusinessException(403, FACTORY_CLOSED_MESSAGE);
        }
    }

    private void recordUse(AuthenticatedActor actor, String factoryId, LocalDate startDate, LocalDate endDate) {
        // 提示词除了这个时间段, 还带最近 30 天的预计算生产统计, 留痕要照实写。⛔ 不写问题原文
        String reason = "平台 AI 成本分析 " + startDate + "~" + endDate + "，并含最近 30 天生产统计";
        if (!access.audit(factoryId, null, actor, ProcessSecretAction.AI_ACCESS, "ALLOW", reason, AUDIT_RESOURCE)) {
            log.error("[platform-ai-analysis] audit write failed, refusing analysis for factory {}", factoryId);
            throw new BusinessException(403, AUDIT_UNAVAILABLE_MESSAGE);
        }
    }
}
