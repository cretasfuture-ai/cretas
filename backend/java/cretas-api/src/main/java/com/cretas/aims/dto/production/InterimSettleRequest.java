package com.cretas.aims.dto.production;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 存货生产小结请求。
 *
 * <p>备注写入现有 {@code production_interim_settlement.summary} JSON，
 * 不改变库存幂等键，也不新增表结构。</p>
 */
@Data
public class InterimSettleRequest {

    @Size(max = 500, message = "小结备注不能超过500个字符")
    private String note;
}
