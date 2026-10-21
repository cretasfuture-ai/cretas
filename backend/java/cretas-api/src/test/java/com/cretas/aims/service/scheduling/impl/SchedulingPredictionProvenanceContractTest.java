package com.cretas.aims.service.scheduling.impl;

import com.cretas.aims.dto.scheduling.CompletionProbabilityResponse;
import com.cretas.aims.entity.LineSchedule;
import com.cretas.aims.entity.SchedulingPlan;
import com.cretas.aims.repository.*;
import com.cretas.aims.service.*;
import com.cretas.aims.service.scheduling.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(MockitoExtension.class)
class SchedulingPredictionProvenanceContractTest {
    @Mock SchedulingPlanRepository planRepository; @Mock LineScheduleRepository scheduleRepository;
    @Mock WorkerAssignmentRepository assignmentRepository; @Mock ProductionLineRepository lineRepository;
    @Mock SchedulingAlertRepository alertRepository; @Mock SchedulingPredictionRepository predictionRepository;
    @Mock UserRepository userRepository; @Mock ProductionBatchRepository batchRepository;
    @Mock ProductionPlanRepository productionPlanRepository; @Mock DroolsRuleRepository droolsRuleRepository;
    @Mock FeatureEngineeringService featureEngineeringService; @Mock NotificationService notificationService;
    @Mock PushNotificationService pushNotificationService; @Mock SchedulingPlanCrudService planCrudService;
    @Mock WorkerAssignmentService workerAssignmentService;
    private final RestTemplate restTemplate = new RestTemplate();
    @Spy @InjectMocks SchedulingAIServiceImpl service;
    private MockRestServiceServer server;

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(service, "hybridPredictEnabled", true);
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://model.test");
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test void missingRouteIsAuditedAndDoesNotPersist() {
        givenSchedule(LocalDateTime.now().plusHours(2));
        server.expect(requestTo("http://model.test/scheduling/hybrid-predict")).andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));
        var result = service.calculateCompletionProbability("F-PROV", "S-PROV");
        assertThat(result.getPredictionOrigin()).isEqualTo("LOCAL_ESTIMATE");
        assertThat(result.getPredictionReason()).isEqualTo("REMOTE_ADAPTER_ROUTE_MISSING");
        verifyNoInteractions(predictionRepository); server.verify();
    }

    @Test void providerFailureAndInvalidBodyRemainDistinct() {
        givenSchedule(LocalDateTime.now().plusHours(2));
        server.expect(requestTo("http://model.test/scheduling/hybrid-predict")).andRespond(withServerError());
        assertThat(service.calculateCompletionProbability("F-PROV", "S-PROV").getPredictionReason()).isEqualTo("PYTHON_ADAPTER_HTTP_ERROR_500");
        server.reset();
        server.expect(requestTo("http://model.test/scheduling/hybrid-predict")).andRespond(withSuccess("{}", org.springframework.http.MediaType.APPLICATION_JSON));
        assertThat(service.calculateCompletionProbability("F-PROV", "S-PROV").getPredictionReason()).isEqualTo("REMOTE_RESPONSE_INVALID");
        verifyNoInteractions(predictionRepository);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "{}", "{\"probability\":-0.1}", "{\"probability\":1.1}",
            "{\"probability\":0.5,\"mode\":{}}", "{\"probability\":0.5,\"efficiency_prediction\":{\"model_version\":[]}}",
            "{\"probability\":0.5,\"confidence_interval\":null}",
            "{\"probability\":0.5,\"confidence_interval\":[0.9,0.1]}",
            "{\"probability\":0.5,\"efficiency_prediction\":{\"confidence\":null}}",
            "{\"probability\":0.5,\"efficiency_prediction\":{\"prediction\":null}}",
            "{\"probability\":0.5,\"mean_hours\":-1}"})
    void invalidRemoteBodiesRemainInvalidResponsesRatherThanProviderOrModelSuccess(String body) {
        givenSchedule(LocalDateTime.now().plusHours(2));
        server.expect(requestTo("http://model.test/scheduling/hybrid-predict"))
                .andRespond(withSuccess(body, org.springframework.http.MediaType.APPLICATION_JSON));
        var result = service.calculateCompletionProbability("F-PROV", "S-PROV");
        assertThat(result.getPredictionOrigin()).isEqualTo("LOCAL_ESTIMATE");
        assertThat(result.getPredictionReason()).isEqualTo("REMOTE_RESPONSE_INVALID");
        verifyNoInteractions(predictionRepository);
        server.verify();
    }

    @Test
    void assignmentReadFailureIsNotAnEmptyFeatureSetOrRemoteSuccess() {
        givenSchedule(LocalDateTime.now().plusHours(2));
        doThrow(new IllegalStateException("assignment store unavailable"))
                .when(assignmentRepository).findByScheduleId("S-PROV");
        var result = service.calculateCompletionProbability("F-PROV", "S-PROV");
        assertThat(result.getPredictionOrigin()).isEqualTo("LOCAL_ESTIMATE");
        assertThat(result.getPredictionReason()).isEqualTo("JAVA_FEATURE_READ_FAILURE");
        verifyNoInteractions(predictionRepository);
        server.verify();
    }

    @Test
    void validLegacyModelResponseIsEstimatedInputAndPersistsOnlyOnLegacyPath() {
        givenSchedule(LocalDateTime.now().plusHours(2));
        server.expect(requestTo("http://model.test/scheduling/hybrid-predict"))
                .andRespond(withSuccess("{\"probability\":0.82,\"mode\":\"hybrid\",\"efficiency_prediction\":{\"model_version\":\"m1\"}}", org.springframework.http.MediaType.APPLICATION_JSON));
        var result = service.calculateCompletionProbability("F-PROV", "S-PROV");
        assertThat(result.getPredictionOrigin()).isEqualTo("REMOTE_MODEL_ESTIMATED_INPUTS");
        assertThat(result.getPredictionReason()).contains("NOT_OPTIMIZATION");
        verify(predictionRepository).save(any());
        server.verify();
    }

    @Test void impossibleWindowIsBusinessConstraintWithoutAdapterCall() {
        givenSchedule(LocalDateTime.now().minusMinutes(1));
        givenScopedResources();
        var result = service.diagnoseCompletionProbability("F-PROV", "S-PROV");
        assertThat(result.getPredictionOrigin()).isEqualTo("BUSINESS_CONSTRAINT");
        assertThat(result.getPredictionReason()).isEqualTo("DETERMINISTIC_IMPOSSIBLE_WINDOW");
        verifyNoInteractions(predictionRepository);
    }

    @Test void incompleteDiagnosticInputsDoNotDefaultOrCallAdapter() {
        var schedule = new LineSchedule(); schedule.setId("S-PROV"); schedule.setPlanId("P-PROV");
        when(scheduleRepository.findById("S-PROV")).thenReturn(Optional.of(schedule));
        when(planRepository.findByIdAndFactoryIdAndDeletedAtIsNull("P-PROV", "F-PROV")).thenReturn(Optional.of(new SchedulingPlan()));
        var result = service.diagnoseCompletionProbability("F-PROV", "S-PROV");
        assertThat(result.getPredictionOrigin()).isEqualTo("BUSINESS_INPUT");
        assertThat(result.getPredictionReason()).isEqualTo("INPUTS_INCOMPLETE");
        verifyNoInteractions(predictionRepository);
    }

    @Test void missingVerifiedModelFeaturesNeverUseLegacyDefaultsOrCallAdapter() {
        givenSchedule(LocalDateTime.now().plusHours(2));
        givenScopedResources();
        var result = service.diagnoseCompletionProbability("F-PROV", "S-PROV");
        assertThat(result.getPredictionOrigin()).isEqualTo("BUSINESS_INPUT");
        assertThat(result.getPredictionReason()).isEqualTo("VERIFIED_MODEL_FEATURES_UNAVAILABLE");
        verifyNoInteractions(predictionRepository);
    }

    @Test void shortFutureWindowIsNotReportedAsImpossible() {
        givenSchedule(LocalDateTime.now().plusSeconds(30));
        givenScopedResources();
        assertThat(service.diagnoseCompletionProbability("F-PROV", "S-PROV").getPredictionReason())
                .isEqualTo("VERIFIED_MODEL_FEATURES_UNAVAILABLE");
        verifyNoInteractions(predictionRepository, featureEngineeringService);
        server.verify();
    }

    @Test void completedScheduleIsNotAnExpiredUnfinishedTask() {
        givenSchedule(LocalDateTime.now().minusMinutes(1));
        givenScopedResources();
        scheduleRepository.findById("S-PROV").orElseThrow().setCompletedQuantity(10);
        assertThat(service.diagnoseCompletionProbability("F-PROV", "S-PROV").getPredictionReason()).isEqualTo("COMPLETED");
        verifyNoInteractions(predictionRepository, featureEngineeringService);
    }

    @Test void invalidNumbersCannotMasqueradeAsCompleted() {
        givenSchedule(LocalDateTime.now().plusHours(2));
        givenScopedResources();
        scheduleRepository.findById("S-PROV").orElseThrow().setPlannedQuantity(-1);
        assertThat(service.diagnoseCompletionProbability("F-PROV", "S-PROV").getPredictionReason())
                .isEqualTo("INVALID_NUMERIC_INPUT");
        verifyNoInteractions(predictionRepository, featureEngineeringService);
    }

    @Test void foreignReferencedLinePreventsAllFeatureAndModelAccess() {
        givenSchedule(LocalDateTime.now().plusHours(2));
        assertThat(service.diagnoseCompletionProbability("F-PROV", "S-PROV").getPredictionReason())
                .isEqualTo("TENANT_SCOPED_RESOURCE_MISSING");
        verifyNoInteractions(predictionRepository, featureEngineeringService);
        server.verify();
    }

    @Test void foreignScheduleAndUnknownScheduleExposeTheSameRequestedScheduleOnly() {
        var foreign = new LineSchedule(); foreign.setId("FOREIGN-S"); foreign.setPlanId("SECRET-FOREIGN-PLAN");
        when(scheduleRepository.findById("FOREIGN-S")).thenReturn(Optional.of(foreign));
        var denied = org.assertj.core.api.Assertions.catchThrowable(() -> service.diagnoseCompletionProbability("F-PROV", "FOREIGN-S"));
        assertThat(denied).isInstanceOf(com.cretas.aims.exception.EntityNotFoundException.class)
                .hasMessageNotContaining("SECRET-FOREIGN-PLAN");
        when(scheduleRepository.findById("FOREIGN-S")).thenReturn(Optional.empty());
        var absent = org.assertj.core.api.Assertions.catchThrowable(() -> service.diagnoseCompletionProbability("F-PROV", "FOREIGN-S"));
        assertThat(absent).hasMessage(denied.getMessage());
        verifyNoInteractions(predictionRepository, featureEngineeringService);
        server.verify();
    }

    @Test
    void loopbackHttp404IsReachedAndAuditedAsMissingRoute() throws Exception {
        var result = runLoopbackProviderFailure(404);
        assertThat(result.getPredictionOrigin()).isEqualTo("LOCAL_ESTIMATE");
        assertThat(result.getPredictionReason()).isEqualTo("REMOTE_ADAPTER_ROUTE_MISSING");
    }

    @Test
    void loopbackHttp503IsReachedAndAuditedAsProviderFailure() throws Exception {
        var result = runLoopbackProviderFailure(503);
        assertThat(result.getPredictionOrigin()).isEqualTo("LOCAL_ESTIMATE");
        assertThat(result.getPredictionReason()).isEqualTo("PYTHON_ADAPTER_HTTP_ERROR_503");
    }

    private CompletionProbabilityResponse runLoopbackProviderFailure(int status) throws Exception {
        return runLoopbackResponse(status, null);
    }

    private CompletionProbabilityResponse runLoopbackResponse(int status, String responseBody) throws Exception {
        givenSchedule(LocalDateTime.now().plusHours(2));
        AtomicInteger requests = new AtomicInteger();
        var body = new java.util.concurrent.atomic.AtomicReference<String>();
        var path = new java.util.concurrent.atomic.AtomicReference<String>();
        var method = new java.util.concurrent.atomic.AtomicReference<String>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/scheduling/hybrid-predict", exchange -> {
            requests.incrementAndGet();
            path.set(exchange.getRequestURI().getPath());
            method.set(exchange.getRequestMethod());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (responseBody == null) exchange.sendResponseHeaders(status, -1);
            else {
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            }
            exchange.close();
        });
        http.setExecutor(executor);
        http.start();
        try {
            var transport = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            transport.setConnectTimeout(2000);
            transport.setReadTimeout(2000);
            ReflectionTestUtils.setField(service, "restTemplate", new RestTemplate(transport));
            ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:" + http.getAddress().getPort());
            var result = service.calculateCompletionProbability("F-PROV", "S-PROV");
            assertThat(requests).hasValue(1);
            assertThat(path.get()).isEqualTo("/scheduling/hybrid-predict");
            assertThat(method.get()).isEqualTo("POST");
            assertThat(body.get()).contains("F-PROV", "remaining_quantity", "features");
            verifyNoInteractions(predictionRepository);
            return result;
        } finally {
            http.stop(0);
            executor.shutdownNow();
        }
    }

    private void givenScopedResources() {
        when(lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull("L-PROV", "F-PROV"))
                .thenReturn(Optional.of(new com.cretas.aims.entity.ProductionLine()));
        scheduleRepository.findById("S-PROV").orElseThrow().setBatchId(9L);
        when(batchRepository.findByIdAndFactoryId(9L, "F-PROV"))
                .thenReturn(Optional.of(new com.cretas.aims.entity.ProductionBatch()));
    }

    private void givenSchedule(LocalDateTime end) {
        var schedule = new LineSchedule(); schedule.setId("S-PROV"); schedule.setPlanId("P-PROV");
        schedule.setPlannedQuantity(10); schedule.setCompletedQuantity(0); schedule.setAssignedWorkers(1);
        schedule.setPlannedEndTime(end); schedule.setProductionLineId("L-PROV");
        var plan = new SchedulingPlan(); plan.setId("P-PROV"); plan.setFactoryId("F-PROV");
        when(scheduleRepository.findById("S-PROV")).thenReturn(Optional.of(schedule));
        when(planRepository.findByIdAndFactoryIdAndDeletedAtIsNull("P-PROV", "F-PROV")).thenReturn(Optional.of(plan));
        var line = new com.cretas.aims.entity.ProductionLine();
        line.setId("L-PROV"); line.setFactoryId("F-PROV"); line.setName("Own line");
        lenient().when(lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull("L-PROV", "F-PROV")).thenReturn(Optional.of(line));
    }

    @Test void lateInvalidRemoteBodyCannotContaminateAnyLocalFallbackField() {
        givenSchedule(LocalDateTime.now().plusHours(2));
        server.expect(requestTo("http://model.test/scheduling/hybrid-predict")).andRespond(withSuccess(
                "{\"probability\":0.5,\"mean_hours\":17,\"std_hours\":3,\"percentile_90\":21,\"confidence_interval\":[0.2,0.8],\"mode\":\"hybrid\",\"risk_analysis\":\"remote risk\",\"efficiency_prediction\":{\"model_version\":\"m1\",\"confidence\":2,\"prediction\":99,\"explanation\":\"remote explanation\"}}", org.springframework.http.MediaType.APPLICATION_JSON));
        var result = service.calculateCompletionProbability("F-PROV", "S-PROV");
        assertThat(result.getPredictionOrigin()).isEqualTo("LOCAL_ESTIMATE");
        assertThat(result.getPredictionReason()).isEqualTo("REMOTE_RESPONSE_INVALID");
        assertThat(result.getPredictionMode()).isEqualTo("local");
        assertThat(result.getMeanHours()).isLessThan(java.math.BigDecimal.ONE);
        assertThat(result.getStdHours()).isNull(); assertThat(result.getPercentile90()).isNull();
        assertThat(result.getModelVersion()).isNull(); assertThat(result.getConfidence()).isNull();
        assertThat(result.getConfidenceLower()).isNull(); assertThat(result.getConfidenceUpper()).isNull();
        assertThat(result.getPredictedEfficiency()).isNull(); assertThat(result.getExplanation()).isNull();
        assertThat(result.getRiskAnalysis()).isNull(); verifyNoInteractions(predictionRepository); server.verify();
    }

    @Test void validRemoteFieldsAreCommittedOnlyAsOneValidatedResponse() {
        givenSchedule(LocalDateTime.now().plusHours(2));
        server.expect(requestTo("http://model.test/scheduling/hybrid-predict")).andRespond(withSuccess(
                "{\"probability\":0.5,\"confidence_interval\":[0.2,0.8],\"mode\":\"hybrid\",\"risk_analysis\":\"remote risk\",\"efficiency_prediction\":{\"model_version\":\"m1\",\"confidence\":0.8,\"prediction\":99,\"explanation\":\"remote explanation\"}}", org.springframework.http.MediaType.APPLICATION_JSON));
        var result = service.calculateCompletionProbability("F-PROV", "S-PROV");
        assertThat(result.getPredictionOrigin()).isEqualTo("REMOTE_MODEL_ESTIMATED_INPUTS");
        assertThat(result.getPredictionMode()).isEqualTo("hybrid"); assertThat(result.getModelVersion()).isEqualTo("m1");
        assertThat(result.getConfidence()).isEqualByComparingTo("0.8");
        assertThat(result.getConfidenceLower()).isEqualByComparingTo("0.2");
        assertThat(result.getConfidenceUpper()).isEqualByComparingTo("0.8");
        assertThat(result.getExplanation()).isEqualTo("remote explanation");
        assertThat(result.getRiskAnalysis()).isEqualTo("remote risk"); verify(predictionRepository).save(any()); server.verify();
    }

    @Test void realLoopbackStructuredProviderRefusalIsAdapterReportedNotInferredFromHttpStatus() throws Exception {
        var result = runLoopbackResponse(200, "{\"success\":false,\"error\":{\"source\":\"MODEL_PROVIDER\",\"code\":\"PROVIDER_UNAVAILABLE\"}}");
        assertThat(result.getPredictionOrigin()).isEqualTo("LOCAL_ESTIMATE");
        assertThat(result.getPredictionReason()).isEqualTo("PYTHON_ADAPTER_REPORTED_PROVIDER_UNAVAILABLE");
    }

    @Test void realLoopbackUnvalidatedProviderLabelIsInvalidResponseNotProviderProof() throws Exception {
        var result = runLoopbackResponse(200, "{\"success\":false,\"error\":{\"source\":\"MODEL_PROVIDER\",\"code\":\"UNKNOWN\"}}");
        assertThat(result.getPredictionReason()).isEqualTo("REMOTE_RESPONSE_INVALID");
    }

    @Test void actualConnectionRefusalIsPythonAdapterTransportNotInternalModelFailure() throws Exception {
        givenSchedule(LocalDateTime.now().plusHours(2));
        int port;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        var transport = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        transport.setConnectTimeout(500); transport.setReadTimeout(500);
        ReflectionTestUtils.setField(service, "restTemplate", new RestTemplate(transport));
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:" + port);
        var result = service.calculateCompletionProbability("F-PROV", "S-PROV");
        assertThat(result.getPredictionOrigin()).isEqualTo("LOCAL_ESTIMATE");
        assertThat(result.getPredictionReason()).isEqualTo("PYTHON_ADAPTER_TRANSPORT_ERROR");
        verifyNoInteractions(predictionRepository);
    }

    @Test void unexpectedJavaFailureRemainsUnknownInsteadOfAttributingItToProvider() {
        givenSchedule(LocalDateTime.now().plusHours(2));
        var peer = mock(RestTemplate.class);
        when(peer.postForObject(anyString(), any(), eq(java.util.Map.class))).thenThrow(new NullPointerException("Java processing"));
        ReflectionTestUtils.setField(service, "restTemplate", peer);
        var result = service.calculateCompletionProbability("F-PROV", "S-PROV");
        assertThat(result.getPredictionReason()).isEqualTo("JAVA_EXECUTION_FAILURE_UNKNOWN");
        assertThat(result.getPredictionOrigin()).isEqualTo("LOCAL_ESTIMATE"); verifyNoInteractions(predictionRepository);
    }

    @ParameterizedTest @ValueSource(strings = {"line", "batch", "both"})
    void legacyForeignReferencesNeverReachNamesFeaturesModelOrPrediction(String reference) {
        givenSchedule(LocalDateTime.now().plusHours(2));
        var schedule = scheduleRepository.findById("S-PROV").orElseThrow(); schedule.setBatchId(9L);
        var line = new com.cretas.aims.entity.ProductionLine(); line.setName("SECRET FOREIGN LINE");
        var batch = new com.cretas.aims.entity.ProductionBatch(); batch.setBatchNumber("SECRET FOREIGN BATCH"); batch.setProductTypeId("SECRET-PRODUCT");
        line.setFactoryId(reference.equals("batch") ? "F-PROV" : "FOREIGN");
        batch.setFactoryId(reference.equals("line") ? "F-PROV" : "FOREIGN");
        if (reference.equals("batch")) line.setName("Own line");
        if (reference.equals("line")) batch.setBatchNumber("Own batch");
        lenient().when(lineRepository.findById("L-PROV")).thenReturn(Optional.of(line));
        lenient().when(batchRepository.findById(9L)).thenReturn(Optional.of(batch));
        when(lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull("L-PROV", "F-PROV"))
                .thenReturn(reference.equals("batch") ? Optional.of(line) : Optional.empty());
        lenient().when(batchRepository.findByIdAndFactoryId(9L, "F-PROV"))
                .thenReturn(reference.equals("line") ? Optional.of(batch) : Optional.empty());
        var peer = mock(RestTemplate.class);
        lenient().when(peer.postForObject(anyString(), any(), eq(java.util.Map.class))).thenReturn(java.util.Map.of("probability", 0.5));
        ReflectionTestUtils.setField(service, "restTemplate", peer);
        var result = service.calculateCompletionProbability("F-PROV", "S-PROV");
        assertThat(result.getScheduleId()).isEqualTo("S-PROV");
        assertThat(result.getBatchNumber()).isNull(); assertThat(result.getProductionLineName()).isNull();
        assertThat(result.getPredictionReason()).isEqualTo("TENANT_SCOPED_RESOURCE_MISSING");
        assertThat(result).hasAllNullFieldsOrPropertiesExcept("scheduleId", "predictionOrigin", "predictionReason");
        verifyNoInteractions(peer, featureEngineeringService, predictionRepository);
        verify(lineRepository, never()).findById(anyString()); verify(batchRepository, never()).findById(anyLong());
    }
}
