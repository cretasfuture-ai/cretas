package com.cretas.aims.service.impl;

import com.cretas.aims.dto.quality.DynamicQualityInspectionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcessingServiceImplDynamicQualityStandardContractTest {
    @Test
    void dynamicRequestCarriesRevisionAndAnswerIdentity() {
        DynamicQualityInspectionRequest request = new DynamicQualityInspectionRequest();
        request.setStandardRevision("binding-a:3:null:10");
        DynamicQualityInspectionRequest.Answer answer = new DynamicQualityInspectionRequest.Answer();
        answer.setBindingId("binding-a");
        answer.setItemId("item-a");
        answer.setItemVersion(3);
        answer.setValue("8.5");
        request.setAnswers(List.of(answer));

        assertEquals("binding-a:3:null:10", request.getStandardRevision());
        assertEquals("binding-a", request.getAnswers().get(0).getBindingId());
        assertEquals(3, request.getAnswers().get(0).getItemVersion());
        assertEquals("8.5", request.getAnswers().get(0).getValue());
    }
}
