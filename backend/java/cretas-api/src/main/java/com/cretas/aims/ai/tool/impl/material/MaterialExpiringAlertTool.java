package com.cretas.aims.ai.tool.impl.material;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ExpiryDateWindow;
import com.cretas.aims.ai.tool.TimeRangeFilter;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.service.MaterialBatchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 即将过期原材料预警工具
 *
 * 查询即将在指定天数内过期的原材料批次。
 * 默认查询7天内过期的批次，可通过参数自定义预警天数。
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-07
 */
@Slf4j
@Component
public class MaterialExpiringAlertTool extends AbstractBusinessTool {

    /**
     * 默认预警天数
     */
    private static final int DEFAULT_WARNING_DAYS = 7;

    private static final Pattern EXPIRY_WORDS = Pattern.compile("到期|过期|临期");
    private static final String COUNT = "([0-9０-９]{1,4}|[一二两三四五六七八九十]{1,3})";
    private static final Pattern FUTURE_WINDOW = Pattern.compile(
            COUNT + "\\h*(天|周)\\h*(?:之?内|后)|未来\\h*" + COUNT + "\\h*天");

    /** 复合问法「快过期的有哪些？库存够不够？」第二问的触发词; 同时带出低库存预警 (同 material_low_stock_alert 的服务)。 */
    private static final java.util.regex.Pattern LOW_STOCK_WORDS =
            java.util.regex.Pattern.compile("库存.{0,4}(不够|不足|偏低|太低|快没|不多)|(不够|不足|缺).{0,2}库存|低库存|安全库存");

    @Autowired
    private MaterialBatchService materialBatchService;

    @Override
    public String getToolName() {
        return "material_expiring_alert";
    }

    @Override
    public String getDescription() {
        return "查询即将过期的原材料批次预警。返回指定天数内即将过期的批次列表，包括批次号、" +
                "原材料名称、过期日期、剩余天数、当前数量等。默认查询7天内过期的批次。" +
                "适用于保质期管理、库存预警、先进先出提醒等场景。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        // warningDays: 预警天数（可选，默认7天）
        Map<String, Object> warningDays = new HashMap<>();
        warningDays.put("type", "integer");
        warningDays.put("description", "预警天数，查询多少天内即将过期的批次。默认为7天。");
        warningDays.put("default", DEFAULT_WARNING_DAYS);
        warningDays.put("minimum", 1);
        warningDays.put("maximum", 365);
        properties.put("warningDays", warningDays);
        ExpiryDateWindow.declareParameters(properties);

        schema.put("properties", properties);

        // 无必需字段
        schema.put("required", Collections.emptyList());

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        // warningDays 是可选参数
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        Object said = params.get("originalUserInput") != null ? params.get("originalUserInput") : params.get("userInput");
        String text = said instanceof String input ? input : "";
        LocalDate today = LocalDate.now();
        applyFutureExpiryWindow(params, text, today);
        int warningDays = getInteger(params, "warningDays", DEFAULT_WARNING_DAYS);
        ExpiryDateWindow dates = ExpiryDateWindow.resolve(params, today, today.plusDays(warningDays));
        int horizon = Math.toIntExact(ChronoUnit.DAYS.between(today, dates.to()));

        log.info("查询即将过期原材料预警: factoryId={}, warningDays={}", factoryId, horizon);

        // 调用服务获取即将过期批次
        List<MaterialBatchDTO> expiringBatches = horizon < 0 ? List.of()
                : materialBatchService.getExpiringBatches(factoryId, horizon).stream()
                        .filter(batch -> dates.includes(batch.getExpireDate()))
                        .collect(Collectors.toList());

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("total", expiringBatches.size());
        result.put("warningDays", horizon);
        String period = dates.from() + " 至 " + dates.to();

        if (expiringBatches.isEmpty()) {
            result.put("message", "到期日范围 " + period + " 内没有即将过期的原材料批次。");
            result.put("batches", Collections.emptyList());
        } else {
            result.put("message", "到期日范围 " + period + " 内有 " + expiringBatches.size() + " 批即将到期。");
            // 转换为简洁格式，按剩余天数排序
            List<Map<String, Object>> batchList = expiringBatches.stream()
                    .sorted(Comparator.comparing(MaterialBatchDTO::getRemainingDays, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(this::convertToSimpleFormat)
                    .collect(Collectors.toList());
            result.put("batches", batchList);
        }

        if (LOW_STOCK_WORDS.matcher(text).find()) {
            result.put("lowStockWarnings", materialBatchService.getLowStockWarnings(factoryId));
        }
        return result;
    }

    /** 从原话绑定到期范围。调度层也在记录本轮查询条件前使用这一口径。 */
    public static boolean applyFutureExpiryWindow(Map<String, Object> params, String text, LocalDate today) {
        if (text == null || !EXPIRY_WORDS.matcher(text).find()) {
            return false;
        }
        Matcher window = FUTURE_WINDOW.matcher(text);
        if (!window.find()) {
            return false;
        }
        String count = window.group(1) != null ? window.group(1) : window.group(3);
        int days = TimeNormalizationRules.parseCount(count) * ("周".equals(window.group(2)) ? 7 : 1);
        LocalDate end = today.plusDays(days);
        params.put("startDate", today.toString());
        params.put("endDate", end.toString());
        params.put(TimeRangeFilter.SAID, today + "~" + end);
        return true;
    }

    /**
     * 转换为简洁格式（包含剩余天数）
     */
    private Map<String, Object> convertToSimpleFormat(MaterialBatchDTO batch) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("batchNumber", batch.getBatchNumber());
        item.put("materialName", batch.getMaterialName());
        item.put("materialCode", batch.getMaterialCode());
        item.put("expireDate", batch.getExpireDate() != null ? batch.getExpireDate().toString() : null);
        item.put("remainingDays", batch.getRemainingDays());
        item.put("currentQuantity", batch.getCurrentQuantity());
        item.put("unit", batch.getUnit());
        item.put("supplierName", batch.getSupplierName());
        item.put("storageLocation", batch.getStorageLocation());
        item.put("status", batch.getStatus() != null ? batch.getStatus().name() : null);
        item.put("statusDisplayName", batch.getStatusDisplayName());
        return item;
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("warningDays".equals(paramName)) {
            return "请问您想查询多少天内即将过期的批次？默认是7天。";
        }
        return super.getParameterQuestion(paramName);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    /**
     * S6 gated-data rule (2026-10-29): the AI / Skill path admits exactly who the direct API admits
     * for the same data; pinned by GatedDataToolAiPathPermissionTest.
     */
    @Override
    public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("inventory:read", "inventory:read_write", "warehouse:read", "warehouse:read_write");
    }
}
