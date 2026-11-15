package com.cretas.aims.service.smartbi;

import com.cretas.aims.entity.smartbi.postgres.SmartBiPgExcelUpload;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.smartbi.postgres.SmartBiDynamicDataRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgAnalysisResultRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgExcelUploadRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgFieldDefinitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 上传记录改名 / 真删除。所有操作先校验 upload 属于当前工厂（跨厂一律 404，不泄露存在性）。
 * 删除是物理删除：数据行、字段定义、分析结果、上传记录同事务删掉（原型的 hiddenIds 假删除不带入）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "smartbi.postgres.enabled", havingValue = "true", matchIfMissing = false)
public class SmartBIUploadManagementService {

    static final int MAX_NAME_LENGTH = 255;

    private final SmartBiPgExcelUploadRepository uploadRepository;
    private final SmartBiDynamicDataRepository dynamicDataRepository;
    private final SmartBiPgFieldDefinitionRepository fieldDefinitionRepository;
    private final SmartBiPgAnalysisResultRepository analysisResultRepository;

    @Transactional("smartbiPostgresTransactionManager")
    public SmartBiPgExcelUpload rename(String factoryId, Long uploadId, String newName) {
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty()) throw new BusinessException(400, "名称不能为空").withHintTarget("fileName");
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(400, "名称不能超过 " + MAX_NAME_LENGTH + " 个字符").withHintTarget("fileName");
        }
        SmartBiPgExcelUpload upload = require(factoryId, uploadId);
        upload.setFileName(name);
        return uploadRepository.save(upload);
    }

    @Transactional("smartbiPostgresTransactionManager")
    public void delete(String factoryId, Long uploadId) {
        require(factoryId, uploadId);
        dynamicDataRepository.deleteByFactoryIdAndUploadId(factoryId, uploadId);
        fieldDefinitionRepository.deleteByUploadId(uploadId);
        analysisResultRepository.deleteByUploadId(uploadId);
        uploadRepository.deleteById(uploadId);
        log.info("SmartBI upload deleted: factoryId={}, uploadId={}", factoryId, uploadId);
    }

    /** 校验归属；供 fields/data 读取复用。 */
    public SmartBiPgExcelUpload require(String factoryId, Long uploadId) {
        return uploadRepository.findById(uploadId)
                .filter(u -> factoryId != null && factoryId.equals(u.getFactoryId()))
                .orElseThrow(() -> new BusinessException(404, "上传记录不存在: " + uploadId));
    }
}
