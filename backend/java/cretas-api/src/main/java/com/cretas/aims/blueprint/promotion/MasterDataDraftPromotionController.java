package com.cretas.aims.blueprint.promotion;

import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.ExecuteRequest;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.PromotionList;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.PromotionPreview;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.PromotionReceipt;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.PreviewRequest;
import com.cretas.aims.blueprint.promotion.MasterDataDraftPromotionModels.PromotionRecord;
import com.cretas.aims.dto.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Master-data DRAFT promotion (products / materials / processes -> live tables), its own entry.
 *
 * <p>It is deliberately not a module activation command and does not go through the blueprint
 * activation orchestration: it writes exactly one live master-data row per confirmation, through
 * that module's own live service, and never touches the DRAFT row. The URL stays under
 * {@code /ai-initialize}, so the JWT interceptor already confines a factory user to its own factory;
 * the service resolves the operator again on the server before reading anything.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@RestController
@RequestMapping("/api/platform/factory-assembly/factories/{factoryId}/ai-initialize/blueprints/{blueprintId}")
public class MasterDataDraftPromotionController {

    private final MasterDataDraftPromotionService promotions;

    public MasterDataDraftPromotionController(MasterDataDraftPromotionService promotions) {
        this.promotions = promotions;
    }

    @PostMapping("/drafts/{capabilityId}/{draftId}/promotion/preview")
    @Operation(summary = "预览把一份主数据草稿转正为正式数据(只读)")
    public ApiResponse<PromotionPreview> preview(@PathVariable String factoryId, @PathVariable String blueprintId,
                                                 @PathVariable String capabilityId, @PathVariable String draftId,
                                                 @RequestBody(required = false) PreviewRequest body,
                                                 HttpServletRequest http) {
        return ApiResponse.success(promotions.preview(http, factoryId, blueprintId, capabilityId, draftId, body));
    }

    @PostMapping("/drafts/{capabilityId}/{draftId}/promotion")
    @Operation(summary = "确认把一份主数据草稿转正为正式数据(只新建, 草稿行不变)")
    public ApiResponse<PromotionReceipt> execute(@PathVariable String factoryId, @PathVariable String blueprintId,
                                                 @PathVariable String capabilityId, @PathVariable String draftId,
                                                 @RequestBody(required = false) ExecuteRequest body,
                                                 HttpServletRequest http) {
        return ApiResponse.success(promotions.execute(http, factoryId, blueprintId, capabilityId, draftId, body));
    }

    @GetMapping("/drafts/{capabilityId}/{draftId}/promotion")
    @Operation(summary = "查看一份主数据草稿的转正记录")
    public ApiResponse<PromotionRecord> find(@PathVariable String factoryId, @PathVariable String blueprintId,
                                             @PathVariable String capabilityId, @PathVariable String draftId,
                                             HttpServletRequest http) {
        return ApiResponse.success(promotions.find(http, factoryId, blueprintId, capabilityId, draftId));
    }

    @GetMapping("/promotions")
    @Operation(summary = "当前蓝图血缘下的全部主数据草稿转正记录")
    public ApiResponse<PromotionList> list(@PathVariable String factoryId, @PathVariable String blueprintId,
                                           HttpServletRequest http) {
        return ApiResponse.success(promotions.list(http, factoryId, blueprintId));
    }
}
