package com.cretas.aims.dto.bom;

import lombok.Data;

/** 驳回 BOM 调价建议: 原因必填 (服务层校验, 空白 400)。驳回人取自登录身份, 不在请求体里。 */
@Data
public class BomPriceAdjustmentRejectRequest {
    private String reason;
}
