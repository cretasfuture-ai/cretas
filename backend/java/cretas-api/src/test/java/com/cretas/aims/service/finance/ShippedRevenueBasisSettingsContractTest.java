package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.report.ShippedRevenueBasis;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class ShippedRevenueBasisSettingsContractTest {
    @Test
    void invalidStoredSettingsWarnWithFactoryIdWithoutLoggingPayload(CapturedOutput output) {
        assertThat(ShippedRevenueBasisSettings.read("MALFORMED", "{\"private-field\":"))
                .isEqualTo(ShippedRevenueBasis.GROSS);
        assertThat(ShippedRevenueBasisSettings.read("UNKNOWN", "{\"shippedRevenueBasis\":\"private-value\"}"))
                .isEqualTo(ShippedRevenueBasis.GROSS);

        assertThat(output.getAll())
                .contains("Ignoring malformed production_settings for shipped revenue basis: factoryId=MALFORMED",
                        "Ignoring unknown shippedRevenueBasis in production_settings: factoryId=UNKNOWN")
                .doesNotContain("private-field", "private-value");
    }

    @Test
    void unsetBasisDefaultsWithoutWarningAndValidLegacyBasisIsPreserved(CapturedOutput output) {
        assertThat(ShippedRevenueBasisSettings.read("UNSET", "{\"shippedRevenueBasis\":null}"))
                .isEqualTo(ShippedRevenueBasis.GROSS);
        assertThat(output.getAll()).doesNotContain("factoryId=UNSET");
        assertThat(ShippedRevenueBasisSettings.preserveOnUpdate(
                "KNOWN", "{\"shippedRevenueBasis\":\"NET\"}", "{\"defaultBatchSize\":140}"))
                .isEqualTo("{\"defaultBatchSize\":140,\"shippedRevenueBasis\":\"NET\"}");
    }

    @Test
    void parsingRejectsTrailingTokensAndExplicitWritesRejectUnknownValues(CapturedOutput output) {
        assertThat(ShippedRevenueBasisSettings.read("TRAILING", "{\"shippedRevenueBasis\":\"NET\"} {}"))
                .isEqualTo(ShippedRevenueBasis.GROSS);
        assertThat(output.getAll()).contains("factoryId=TRAILING");
        assertThatThrownBy(() -> ShippedRevenueBasisSettings.validate("net"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("发货收入口径必须选 GROSS");
    }
}
