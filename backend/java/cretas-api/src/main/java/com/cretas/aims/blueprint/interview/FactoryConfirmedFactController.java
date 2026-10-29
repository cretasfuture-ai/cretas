package com.cretas.aims.blueprint.interview;

import com.cretas.aims.blueprint.canonical.CanonicalBlueprintActorAuthority;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.conversation.FactoryConfirmedFact;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.RequestUserContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-item source choice for conflicting conversational-build answers, and the version history of
 * what the factory has confirmed. Lives under /api/mobile/{factoryId} so the JWT interceptor's
 * tenant check applies; a factory user must still re-read as an active factory_super_admin of this
 * factory ({@link CanonicalBlueprintActorAuthority}). Only a factory account may decide a source.
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true")
@RequestMapping("/api/mobile/{factoryId}/factory-build/facts")
public class FactoryConfirmedFactController {

    private final FactoryConfirmedFactService service;
    private final CanonicalBlueprintActorAuthority authority;
    private final FactoryBuildInterviewService interview;

    public record HistoryRow(String id, String field, String label, String value, String status, int version,
            String sourceKind, String sourceRef, String supersedesId, String decidedBy, LocalDateTime decidedAt,
            LocalDateTime createdAt) { }

    public record FactHistory(List<FactoryConfirmedFactService.Conflict> conflicts, List<HistoryRow> history) { }

    public record ResolveRequest(String field, String choice) { }

    @GetMapping
    public ApiResponse<FactHistory> history(@PathVariable String factoryId, HttpServletRequest request) {
        authority.requireAuthorized(factoryId, RequestUserContext.currentActor(request));
        return ApiResponse.success(new FactHistory(service.openConflicts(factoryId),
                service.history(factoryId).stream().map(FactoryConfirmedFactController::toRow).toList()));
    }

    @PostMapping("/resolve")
    public ApiResponse<HistoryRow> resolve(@PathVariable String factoryId, @RequestBody ResolveRequest body,
            HttpServletRequest request) {
        ActorRef actor = RequestUserContext.currentActor(request);
        if (actor.type() != AuthenticatedActorType.FACTORY_USER) {
            throw new BusinessException(403, "来源选择只能由本厂管理员账号作出")
                    .withCode("FACT_CONFLICT_FACTORY_USER_REQUIRED");
        }
        authority.requireAuthorized(factoryId, actor);
        if (body == null || body.field() == null || body.field().isBlank()) {
            throw new BusinessException(400, "缺少要选择来源的事项").withCode("FACT_CONFLICT_FIELD_REQUIRED");
        }
        // 先记下被挑战的那条回答来自哪次访谈, 选完后让还在进行的访谈与结果一致。
        String answeredIn = service.openConflicts(factoryId).stream()
                .filter(conflict -> conflict.field().equals(body.field()))
                .map(FactoryConfirmedFactService.Conflict::newSource).findFirst().orElse(null);
        FactoryConfirmedFact resolved = service.resolve(
                factoryId, body.field(), body.choice(), actor.type() + ":" + actor.id());
        interview.syncSessionToConfirmedFact(factoryId, answeredIn, body.field());
        return ApiResponse.success(toRow(resolved));
    }

    private static HistoryRow toRow(FactoryConfirmedFact row) {
        return new HistoryRow(row.getId(), row.getFieldKey(), row.getFieldLabel(), row.getValueText(),
                row.getStatus(), row.getVersionNo(), row.getSourceKind(), row.getSourceRef(),
                row.getSupersedesId(), row.getDecidedBy(), row.getDecidedAt(), row.getCreatedAt());
    }
}
