package com.cretas.aims.ai.tool.impl.factory;

import com.cretas.aims.ai.tool.StatusFilter;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition.Status;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * P0-5 Tool — 查询待备料的物料需求单.
 *
 * <p>触发短语: "待备料" / "今天要备的料" / "物料需求单列表".
 */
@Slf4j
@Component
public class MaterialRequisitionQueryPendingTool extends AbstractBusinessTool {

    @Autowired
    private FactoryMaterialRequisitionService service;

    @Override
    public String getToolName() {
        return "factory_material_requisition_query_pending";
    }

    @Override
    public String getDescription() {
        return "查询工厂待备料 / 备料中 / 已调拨的物料需求单列表。" +
               "当用户说'待备料'、'今天要备的料'、'物料需求单'时调用";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "status", StatusFilter.property("领料单状态, 为空返回 PENDING (待备料)",
                        // 取自枚举本身: 以前的说明只列了 4 个, 实际有 8 个
                        StatusFilter.wordsOf(Status.class, null, Map.of(
                                Status.DRAFT, List.of("草稿"),
                                Status.PENDING, List.of("待备料", "待处理"),
                                Status.PICKING, List.of("备料中"),
                                Status.TRANSFERRED, List.of("已调拨"),
                                Status.ISSUED, List.of("已签收", "已发料"),
                                Status.IN_USE, List.of("生产中", "使用中"),
                                Status.CLOSED, List.of("已关单", "已退料"),
                                Status.CANCELLED, List.of("已取消")))),
                "size", Map.of("type", "integer", "description", "最多返回条数, 默认 20")
            ),
            "required", List.of()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("factory", "material", "production");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String statusStr = getString(params, "status");
        Status status;
        try {
            status = statusStr != null && !statusStr.isBlank() ? Status.valueOf(statusStr) : Status.PENDING;
        } catch (IllegalArgumentException e) {
            status = Status.PENDING;
        }
        int size = params.get("size") != null ? ((Number) params.get("size")).intValue() : 20;
        Page<FactoryMaterialRequisition> page = service.list(factoryId, status,
                PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return buildSimpleResult(
                "找到 " + page.getTotalElements() + " 条 " + status + " 领料单",
                Map.of("status", status, "total", page.getTotalElements(), "list", page.getContent()));
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
