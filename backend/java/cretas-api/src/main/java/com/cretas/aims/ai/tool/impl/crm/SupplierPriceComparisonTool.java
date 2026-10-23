package com.cretas.aims.ai.tool.impl.crm;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.supplier.SupplierDTO;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class SupplierPriceComparisonTool extends AbstractBusinessTool {
    private static final String UNAVAILABLE_MESSAGE = "供应商价格比较暂不可用：未接入真实报价，本次未读取、比较或排序供应商价格";
    private static final String FORBIDDEN_MESSAGE = "供应商价格比较需要绑定的工厂用户身份";
    private static final String FORBIDDEN_CODE = "FORBIDDEN";

    @Autowired
    private SupplierService supplierService;

    @Override
    public String getToolName() {
        return "supplier_price_comparison";
    }

    @Override
    public String getDescription() {
        return "供应商资料查询。当前未接入真实报价，不能进行价格对比或排序。" +
                "适用场景：查看活跃供应商资料；价格比较暂不可用。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> materialType = new HashMap<>();
        materialType.put("type", "string");
        materialType.put("description", "原料类型，用于筛选特定类别的供应商进行比价");
        properties.put("materialType", materialType);

        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        Long userId = getUserId(context);
        if (!hasBoundFactoryActor(factoryId, userId)) {
            throw new BusinessException(403, FORBIDDEN_MESSAGE).withCode(FORBIDDEN_CODE);
        }
        throw new BusinessException(UNAVAILABLE_MESSAGE)
                .withCode("SUPPLIER_PRICE_COMPARISON_UNAVAILABLE");
    }

    @Override
    protected String buildErrorResult(String message) {
        if (FORBIDDEN_MESSAGE.equals(message)) {
            try {
                return objectMapper.writeValueAsString(Map.of(
                        "success", false, "error", message, "code", FORBIDDEN_CODE,
                        "data", Map.of("status", FORBIDDEN_CODE, "code", FORBIDDEN_CODE,
                                "toolName", getToolName())));
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
                return super.buildErrorResult(message);
            }
        }
        if (!UNAVAILABLE_MESSAGE.equals(message)) return super.buildErrorResult(message);
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "success", false, "error", message,
                    "data", Map.of("status", "UNAVAILABLE", "code", "SUPPLIER_PRICE_COMPARISON_UNAVAILABLE",
                            "source", "NO_QUOTE_SERVICE", "toolName", getToolName(), "message", message)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return super.buildErrorResult(message);
        }
    }

    @Override
    public String getVersion() {
        return "2.0.0";
    }

    @Override
    public boolean requiresPermission() { return true; }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("procurement:read_write", "finance:read_write");
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    private boolean hasBoundFactoryActor(String factoryId, Long userId) {
        return AuthenticatedActor.fromCurrentExecution()
                .filter(actor -> actor.type() == AuthenticatedActorType.FACTORY_USER)
                .filter(actor -> Objects.equals(actor.id(), userId))
                .filter(actor -> Objects.equals(actor.factoryId(), factoryId))
                .isPresent();
    }
}
