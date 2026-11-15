package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.service.CustomerService;
import com.cretas.aims.service.entity.EntityKind;
import com.cretas.aims.service.entity.FactoryEntityResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Sprint 4 W2 S-CRM-FULL-1 — 客户 CRM 详情查询工具.
 *
 * 返回 客户生命周期阶段 / 重要程度 / 来源渠道 / 最近接洽时间 / 客户级开票默认.
 *
 * 防呆 R2: result 包含 customer name + customerCode 完整身份信息.
 */
@Slf4j
@Component
public class CustomerCrmStatusQueryTool extends AbstractBusinessTool {

    @Autowired
    private CustomerService customerService;

    /** 按名称找客户只走实体解析公共能力 (本厂主数据规范化后精确匹配), ⛔ 不用包含匹配的名称搜索。 */
    @Autowired
    private FactoryEntityResolver entityResolver;

    @Override
    public String getToolName() {
        return "customer_crm_status_query";
    }

    @Override
    public String getDescription() {
        return "查询客户 CRM 信息: 生命周期阶段 (LEAD/RECURRING/LOST 等 11 阶段) / 重要程度 " +
                "(VIP/IMPORTANT/NORMAL/LOW) / 来源渠道 / 最近接洽时间 / 客户级开票默认 " +
                "(税率 + 开票类型). 适用场景: 销售跟进前查客户状态、判断重要程度排资源优先级、" +
                "查上次接洽时间识别沉睡客户、新建销售单时确认默认税率/开票类型.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户 ID — 优先使用此参数精确定位");
        properties.put("customerId", customerId);

        Map<String, Object> customerName = new HashMap<>();
        customerName.put("type", "string");
        customerName.put("description", "客户名称；未提供客户编号时，按名称精确匹配单个客户");
        properties.put("customerName", customerName);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String customerId = getString(params, "customerId", null);
        String customerName = getString(params, "customerName", null);
        log.info("执行客户 CRM 状态查询 - 工厂={}, customerId={}, name={}",
                factoryId, customerId, customerName);

        CustomerDTO customer = null;
        if (customerId != null && !customerId.isBlank()) {
            customer = customerService.getCustomerById(factoryId, customerId);
        } else if (customerName != null && !customerName.isBlank()) {
            FactoryEntityResolver.Resolution resolution =
                    entityResolver.resolve(factoryId, EntityKind.CUSTOMER, customerName);
            if (resolution.status() == FactoryEntityResolver.Status.UNIQUE) {
                customer = customerService.getCustomerById(factoryId, resolution.matches().get(0).id());
            } else if (resolution.status() == FactoryEntityResolver.Status.MULTIPLE) {
                // 口径规则 8 (INTENT-DISTILL-20261111): 只读查询名字命中多个时不先问、不只取第一个 ——
                // 全部列出 (前 5 个), 每个标出解析器给的区分字段 (客户编码 / 地址 / 电话尾号) 与 CRM 状态。
                List<Map<String, Object>> rows = new java.util.ArrayList<>();
                List<com.cretas.aims.ai.tool.SameNameMatches.Match> listed = new java.util.ArrayList<>();
                for (FactoryEntityResolver.Candidate c
                        : com.cretas.aims.ai.tool.SameNameMatches.listedCandidates(resolution)) {
                    Map<String, Object> row = statusData(customerService.getCustomerById(factoryId, c.id()));
                    rows.add(row);
                    listed.add(com.cretas.aims.ai.tool.SameNameMatches.matchOf(c,
                            row.get("customerStatus") + "，" + row.get("importance")));
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("customerName", resolution.query());
                data.put("matchCount", resolution.matches().size());
                data.put("customers", rows);
                Map<String, Object> result = new java.util.HashMap<>();
                result.put("data", data);
                com.cretas.aims.ai.tool.SameNameMatches.annotate(result, "客户", resolution, listed);
                result.put("message", com.cretas.aims.ai.tool.SameNameMatches.reply(result));
                return result;
            } else {
                // 全名零命中: 称呼核心 / 包含匹配出来的候选列出来让人选 (⛔ 不自动选, 哪怕只有一个)
                Map<String, Object> result = new java.util.HashMap<>();
                if (com.cretas.aims.ai.tool.SameNameMatches.annotateSuggestions(result, "客户", resolution)) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("customerName", resolution.query());
                    data.put("matchCount", 0);
                    data.put("suggestionCount", resolution.suggestions().size());
                    result.put("data", data);
                    result.put("message", com.cretas.aims.ai.tool.SameNameMatches.reply(result));
                    return result;
                }
            }
        }

        if (customer == null) {
            return buildSimpleResult("未找到客户, 请确认 customerId 或 customerName", null);
        }

        Map<String, Object> data = statusData(customer);

        return buildSimpleResult(
                String.format("客户 %s (%s) — %s, %s",
                        customer.getName(),
                        customer.getCustomerCode(),
                        data.get("customerStatus"),
                        data.get("importance")),
                data);
    }

    /** 防呆 R2: result 全身份信息 — name + customerCode + 4 CRM 字段 + 2 开票默认. */
    private static Map<String, Object> statusData(CustomerDTO customer) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerId", customer.getId());
        data.put("customerCode", customer.getCustomerCode());
        data.put("customerName", customer.getName());
        data.put("customerStatus", customer.getCustomerStatus() != null
                ? customer.getCustomerStatus().getDisplayName() + " (" + customer.getCustomerStatus().name() + ")"
                : "未分类");
        data.put("importance", customer.getImportance() != null
                ? customer.getImportance().getDisplayName()
                : "未分级");
        data.put("source", customer.getSource() != null
                ? customer.getSource().getDisplayName()
                : "未记录");
        data.put("lastContactedAt", customer.getLastContactedAt() != null
                ? customer.getLastContactedAt().toString()
                : "从未接洽");
        data.put("defaultTaxRate", customer.getDefaultTaxRate() != null
                ? customer.getDefaultTaxRate().toString() + "%"
                : "未设 (开票时按行项目)");
        data.put("defaultInvoiceType", customer.getDefaultInvoiceType() != null
                ? customer.getDefaultInvoiceType().getDisplayName()
                : "未设 (默认普通发票)");
        return data;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
