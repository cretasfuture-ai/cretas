package com.cretas.aims.service.intent;

import com.cretas.aims.controller.AiPurchaseTaskGrantController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real service/DTO contracts for deferred authorization. Database lifecycle coverage is run by the PG slice. */
class DeferredPurchaseGrantContractTest {

    @Test
    void controllerDtoRejectsUnknownAuthorityFields() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        assertThatThrownBy(() -> json.readValue(
                "{\"proofToken\":\"p\",\"commandDigest\":\"d\",\"expiresAt\":\"2026-10-20T00:00:00Z\",\"factoryId\":\"EVIL\"}",
                AiPurchaseTaskGrantController.AuthorizeLaterRequest.class))
                .hasMessageContaining("未知字段");
    }

}
