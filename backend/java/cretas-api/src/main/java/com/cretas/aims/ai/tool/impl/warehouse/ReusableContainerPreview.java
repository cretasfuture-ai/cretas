package com.cretas.aims.ai.tool.impl.warehouse;

import com.cretas.aims.entity.warehouse.ReusableContainer;
import com.cretas.aims.service.warehouse.ReusableContainerService;
import com.cretas.aims.service.warehouse.ReusableContainerService.ContainerNotFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.cretas.aims.ai.tool.WritePreviewSupport.blocked;
import static com.cretas.aims.ai.tool.WritePreviewSupport.notBlank;
import static com.cretas.aims.ai.tool.WritePreviewSupport.quantityText;
import static com.cretas.aims.ai.tool.WritePreviewSupport.ready;

/**
 * 周转框「发出 / 归还」的<b>只读</b>预览。
 *
 * <p>⛔ 只调 {@link ReusableContainerService#assertShipOutAllowed} / {@code assertReturnInAllowed}
 * (纯读校验), 绝不调 {@code shipOut / returnIn}。这两个校验方法是写路径<b>自己也在用</b>的那一份
 * (数量 &gt; 0 / 周转框属于本工厂 / 库存够不够), 预览说「能做」与写路径放行是同一个判断。
 */
final class ReusableContainerPreview {

    private static final String UNIT = "个";

    private ReusableContainerPreview() {
    }

    static Map<String, Object> shipOut(ReusableContainerService service, String factoryId,
                                       String containerId, Integer quantity, String customerName) {
        ReusableContainer c;
        try {
            c = service.assertShipOutAllowed(factoryId, containerId, quantity);
        } catch (ContainerNotFoundException e) {
            return notFound();
        } catch (IllegalArgumentException e) {
            return refused(service, factoryId, containerId, e.getMessage());
        }
        int inWh = c.getInWarehouseQuantity();
        int inTransit = c.getInTransitQuantity();
        Map<String, Object> current = rows(c, customerName);
        current.put("在库", quantityText(inWh, UNIT));
        current.put("在途（客户处）", quantityText(inTransit, UNIT));
        Map<String, Object> next = rows(c, customerName);
        next.put("在库", quantityText(inWh - quantity, UNIT));
        next.put("在途（客户处）", quantityText(inTransit + quantity, UNIT));
        String message = String.format(
                "确认发出 %s「%s」给「%s」？在库 %d → %d，在途（客户处）%d → %d。",
                quantityText(quantity, UNIT), nameOf(c), customerName,
                inWh, inWh - quantity, inTransit, inTransit + quantity);
        return ready(message, current, next, null);
    }

    static Map<String, Object> returnIn(ReusableContainerService service, String factoryId,
                                        String containerId, Integer quantity, String customerName) {
        ReusableContainer c;
        try {
            c = service.assertReturnInAllowed(factoryId, containerId, quantity);
        } catch (ContainerNotFoundException e) {
            return notFound();
        } catch (IllegalArgumentException e) {
            return refused(service, factoryId, containerId, e.getMessage());
        }
        int inWh = c.getInWarehouseQuantity();
        int inTransit = c.getInTransitQuantity();
        Map<String, Object> current = rows(c, customerName);
        current.put("在途（客户处）", quantityText(inTransit, UNIT));
        current.put("在库", quantityText(inWh, UNIT));
        Map<String, Object> next = rows(c, customerName);
        next.put("在途（客户处）", quantityText(inTransit - quantity, UNIT));
        next.put("在库", quantityText(inWh + quantity, UNIT));
        String message = String.format(
                "确认「%s」归还 %s「%s」入库？在途（客户处）%d → %d，在库 %d → %d。",
                customerName, quantityText(quantity, UNIT), nameOf(c),
                inTransit, inTransit - quantity, inWh, inWh + quantity);
        return ready(message, current, next, null);
    }

    /** 身份行: 当前 / 修改后两侧相同, 让人认得出动的是哪一种、哪一个客户。 */
    private static Map<String, Object> rows(ReusableContainer c, String customerName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("周转框", nameOf(c));
        m.put("客户", customerName);
        return m;
    }

    private static Map<String, Object> notFound() {
        return blocked("CONTAINER_NOT_FOUND",
                "找不到这种周转框，或它不属于本工厂。请核对周转框的名称后再试。", null);
    }

    /** 数量不对 / 库存不够: 原因取自写路径同一份校验的消息; 能查到周转框名字就带上, 让人知道说的是哪个。 */
    private static Map<String, Object> refused(ReusableContainerService service, String factoryId,
                                               String containerId, String reason) {
        String prefix = "";
        try {
            prefix = "「" + nameOf(service.getContainer(factoryId, containerId)) + "」";
        } catch (RuntimeException ignored) {
            // 取不到名字不影响原因本身
        }
        return blocked("INVALID_STATE", prefix + reason, null);
    }

    private static String nameOf(ReusableContainer c) {
        String name = notBlank(c.getContainerName()) ? c.getContainerName() : "周转框";
        return notBlank(c.getContainerCode()) ? name + "（" + c.getContainerCode() + "）" : name;
    }
}
