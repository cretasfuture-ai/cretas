package com.cretas.aims.dto.vision;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 拍照核验请求。
 *
 * <p>图片走 base64 而不是 multipart —— 与本仓既有的
 * {@code FormAssistantController.parseFormOCR} 一致(RN 侧 {@code ImagePicker} /
 * {@code CameraView} 直接拿得到 base64, 不必再造一次 multipart 上传通道)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "拍照核验请求")
public class VisionVerifyRequest {

    @NotBlank(message = "模板编码不能为空")
    @Schema(description = "模板编码: EAR_TAG(标识牌识别) / MEAT_CUT(肉品分割块初评)", example = "EAR_TAG")
    private String templateCode;

    @NotBlank(message = "图片不能为空")
    @Schema(description = "Base64 图片; 允许带 data:image/jpeg;base64, 前缀")
    private String imageBase64;

    @Schema(description = "图片 MIME 类型, 不传默认 image/jpeg", example = "image/jpeg")
    private String contentType;
}
