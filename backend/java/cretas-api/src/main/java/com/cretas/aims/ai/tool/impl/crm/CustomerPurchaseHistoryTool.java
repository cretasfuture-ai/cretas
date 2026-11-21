package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.service.CustomerService;
import com.cretas.aims.service.entity.EntityKind;
import com.cretas.aims.service.entity.FactoryEntityResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 客户购买历史查询工具
 *
 * 获取指定客户的购买历史记录。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class CustomerPurchaseHistoryTool extends AbstractBusinessTool {

    private static final int MAX_CUSTOMER_CHOICES = 5;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private FactoryEntityResolver entityResolver;

    @Override
    public String getToolName() {
        return "customer_purchase_history";
    }

    @Override
    public String getDescription() {
        return "查询客户购买历史。获取指定客户的历史购买记录，包括购买时间、产品、数量等信息。" +
                "适用场景：查看客户订单历史、分析客户购买行为、了解客户消费情况。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // 保留提参所需的字段，工具内另行验证它是否真的标识本厂客户。
        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "明确指定的客户 ID 或客户全名；指代不明确时留空");
        properties.put("customerId", customerId);

        // limit: 返回数量限制（可选）
        Map<String, Object> limit = new HashMap<>();
        limit.put("type", "integer");
        limit.put("description", "返回记录数量限制");
        limit.put("default", 50);
        limit.put("minimum", 1);
        limit.put("maximum", 200);
        properties.put("limit", limit);

        schema.put("properties", properties);
        schema.put("required", Arrays.asList("customerId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行客户购买历史查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        String customerReference = getString(params, "customerId", "").strip();
        CustomerDTO customer = customerReference.isEmpty() ? null : findCustomer(factoryId, customerReference);
        if (customer == null) {
            var resolution = entityResolver.resolve(factoryId, EntityKind.CUSTOMER, customerReference);
            customer = resolution.unique().map(match -> findCustomer(factoryId, match.id())).orElse(null);
            if (customer == null) {
                return askForCustomer(factoryId, resolution);
            }
        }
        String customerId = customer.getId();
        Integer limit = getInteger(params, "limit", 50);

        List<Map<String, Object>> purchaseHistory = customerService.getCustomerPurchaseHistory(factoryId, customerId);

        // 应用数量限制
        if (purchaseHistory != null && purchaseHistory.size() > limit) {
            purchaseHistory = purchaseHistory.subList(0, limit);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("customerId", customerId);
        result.put("customerName", customer.getName());
        result.put("purchaseHistory", purchaseHistory != null ? purchaseHistory : Collections.emptyList());
        result.put("count", purchaseHistory != null ? purchaseHistory.size() : 0);

        log.info("客户购买历史查询完成 - 客户ID: {}, 找到: {} 条记录", customerId,
                purchaseHistory != null ? purchaseHistory.size() : 0);

        return result;
    }

    private CustomerDTO findCustomer(String factoryId, String customerId) {
        try {
            return customerService.getCustomerById(factoryId, customerId);
        } catch (EntityNotFoundException e) {
            return null;
        }
    }

    private Map<String, Object> askForCustomer(String factoryId, FactoryEntityResolver.Resolution resolution) {
        var matches = resolution.matches().isEmpty() ? resolution.suggestions() : resolution.matches();
        List<CustomerDTO> candidates = matches.stream()
                .map(match -> visibleCandidate(factoryId, match.id()))
                .filter(Objects::nonNull)
                .limit(MAX_CUSTOMER_CHOICES)
                .toList();
        if (candidates.isEmpty()) {
            candidates = customerService.getCustomerList(factoryId, PageRequest.of(1, MAX_CUSTOMER_CHOICES),
                    null, null, null, null).getContent();
        }

        String question = "要查哪个客户？请提供客户名称或 ID。";
        if (!candidates.isEmpty()) {
            question += "\n可选客户：\n" + String.join("\n", candidates.stream()
                    .map(candidate -> "- " + candidate.getName() + "（ID：" + candidate.getId() + "）")
                    .toList());
        }
        return new LinkedHashMap<>(Map.of(
                "success", false, "status", "NEED_MORE_INFO", "needMoreInfo", true,
                "missingParameters", List.of("customerId"), "clarificationQuestions", List.of(question),
                "message", question, "error", question));
    }

    private CustomerDTO visibleCandidate(String factoryId, String customerId) {
        try {
            return findCustomer(factoryId, customerId);
        } catch (BusinessException e) {
            if (Integer.valueOf(403).equals(e.getCode())) {
                return null;
            }
            throw e;
        }
    }

    @Override
    protected String buildSuccessResult(Object data) {
        if (data instanceof Map<?, ?> result && "NEED_MORE_INFO".equals(result.get("status"))) {
            return objectMapper.valueToTree(result).toString();
        }
        return super.buildSuccessResult(data);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
