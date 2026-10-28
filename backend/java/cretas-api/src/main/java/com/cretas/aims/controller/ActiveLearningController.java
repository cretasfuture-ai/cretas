package com.cretas.aims.controller;

import com.cretas.aims.entity.learning.*;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.scheduler.ActiveLearningScheduler;
import com.cretas.aims.service.ActiveLearningService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Active Learning Controller
 *
 * Provides REST API endpoints for managing the active learning system:
 * - View and manage learning suggestions
 * - Monitor model performance
 * - Manage annotation queue
 * - Trigger manual learning tasks
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-24
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/ai/active-learning")
@Slf4j
@CrossOrigin(origins = {"https://www.cretaceousfuture.com", "http://139.196.165.140:8086", "http://localhost:5173"})
public class ActiveLearningController {

    private final ActiveLearningService activeLearningService;

    @Autowired(required = false)
    private ActiveLearningScheduler activeLearningScheduler;

    public ActiveLearningController(ActiveLearningService activeLearningService) {
        this.activeLearningService = activeLearningService;
    }

    // ==================== Suggestions ====================

    /**
     * Get pending learning suggestions
     */
    @GetMapping("/suggestions")
    public ResponseEntity<Map<String, Object>> getPendingSuggestions(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "20") int limit) {

        List<LearningSuggestion> suggestions = activeLearningService
                .getPendingSuggestions(factoryId, limit);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", suggestions);
        response.put("total", suggestions.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Approve a learning suggestion
     */
    @RequirePermission({"system:read_write"})
    @PostMapping("/suggestions/{suggestionId}/approve")
    public ResponseEntity<Map<String, Object>> approveSuggestion(
            @PathVariable String factoryId,
            @PathVariable Long suggestionId,
            @RequestParam(required = false) String reviewer,
            @RequestParam(required = false) String notes) {

        // 按 (建议 ID, 路径工厂) 取, 别厂的建议 404 (复审 M-B, 2026-10-29)
        activeLearningService.approveSuggestion(factoryId, suggestionId, currentOperatorName(), notes);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Suggestion approved successfully");

        return ResponseEntity.ok(response);
    }

    /**
     * Reject a learning suggestion
     */
    @RequirePermission({"system:read_write"})
    @PostMapping("/suggestions/{suggestionId}/reject")
    public ResponseEntity<Map<String, Object>> rejectSuggestion(
            @PathVariable String factoryId,
            @PathVariable Long suggestionId,
            @RequestParam(required = false) String reviewer,
            @RequestParam String reason) {

        activeLearningService.rejectSuggestion(factoryId, suggestionId, currentOperatorName(), reason);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Suggestion rejected");

        return ResponseEntity.ok(response);
    }

    /**
     * Apply an approved suggestion
     */
    @RequirePermission({"system:read_write"})
    @PostMapping("/suggestions/{suggestionId}/apply")
    public ResponseEntity<Map<String, Object>> applySuggestion(
            @PathVariable String factoryId,
            @PathVariable Long suggestionId,
            @RequestParam(required = false) String applier) {

        activeLearningService.applySuggestion(factoryId, suggestionId, currentOperatorName());

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Suggestion applied successfully");

        return ResponseEntity.ok(response);
    }

    // ==================== Performance Monitoring ====================

    /**
     * Get model performance trend
     */
    @GetMapping("/performance")
    public ResponseEntity<Map<String, Object>> getPerformanceTrend(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "DAILY") ModelPerformanceLog.PeriodType periodType,
            @RequestParam(defaultValue = "30") int days) {

        List<ModelPerformanceLog> trend = activeLearningService
                .getPerformanceTrend(factoryId, periodType, days);

        boolean isDegrading = activeLearningService.isPerformanceDegrading(factoryId, 0.05);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", trend);
        response.put("isDegrading", isDegrading);
        response.put("periodType", periodType);
        response.put("days", days);

        return ResponseEntity.ok(response);
    }

    // ==================== Cross-Factory Knowledge ====================

    /**
     * Get available global knowledge for adoption
     */
    @GetMapping("/knowledge/available")
    public ResponseEntity<Map<String, Object>> getAvailableKnowledge(
            @PathVariable String factoryId) {

        List<CrossFactoryKnowledge> available = activeLearningService
                .getAvailableGlobalKnowledge(factoryId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", available);
        response.put("total", available.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Adopt global knowledge
     */
    @RequirePermission({"system:read_write"})
    @PostMapping("/knowledge/{knowledgeId}/adopt")
    public ResponseEntity<Map<String, Object>> adoptKnowledge(
            @PathVariable String factoryId,
            @PathVariable Long knowledgeId) {

        CrossFactoryKnowledgeAdoption adoption = activeLearningService
                .adoptKnowledge(factoryId, knowledgeId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", adoption);
        response.put("message", "Knowledge adopted successfully");

        return ResponseEntity.ok(response);
    }

    /**
     * Record feedback for knowledge
     */
    @RequirePermission({"system:read_write"})
    @PostMapping("/knowledge/{knowledgeId}/feedback")
    public ResponseEntity<Map<String, Object>> recordKnowledgeFeedback(
            @PathVariable String factoryId,
            @PathVariable Long knowledgeId,
            @RequestParam boolean isPositive) {

        activeLearningService.recordKnowledgeFeedback(knowledgeId, factoryId, isPositive);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Feedback recorded");

        return ResponseEntity.ok(response);
    }

    /**
     * Get keyword effectiveness evaluation
     */
    @GetMapping("/keywords/effectiveness")
    public ResponseEntity<Map<String, Object>> getKeywordEffectiveness(
            @PathVariable String factoryId) {

        Map<String, BigDecimal> effectiveness = activeLearningService
                .evaluateKeywordEffectiveness(factoryId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", effectiveness);
        response.put("total", effectiveness.size());

        return ResponseEntity.ok(response);
    }

    /**
     * 审批人 / 应用人 / 标注人 / 申请人一律取登录身份 (用户名; 没有用户名时 user:<id>), 不信请求参数
     * (复审 Low 1, Steve 2026-10-29)。
     */
    public static String currentOperatorName() {
        String name = com.cretas.aims.utils.SecurityUtils.currentUsername();
        if (name != null && !name.isBlank()) {
            return name;
        }
        Long id = com.cretas.aims.utils.SecurityUtils.currentUserId();
        return id == null ? "unknown" : "user:" + id;
    }

    // ==================== Annotation Queue ====================

    /**
     * Assign annotations to an annotator
     */
    @RequirePermission({"system:read_write"})
    @PostMapping("/annotations/assign")
    public ResponseEntity<Map<String, Object>> assignAnnotations(
            @PathVariable String factoryId,
            @RequestParam(required = false) String annotator,
            @RequestParam(defaultValue = "10") int count) {

        List<AnnotationQueue> assigned = activeLearningService
                .assignAnnotations(factoryId, currentOperatorName(), count);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", assigned);
        response.put("assigned", assigned.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Complete an annotation
     */
    @RequirePermission({"system:read_write"})
    @PostMapping("/annotations/{annotationId}/complete")
    public ResponseEntity<Map<String, Object>> completeAnnotation(
            @PathVariable String factoryId,
            @PathVariable Long annotationId,
            @RequestParam String intentCode,
            @RequestParam(required = false) String annotator,
            @RequestParam(required = false) String notes) {

        activeLearningService.completeAnnotation(factoryId, annotationId, intentCode, currentOperatorName(), notes);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Annotation completed");

        return ResponseEntity.ok(response);
    }

    // ==================== Intent Transitions ====================

    /**
     * Get most likely next intents from a given intent
     */
    @GetMapping("/transitions/{fromIntent}/next")
    public ResponseEntity<Map<String, Object>> getMostLikelyNextIntents(
            @PathVariable String factoryId,
            @PathVariable String fromIntent,
            @RequestParam(defaultValue = "5") int topN) {

        List<Map.Entry<String, BigDecimal>> nextIntents = activeLearningService
                .getMostLikelyNextIntents(factoryId, fromIntent, topN);

        // Convert to list of maps for JSON serialization
        List<Map<String, Object>> results = nextIntents.stream()
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("intentCode", e.getKey());
                    m.put("probability", e.getValue());
                    return m;
                })
                .toList();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("fromIntent", fromIntent);
        response.put("data", results);

        return ResponseEntity.ok(response);
    }

    // ==================== Manual Triggers ====================

    /**
     * Trigger sample analysis manually
     */
    @RequirePermission({"system:read_write"})
    @PostMapping("/trigger/analyze-samples")
    public ResponseEntity<Map<String, Object>> triggerSampleAnalysis(
            @PathVariable String factoryId) {

        if (activeLearningScheduler == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Scheduler not enabled"));
        }
        int suggestions = activeLearningScheduler.triggerSampleAnalysis(factoryId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Sample analysis completed");
        response.put("suggestionsGenerated", suggestions);

        return ResponseEntity.ok(response);
    }

    /**
     * Trigger transition matrix update manually
     */
    @RequirePermission({"system:read_write"})
    @PostMapping("/trigger/update-transitions")
    public ResponseEntity<Map<String, Object>> triggerTransitionUpdate(
            @PathVariable String factoryId) {

        if (activeLearningScheduler == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Scheduler not enabled"));
        }
        activeLearningScheduler.triggerTransitionMatrixUpdate(factoryId);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Transition matrix updated");

        return ResponseEntity.ok(response);
    }

    /**
     * Trigger knowledge promotion check
     */
    @RequirePermission({"system:read_write"})
    @PostMapping("/trigger/promote-knowledge")
    public ResponseEntity<Map<String, Object>> triggerKnowledgePromotion(
            @PathVariable String factoryId) {

        if (activeLearningScheduler == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "Scheduler not enabled"));
        }
        int promoted = activeLearningScheduler.triggerKnowledgePromotion();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Knowledge promotion check completed");
        response.put("promotedCount", promoted);

        return ResponseEntity.ok(response);
    }

    // ==================== Statistics ====================

    /**
     * Get active learning statistics overview
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStatistics(
            @PathVariable String factoryId) {

        List<LearningSuggestion> pendingSuggestions = activeLearningService
                .getPendingSuggestions(factoryId, 100);
        List<CrossFactoryKnowledge> availableKnowledge = activeLearningService
                .getAvailableGlobalKnowledge(factoryId);
        Map<String, BigDecimal> keywordEffectiveness = activeLearningService
                .evaluateKeywordEffectiveness(factoryId);
        boolean isDegrading = activeLearningService.isPerformanceDegrading(factoryId, 0.05);

        Map<String, Object> stats = new HashMap<>();
        stats.put("pendingSuggestions", pendingSuggestions.size());
        stats.put("availableGlobalKnowledge", availableKnowledge.size());
        stats.put("trackedKeywords", keywordEffectiveness.size());
        stats.put("performanceDegrading", isDegrading);

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("data", stats);

        return ResponseEntity.ok(response);
    }
}
