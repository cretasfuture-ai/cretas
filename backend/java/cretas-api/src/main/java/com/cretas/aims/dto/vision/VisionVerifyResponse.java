package com.cretas.aims.dto.vision;

import com.cretas.aims.dto.source.SourceIndividualDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 拍照核验结果。
 *
 * <h3>⛔ 这里没有「兜底字段」</h3>
 * {@link #fields} 是模型<b>真的返回</b>的那些键, 原样透传。模型没给的键就不在 map 里 ——
 * ⛔ 不补 {@code null}、不补 0、不补「未知」。本仓踩过的形状是
 * 「兜底的默认值把『我不知道』翻译成『是 0』」, 下游分不出这两件事。
 *
 * <p>解析失败时本对象<b>只</b>带 {@link #templateCode} + {@link #rawText} + {@link #parseError},
 * 由控制器以 HTTP 422 返回。那种情况下 {@link #fields} 是 {@code null} 而不是空 map ——
 * 「没解析出来」和「解析出来是空的」不是同一件事。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "拍照核验结果")
public class VisionVerifyResponse {

    @Schema(description = "模板编码")
    private String templateCode;

    @Schema(description = "模板中文名")
    private String templateName;

    @Schema(description = "模型返回的结构化字段(原样透传, 缺的键就是缺)")
    private Map<String, Object> fields;

    @Schema(description = "模型自报的置信度 0~1")
    private Double confidence;

    @Schema(description = "模型原始输出 —— 解析成功时也保留, 便于现场核对")
    private String rawText;

    @Schema(description = "解析失败原因; 成功时为 null")
    private String parseError;

    // ── EAR_TAG 专有 ────────────────────────────────────────────────────────

    @Schema(description = "EAR_TAG: 归一化后的耳标号(只留字母数字并大写)")
    private String normalizedTagNumber;

    @Schema(description = "EAR_TAG: 按耳标号命中的个体条数; 0=未建档, >1=耳标撞号需人工选择")
    private Integer matchCount;

    @Schema(description = "EAR_TAG: 最近建档的那一条; 未命中时为 null(⛔ 不是空对象)")
    private SourceIndividualDTO matched;

    @Schema(description = "EAR_TAG: 全部命中")
    private List<SourceIndividualDTO> allMatches;

    // ── MEAT_CUT 专有 ───────────────────────────────────────────────────────

    /**
     * 固定为 {@code "AI_PRELIMINARY"} —— 它不是一个可变状态, 是一句<b>不许被去掉的限定</b>:
     * 这个结论是机器初评, 必须人工复核才能作数。UI 上显示「AI 初评 · 需人工复核」。
     */
    @Schema(description = "MEAT_CUT: 固定 AI_PRELIMINARY —— AI 初评, 需人工复核")
    private String assessmentLevel;
}
