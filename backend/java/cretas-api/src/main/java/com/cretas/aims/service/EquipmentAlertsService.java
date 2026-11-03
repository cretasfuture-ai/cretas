package com.cretas.aims.service;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.equipment.CreateEquipmentAlertRequest;
import com.cretas.aims.dto.equipment.EquipmentAlertDTO;
import com.cretas.aims.entity.enums.AlertStatus;

import java.util.Map;

/**
 * 设备告警服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-27
 */
public interface EquipmentAlertsService {

    /**
     * 获取告警列表（分页）
     */
    PageResponse<EquipmentAlertDTO> getAlertList(String factoryId, PageRequest pageRequest,
                                                  String keyword, String severity, String status);

    /**
     * 同上, 另按设备过滤 (equipmentId 为 null 时不限)。设备条件在数据库里、分页之前生效, 总数是筛过的 ——
     * 以前 alert_list / alert_by_equipment 在查回的这一页里按设备筛, 总数仍是全厂的。
     */
    PageResponse<EquipmentAlertDTO> getAlertList(String factoryId, PageRequest pageRequest,
                                                  String keyword, String severity, String status, Long equipmentId);

    /**
     * 获取告警统计
     */
    Map<String, Object> getAlertStatistics(String factoryId);

    /**
     * 获取告警详情
     */
    EquipmentAlertDTO getAlertById(String factoryId, Integer alertId);

    /**
     * 确认告警
     */
    EquipmentAlertDTO acknowledgeAlert(String factoryId, Integer alertId, Long userId, String userName);

    /**
     * 处理告警
     */
    EquipmentAlertDTO resolveAlert(String factoryId, Integer alertId, Long userId, String userName, String resolution);

    /**
     * 创建告警
     */
    EquipmentAlertDTO createAlert(String factoryId, CreateEquipmentAlertRequest request);
}
