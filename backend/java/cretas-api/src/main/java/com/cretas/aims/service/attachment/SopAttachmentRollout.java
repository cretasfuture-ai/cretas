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
                    "SOP 写入暂未启用（cretas.sop-attachments.write-enabled=false），请等待第二阶段发布");
        }
    }
}
