package com.cretas.aims.service.attachment;

import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SopAttachmentRolloutContractTest {

    @Test
    void defaultDisabledGateRejectsWritesWithRolloutHint() {
        BusinessException error = catchBusinessException(new SopAttachmentRollout(false));
        assertThat(error.getCode()).isEqualTo(503);
        assertThat(error.getMessage()).contains("cretas.sop-attachments.write-enabled=false");
    }

    @Test
    void explicitlyEnabledGateAllowsWritePhase() {
        assertThatCode(() -> new SopAttachmentRollout(true).requireWriteEnabled())
                .doesNotThrowAnyException();
    }

    private static BusinessException catchBusinessException(SopAttachmentRollout rollout) {
        try {
            rollout.requireWriteEnabled();
            throw new AssertionError("SOP write gate unexpectedly allowed the default-disabled phase");
        } catch (BusinessException error) {
            return error;
        }
    }
}
