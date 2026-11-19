package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.entity.smartbi.enums.UploadStatus;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgExcelUpload;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgExcelUploadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把「保存失败」的上传标成 FAILED (PROTO-YDWM-B2)。
 *
 * confirm 保存在 422「上传数据暂无可分析图表」时整个事务会回滚, 上传行却已在前一步入库为 COMPLETED,
 * 于是它留在数据源下拉里还可能成为问数据的默认源。这里用独立事务 (REQUIRES_NEW) 落 FAILED,
 * 不会被外层回滚吃掉; 只改本工厂自己的上传。
 */
@Slf4j
@Component
public class SmartBIUploadFailureMarker {

    @Autowired(required = false)
    private SmartBiPgExcelUploadRepository pgUploadRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String factoryId, Long uploadId, String reason) {
        if (pgUploadRepository == null || factoryId == null || uploadId == null) {
            return;
        }
        pgUploadRepository.findById(uploadId).ifPresent((SmartBiPgExcelUpload upload) -> {
            if (!factoryId.equals(upload.getFactoryId())) {
                log.warn("拒绝标记跨工厂上传为 FAILED: factoryId={}, uploadId={}", factoryId, uploadId);
                return;
            }
            upload.setUploadStatus(UploadStatus.FAILED);
            upload.setErrorMessage(reason);
            pgUploadRepository.save(upload);
            log.warn("上传保存失败，已标记 FAILED: uploadId={}, reason={}", uploadId, reason);
        });
    }
}
