package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds safe choices for an intent clarification response.
 *
 * <p>The recognizer supplies candidate codes, but it is not the authority for the
 * display name, active state, tenant scope, business domain, or permissions. This
 * service resolves every candidate against the current factory configuration and
 * returns only actions that the current caller may select.</p>
 */
@Service
public class ClarificationCandidateService {

    private static final int MAX_BUSINESS_CHOICES = 3;
    private static final String MODE_READ = "READ";

    private final IntentConfigManagementService configService;
    private final IntentPermissionGate permissionGate;
    private final IntentAccessModeFilter accessModeFilter;
    private final WriteGuardService writeGuardService;

    @Autowired
    public ClarificationCandidateService(
            IntentConfigManagementService configService,
            IntentPermissionGate permissionGate,
            IntentAccessModeFilter accessModeFilter,
            WriteGuardService writeGuardService) {
        this.configService = configService;
        this.permissionGate = permissionGate;
        this.accessModeFilter = accessModeFilter;
        this.writeGuardService = writeGuardService;
    }

    /**
     * Convert recognizer candidates to canonical, permission-filtered actions.
     * No default or synthetic business choice is added when the candidate cannot
     * be proven selectable. A rephrase affordance remains truthful in that case.
     */
    List<IntentExecuteResponse.SuggestedAction> buildCandidateActions(
            IntentMatchResult matchResult,
            String factoryId,
            Long userId,
            String userRole,
            String mode) {
        List<IntentExecuteResponse.SuggestedAction> actions = new ArrayList<>();
        Set<String> seenCodes = new HashSet<>();

        if (matchResult != null && hasIdentity(factoryId, userId, userRole)) {
            DomainResolution domainResolution = resolveFactoryDomain(factoryId);
            if (domainResolution.failed()) {
                return rephraseOnly();
            }
            String factoryDomain = domainResolution.domain();
            List<IntentMatchResult.CandidateIntent> candidates = matchResult.getTopCandidates();
            if (candidates != null) {
                for (IntentMatchResult.CandidateIntent candidate : candidates) {
                    if (actions.size() >= MAX_BUSINESS_CHOICES) {
                        break;
                    }
                    if (candidate == null) {
                        continue;
                    }
                    String code = candidate.getIntentCode();
                    if (code == null || code.isBlank() || !seenCodes.add(code)) {
                        continue;
                    }
                    AIIntentConfig config = currentActiveConfig(factoryId, code);
                    if (config == null
                            || !domainAllowed(config, factoryDomain)
                            || !modeAllowed(config, factoryId, mode)
                            || !permissionAllowed(config, factoryId, userId, userRole)) {
                        continue;
                    }
                    actions.add(toSelectAction(config, factoryId, candidate));
                }
            }
        }

        // This is the only fallback that does not claim a business operation can run.
        actions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("REPHRASE")
                .actionName("重新描述")
                .build());
        return actions;
    }

    private List<IntentExecuteResponse.SuggestedAction> rephraseOnly() {
        return List.of(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("REPHRASE").actionName("重新描述").build());
    }

    private boolean hasIdentity(String factoryId, Long userId, String userRole) {
        return factoryId != null && !factoryId.isBlank()
                && userId != null && userId > 0
                && userRole != null && !userRole.isBlank();
    }

    private AIIntentConfig currentActiveConfig(String factoryId, String intentCode) {
        try {
            return configService.getIntentByCode(factoryId, intentCode)
                    .filter(config -> Boolean.TRUE.equals(config.getIsActive()))
                    .filter(config -> intentCode.equals(config.getIntentCode()))
                    .filter(config -> config.getFactoryId() == null || factoryId.equals(config.getFactoryId()))
                    .filter(config -> config.getIntentName() != null && !config.getIntentName().isBlank())
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private DomainResolution resolveFactoryDomain(String factoryId) {
        try {
            String domain = configService.resolveBusinessDomain(factoryId);
            if (domain == null || domain.isBlank()) {
                return new DomainResolution(null, false);
            }
            String normalized = domain.trim().toUpperCase(Locale.ROOT);
            return new DomainResolution(
                    "FACTORY".equals(normalized) || "RESTAURANT".equals(normalized)
                            ? normalized : null,
                    false);
        } catch (Exception ignored) {
            return new DomainResolution(null, true);
        }
    }

    private record DomainResolution(String domain, boolean failed) { }

    private boolean domainAllowed(AIIntentConfig config, String factoryDomain) {
        String businessType = config.getBusinessType();
        if (businessType == null || businessType.isBlank()
                || "COMMON".equalsIgnoreCase(businessType)) {
            return true;
        }
        // Domain-exclusive choices must never be shown while the factory domain is unknown.
        return factoryDomain != null && businessType.equalsIgnoreCase(factoryDomain);
    }

    private boolean modeAllowed(AIIntentConfig config, String factoryId, String mode) {
        if (!MODE_READ.equalsIgnoreCase(mode)) {
            return true;
        }
        try {
            return accessModeFilter != null
                    ? !accessModeFilter.isWriteIntent(config)
                    : !writeGuardService.isWriteIntent(config);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean permissionAllowed(AIIntentConfig config, String factoryId,
                                      Long userId, String userRole) {
        try {
            String requiredPermission = config.getRequiredPermission();
            if (requiredPermission != null && !requiredPermission.isBlank()) {
                return permissionGate != null
                        && permissionGate.check(config, userId, userRole).isAllowed();
            }
            // Empty required_permission retains the existing requiredRoles gate.
            return configService.hasPermission(factoryId, config.getIntentCode(), userRole);
        } catch (Exception ignored) {
            return false;
        }
    }

    private IntentExecuteResponse.SuggestedAction toSelectAction(
            AIIntentConfig config, String factoryId, IntentMatchResult.CandidateIntent candidate) {
        return toSelectAction(factoryId, config.getIntentCode(), config.getIntentName(),
                config.getDescription(), candidate.getConfidence());
    }

    static IntentExecuteResponse.SuggestedAction toSelectAction(
            String factoryId, String intentCode, String intentName, String description, Double confidence) {
        String canonicalDescription = description;
        if (canonicalDescription == null || canonicalDescription.isBlank()) {
            canonicalDescription = confidence == null ? null : String.format("置信度: %.0f%%", confidence * 100);
        }
        HashMap<String, Object> parameters = new HashMap<>();
        parameters.put("intentCode", intentCode);
        return IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("SELECT_INTENT")
                .actionName(intentName)
                .description(canonicalDescription)
                .endpoint("/api/mobile/" + factoryId + "/ai-intents/execute")
                .parameters(parameters)
                .build();
    }
}
