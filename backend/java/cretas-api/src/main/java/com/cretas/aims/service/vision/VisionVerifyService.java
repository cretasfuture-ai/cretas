package com.cretas.aims.service.vision;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.dto.source.SourceIndividualLookupResult;
import com.cretas.aims.dto.vision.VisionVerifyRequest;
import com.cretas.aims.dto.vision.VisionVerifyResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.source.SourceIndividualService;
import com.cretas.aims.service.vision.VisionTemplateRegistry.VisionTemplate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 拍照核验 —— 按模板拍一张照片, 出结构化字段, 再按模板做后处理。
 *
 * <h3>三条不许破的纪律</h3>
 * <ol>
 *   <li><b>解析不出来就 422</b>, 带原始输出。⛔ 不返回一个「空但成功」的结果
 *       —— 那会让前端把「没识别出来」渲染成「识别出来是空的」。</li>
 *   <li><b>模型没给的字段就不给</b>。⛔ 不补 null/0/"未知"。</li>
 *   <li><b>MEAT_CUT 的结论永远带 {@code AI_PRELIMINARY}</b>。它不是状态机的一个态,
 *       是一句不许被摘掉的限定。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisionVerifyService {

    /**
     * 10 MB(解码后) —— <b>与上游 Python {@code /api/llm/vision} 的
     * {@code MAX_IMAGE_BYTES} 对齐</b>({@code backend/python/llm/api/endpoints.py:132})。
     *
     * <p>🔴 ⛔ <b>不要</b>照抄 {@code LabelQcImageProxyService} 的 20 MB:
     * 那条路不经过 Python。这条经过 —— 把上限设得比上游<b>松</b>, 结果是
     * 一张 15 MB 的照片在 Java 这里通过, 然后 Python 回 413,
     * 用户看到的是一句「视觉识别服务暂时不可用」(502) 而不是「图片太大」。
     * <b>两个限制中较严的那个才是真的限制</b>, 报错也该在那一层的语言里说出来。
     *
     * <p>⚠️ 这是<b>解码后</b>的字节数; base64 串本身约为它的 4/3。
     * ⚠️ 上游那个常量改了, 这里要跟着改 —— 跨语言抽不成一份(形态 D),
     * 所以两处各自写明对方在哪。
     */
    static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    private static final Pattern DATA_URL_PREFIX =
            Pattern.compile("^data:([^;,]+)?(;[^,]*)?,", Pattern.CASE_INSENSITIVE);

    /** ```json … ``` / ``` … ``` 围栏 —— 模型常常不听「不要 markdown」那句话。 */
    private static final Pattern CODE_FENCE =
            Pattern.compile("```[a-zA-Z]*\\s*([\\s\\S]*?)```");

    private final PythonLLMClient pythonLLMClient;
    private final SourceIndividualService sourceIndividualService;
    private final ObjectMapper objectMapper;

    public VisionVerifyResponse verify(String factoryId, VisionVerifyRequest request) {
        VisionTemplate template = VisionTemplateRegistry.find(request.getTemplateCode())
                .orElseThrow(() -> new BusinessException(400,
                        "未知的核验模板: " + request.getTemplateCode()
                                + " (支持: " + String.join(", ", VisionTemplateRegistry.codes()) + ")"));

        byte[] imageBytes = decodeImage(request.getImageBase64());
        String contentType = StringUtils.hasText(request.getContentType())
                ? request.getContentType().trim() : "image/jpeg";

        String rawText;
        try {
            rawText = pythonLLMClient.visionChat(imageBytes, contentType, template.prompt());
        } catch (Exception e) {
            // 上游不可用是 502, ⛔ 不是「识别不出来」—— 两者的下一步动作完全不同
            // (一个是重试/找运维, 一个是重拍)。
            log.error("视觉服务调用失败: factoryId={}, template={}", factoryId, template.code(), e);
            throw new BusinessException(502, "视觉识别服务暂时不可用, 请稍后重试");
        }

        Map<String, Object> fields = parseJsonObject(template, rawText);
        Double confidence = readConfidence(template, fields, rawText);

        VisionVerifyResponse.VisionVerifyResponseBuilder builder = VisionVerifyResponse.builder()
                .templateCode(template.code())
                .templateName(template.displayName())
                .fields(fields)
                .confidence(confidence)
                .rawText(rawText);

        if (VisionTemplateRegistry.EAR_TAG.equals(template.code())) {
            applyEarTagPostProcessing(factoryId, fields, builder);
        } else if (VisionTemplateRegistry.MEAT_CUT.equals(template.code())) {
            // 固定标记 —— 见 VisionVerifyResponse#assessmentLevel 的字段说明。
            builder.assessmentLevel("AI_PRELIMINARY");
        }
        return builder.build();
    }

    // ── EAR_TAG 后处理 ─────────────────────────────────────────────────────

    private void applyEarTagPostProcessing(String factoryId, Map<String, Object> fields,
                                           VisionVerifyResponse.VisionVerifyResponseBuilder builder) {
        Object raw = fields.get("tagNumber");
        String normalized = raw == null ? null : SourceIndividualService.normaliseTag(String.valueOf(raw));
        if (!StringUtils.hasText(normalized)) {
            // 模型明确说「读不出来」(tagNumber: null)。这是一个**有效**结果, 不是错误 ——
            // 前端据此提示重拍。⛔ 不查库(没号可查), ⛔ 也不把 matchCount 写成 0
            // (0 的意思是「查了, 没有这个个体」, 与「没号可查」是两件事)。
            return;
        }
        builder.normalizedTagNumber(normalized);
        SourceIndividualLookupResult lookup = sourceIndividualService.findByTagNumber(factoryId, normalized);
        builder.matchCount(lookup.getMatchCount())
                .matched(lookup.getMatched())
                .allMatches(lookup.getAllMatches());
    }

    // ── 解析 ──────────────────────────────────────────────────────────────

    /** 剥围栏 / 截取第一个 {...} / 解析成 map。任何一步失败都是 422, 带原文。 */
    private Map<String, Object> parseJsonObject(VisionTemplate template, String rawText) {
        if (!StringUtils.hasText(rawText)) {
            throw new VisionResponseUnparseableException(template.code(),
                    "视觉模型没有返回任何内容", rawText);
        }
        String candidate = stripFence(rawText).trim();
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new VisionResponseUnparseableException(template.code(),
                    "视觉模型返回的不是 JSON", rawText);
        }
        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(candidate.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new VisionResponseUnparseableException(template.code(),
                    "视觉模型返回的 JSON 解析失败: " + e.getMessage(), rawText);
        }

        // 模板要求的键少一个就是 422 —— ⛔ 不用默认值把缺口填上。
        var missing = template.requiredKeys().stream().filter(k -> !parsed.containsKey(k)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new VisionResponseUnparseableException(template.code(),
                    "视觉模型返回缺少必需字段: " + String.join(", ", missing), rawText);
        }
        return parsed;
    }

    /**
     * confidence 必须是个数。
     *
     * <p>⚠️ 这条单独拎出来是因为 {@code containsKey} 过得了而值是 {@code null} / 字符串的情况
     * 真的会发生 —— 那时「有这个键」是真的, 「有这个读数」是假的。
     */
    private Double readConfidence(VisionTemplate template, Map<String, Object> fields, String rawText) {
        Object value = fields.get("confidence");
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s && StringUtils.hasText(s)) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // 落到下面统一报 422
            }
        }
        throw new VisionResponseUnparseableException(template.code(),
                "视觉模型没有给出可用的 confidence", rawText);
    }

    private static String stripFence(String text) {
        Matcher m = CODE_FENCE.matcher(text);
        return m.find() ? m.group(1) : text;
    }

    // ── 图片 ──────────────────────────────────────────────────────────────

    private byte[] decodeImage(String imageBase64) {
        if (!StringUtils.hasText(imageBase64)) {
            throw new BusinessException(400, "图片不能为空");
        }
        String payload = DATA_URL_PREFIX.matcher(imageBase64.trim()).replaceFirst("")
                .replaceAll("\\s", "");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "图片 Base64 解码失败, 请重新拍摄");
        }
        if (bytes.length == 0) {
            throw new BusinessException(400, "图片内容为空, 请重新拍摄");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new BusinessException(400,
                    "图片过大(" + (bytes.length / 1024 / 1024) + "MB), 上限 "
                            + (MAX_IMAGE_BYTES / 1024 / 1024) + "MB");
        }
        return bytes;
    }
}
