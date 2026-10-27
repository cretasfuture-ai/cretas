package com.cretas.aims.service.isapi;

import com.cretas.aims.repository.FactorySettingsRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 摄像头抓拍交给 AI 识别的本厂开关 (AI 设置 {@code cameraSnapshotAnalysis}, 默认关闭)。
 * 抓拍里可能有人, 图片识别不做名称脱敏 —— 告警抓拍、工位计数定时抓帧、标签识别抓帧都先过这里, 没开就不发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CameraSnapshotAiConsent {

    public static final String DISABLED_MESSAGE = "本厂未开启摄像头抓拍 AI 识别";

    private final FactorySettingsRepository factorySettingsRepository;
    private final ObjectMapper objectMapper;

    /**
     * 本厂 AI 设置里的 {@code cameraSnapshotAnalysis} 是否为 true。只读 (不像设置服务那样顺手建行);
     * 没有设置行 / 没写这一项 / 解析失败 / 查不了, 一律当关。
     */
    public boolean enabled(String factoryId) {
        if (factoryId == null) {
            return false;
        }
        try {
            String json = factorySettingsRepository.findAiSettingsByFactoryId(factoryId);
            if (json == null || json.isBlank()) {
                return false;
            }
            JsonNode flag = objectMapper.readTree(json).path("cameraSnapshotAnalysis");
            return flag.isBoolean() && flag.booleanValue();
        } catch (Exception e) {
            log.warn("读取摄像头抓拍 AI 识别开关失败, 按关闭处理: factoryId={}, error={}", factoryId, e.getMessage());
            return false;
        }
    }
}
