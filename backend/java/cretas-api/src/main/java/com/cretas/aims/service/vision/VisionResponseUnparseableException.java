package com.cretas.aims.service.vision;

/**
 * 视觉模型的输出<b>解析不出来</b> —— 不是 JSON、或缺了模板要求的键。
 *
 * <p>它带着 {@code rawText} 一路走到 HTTP 422, 因为「模型到底说了什么」是现场唯一能
 * 据以判断的东西: 是照片糊了、是标识牌被遮住了, 还是模型在闲聊。
 * ⛔ 吞掉 rawText 只报一句「识别失败」等于把现场唯一的线索删掉。
 *
 * <p>⛔ <b>不要</b>在任何地方把它降级成「返回一个空结果」——
 * 核心原则 1: 读不到就报错, 不造假字段。
 */
public class VisionResponseUnparseableException extends RuntimeException {

    private final String rawText;
    private final String templateCode;

    public VisionResponseUnparseableException(String templateCode, String reason, String rawText) {
        super(reason);
        this.templateCode = templateCode;
        this.rawText = rawText;
    }

    public String getRawText() {
        return rawText;
    }

    public String getTemplateCode() {
        return templateCode;
    }
}
