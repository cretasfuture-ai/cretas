package com.cretas.aims.blueprint.interview;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.entity.ProductionLineCapacityConfig.CapacityUnit;
import com.cretas.aims.service.scheduling.LineCapacityEquipmentCheck;

/**
 * S4 对话建厂产线与产能: 预览 (planChanges) 与落库回执 (written / skipped) 共用的键。
 * 两边各拼一份就会漂 —— 复核界面按这个键把「预览说要做的」和「实际做了的」对上。
 */
final class FactoryBuildProductionLines {

    private FactoryBuildProductionLines() {}

    static String lineKey(String lineName) {
        return "产线 " + lineName;
    }

    /** 产品为空 = 该线所有产品通用。 */
    static String capacityKey(String lineName, String productCode) {
        return lineName + " · " + (productCode == null || productCode.isBlank() ? "所有产品" : productCode.trim());
    }

    static String capacityDetail(ApplyAIInitRequest.LineCapacityItem c, String productName) {
        CapacityUnit unit = LineCapacityEquipmentCheck.parseUnit(c.getCapacityUnit());
        String rate = c.getCapacityPerHour() == null ? "?" : c.getCapacityPerHour().stripTrailingZeros().toPlainString();
        return (productName == null ? "所有产品" : productName) + " 每小时 " + rate + " "
                + (unit == null ? c.getCapacityUnit() : LineCapacityEquipmentCheck.unitLabel(unit));
    }
}
