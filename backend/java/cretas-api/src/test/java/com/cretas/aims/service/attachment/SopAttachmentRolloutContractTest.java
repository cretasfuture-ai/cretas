package com.cretas.aims.service.attachment;

import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SopAttachmentRolloutContractTest {

    @Test
    void defaultDisabledGateRejectsWritesWithRolloutHint() {
        new ApplicationContextRunner().withUserConfiguration(SopAttachmentRollout.class).run(context -> {
            BusinessException error = catchBusinessException(context.getBean(SopAttachmentRollout.class));
            assertThat(error.getCode()).isEqualTo(503);
            assertThat(error.getErrorCode()).isEqualTo("SOP_WRITE_ROLLOUT_PENDING");
            assertThat(error.getActionHint()).contains("查看");
        });
    }

    @Test
    void explicitlyEnabledGateAllowsWritePhase() {
        new ApplicationContextRunner().withUserConfiguration(SopAttachmentRollout.class)
                .withPropertyValues("cretas.sop-attachments.write-enabled=true")
                .run(context -> assertThatCode(() -> context.getBean(SopAttachmentRollout.class).requireWriteEnabled())
                        .doesNotThrowAnyException());
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
