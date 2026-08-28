package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.inventory.SalesException;
import com.cretas.aims.service.inventory.SalesExceptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * 销售异常单 REST 控制器 —— <b>只读</b>。
 *
 * <h2>⛔ 为什么没有决策端点（与采购异常不同）</h2>
 * 采购异常单记的是「货已经收进来了」，所以有 ACCEPT/RETURN 决策 + 退货单。
 * 本表记的是「发货<b>被拒绝了</b>」——什么都没发生，没有既成事实可决策。
 * 硬加一个决策端点会造出一条前端点了以为有用、后端什么也不做的死路
 * （采购侧的 {@code RETURN_OVER} 就当过一次这样的死路，见 V20261027_05）。
 *
 * <p>⚠️ 本控制器目前<b>没有前端入口</b>（web-admin / RN 都还没有这一屏）。
 * 这是显式登记的缺口，不是漏了：本轮任务的边界是后端留痕，
 * 前端入口另开一条线。「零使用量」是本仓认「没接上」的最早信号 ——
 * 如果日后统计到这个端点长期零调用，先查的应该是<b>有没有人能走到它</b>。
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/sales-exceptions")
@RequiredArgsConstructor
@Tag(name = "销售异常管理", description = "被拦下的超发尝试（只读留痕）")
@RequirePermission({"sales:read_write", "sales:read"})
public class SalesExceptionController {

    private final SalesExceptionService salesExceptionService;

    @GetMapping
    @Operation(summary = "查询销售异常单列表",
            description = "按尝试时间倒序。deliveryRecordId 非空时只看那一张发货单。")
    public ApiResponse<List<SalesException>> listExceptions(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) String deliveryRecordId) {
        return ApiResponse.success("查询成功",
                salesExceptionService.listExceptions(factoryId, deliveryRecordId));
    }
}
