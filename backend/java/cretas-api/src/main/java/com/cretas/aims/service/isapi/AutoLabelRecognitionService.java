package com.cretas.aims.service.isapi;

import com.cretas.aims.ai.client.DashScopeVisionClient;
import com.cretas.aims.ai.client.DashScopeVisionClient.LabelRecognitionResult;
import com.cretas.aims.dto.isapi.IsapiCaptureDTO;
import com.cretas.aims.entity.isapi.LabelRecognitionConfig;
import com.cretas.aims.entity.isapi.LabelRecognitionRecord;
import com.cretas.aims.entity.isapi.LabelRecognitionRecord.*;
import com.cretas.aims.repository.isapi.LabelRecognitionConfigRepository;
import com.cretas.aims.repository.isapi.LabelRecognitionRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自动标签识别服务
 *
 * 核心功能:
 * - 监听ISAPI事件，自动触发标签识别
 * - 异步执行识别：抓帧 -> OCR识别 -> 保存结果 -> WebSocket推送
 * - 支持手动触发识别
 *
 * @author Cretas Team
 * @since 2026-01-13
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoLabelRecognitionService {

    private final LabelRecognitionConfigRepository configRepository;
    private final LabelRecognitionRecordRepository recordRepository;
    private final IsapiDeviceService isapiDeviceService;
    private final DashScopeVisionClient visionClient;
    private final CameraSnapshotAiConsent cameraAiConsent;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    // ==================== 配置管理 ====================

    /**
     * 获取工厂的所有配置
     */
    public List<LabelRecognitionConfig> getConfigs(String factoryId) {
        return configRepository.findByFactoryId(factoryId);
    }

    /**
     * 获取工厂的所有配置（分页）
     */
    public Page<LabelRecognitionConfig> getConfigs(String factoryId, Pageable pageable) {
        return configRepository.findByFactoryId(factoryId, pageable);
    }

    /**
     * 获取单个配置 (只认本厂: 别厂的配置与不存在同样报「配置不存在」)
     */
    public LabelRecognitionConfig getConfig(String factoryId, Long configId) {
        return configRepository.findById(configId)
                .filter(c -> factoryId != null && factoryId.equals(c.getFactoryId()))
                .orElseThrow(() -> new IllegalArgumentException("配置不存在: " + configId));
    }

    /**
     * 创建配置
     */
    @Transactional
    public LabelRecognitionConfig createConfig(String factoryId, LabelRecognitionConfig config) {
        // 摄像头必须是本厂的: 否则可以把配置挂到别厂摄像头上, 再手动触发去抓别厂画面
        isapiDeviceService.getDevice(factoryId, config.getDeviceId());
        // 检查设备是否已有配置
        if (configRepository.existsByFactoryIdAndDeviceId(factoryId, config.getDeviceId())) {
            throw new IllegalArgumentException("该设备已有标签识别配置");
        }

        config.setFactoryId(factoryId);
        config = configRepository.save(config);

        log.info("创建标签识别配置: factoryId={}, deviceId={}, configId={}",
                factoryId, config.getDeviceId(), config.getId());

        return config;
    }

    /**
     * 更新配置
     */
    @Transactional
    public LabelRecognitionConfig updateConfig(String factoryId, Long configId, LabelRecognitionConfig updates) {
        LabelRecognitionConfig config = getConfig(factoryId, configId);

        if (updates.getConfigName() != null) {
            config.setConfigName(updates.getConfigName());
        }
        if (updates.getChannelId() != null) {
            config.setChannelId(updates.getChannelId());
        }
        if (updates.getTriggerOnVmd() != null) {
            config.setTriggerOnVmd(updates.getTriggerOnVmd());
        }
        if (updates.getTriggerOnFieldDetection() != null) {
            config.setTriggerOnFieldDetection(updates.getTriggerOnFieldDetection());
        }
        if (updates.getCooldownSeconds() != null) {
            config.setCooldownSeconds(updates.getCooldownSeconds());
        }
        if (updates.getMinConfidence() != null) {
            config.setMinConfidence(updates.getMinConfidence());
        }
        if (updates.getDefaultBatchId() != null) {
            config.setDefaultBatchId(updates.getDefaultBatchId());
        }

        return configRepository.save(config);
    }

    /**
     * 启用/禁用配置
     */
    @Transactional
    public LabelRecognitionConfig toggleConfig(String factoryId, Long configId, boolean enabled) {
        LabelRecognitionConfig config = getConfig(factoryId, configId);
        config.setEnabled(enabled);
        config = configRepository.save(config);

        log.info("{}标签识别配置: configId={}", enabled ? "启用" : "禁用", configId);

        return config;
    }

    /**
     * 删除配置
     */
    @Transactional
    public void deleteConfig(String factoryId, Long configId) {
        LabelRecognitionConfig config = getConfig(factoryId, configId);
        config.softDelete();
        configRepository.save(config);

        log.info("删除标签识别配置: configId={}", configId);
    }

    // ==================== ISAPI 事件触发 ====================

    /**
     * 处理ISAPI事件，判断是否触发标签识别
     *
     * @param factoryId 工厂ID
     * @param eventType 事件类型 (VMD, fielddetection 等)
     * @param deviceId  设备ID
     * @param eventId   事件ID（可选）
     */
    public void onIsapiEvent(String factoryId, String eventType, String deviceId, String eventId) {
        log.debug("收到ISAPI事件: factoryId={}, eventType={}, deviceId={}", factoryId, eventType, deviceId);
        // 抓帧交给 AI 识别默认关闭: 本厂没开就不抓帧、不发、也不留记录
        if (!cameraAiConsent.enabled(factoryId)) {
            log.debug("本厂未开启摄像头抓拍 AI 识别, 跳过标签识别: factoryId={}", factoryId);
            return;
        }

        // 查询该设备的启用配置 (只取本厂的: 设备 id 不带工厂, 别厂挂上来的配置不触发)
        List<LabelRecognitionConfig> configs = configRepository.findEnabledConfigsByDeviceId(deviceId).stream()
                .filter(c -> factoryId != null && factoryId.equals(c.getFactoryId()))
                .toList();

        for (LabelRecognitionConfig config : configs) {
            // 检查是否应该触发
            if (config.shouldTrigger(eventType)) {
                log.info("触发标签识别: configId={}, eventType={}", config.getId(), eventType);

                // 解析触发类型
                TriggerType triggerType = "VMD".equalsIgnoreCase(eventType)
                        ? TriggerType.VMD : TriggerType.FIELD_DETECTION;

                // 异步执行识别
                recognizeAsync(config, triggerType, eventId);
            }
        }
    }

    // ==================== 识别执行 ====================

    /**
     * 异步执行标签识别
     *
     * @param config      配置
     * @param triggerType 触发类型
     * @param eventId     事件ID（可选）
     */
    @Async
    @Transactional
    public void recognizeAsync(LabelRecognitionConfig config, TriggerType triggerType, String eventId) {
        // 冷却时间检查
        if (config.isInCooldown()) {
            log.debug("配置 {} 在冷却期内，跳过识别", config.getId());
            return;
        }

        // 更新触发时间
        config.updateTriggerTime();
        configRepository.save(config);

        // 执行识别
        LabelRecognitionRecord record = doRecognize(config, triggerType, eventId);

        // WebSocket 推送
        pushToWebSocket(config.getFactoryId(), record);
    }

    /**
     * 手动触发识别
     *
     * @param configId 配置ID
     * @return 识别记录
     */
    @Transactional
    public LabelRecognitionRecord manualRecognize(String factoryId, Long configId) {
        LabelRecognitionConfig config = getConfig(factoryId, configId);

        log.info("手动触发标签识别: configId={}", configId);

        // 更新触发时间
        config.updateTriggerTime();
        configRepository.save(config);

        // 执行识别
        LabelRecognitionRecord record = doRecognize(config, TriggerType.MANUAL, null);

        // WebSocket 推送
        pushToWebSocket(config.getFactoryId(), record);

        return record;
    }

    /**
     * 执行识别核心逻辑
     */
    private LabelRecognitionRecord doRecognize(LabelRecognitionConfig config, TriggerType triggerType, String eventId) {
        long startTime = System.currentTimeMillis();
        LocalDateTime recognitionTime = LocalDateTime.now();
        byte[] capturedImageData = null;

        if (!cameraAiConsent.enabled(config.getFactoryId())) {
            return saveFailedRecord(config, triggerType, eventId, recognitionTime,
                    null, CameraSnapshotAiConsent.DISABLED_MESSAGE, startTime);
        }

        try {
            // 1. 抓帧
            log.debug("开始抓帧: deviceId={}, channelId={}", config.getDeviceId(), config.getChannelId());
            // 按配置所属工厂抓帧: 摄像头不是该厂的就不抓 (存量配置里可能有挂错厂的)
            IsapiCaptureDTO capture = isapiDeviceService.capturePicture(
                    config.getFactoryId(), config.getDeviceId(), config.getChannelId());

            if (!capture.isSuccess()) {
                log.error("抓帧失败: {}", capture.getError());
                return saveFailedRecord(config, triggerType, eventId, recognitionTime,
                        null, "抓帧失败: " + capture.getError(), startTime);
            }

            // 保存抓拍图片
            capturedImageData = Base64.getDecoder().decode(capture.getPictureBase64());

            // 2. OCR识别
            log.debug("开始OCR识别");
            Map<String, Object> context = new HashMap<>();
            context.put("deviceName", capture.getDeviceName());

            LabelRecognitionResult result = visionClient.recognizeLabel(
                    capture.getPictureBase64(),
                    config.getDefaultBatchId(),
                    context);

            if (!result.isSuccess()) {
                log.warn("OCR识别失败: {}", result.getMessage());
                return saveFailedRecord(config, triggerType, eventId, recognitionTime,
                        capturedImageData, result.getMessage(), startTime);
            }

            // 3. 处理识别结果
            long duration = System.currentTimeMillis() - startTime;

            // 判断识别状态
            RecognitionStatus status = determineStatus(result, config);
            PrintQuality printQuality = parsePrintQuality(result.getPrintQuality());

            LabelRecognitionRecord record = LabelRecognitionRecord.builder()
                    .factoryId(config.getFactoryId())
                    .configId(config.getId())
                    .deviceId(config.getDeviceId())
                    .triggerType(triggerType)
                    .triggerEventId(eventId)
                    .recognitionTime(recognitionTime)
                    .expectedBatchNumber(config.getDefaultBatchId())
                    .capturedImage(capturedImageData)
                    .status(status)
                    .recognizedBatchNumber(result.getRecognizedBatchNumber())
                    .batchMatch(result.getBatchMatch())
                    .printQuality(printQuality)
                    .confidence(result.getOverallScore() / 100.0)
                    .qualityScore((double) result.getOverallScore())
                    .processingDurationMs((int) duration)
                    .aiResponse(result.toString())
                    .build();

            // 设置质量问题
            if (result.getQualityIssues() != null && !result.getQualityIssues().isEmpty()) {
                record.setQualityIssues(result.getQualityIssues());
            }

            record = recordRepository.save(record);

            log.info("标签识别完成: recordId={}, status={}, batch={}, duration={}ms",
                    record.getId(), status, result.getRecognizedBatchNumber(), duration);

            return record;

        } catch (Exception e) {
            log.error("标签识别异常: configId={}", config.getId(), e);
            return saveFailedRecord(config, triggerType, eventId, recognitionTime,
                    capturedImageData, "识别异常: " + e.getMessage(), startTime);
        }
    }

    /**
     * 判断识别状态
     */
    private RecognitionStatus determineStatus(LabelRecognitionResult result, LabelRecognitionConfig config) {
        if (!result.isReadable()) {
            return RecognitionStatus.NO_LABEL;
        }

        // 检查置信度
        double confidence = result.getOverallScore() / 100.0;
        if (confidence < config.getMinConfidence()) {
            return RecognitionStatus.LOW_CONFIDENCE;
        }

        return RecognitionStatus.SUCCESS;
    }

    /**
     * 解析打印质量
     */
    private PrintQuality parsePrintQuality(String quality) {
        if (quality == null) return PrintQuality.ACCEPTABLE;
        return switch (quality.toUpperCase()) {
            case "GOOD" -> PrintQuality.GOOD;
            case "ACCEPTABLE" -> PrintQuality.ACCEPTABLE;
            case "POOR" -> PrintQuality.POOR;
            case "UNREADABLE" -> PrintQuality.UNREADABLE;
            default -> PrintQuality.ACCEPTABLE;
        };
    }

    /**
     * 保存失败记录
     */
    private LabelRecognitionRecord saveFailedRecord(
            LabelRecognitionConfig config,
            TriggerType triggerType,
            String eventId,
            LocalDateTime recognitionTime,
            byte[] capturedImage,
            String errorMessage,
            long startTime) {
        long duration = System.currentTimeMillis() - startTime;

        LabelRecognitionRecord record = LabelRecognitionRecord.builder()
                .factoryId(config.getFactoryId())
                .configId(config.getId())
                .deviceId(config.getDeviceId())
                .triggerType(triggerType)
                .triggerEventId(eventId)
                .recognitionTime(recognitionTime)
                .expectedBatchNumber(config.getDefaultBatchId())
                .capturedImage(capturedImage)
                .status(RecognitionStatus.FAILED)
                .errorMessage(errorMessage)
                .processingDurationMs((int) duration)
                .build();

        return recordRepository.save(record);
    }

    // ==================== WebSocket 推送 ====================

    /**
     * 推送识别结果到 WebSocket
     */
    private void pushToWebSocket(String factoryId, LabelRecognitionRecord record) {
        if (messagingTemplate == null) {
            return;
        }

        try {
            // 构建推送数据（不包含图片数据，太大）
            Map<String, Object> data = new HashMap<>();
            data.put("id", record.getId());
            data.put("configId", record.getConfigId());
            data.put("deviceId", record.getDeviceId());
            data.put("triggerType", record.getTriggerType());
            data.put("status", record.getStatus());
            data.put("statusName", record.getStatusDisplayName());
            data.put("recognizedBatchNumber", record.getRecognizedBatchNumber());
            data.put("expectedBatchNumber", record.getExpectedBatchNumber());
            data.put("batchMatch", record.getBatchMatch());
            data.put("printQuality", record.getPrintQuality());
            data.put("confidence", record.getConfidence());
            data.put("recognitionTime", record.getRecognitionTime());
            data.put("processingDurationMs", record.getProcessingDurationMs());
            data.put("requiresAlert", record.requiresAlert());

            String destination = "/topic/factory/" + factoryId + "/label-recognition";
            messagingTemplate.convertAndSend(destination, data);

            log.debug("推送标签识别结果: destination={}, recordId={}", destination, record.getId());

        } catch (Exception e) {
            log.warn("WebSocket推送失败: {}", e.getMessage());
        }
    }

    // ==================== 历史记录查询 ====================

    /**
     * 获取识别历史（分页）
     */
    public Page<LabelRecognitionRecord> getRecords(String factoryId, Pageable pageable) {
        return recordRepository.findByFactoryIdOrderByRecognitionTimeDesc(factoryId, pageable);
    }

    /**
     * 根据配置ID获取识别历史
     */
    public Page<LabelRecognitionRecord> getRecordsByConfig(String factoryId, Long configId, Pageable pageable) {
        getConfig(factoryId, configId);
        return recordRepository.findByConfigIdOrderByRecognitionTimeDesc(configId, pageable);
    }

    /**
     * 获取最近的识别记录
     */
    public List<LabelRecognitionRecord> getRecentRecords(String factoryId, int limit) {
        return recordRepository.findRecentRecords(factoryId, PageRequest.of(0, limit));
    }

    // ==================== 统计数据 ====================

    /**
     * 获取统计数据
     */
    public Map<String, Object> getStatistics(String factoryId, LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);

        Map<String, Object> stats = new HashMap<>();

        // 总识别数
        long totalCount = recordRepository.countByFactoryIdAndTimeBetween(
                factoryId, startOfDay, endOfDay);
        stats.put("totalCount", totalCount);

        // 成功数
        long successCount = recordRepository.countByFactoryIdAndStatusAndRecognitionTimeBetween(
                factoryId, RecognitionStatus.SUCCESS, startOfDay, endOfDay);
        stats.put("successCount", successCount);

        // 失败数
        long failedCount = recordRepository.countByFactoryIdAndStatusAndRecognitionTimeBetween(
                factoryId, RecognitionStatus.FAILED, startOfDay, endOfDay);
        stats.put("failedCount", failedCount);

        // 成功率
        double successRate = totalCount > 0 ? (double) successCount / totalCount * 100 : 0;
        stats.put("successRate", Math.round(successRate * 100) / 100.0);

        // 异常数（包括失败、低置信度）
        long lowConfidenceCount = recordRepository.countByFactoryIdAndStatusAndRecognitionTimeBetween(
                factoryId, RecognitionStatus.LOW_CONFIDENCE, startOfDay, endOfDay);
        long noLabelCount = recordRepository.countByFactoryIdAndStatusAndRecognitionTimeBetween(
                factoryId, RecognitionStatus.NO_LABEL, startOfDay, endOfDay);
        stats.put("abnormalCount", failedCount + lowConfidenceCount + noLabelCount);

        // 平均处理时间
        Double avgTime = recordRepository.getAverageProcessingTime(factoryId, startOfDay, endOfDay);
        stats.put("avgProcessingTimeMs", avgTime != null ? Math.round(avgTime) : 0);

        // 各状态统计
        List<Object[]> statusCounts = recordRepository.countByStatusInTimeRange(
                factoryId, startOfDay, endOfDay);
        Map<String, Long> statusStats = new HashMap<>();
        for (Object[] row : statusCounts) {
            RecognitionStatus status = (RecognitionStatus) row[0];
            Long count = (Long) row[1];
            statusStats.put(status.name(), count);
        }
        stats.put("statusBreakdown", statusStats);

        // 各触发类型统计
        List<Object[]> triggerCounts = recordRepository.countByTriggerTypeInTimeRange(
                factoryId, startOfDay, endOfDay);
        Map<String, Long> triggerStats = new HashMap<>();
        for (Object[] row : triggerCounts) {
            TriggerType type = (TriggerType) row[0];
            Long count = (Long) row[1];
            triggerStats.put(type.name(), count);
        }
        stats.put("triggerTypeBreakdown", triggerStats);

        return stats;
    }
}
