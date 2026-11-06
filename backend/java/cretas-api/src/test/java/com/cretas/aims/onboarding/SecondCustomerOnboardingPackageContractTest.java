package com.cretas.aims.onboarding;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.blueprint.interview.InterviewModuleConfigCatalog;
import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.entity.workprocess.WorkProcessCustomFieldSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecondCustomerOnboardingPackageContractTest {

    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    void qaFixtureStrictlyDeserializesIntoCurrentApplyRequest() throws Exception {
        JsonNode document = STRICT_JSON.readTree(Files.readString(fixturePath()));
        ApplyAIInitRequest request = STRICT_JSON.treeToValue(document.required("payload"), ApplyAIInitRequest.class);

        assertThat(request.getIndustryCode()).isEqualTo("qa-food");
        assertThat(request.getSuggestedData().getProductTypes()).hasSize(1);
        assertThat(request.getSuggestedData().getWorkProcesses()).hasSize(1);
        assertThat(request.getSuggestedData().getWorkProcesses().get(0).getCustomFields()).hasSize(1);
        assertThat(request.getOverwrite()).isFalse();
        assertThat(request.getSuggestedData().getProvisionDefaultUsers()).isFalse();
    }

    @Test
    void customFieldShapeUsesTheSharedWorkProcessContract() throws Exception {
        JsonNode document = STRICT_JSON.readTree(Files.readString(fixturePath()));
        ApplyAIInitRequest request = STRICT_JSON.treeToValue(document.required("payload"), ApplyAIInitRequest.class);
        List<Map<String, Object>> fields = request.getSuggestedData().getWorkProcesses().get(0).getCustomFields();

        assertThat(WorkProcessCustomFieldSchema.firstProblemMessage(fields)).isNull();
        assertThat(WorkProcessCustomFieldSchema.TYPES).containsExactlyInAnyOrder("number", "text", "date");
        assertThat(fields.get(0).keySet()).containsOnlyElementsOf(WorkProcessCustomFieldSchema.ENTRY_KEYS);
    }

    @Test
    void moduleConfigIdsUseTheCurrentInterviewCatalog() throws Exception {
        JsonNode document = STRICT_JSON.readTree(Files.readString(fixturePath()));
        ApplyAIInitRequest request = STRICT_JSON.treeToValue(document.required("payload"), ApplyAIInitRequest.class);

        request.getSuggestedData().getModuleConfigs().forEach(item ->
                assertThat(InterviewModuleConfigCatalog.find(item.getModuleId()))
                        .as("module id %s", item.getModuleId()).isNotNull());
        assertThat(InterviewModuleConfigCatalog.all()).isNotEmpty();
    }

    @Test
    void unknownPayloadPropertyIsRejectedByStrictDeserialization() throws Exception {
        JsonNode payload = STRICT_JSON.readTree(Files.readString(fixturePath())).required("payload");
        ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("unsupportedCustomerField", "must fail");

        assertThatThrownBy(() -> STRICT_JSON.treeToValue(payload, ApplyAIInitRequest.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
    }

    private Path fixturePath() {
        List<Path> candidates = List.of(
                Path.of("../../../scripts/onboarding/second-customer-package/fixtures/valid-qa.json"),
                Path.of("scripts/onboarding/second-customer-package/fixtures/valid-qa.json"),
                Path.of("../../scripts/onboarding/second-customer-package/fixtures/valid-qa.json"));
        return candidates.stream().filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new IllegalStateException("valid QA fixture not found from " + Path.of(".").toAbsolutePath()));
    }
}
