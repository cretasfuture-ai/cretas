package com.cretas.aims.service.attachment;

import com.cretas.aims.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Two-phase rollout gate for SOP writes; reads remain available while this is disabled. */
@Component
public final class SopAttachmentRollout {

    private final boolean writeEnabled;

    public SopAttachmentRollout(
            @Value("${cretas.sop-attachments.write-enabled:false}") boolean writeEnabled) {
        this.writeEnabled = writeEnabled;
    }

    public void requireWriteEnabled() {
        if (!writeEnabled) {
            throw new BusinessException(503,
                    "SOP 资料正在升级，暂时无法上传、修改或删除")
                    .withCode("SOP_WRITE_ROLLOUT_PENDING")
                    .withHint("现有资料仍可查看，请稍后重试");
        }
    }
}
