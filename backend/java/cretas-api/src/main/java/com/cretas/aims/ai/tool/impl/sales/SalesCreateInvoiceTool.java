package com.cretas.aims.ai.tool.impl.sales;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class SalesCreateInvoiceTool extends AbstractBusinessTool {

    @Override
    public String getToolName() {
        return "sales_create_invoice";
    }

    @Override
    public String getDescription() {
        return "兼容墓碑：sales_create_invoice 已永久停用，不创建应收或发票记录；销售订单完成后由系统自动生成财务待办";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> counterpartyId = new HashMap<>();
        counterpartyId.put("type", "string");
        counterpartyId.put("description", "客户ID");
        properties.put("counterpartyId", counterpartyId);

        Map<String, Object> orderId = new HashMap<>();
        orderId.put("type", "string");
        orderId.put("description", "关联的销售订单ID（可选）");
        properties.put("orderId", orderId);

        Map<String, Object> amount = new HashMap<>();
        amount.put("type", "number");
        amount.put("description", "开票金额（元）");
        properties.put("amount", amount);

        Map<String, Object> dueDate = new HashMap<>();
        dueDate.put("type", "string");
        dueDate.put("description", "到期日，格式: yyyy-MM-dd");
        properties.put("dueDate", dueDate);

        Map<String, Object> remark = new HashMap<>();
        remark.put("type", "string");
        remark.put("description", "备注");
        properties.put("remark", remark);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("counterpartyId", "amount"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("counterpartyId", "amount");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        throw new BusinessException(410,
                "sales_create_invoice 已停用；禁止通过兼容入口创建手工应收或发票记录")
                .withHint("请等待销售订单完成后处理系统生成的财务待办")
                .withHintTarget("销售开票");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        Map<String, String> questions = new HashMap<>();
        questions.put("counterpartyId", "请问给哪个客户开票？请提供客户ID。");
        questions.put("amount", "请问开票金额是多少？");
        return questions.get(paramName);
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        Map<String, String> names = Map.of(
            "counterpartyId", "客户ID",
            "amount", "金额",
            "orderId", "订单ID",
            "dueDate", "到期日"
        );
        return names.getOrDefault(paramName, paramName);
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("finance:read_write");
    }

    @Override
    public ActionType getActionType() {
        return ActionType.WRITE;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.HIGH;
    }
}
