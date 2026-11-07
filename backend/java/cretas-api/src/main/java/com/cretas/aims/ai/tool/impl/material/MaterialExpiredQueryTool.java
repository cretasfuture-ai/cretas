package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 已过期原材料批次查询工具
 *
 * 查询当前工厂所有已过期的原材料批次。
 * 返回包含批次号、原材料名称、过期日期、当前数量等关键信息的列表。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialExpiredQueryTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_expired_query";
    }

    @Override
    public String getDescription() {
        return "按现有业务口径查询待处理过期清单：到期日期早于今天，且尚未标记为EXPIRED；" +
                "不等同于按状态字段EXPIRED筛选，状态筛选用material_batch_query。" +
                "支持page/size分页，按到期日再批次ID稳定排序；不传分页参数仍返回全集。" +
                "不支持改成其他截止日期或任意状态过滤。";
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public boolean supportsPreview() {
        return false;
    }

    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public boolean hasPermission(String userRole) {
        return false;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of(
                "warehouse:read",
                "warehouse:read_write",
                "inventory:read",
                "inventory:read_write");
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("material");
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("x-clarification-description", "按到期日期早于今天、且尚未标记为过期筛选待处理清单；不是查询已标记过期的状态。");

        Map<String, Object> properties = new HashMap<>();
        properties.put("page", Map.of("type","integer","minimum",1,"description","页码，从1开始；不提供任何分页参数则保持全集查询"));
        properties.put("size", Map.of("type","integer","minimum",1,"maximum",100,"description","每页条数；启动分页后未指定则20条"));
        schema.put("properties", properties);
        schema.put("x-llm-extract-optional",true);

        // 无必需字段
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // 无必需参数
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("查询已过期原材料批次: factoryId={}", factoryId);

        // 调用服务获取已过期批次
        List<MaterialBatchDTO> expiredBatches = materialBatchService.getExpiredBatches(factoryId);
        int total = expiredBatches.size();
        boolean paged = params.get("page") != null || params.get("size") != null;
        int page = 1, size = 20;
        if (paged) {
            page = getInteger(params,"page",1); size = getInteger(params,"size",20);
            if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("页码须从1开始，每页条数须为1到100。");
            List<MaterialBatchDTO> sorted = new ArrayList<>(expiredBatches);
            sorted.sort(Comparator.comparing(MaterialBatchDTO::getExpireDate,Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(MaterialBatchDTO::getId,Comparator.nullsLast(Comparator.naturalOrder())));
            long start = (long)(page-1)*size;
            expiredBatches = start>=total ? Collections.emptyList() : sorted.subList((int)start,(int)Math.min(start+size,total));
        }

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("total", total);
        result.put("queryConditions", Map.of("basis","EXPIRY_DATE","expiryBefore",java.time.LocalDate.now().toString(),"excludedStatus","EXPIRED"));
        result.put("source","material_batches");
        if (paged) {
            result.put("page",page);result.put("size",size);result.put("totalPages",(total+size-1)/size);
        }

        if (expiredBatches.isEmpty()) {
            result.put("message", total==0 ? "按到期日查询，当前没有尚未标记过期的待处理批次。" : "当前页没有待处理过期批次。");
            result.put("batches", Collections.emptyList());
        } else {
            result.put("message", String.format("按到期日查询尚未标记过期的批次，共 %d 条，本次返回 %d 条。", total, expiredBatches.size()));
            // 转换为简洁格式
            List<Map<String, Object>> batchList = expiredBatches.stream()
                    .map(this::convertToSimpleFormat)
                    .collect(Collectors.toList());
            result.put("batches", batchList);
        }

        return result;
    }

    /**
     * 转换为简洁格式（避免返回过多字段）
     */
    private Map<String, Object> convertToSimpleFormat(MaterialBatchDTO batch) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id",batch.getId());
        item.put("batchNumber", batch.getBatchNumber());
        item.put("materialName", batch.getMaterialName());
        item.put("materialCode", batch.getMaterialCode());
        item.put("expireDate", batch.getExpireDate() != null ? batch.getExpireDate().toString() : null);
        item.put("currentQuantity", batch.getCurrentQuantity());
        item.put("unit", batch.getUnit());
        item.put("supplierName", batch.getSupplierName());
        item.put("storageLocation", batch.getStorageLocation());
        item.put("status", batch.getStatus() != null ? batch.getStatus().name() : null);
        item.put("statusDisplayName", batch.getStatusDisplayName());
        return item;
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
