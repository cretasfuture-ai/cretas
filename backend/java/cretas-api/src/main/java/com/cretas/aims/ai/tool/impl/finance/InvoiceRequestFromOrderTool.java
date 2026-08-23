package com.cretas.aims.ai.tool.impl.finance;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 旧“按订单手工申请”工具的兼容墓碑。
 *
 * <p>保留名称供历史调用得到可解释错误；工具禁用且 fail closed。税率分组记录只由
 * 销售订单全部发货后的持久待办自动生成。</p>
 */
@Slf4j
@Component
public class InvoiceRequestFromOrderTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "finance_invoice_request_from_order";
    }

    @Override
    public String getDescription() {
        return "兼容入口：手工按订单申请开票已停用；销售订单全部发货后由系统自动按税率分组生成待审核记录";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "salesOrderId", Map.of(
                                "type", "string",
                                "description", "销售订单ID"),
                        "invoiceType", Map.of(
                                "type", "string",
                                "description", "发票类型: NORMAL (普票) / SPECIAL (专票), 默认 NORMAL"),
                        "remark", Map.of(
                                "type", "string",
                                "description", "备注 (选填)")),
                "required", List.of("salesOrderId"));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of("salesOrderId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                             Map<String, Object> context) throws Exception {
        throw new BusinessException(410,
                "手工按订单申请开票已停用；销售订单全部发货完成后，系统会自动按税率分组生成待审核开票记录")
                .withHint("请到销售开票工作台处理系统生成的记录")
                .withHintTarget("销售开票");
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }

    @Override
    public boolean isEnabled() { return false; }

    @Override
    public boolean requiresPermission() { return true; }

    @Override
    public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("finance:read_write");
    }

    @Override
    public ActionType getActionType() { return ActionType.WRITE; }

    @Override
    public RiskLevel getRiskLevel() { return RiskLevel.HIGH; }
}
