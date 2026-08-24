package com.cretas.aims.service;

import com.cretas.aims.entity.Attachment;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.LabelQcPhotoRepository;
import com.cretas.aims.service.attachment.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/** Same-origin, authenticated image bridge for browsers that cannot reach private OSS directly. */
@Service
@RequiredArgsConstructor
public class LabelQcImageProxyService {

    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private final LabelQcPhotoRepository photoRepository;
    private final AttachmentService attachmentService;

    @Transactional(readOnly = true)
    public ImagePayload load(String factoryId, String photoId) {
        var photo = photoRepository.findByFactoryIdAndId(factoryId, photoId)
                .orElseThrow(() -> new BusinessException(404, "质检照片不存在或无权访问")
                        .withCode("LABEL_QC_PHOTO_NOT_FOUND"));
        Attachment attachment = attachmentService.getById(factoryId, photo.getAttachmentId());
        String contentType = attachment.getFileType();
        if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
            throw new BusinessException(415, "质检附件不是可预览图片")
                    .withCode("LABEL_QC_IMAGE_TYPE_INVALID");
        }
        if (attachment.getFileSize() != null && attachment.getFileSize() > MAX_IMAGE_BYTES) {
            throw new BusinessException(413, "质检图片超过 20MB，无法在线预览")
                    .withCode("LABEL_QC_IMAGE_TOO_LARGE");
        }
        return download(attachmentService.generateDownloadUrl(factoryId, attachment.getId()), contentType);
    }

    private ImagePayload download(String signedUrl, String fallbackContentType) {
        try {
            URI signedUri = URI.create(signedUrl);
            String host = signedUri.getHost();
            if (host == null || !(host.endsWith(".aliyuncs.com") || host.endsWith(".aliyun.com"))) {
                throw new BusinessException(502, "质检图片来源不受信任")
                        .withCode("LABEL_QC_IMAGE_HOST_INVALID");
            }
            HttpURLConnection connection = (HttpURLConnection) signedUri.toURL().openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(15_000);
            connection.setInstanceFollowRedirects(true);
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int total = 0;
                for (int read; (read = input.read(buffer)) >= 0;) {
                    total += read;
                    if (total > MAX_IMAGE_BYTES) {
                        throw new BusinessException(413, "质检图片超过 20MB，无法在线预览")
                                .withCode("LABEL_QC_IMAGE_TOO_LARGE");
                    }
                    output.write(buffer, 0, read);
                }
                String responseType = connection.getContentType();
                return new ImagePayload(
                        output.toByteArray(),
                        responseType != null && responseType.startsWith("image/")
                                ? responseType : fallbackContentType);
            } finally {
                connection.disconnect();
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(502, "质检图片暂时无法读取")
                    .withCode("LABEL_QC_IMAGE_PROXY_FAILED")
                    .withHint("稍后重试；原始标注数据不会丢失");
        }
    }

    public record ImagePayload(byte[] bytes, String contentType) {}
}
