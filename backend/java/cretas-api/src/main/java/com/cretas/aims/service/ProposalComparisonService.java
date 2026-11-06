package com.cretas.aims.service;

import com.cretas.aims.dto.decision.ProposalComparisonRequest;
import com.cretas.aims.dto.decision.ProposalComparisonRequest.Candidate;
import com.cretas.aims.dto.decision.ProposalComparisonResult;
import com.cretas.aims.dto.decision.ProposalComparisonResult.*;
import com.cretas.aims.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/** Bounded, deterministic comparison of supplied facts. No repositories, AI, executors or writers. */
@Service
@RequiredArgsConstructor
public class ProposalComparisonService {
    private final Validator validator;

    public ProposalComparisonResult compare(ProposalComparisonRequest request) {
        if (request == null) throw new BusinessException(400, "请提供比较口径和至少两个候选方案");
        // Reject an oversized list before Bean Validation traverses its elements.
        if (request.candidates() == null || request.candidates().size() < 2 || request.candidates().size() > 20) {
            throw new BusinessException(400, "候选方案数量必须为2至20个");
        }
        var violations = validator.validate(request);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw new BusinessException(400, "资源口径窗口的结束时间必须晚于开始时间");
        }
        if (request.requiredBy() != null && request.requiredBy().isBefore(request.windowStart())) {
            throw new BusinessException(400, "交付截止时间不能早于资源口径窗口开始时间");
        }
        try {
            Currency.getInstance(request.currency());
        } catch (IllegalArgumentException unsupported) {
            throw new BusinessException(400, "请提供有效的ISO币种代码");
        }
        Set<String> ids = new HashSet<>();
        for (Candidate candidate : request.candidates()) {
            if (!ids.add(candidate.id())) throw new BusinessException(400, "候选方案ID不能重复");
        }

        List<Candidate> ordered = request.candidates().stream().sorted(Comparator.comparing(Candidate::id)).toList();
        List<CandidateEvaluation> evaluated = ordered.stream().map(candidate -> evaluate(request, candidate)).toList();
        // Unknown alternatives may dominate known ones: never silently omit them to manufacture a frontier.
        if (evaluated.stream().anyMatch(item -> item.status() == CandidateStatus.NOT_COMPARABLE)) {
            return result(request, ComparisonStatus.NOT_COMPARABLE, "MISSING_OR_INCOMPATIBLE_FACTS", evaluated, List.of());
        }
        Set<String> eligibleIds = new HashSet<>();
        evaluated.stream().filter(item -> item.status() == CandidateStatus.ELIGIBLE_BY_SUPPLIED_FACTS)
                .forEach(item -> eligibleIds.add(item.id()));
        List<Candidate> eligible = ordered.stream().filter(item -> eligibleIds.contains(item.id())).toList();
        if (eligible.size() < 2) {
            return result(request, ComparisonStatus.NOT_COMPARABLE, "INSUFFICIENT_ELIGIBLE_PROPOSALS", evaluated, List.of());
        }
        Map<String, List<String>> dominators = new HashMap<>();
        for (Candidate candidate : eligible) {
            dominators.put(candidate.id(), eligible.stream()
                    .filter(other -> !other.id().equals(candidate.id()) && dominates(other, candidate))
                    .map(Candidate::id).toList());
        }
        List<CandidateEvaluation> compared = evaluated.stream().map(item -> new CandidateEvaluation(
                item.id(), item.status(), item.reasons(), dominators.getOrDefault(item.id(), List.of()))).toList();
        List<String> frontier = eligible.stream().filter(item -> dominators.get(item.id()).isEmpty())
                .map(Candidate::id).toList();
        return result(request, ComparisonStatus.SUPPLIED_FACTS_COMPARABLE,
                "PARETO_FRONTIER_OF_SUPPLIED_FACTS", compared, frontier);
    }

    private static CandidateEvaluation evaluate(ProposalComparisonRequest basis, Candidate candidate) {
        EnumSet<Reason> unknown = EnumSet.noneOf(Reason.class);
        if (!basis.comparisonBasisId().equals(candidate.comparisonBasisId())) unknown.add(Reason.BASIS_MISMATCH);
        if (!basis.currency().equals(candidate.currency())) unknown.add(Reason.CURRENCY_MISMATCH);
        if (!basis.costUnit().equals(candidate.costUnit())) unknown.add(Reason.COST_UNIT_MISMATCH);
        if (basis.resourceUnit() != candidate.resourceUnit()) unknown.add(Reason.RESOURCE_UNIT_MISMATCH);
        if (candidate.cost() == null) unknown.add(Reason.MISSING_COST);
        if (candidate.completionTime() == null) unknown.add(Reason.MISSING_COMPLETION_TIME);
        if (candidate.requiredResource() == null) unknown.add(Reason.MISSING_RESOURCE_DEMAND);
        if (basis.availableResource() == null) unknown.add(Reason.MISSING_RESOURCE_CAPACITY);
        if (candidate.completionTime() != null && (candidate.completionTime().isBefore(basis.windowStart())
                || candidate.completionTime().isAfter(basis.windowEnd()))) unknown.add(Reason.COMPLETION_OUTSIDE_WINDOW);
        if (!unknown.isEmpty()) return new CandidateEvaluation(candidate.id(), CandidateStatus.NOT_COMPARABLE,
                List.copyOf(unknown), List.of());
        EnumSet<Reason> excluded = EnumSet.noneOf(Reason.class);
        if (candidate.requiredResource().compareTo(basis.availableResource()) > 0) excluded.add(Reason.RESOURCE_SHORTAGE);
        if (basis.requiredBy() != null && candidate.completionTime().isAfter(basis.requiredBy())) excluded.add(Reason.DEADLINE_MISSED);
        return new CandidateEvaluation(candidate.id(), excluded.isEmpty() ? CandidateStatus.ELIGIBLE_BY_SUPPLIED_FACTS
                : CandidateStatus.EXCLUDED_BY_SUPPLIED_FACTS, List.copyOf(excluded), List.of());
    }

    private static boolean dominates(Candidate a, Candidate b) {
        int cost = a.cost().compareTo(b.cost());
        int resource = a.requiredResource().compareTo(b.requiredResource());
        boolean noLater = !a.completionTime().isAfter(b.completionTime());
        return cost <= 0 && resource <= 0 && noLater
                && (cost < 0 || resource < 0 || a.completionTime().isBefore(b.completionTime()));
    }

    private static ProposalComparisonResult result(ProposalComparisonRequest request, ComparisonStatus status,
            String reason, List<CandidateEvaluation> candidates, List<String> frontier) {
        return new ProposalComparisonResult(status, request.comparisonBasisId(), reason, candidates, frontier,
                false, false, false);
    }
}
