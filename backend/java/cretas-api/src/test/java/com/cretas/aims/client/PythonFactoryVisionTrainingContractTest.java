package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.client.PythonSmartBIClient.VisionTrainingAction;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PythonFactoryVisionTrainingContractTest {
    private PythonSmartBIClient client(MockWebServer server) {
        var config = new PythonSmartBIConfig(); config.setUrl(server.url("/").toString()); config.setEnabled(true); config.setMaxRetries(0);
        return new PythonSmartBIClient(config, new OkHttpClient(), new ObjectMapper(), new PythonServiceCircuitBreaker(), "factory-contract-secret");
    }
    @Test void dailyFactoryCallsReuseFactoryRouterWithVerifiedRole() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("{\"success\":true,\"data\":{\"templates\":[]}}"));
            client(server).callFactoryVisionTraining("FA",62L,"quality_manager",VisionTrainingAction.TEMPLATES,null,null);
            var request=server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/api/vision-training/templates");
            assertThat(request.getHeader("X-Factory-Id")).isEqualTo("FA");
            assertThat(request.getHeader("X-Actor-Type")).isEqualTo("FACTORY_USER");
            assertThat(request.getHeader("X-User-Role")).isEqualTo("quality_manager");
            assertThat(request.getHeader("X-User-Id")).isEqualTo("62");
            assertThat(request.getHeader("X-Internal-Secret")).isEqualTo("factory-contract-secret");
        }
    }
    @Test void untrustedRolesAndIdentifiersAreRejectedBeforeHttp() throws Exception {
        try (var server = new MockWebServer()) {
            var client=client(server);
            assertThatThrownBy(() -> client.callFactoryVisionTraining("FA",62,"super_admin",VisionTrainingAction.TEMPLATES,null,null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.callFactoryVisionTraining("FA",62,"operator",VisionTrainingAction.TEMPLATES,null,null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.callFactoryVisionTraining("FA/FB",62,"quality_manager",VisionTrainingAction.TEMPLATES,null,null)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.callFactoryVisionTraining("FA",62,"quality_manager",VisionTrainingAction.DRAFT,"../FB",null)).isInstanceOf(IllegalArgumentException.class);
            assertThat(server.getRequestCount()).isZero();
        }
    }
    @Test void photoWorkflowUsesOnlyFixedFactoryOperationsAndPreservesBusinessErrors() throws Exception {
        try (var server = new MockWebServer()) {
            var client=client(server);
            var mapper=new ObjectMapper();
            var actions=new VisionTrainingAction[]{VisionTrainingAction.PREPARATION,VisionTrainingAction.PREPARATION_PHOTOS,
                VisionTrainingAction.PREPARATION_IMAGE,VisionTrainingAction.PREPARATION_REVIEW,VisionTrainingAction.PREPARATION_STANDARD,
                VisionTrainingAction.PREPARATION_SUBMIT,VisionTrainingAction.PREPARATION_EXPORT};
            var suffixes=new String[]{"","/photos","/image","/review","/standard","/submit","/export"};
            for(int index=0;index<actions.length;index++) {
                server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("{\"success\":true,\"data\":{\"revision\":1}}"));
                client.callFactoryVisionTraining("FA",62,"quality_manager",actions[index],"project-a",index==0?null:mapper.createObjectNode());
                var request=server.takeRequest();
                assertThat(request.getPath()).isEqualTo("/api/vision-training/drafts/project-a/preparation"+suffixes[index]);
                assertThat(request.getMethod()).isEqualTo(index==0?"GET":"POST");
                assertThat(request.getHeader("X-Actor-Type")).isEqualTo("FACTORY_USER");
                assertThat(request.getHeader("X-Internal-Secret")).isEqualTo("factory-contract-secret");
            }
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("{\"success\":false,\"data\":null,\"code\":409,\"message\":\"资料已更新，请刷新后核对\"}"));
            assertThatThrownBy(()->client.callFactoryVisionTraining("FA",62,"quality_manager",VisionTrainingAction.PREPARATION_REVIEW,"project-a",mapper.createObjectNode()))
                .isInstanceOfSatisfying(BusinessException.class,error->{assertThat(error.getCode()).isEqualTo(409);assertThat(error.getMessage()).contains("请刷新");});
            assertThatThrownBy(()->client.callPlatformVisionTraining("FA",62,VisionTrainingAction.PREPARATION,"project-a",null)).isInstanceOf(IllegalArgumentException.class);
            assertThat(server.getRequestCount()).isEqualTo(8);
        }
    }
    @Test void nativeValidationErrorsDoNotOpenTheSharedPythonCircuitOrRetryWrites() throws Exception {
        try(var server=new MockWebServer()) {
            var client=client(server);
            for(int index=0;index<7;index++) {
                server.enqueue(new MockResponse().setResponseCode(422).setHeader("Content-Type","application/json")
                    .setBody("{\"detail\":[{\"loc\":[\"body\",\"expectedRevision\"],\"msg\":\"Field required\"}]}"));
                assertThatThrownBy(()->client.callFactoryVisionTraining("FA",62,"quality_manager",VisionTrainingAction.PREPARATION_REVIEW,
                        "project-a",new ObjectMapper().createObjectNode()))
                    .isInstanceOfSatisfying(BusinessException.class,error->{assertThat(error.getCode()).isEqualTo(422);assertThat(error.getMessage()).contains("核对");});
            }
            server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody("{\"success\":true,\"data\":{\"templates\":[]}}"));
            assertThat(client.callFactoryVisionTraining("FA",62,"quality_manager",VisionTrainingAction.TEMPLATES,null,null).path("templates").isArray()).isTrue();
            assertThat(server.getRequestCount()).isEqualTo(8);
        }
    }
}
