package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.material.MaterialBatchListFilter;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 原料批次查询工具
 *
 * 提供原料批次的分页查询功能，支持按批次号、原料类型、状态等条件筛选。
 * 作为查询类Tool，无必需参数，所有参数均为可选。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialBatchQueryTool extends AbstractBusinessTool {

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_batch_query";
    }

    @Override
    public String getDescription() {
        return "查询原料批次列表，按已保存的状态字段筛选并支持分页。EXPIRED表示已标记过期，" +
                "不等同于按有效期日期查待处理过期清单；后者用material_expired_query。" +
                "现有状态列表中的状态纠正由本工具继续处理。支持按批次号、原料类型ID、入库日期、状态筛选。" +
                "适用场景：查看库存批次、查找特定批次、查询某类原料的所有批次。";
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
        schema.put("x-clarification-description", "按已经保存的批次状态筛选，例如已标记过期、检验中或可用；不按到期日判断。");

        Map<String, Object> properties = new HashMap<>();

        // batchNumber: 批次号（可选）
        Map<String, Object> batchNumber = new HashMap<>();
        batchNumber.put("type", "string");
        batchNumber.put("description", "批次号，支持模糊查询");
        properties.put("batchNumber", batchNumber);

        // materialTypeId: 原料类型ID（可选）
        Map<String, Object> materialTypeId = new HashMap<>();
        materialTypeId.put("type", "string");
        materialTypeId.put("description", "原料类型ID，精确匹配");
        properties.put("materialTypeId", materialTypeId);

        // status: 状态（可选）
        // 状态词表 (含「即将过期」等要换个问法的说法): 对话里说的状态由 AbstractBusinessTool 统一认 (StatusFilter)
        // enum 取自批次状态枚举本身: 以前手写的列表里有不存在的状态、又漏了 DEPLETED。
        properties.put("status", MaterialBatchStatusPhrase.property());

        // page: 页码（可选，默认1）
        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从1开始");
        page.put("default", 1);
        page.put("minimum", 1);
        properties.put("page", page);

        // size: 每页数量（可选，默认10）
        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 10);
        size.put("minimum", 1);
        size.put("maximum", 100);
        properties.put("size", size);

        // startDate: 入库日期开始（可选，格式 YYYY-MM-DD）
        Map<String, Object> startDate = new HashMap<>();
        startDate.put("type", "string");
        startDate.put("format", "date");
        startDate.put("description", "入库日期开始（含），格式：YYYY-MM-DD");
        properties.put("startDate", startDate);

        // endDate: 入库日期结束（可选，格式 YYYY-MM-DD）
        Map<String, Object> endDate = new HashMap<>();
        endDate.put("type", "string");
        endDate.put("format", "date");
        endDate.put("description", "入库日期结束（含），格式：YYYY-MM-DD");
        properties.put("endDate", endDate);

        schema.put("properties", properties);

        // 查询类Tool无必需参数
        schema.put("required", Collections.emptyList());

        return schema;
    }

    /**
     * 查询类Tool无必需参数
     */
    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        log.info("执行原料批次查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析分页参数
        Integer page = getInteger(params, "page", 1);
        Integer size = getInteger(params, "size", 10);

        // 解析筛选参数
        String batchNumber = getString(params, "batchNumber");
        String materialTypeId = getString(params, "materialTypeId");
        // ERP-S6-10: 对话里说的状态 (「已用完」) 已由 AbstractBusinessTool 按 status 词表认成代码放进 params;
        // 说了却认不出的在那里就 400 了, 不会静默变成「不限状态」。
        String status = getString(params, "status");
        String startDateStr = getString(params, "startDate");
        String endDateStr = getString(params, "endDate");

        // 解析日期参数
        LocalDate startDate = parseDate(startDateStr);
        LocalDate endDate = parseDate(endDateStr);
        if (startDate != null || endDate != null) {
            log.info("日期过滤条件: {} ~ {}", startDate, endDate);
        }

        // 构建分页请求
        PageRequest pageRequest = new PageRequest();
        pageRequest.setPage(page);
        pageRequest.setSize(size);

        // 设置筛选条件
        if (batchNumber != null && !batchNumber.trim().isEmpty()) {
            pageRequest.setKeyword(batchNumber);
        }
        if (status != null && !status.trim().isEmpty()) {
            pageRequest.setStatus(status);
        }

        // 调用服务获取数据
        // 原料类型 / 入库日期与状态一样交给数据库在分页之前过滤 (ERP-S6-02):
        // 以前是查回一页再在这一页里筛, 匹配项在第 2 页以后就读成「没有」, 总数也是错的。
        PageResponse<MaterialBatchDTO> pageResponse = materialBatchService.getMaterialBatchList(
                factoryId, pageRequest, new MaterialBatchListFilter(
                        materialTypeId != null && !materialTypeId.trim().isEmpty() ? materialTypeId.trim() : null,
                        startDate, endDate));
        if (pageResponse == null) {
            // ⛔ 不当成「0 条」: 服务没给出分页结果就是没查到, 明确报错而不是空列表或 NPE
            throw new IllegalStateException("原料批次查询没有返回分页结果 (factoryId=" + factoryId + ")");
        }

        List<MaterialBatchDTO> content = pageResponse.getContent();

        // 构建返回结果
        Map<String, Object> result = buildPageResult(
                content != null ? content : Collections.emptyList(),
                pageResponse.getTotalElements() != null ? pageResponse.getTotalElements() : 0L,
                pageResponse.getTotalPages() != null ? pageResponse.getTotalPages() : 0,
                page
        );

        // Keep the service's actual page size so server-owned pagination never infers it from the row count.
        result.put("size", pageResponse.getSize() != null ? pageResponse.getSize() : size);

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        if (batchNumber != null) queryConditions.put("batchNumber", batchNumber);
        if (materialTypeId != null) queryConditions.put("materialTypeId", materialTypeId);
        if (status != null) queryConditions.put("status", status);
        if (startDate != null) queryConditions.put("startDate", startDate.toString());
        if (endDate != null) queryConditions.put("endDate", endDate.toString());
        result.put("queryConditions", queryConditions);
        // totalElements 用数据库给的真实总数 (buildPageResult 已写入); ⛔ 不再改成「本页条数」

        log.info("原料批次查询完成 - 总记录数: {}, 当前页: {}",
                pageResponse.getTotalElements(), page);

        return result;
    }

    /**
     * 解析日期字符串
     *
     * @param dateStr 日期字符串 (YYYY-MM-DD)
     * @return LocalDate 或 null
     */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (DateTimeParseException e) {
            // ⛔ 不静默当成「不限日期」: 那会把一个按日期查的问题答成全部批次
            throw new com.cretas.aims.exception.BusinessException(400,
                    "无法识别的日期: " + dateStr.trim())
                    .withHint("日期请用 YYYY-MM-DD, 例如 2026-11-03");
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
