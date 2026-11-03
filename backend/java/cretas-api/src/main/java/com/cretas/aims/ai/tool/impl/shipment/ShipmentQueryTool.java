package com.cretas.aims.ai.tool.impl.shipment;

import com.cretas.aims.ai.tool.TimeRangeFilter;
import com.cretas.aims.ai.tool.StatusFilter;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.service.ShipmentRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 出货单查询工具
 *
 * 提供出货单的分页查询功能，支持按状态、客户、日期范围等条件筛选。
 * 作为查询类Tool，无必需参数，所有参数均为可选。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class ShipmentQueryTool extends AbstractBusinessTool {

    @Autowired
    private ShipmentRecordService shipmentRecordService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String getToolName() {
        return "shipment_query";
    }

    @Override
    public String getDescription() {
        return "查询出货单列表。支持按状态、客户ID、日期范围进行筛选，支持分页。" +
                "适用场景：查看出货记录、查找特定客户的出货单、查询某时间段的出货情况。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // status: 状态筛选（可选）
        Map<String, Object> status = StatusFilter.property("出货单状态筛选", StatusFilter.table(
                StatusFilter.words("PENDING", "待发货", "未发货"),
                StatusFilter.words("SHIPPED", "已发货", "运输中", "在途"),
                StatusFilter.words("DELIVERED", "已送达", "已签收", "已到货"),
                StatusFilter.words("CANCELLED", "已取消")));
        properties.put("status", status);

        // customerId: 客户ID筛选（可选）
        Map<String, Object> customerId = new HashMap<>();
        customerId.put("type", "string");
        customerId.put("description", "客户ID，筛选指定客户的出货单");
        properties.put("customerId", customerId);

        // dateFrom: 开始日期（可选）
        Map<String, Object> dateFrom = new HashMap<>();
        dateFrom.put("type", "string");
        dateFrom.put("description", "查询开始日期，格式：yyyy-MM-dd");
        dateFrom.put("format", "date");
        properties.put("dateFrom", dateFrom);

        // dateTo: 结束日期（可选）
        Map<String, Object> dateTo = new HashMap<>();
        dateTo.put("type", "string");
        dateTo.put("description", "查询结束日期，格式：yyyy-MM-dd");
        dateTo.put("format", "date");
        properties.put("dateTo", dateTo);

        // startDate / endDate: 对话入口放进来的标准时间参数 (「上个月的出货记录」); dateFrom / dateTo 仍作别名
        properties.put("startDate", TimeRangeFilter.dateProperty("出货日期开始 (含), YYYY-MM-DD"));
        properties.put("endDate", TimeRangeFilter.dateProperty("出货日期结束 (含), YYYY-MM-DD"));

        // shipmentNumber: 出货单号（可选，精确查询）
        Map<String, Object> shipmentNumber = new HashMap<>();
        shipmentNumber.put("type", "string");
        shipmentNumber.put("description", "出货单号，用于精确查询单个出货单");
        properties.put("shipmentNumber", shipmentNumber);

        // shipmentId: 出货单ID（可选，精确查询）
        Map<String, Object> shipmentId = new HashMap<>();
        shipmentId.put("type", "string");
        shipmentId.put("description", "出货单ID，用于精确查询单个出货单");
        properties.put("shipmentId", shipmentId);

        // page: 页码（可选，默认0）
        Map<String, Object> page = new HashMap<>();
        page.put("type", "integer");
        page.put("description", "页码，从0开始");
        page.put("default", 0);
        page.put("minimum", 0);
        properties.put("page", page);

        // size: 每页数量（可选，默认10）
        Map<String, Object> size = new HashMap<>();
        size.put("type", "integer");
        size.put("description", "每页记录数");
        size.put("default", 10);
        size.put("minimum", 1);
        size.put("maximum", 100);
        properties.put("size", size);

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
        log.info("执行出货单查询 - 工厂ID: {}, 参数: {}", factoryId, params);

        // 解析查询参数
        String shipmentId = getString(params, "shipmentId");
        String shipmentNumber = getString(params, "shipmentNumber");
        String status = getString(params, "status");
        String customerId = getString(params, "customerId");
        // 标准名 startDate/endDate 优先, dateFrom/dateTo 作别名 (以前只认后者, 对话放进来的时间被丢掉)
        String dateFromStr = getString(params, "startDate") != null ? getString(params, "startDate") : getString(params, "dateFrom");
        String dateToStr = getString(params, "endDate") != null ? getString(params, "endDate") : getString(params, "dateTo");
        Integer page = getInteger(params, "page", 0);
        Integer size = getInteger(params, "size", 10);

        // 1. 优先精确查询 - 按ID
        if (shipmentId != null && !shipmentId.trim().isEmpty()) {
            Optional<ShipmentRecord> record = shipmentRecordService.getByIdAndFactoryId(shipmentId, factoryId);
            if (record.isPresent()) {
                return buildSingleResult(record.get());
            } else {
                Map<String, Object> result = new HashMap<>();
                result.put("found", false);
                result.put("message", "未找到出货单: " + shipmentId);
                return result;
            }
        }

        // 2. 精确查询 - 按出货单号
        if (shipmentNumber != null && !shipmentNumber.trim().isEmpty()) {
            Optional<ShipmentRecord> record = shipmentRecordService.getByShipmentNumberAndFactoryId(shipmentNumber, factoryId);
            if (record.isPresent()) {
                return buildSingleResult(record.get());
            } else {
                Map<String, Object> result = new HashMap<>();
                result.put("found", false);
                result.put("message", "未找到出货单号: " + shipmentNumber);
                return result;
            }
        }

        // 3. 列表: 客户 / 状态 / 出货日期一起在数据库里、分页之前过滤 (S6 验收 2026-11-03)。
        //    以前客户、日期、状态各走一条路径, 同时给就静默丢掉其余条件; 日期解析失败也静默变成不限。
        LocalDate dateFrom = TimeRangeFilter.parseDate(dateFromStr, "startDate");
        LocalDate dateTo = TimeRangeFilter.parseDate(dateToStr, "endDate");
        Page<ShipmentRecord> pageResult = shipmentRecordService.searchFiltered(factoryId,
                customerId != null && !customerId.trim().isEmpty() ? customerId.trim() : null,
                mapStatus(status), dateFrom, dateTo, page, size);

        // 构建分页结果
        Map<String, Object> result = buildPageResult(
                pageResult.getContent(),
                pageResult.getTotalElements(),
                pageResult.getTotalPages(),
                page
        );

        // 添加查询条件摘要
        Map<String, Object> queryConditions = new HashMap<>();
        if (status != null) queryConditions.put("status", status);
        if (customerId != null) queryConditions.put("customerId", customerId);
        if (dateFrom != null) queryConditions.put("startDate", dateFrom.toString());
        if (dateTo != null) queryConditions.put("endDate", dateTo.toString());
        result.put("queryConditions", queryConditions);

        log.info("出货单查询完成 - 总记录数: {}, 当前页: {}", pageResult.getTotalElements(), page);

        return result;
    }

    /**
     * 构建单条记录结果
     */
    private Map<String, Object> buildSingleResult(ShipmentRecord record) {
        Map<String, Object> result = new HashMap<>();
        result.put("found", true);
        result.put("shipment", convertToMap(record));
        result.put("message", "查询到出货单: " + record.getShipmentNumber());
        return result;
    }

    /**
     * 将实体转换为Map
     */
    private Map<String, Object> convertToMap(ShipmentRecord record) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", record.getId());
        map.put("shipmentNumber", record.getShipmentNumber());
        map.put("customerId", record.getCustomerId());
        map.put("productName", record.getProductName());
        map.put("quantity", record.getQuantity());
        map.put("unit", record.getUnit());
        map.put("unitPrice", record.getUnitPrice());
        map.put("totalAmount", record.getTotalAmount());
        map.put("shipmentDate", record.getShipmentDate() != null ? record.getShipmentDate().toString() : null);
        map.put("status", mapStatusToUpper(record.getStatus()));
        map.put("deliveryAddress", record.getDeliveryAddress());
        map.put("logisticsCompany", record.getLogisticsCompany());
        map.put("trackingNumber", record.getTrackingNumber());
        map.put("notes", record.getNotes());
        return map;
    }

    /**
     * 映射状态值（从请求参数到数据库值）
     */
    private String mapStatus(String status) {
        if (status == null) return null;
        return switch (status.toUpperCase()) {
            case "PENDING" -> "pending";
            case "SHIPPED" -> "shipped";
            case "DELIVERED" -> "delivered";
            case "CANCELLED" -> "returned"; // CANCELLED映射到returned
            default -> status.toLowerCase();
        };
    }

    /**
     * 映射状态值（从数据库值到响应格式）
     */
    private String mapStatusToUpper(String status) {
        if (status == null) return null;
        return switch (status.toLowerCase()) {
            case "pending" -> "PENDING";
            case "shipped" -> "SHIPPED";
            case "delivered" -> "DELIVERED";
            case "returned" -> "CANCELLED";
            default -> status.toUpperCase();
        };
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        switch (paramName) {
            case "status":
                return "请问要查询哪种状态的出货单？（PENDING待发货/SHIPPED已发货/DELIVERED已送达/CANCELLED已取消）";
            case "customerId":
                return "请问要查询哪个客户的出货单？";
            case "dateFrom":
                return "请问查询的开始日期是？（格式：yyyy-MM-dd）";
            case "dateTo":
                return "请问查询的结束日期是？（格式：yyyy-MM-dd）";
            default:
                return super.getParameterQuestion(paramName);
        }
    }

    @Override
    protected String getParameterDisplayName(String paramName) {
        switch (paramName) {
            case "status":
                return "状态";
            case "customerId":
                return "客户ID";
            case "dateFrom":
                return "开始日期";
            case "dateTo":
                return "结束日期";
            case "shipmentNumber":
                return "出货单号";
            case "shipmentId":
                return "出货单ID";
            default:
                return super.getParameterDisplayName(paramName);
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
