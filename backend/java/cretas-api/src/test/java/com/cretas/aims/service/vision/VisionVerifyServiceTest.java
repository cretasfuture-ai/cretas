package com.cretas.aims.service.vision;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.dto.source.SourceIndividualDTO;
import com.cretas.aims.dto.source.SourceIndividualLookupResult;
import com.cretas.aims.dto.vision.VisionVerifyRequest;
import com.cretas.aims.dto.vision.VisionVerifyResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.source.SourceIndividualService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 拍照核验的行为闸。
 *
 * <h3>它守的三条(逐条对应产品里那三条纪律)</h3>
 * <ol>
 *   <li>模型输出解析不出来 ⇒ 抛 {@link VisionResponseUnparseableException} 并<b>带上原文</b>,
 *       ⛔ 不返回一个「空但成功」的结果。</li>
 *   <li>{@code EAR_TAG} 的匹配/不匹配是<b>两个不同的读数</b>: 未命中是 {@code matchCount = 0} +
 *       {@code matched = null}; 而「模型根本没读出号」时 {@code matchCount} 必须<b>缺席</b>
 *       (null) —— 「查了没有」和「没号可查」不是同一件事。</li>
 *   <li>{@code MEAT_CUT} 的 {@code assessmentLevel} 永远是 {@code AI_PRELIMINARY}。</li>
 * </ol>
 *
 * <p>⚠️ 桩的形状要能在生产上出现: 下面每个 {@code visionChat} 的返回值都是<b>字符串</b>,
 * 与真实那一侧一致(VL 返回自由文本, 不是已解析的对象)。带 ```json 围栏的那一条尤其重要 ——
 * 那是模型实际最常见的输出形状, 而不是我们希望它输出的形状。
 */
class VisionVerifyServiceTest {

    private static final String FACTORY = "F006";
    /** 一小段随便什么字节, base64 之后就是一张「图片」—— 本测试不关心像素, 只关心链路。 */
    private static final String IMAGE_B64 =
            Base64.getEncoder().encodeToString("fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8));

    private PythonLLMClient llm;
    private SourceIndividualService individuals;
    private VisionVerifyService service;

    @BeforeEach
    void setUp() {
        llm = mock(PythonLLMClient.class);
        individuals = mock(SourceIndividualService.class);
        service = new VisionVerifyService(llm, individuals, new ObjectMapper());
    }

    // ── ① 正向解析 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("EAR_TAG 正向: 读出耳标号 → 归一化 → 反查命中 → matched 有值")
    void earTagHappyPathResolvesTheIndividual() {
        when(llm.visionChat(any(), anyString(), anyString()))
                .thenReturn("{\"tagNumber\": \"118-0245\", \"confidence\": 0.92, \"notes\": null}");
        when(individuals.findByTagNumber(eq(FACTORY), eq("1180245")))
                .thenReturn(SourceIndividualLookupResult.builder()
                        .tagNumber("1180245")
                        .matchCount(1)
                        .matched(SourceIndividualDTO.builder()
                                .id("si-1").individualCode("SI-0001").species("CATTLE").build())
                        .allMatches(List.of(SourceIndividualDTO.builder()
                                .id("si-1").individualCode("SI-0001").species("CATTLE").build()))
                        .build());

        VisionVerifyResponse res = service.verify(FACTORY, request("EAR_TAG"));

        assertThat(res.getTemplateCode()).isEqualTo("EAR_TAG");
        assertThat(res.getConfidence()).isEqualTo(0.92);
        // 归一化: 横杠被吃掉。⚠️ 归一化只有一处实现(SourceIndividualService.normaliseTag),
        // 这里断言的是「拍照这条路真的用了它」, 不是再实现一遍。
        assertThat(res.getNormalizedTagNumber()).isEqualTo("1180245");
        assertThat(res.getMatchCount()).isEqualTo(1);
        assertThat(res.getMatched()).isNotNull();
        assertThat(res.getMatched().getIndividualCode()).isEqualTo("SI-0001");
        // fields 是原样透传 —— 模型给了什么就是什么。
        assertThat(res.getFields()).containsEntry("tagNumber", "118-0245");
        // EAR_TAG ⛔ 不该带 MEAT_CUT 的初评标签。
        assertThat(res.getAssessmentLevel()).isNull();
    }

    @Test
    @DisplayName("EAR_TAG: 读到号但库里没有 ⇒ matchCount=0 且 matched=null (⛔ 不是空对象)")
    void earTagUnmatchedIsZeroNotNull() {
        when(llm.visionChat(any(), anyString(), anyString()))
                .thenReturn("{\"tagNumber\": \"9999999\", \"confidence\": 0.71}");
        when(individuals.findByTagNumber(eq(FACTORY), eq("9999999")))
                .thenReturn(SourceIndividualLookupResult.builder()
                        .tagNumber("9999999").matchCount(0).matched(null).allMatches(List.of()).build());

        VisionVerifyResponse res = service.verify(FACTORY, request("EAR_TAG"));

        assertThat(res.getNormalizedTagNumber()).isEqualTo("9999999");
        assertThat(res.getMatchCount()).isZero();
        assertThat(res.getMatched()).isNull();
        assertThat(res.getAllMatches()).isEmpty();
    }

    @Test
    @DisplayName("🔴 EAR_TAG: 模型说读不出来(tagNumber=null) ⇒ matchCount **缺席**, 且不去查库")
    void earTagUnreadableIsNotTheSameAsUnmatched() {
        when(llm.visionChat(any(), anyString(), anyString()))
                .thenReturn("{\"tagNumber\": null, \"confidence\": 0.12, \"notes\": \"标牌被泥遮住\"}");

        VisionVerifyResponse res = service.verify(FACTORY, request("EAR_TAG"));

        // 「没号可查」与「查了没有」必须可区分: 前者 matchCount 是 null, 后者是 0。
        // ⚠️ 如果这里退化成 0, 前端会显示「未建档, 去登记新个体」—— 而真相是这张照片
        //    根本没读出号, 正确动作是重拍。
        assertThat(res.getMatchCount()).isNull();
        assertThat(res.getNormalizedTagNumber()).isNull();
        assertThat(res.getMatched()).isNull();
        // 没号就不该去打扰数据库 —— 这条同时证明上面那个 null 不是「查了一下没查到」。
        verify(individuals, never()).findByTagNumber(anyString(), anyString());
        // 但它仍是一次**成功**的识别: confidence 与 notes 都在。
        assertThat(res.getConfidence()).isEqualTo(0.12);
        assertThat(res.getFields()).containsEntry("notes", "标牌被泥遮住");
    }

    @Test
    @DisplayName("MEAT_CUT 正向: assessmentLevel 固定 AI_PRELIMINARY, 且 markdown 围栏能剥掉")
    void meatCutAlwaysCarriesThePreliminaryStamp() {
        when(llm.visionChat(any(), anyString(), anyString())).thenReturn("""
                这是识别结果:
                ```json
                {"cut": "西冷", "marblingScore": 3, "foreignObjectSuspected": false,
                 "contaminationSuspected": false, "confidence": 0.83, "notes": null}
                ```
                """);

        VisionVerifyResponse res = service.verify(FACTORY, request("MEAT_CUT"));

        assertThat(res.getAssessmentLevel()).isEqualTo("AI_PRELIMINARY");
        assertThat(res.getFields()).containsEntry("cut", "西冷");
        assertThat(res.getFields()).containsEntry("marblingScore", 3);
        assertThat(res.getFields()).containsEntry("foreignObjectSuspected", false);
        assertThat(res.getConfidence()).isEqualTo(0.83);
        // MEAT_CUT ⛔ 不做个体反查。
        verify(individuals, never()).findByTagNumber(anyString(), anyString());
    }

    // ── ①′ 真实上游输出的**逐字**回放 ──────────────────────────────────────
    //
    // 下面两条的字符串不是我编的, 是 2026-09-15 打 prod 那把 key、
    // 经 `/api/llm/vision` 拿到的 **qwen3.8-flash 原文**
    // (OPS-VL-SLOT-QWEN38-FLASH-20260915 的本地冒烟)。
    //
    // 🔴 为什么要单独放一组: 上面那些用例的桩是**我按 prompt 想象**模型会怎么答,
    //    而真实那一侧当天给出了第三种形状 —— **多行 pretty-print 的裸 JSON**
    //    (既不是我桩的单行裸 JSON, 也不是带 ```json 围栏的)。
    //    形态 B‴: 桩的形状要能在生产上出现 —— 反过来, 生产上出现的形状也必须在桩里有。

    @Test
    @DisplayName("真实回放 EAR_TAG: qwen3.8-flash 的单行裸 JSON")
    void realQwen38FlashEarTagPayloadParses() {
        // 逐字来自 2026-09-15 本地冒烟(图片 md5 4dc45a41…, 合成标识牌样张)
        when(llm.visionChat(any(), anyString(), anyString()))
                .thenReturn("{\"tagNumber\": \"1180245\", \"confidence\": 1.0, \"notes\": null}");
        when(individuals.findByTagNumber(eq(FACTORY), eq("1180245")))
                .thenReturn(SourceIndividualLookupResult.builder()
                        .tagNumber("1180245").matchCount(0).matched(null).allMatches(List.of()).build());

        VisionVerifyResponse res = service.verify(FACTORY, request("EAR_TAG"));

        assertThat(res.getNormalizedTagNumber()).isEqualTo("1180245");
        assertThat(res.getConfidence()).isEqualTo(1.0);
        assertThat(res.getParseError()).isNull();
    }

    @Test
    @DisplayName("🔴 真实回放 MEAT_CUT: qwen3.8-flash 的**多行** pretty-print 裸 JSON, 且 cut 为 null")
    void realQwen38FlashMeatCutPayloadParses() {
        // 逐字来自 2026-09-15 本地冒烟(图片 md5 c6babe4b…, 真空包装带骨肉眼照片)。
        // ⚠️ 两个值得留意的真实特征:
        //   ① 多行缩进 —— 解析必须容得下换行(截取第一个 { 到最后一个 } 再交给 Jackson);
        //   ② `"cut": null` —— 模型**照 prompt 要求没有猜**。这是正确行为:
        //      键在、值是 null ⇒ 必需键检查通过, 而下游看到的是「模型说它判断不出」,
        //      ⛔ 不是「我们把它兜底成了某个部位」。
        when(llm.visionChat(any(), anyString(), anyString())).thenReturn("""
                {
                  "cut": null,
                  "marblingScore": 3,
                  "foreignObjectSuspected": false,
                  "contaminationSuspected": false,
                  "confidence": 0.95,
                  "notes": "真空包装完好，肉质纹理清晰，脂肪分布适中，无明显异物或污染迹象。"
                }""");

        VisionVerifyResponse res = service.verify(FACTORY, request("MEAT_CUT"));

        assertThat(res.getParseError()).isNull();
        assertThat(res.getAssessmentLevel()).isEqualTo("AI_PRELIMINARY");
        assertThat(res.getConfidence()).isEqualTo(0.95);
        assertThat(res.getFields()).containsEntry("marblingScore", 3);
        assertThat(res.getFields()).containsEntry("foreignObjectSuspected", false);
        // `cut` 的键**在**, 值是 null —— 两件事都要成立。
        assertThat(res.getFields()).containsKey("cut");
        assertThat(res.getFields().get("cut")).isNull();
    }

    // ── ② 解析失败 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("非 JSON ⇒ 抛 VisionResponseUnparseableException 并带上模型原文")
    void nonJsonResponseThrowsWithRawText() {
        String babble = "抱歉，我看不清这张图片里的内容。";
        when(llm.visionChat(any(), anyString(), anyString())).thenReturn(babble);

        assertThatThrownBy(() -> service.verify(FACTORY, request("EAR_TAG")))
                .isInstanceOf(VisionResponseUnparseableException.class)
                .hasMessageContaining("不是 JSON")
                .extracting(e -> ((VisionResponseUnparseableException) e).getRawText())
                // 原文必须原样带出去 —— 现场唯一能据以判断「为什么失败」的东西。
                .isEqualTo(babble);
    }

    @Test
    @DisplayName("缺 confidence ⇒ 抛解析异常, ⛔ 不补一个默认置信度")
    void missingConfidenceIsAnErrorNotADefault() {
        when(llm.visionChat(any(), anyString(), anyString()))
                .thenReturn("{\"tagNumber\": \"1180245\"}");

        assertThatThrownBy(() -> service.verify(FACTORY, request("EAR_TAG")))
                .isInstanceOf(VisionResponseUnparseableException.class)
                .hasMessageContaining("confidence");

        // 阴性对照: 缺字段的那一刻就该停 —— ⛔ 不许已经去查了库再失败。
        verify(individuals, never()).findByTagNumber(anyString(), anyString());
    }

    @Test
    @DisplayName("confidence 是字符串/null 一样算缺 —— 「有这个键」不等于「有这个读数」")
    void confidencePresentButUnusableIsStillAnError() {
        when(llm.visionChat(any(), anyString(), anyString()))
                .thenReturn("{\"tagNumber\": \"1180245\", \"confidence\": null}");

        assertThatThrownBy(() -> service.verify(FACTORY, request("EAR_TAG")))
                .isInstanceOf(VisionResponseUnparseableException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    @DisplayName("MEAT_CUT 缺 marblingScore 之外的必需键 ⇒ 解析异常, 逐个列出缺了什么")
    void meatCutMissingRequiredKeysAreListed() {
        when(llm.visionChat(any(), anyString(), anyString()))
                .thenReturn("{\"cut\": \"西冷\", \"confidence\": 0.5}");

        assertThatThrownBy(() -> service.verify(FACTORY, request("MEAT_CUT")))
                .isInstanceOf(VisionResponseUnparseableException.class)
                .hasMessageContaining("contaminationSuspected")
                .hasMessageContaining("foreignObjectSuspected")
                .hasMessageContaining("marblingScore");
    }

    // ── ③ 输入与上游 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("未知模板 ⇒ 400, 且错误信息把支持的模板列出来")
    void unknownTemplateIs400() {
        assertThatThrownBy(() -> service.verify(FACTORY, request("COW_FACE")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("EAR_TAG")
                .hasMessageContaining("MEAT_CUT");

        // 模板不认识就不该打上游 —— 省一次 VL 调用, 也让 400 与 502 不会混淆。
        verify(llm, never()).visionChat(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("上游异常 ⇒ 502 「服务不可用」, ⛔ 不伪装成「识别失败」")
    void upstreamFailureIs502NotAParseError() {
        when(llm.visionChat(any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("connect timed out"));

        assertThatThrownBy(() -> service.verify(FACTORY, request("EAR_TAG")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(502));
    }

    @Test
    @DisplayName("data: 前缀会被剥掉, 传给 VL 的是解码后的字节 + contentType")
    void dataUrlPrefixIsStrippedBeforeCallingTheModel() {
        when(llm.visionChat(any(), anyString(), anyString()))
                .thenReturn("{\"tagNumber\": null, \"confidence\": 0.1}");

        VisionVerifyRequest req = request("EAR_TAG");
        req.setImageBase64("data:image/png;base64," + IMAGE_B64);
        req.setContentType("image/png");
        service.verify(FACTORY, req);

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<String> ct = ArgumentCaptor.forClass(String.class);
        verify(llm).visionChat(bytes.capture(), ct.capture(), anyString());
        assertThat(new String(bytes.getValue(), StandardCharsets.UTF_8)).isEqualTo("fake-jpeg-bytes");
        assertThat(ct.getValue()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("图片不是合法 base64 ⇒ 400, 不打上游")
    void malformedBase64Is400() {
        VisionVerifyRequest req = request("EAR_TAG");
        req.setImageBase64("!!!not-base64!!!");

        assertThatThrownBy(() -> service.verify(FACTORY, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Base64");
        verify(llm, never()).visionChat(any(), anyString(), anyString());
    }

    private static VisionVerifyRequest request(String templateCode) {
        return VisionVerifyRequest.builder()
                .templateCode(templateCode)
                .imageBase64(IMAGE_B64)
                .contentType("image/jpeg")
                .build();
    }
}
