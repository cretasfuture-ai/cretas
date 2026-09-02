package com.cretas.aims.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowBomSyncPreflightResponse {

    public enum Classification {
        READY,
        AUTO_MIGRATABLE,
        USER_INPUT_REQUIRED,
        CONFLICT
    }

    private Classification classification;
    private Integer activeBomVersion;
    private Integer syncDraftVersion;
    private Long activeBomWorkflowRevisionId;
    private Long targetWorkflowRevisionId;
    @Builder.Default
    private List<String> preservedItems = new ArrayList<>();
    @Builder.Default
    private List<AutomaticMapping> automaticMappings = new ArrayList<>();
    @Builder.Default
    private List<SyncIssue> missingItems = new ArrayList<>();
    @Builder.Default
    private List<SyncIssue> conflicts = new ArrayList<>();
    private boolean canCompleteAutomatically;
    /**
     * 生效 family 缺员, 但有一个<b>钉在目标修订上的 DRAFT family</b> 覆盖了全部终端 ⇒ 发布时整体激活它。
     * 非 null 即「可收编」; 是那个 family 里锚点产品那份草稿的 id。见 2026-09-02 LIUSHANMEN 死锁。
     */
    private String adoptableDraftRecipeId;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AutomaticMapping {
        private String materialTypeId;
        private String materialName;
        private String fromNodeId;
        private String toNodeId;
        private String toProcessNodeId;
        private String toInputPortId;
        private String toEdgeId;
        private String ownerRecipeId;
        private String costScope;
        private String costScopeKey;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncIssue {
        private String code;
        private String materialTypeId;
        private String materialName;
        private String processNodeId;
        private String field;
        private String message;
        private String action;
    }
}
