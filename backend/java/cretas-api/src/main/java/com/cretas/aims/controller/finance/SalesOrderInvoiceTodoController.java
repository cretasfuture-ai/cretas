package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.finance.impl.SalesOrderInvoiceTodoProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.List;

/** Finance-admin recovery endpoint for exhausted automatic invoice todos. */
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance/invoice-todos")
@RequiredArgsConstructor
@RequirePermission("finance:read_write")
@RequireModule("finance_ar")
@RequireRole({"factory_super_admin", "platform_admin", "permission_admin", "finance_manager"})
public class SalesOrderInvoiceTodoController {

    private final SalesOrderInvoiceTodoProcessor processor;

    /** Visible dead-letter queue for finance operators; tenant is constrained by the path relation gate. */
    @GetMapping("/failed")
    public ApiResponse<List<Map<String, Object>>> failed(@PathVariable("factoryId") String factoryId) {
        return ApiResponse.success("查询成功", processor.listFailedTodos(factoryId));
    }

    /** Reset one FAILED todo; the scheduler performs the leased invoice attempt afterward. */
    @PostMapping("/{salesOrderId}/retry")
    public ApiResponse<Map<String, Object>> retry(@PathVariable("factoryId") String factoryId,
                                                   @PathVariable("salesOrderId") String salesOrderId) {
        boolean retried = processor.retryFailedTodo(factoryId, salesOrderId);
        if (!retried) {
            throw new com.cretas.aims.exception.BusinessException(409,
                    "该自动开票待办不存在、未耗尽重试次数或已被其他处理器接管")
                    .withHint("请刷新销售开票工作台，确认待办仍处于失败状态")
                    .withHintTarget("销售开票");
        }
        return ApiResponse.success("自动开票待办已重新排队", Map.of(
                "factoryId", factoryId,
                "salesOrderId", salesOrderId,
                "status", "PENDING"));
    }
}
