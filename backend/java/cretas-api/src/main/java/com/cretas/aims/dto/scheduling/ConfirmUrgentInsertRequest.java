package com.cretas.aims.dto.scheduling;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * 确认紧急插单请求
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "确认紧急插单请求")
public class ConfirmUrgentInsertRequest {

    @Schema(description = "选中的时段ID", required = true)
    @NotBlank(message = "时段ID不能为空")
    private String slotId;

    @Schema(description = "影响分析返回的不可变预览ID", required = true)
    @NotBlank(message = "预览ID不能为空")
    private String previewId;

    @Schema(description = "产品类型ID", required = true)
    @NotNull(message = "产品类型不能为空")
    private String productTypeId;

    @Schema(description = "计划数量，必须与服务端预览快照一致", required = true)
    @NotNull(message = "计划数量不能为空")
    @DecimalMin(value = "0.01", message = "计划数量必须大于0")
    @Digits(integer = 8, fraction = 2, message = "计划数量最多8位整数、2位小数")
    private BigDecimal plannedQuantity;

    @Schema(description = "产能核对单位 PIECE / KG / BATCH", required = true,
            allowableValues = {"PIECE", "KG", "BATCH"})
    @NotBlank(message = "数量单位不能为空")
    @jakarta.validation.constraints.Pattern(
            regexp = "(?i)PIECE|KG|BATCH",
            message = "数量单位只能是 PIECE / KG / BATCH")
    private String quantityUnit;

    @Schema(description = "客户订单号")
    private String customerOrderNumber;

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "需核验的工人ID列表；提交时核对本厂班次和占用，不代表已预留或完成任务分配。空名单由后续排班分配")
    private List<Long> assignedWorkerIds;

    @Schema(description = "分配的设备ID列表")
    private List<String> assignedEquipmentIds;

    @Schema(description = "使用的原材料批次ID列表")
    private List<String> materialBatchIds;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注不能超过500个字符")
    private String notes;

    @Schema(description = "缺料时强制插单的原因。仅当工厂开了「缺料时拦开工」且本次插单缺料时才需要；原因会写进计划备注留痕")
    @Size(max = 300, message = "强制插单原因不能超过300个字符")
    private String shortageForceReason;

    @Schema(description = "紧急原因", required = true)
    @NotBlank(message = "紧急原因不能为空")
    private String urgentReason;

    @Schema(description = "请求交期")
    private String requestedDeadline;

    @Schema(description = "优先级 (1-10)")
    @Min(value = 1, message = "优先级最小为1")
    @Max(value = 10, message = "优先级最大为10")
    @Builder.Default
    private Integer priority = 9;

    @Schema(description = "是否强制插入（跳过影响检查）")
    @Builder.Default
    private Boolean forceInsert = false;
}
