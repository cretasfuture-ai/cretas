package com.cretas.aims.client;

import com.cretas.aims.entity.enums.LabelQcLabel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class LabelQcAnalysisClientTest {

    @Test
    void postsCaptureBytesAndMapsQualityGateContract() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        LabelQcAnalysisClient client = new LabelQcAnalysisClient(
                restTemplate,
                "http://python.test",
                "internal-secret",
                new ObjectMapper(),
                false);

        server.expect(requestTo("http://python.test/api/label-qc/capture-quality"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Secret", "internal-secret"))
                .andExpect(header("X-Factory-Id", "LIUSHANMEN"))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "data": {
                            "accepted": false,
                            "reasonCode": "TRAY_CROPPED_AT_SIDE",
                            "message": "左右下角托盘没有拍全，请后退一点或切换广角重拍",
                            "imageWidth": 3072,
                            "imageHeight": 4096,
                            "trayCount": 18,
                            "sharpness": 880.4,
                            "leftMarginRatio": 0.04,
                            "rightMarginRatio": 0.002
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        var result = client.analyzeCaptureQuality(
                new byte[]{1, 2, 3},
                "capture.jpg",
                "LIUSHANMEN");

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasonCode()).isEqualTo("TRAY_CROPPED_AT_SIDE");
        assertThat(result.trayCount()).isEqualTo(18);
        assertThat(result.rightMarginRatio()).isEqualTo(0.002);
        server.verify();
    }

    @Test
    void downloadsPhotoAndMapsPythonCandidateContract() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpServer imageServer;
        try {
            imageServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception ex) {
            throw new AssertionError("Could not start image fixture server", ex);
        }
        imageServer.createContext("/photo", exchange -> {
            byte[] body = new byte[]{1, 2, 3};
            exchange.getResponseHeaders().set("Content-Type", MediaType.IMAGE_JPEG_VALUE);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        imageServer.start();
        LabelQcAnalysisClient client = new LabelQcAnalysisClient(
                restTemplate,
                "http://python.test",
                "internal-secret",
                new ObjectMapper(),
                false);

        server.expect(requestTo("http://python.test/api/label-qc/analyze"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Secret", "internal-secret"))
                .andExpect(header("X-Factory-Id", "F006"))
                .andRespond(withSuccess("""
                        {
                          "success": true,
                          "data": {
                            "verdict": "SUSPECTED",
                            "model": "qwen-vl",
                            "promptVersion": "label-presence-high-recall-v1",
                            "customerReviewRequired": false,
                            "candidates": [{
                              "candidateId": "ai-1",
                              "label": "MISSING_WHITE_LABEL",
                              "confidence": 0.82,
                              "bbox": [0.1, 0.2, 0.4, 0.5],
                              "evidence": "盒边没有白色矩形标签",
                              "sourceTiles": [3]
                            }],
                            "platformCandidates": [{
                              "candidateId": "platform-tray-9",
                              "label": "UNJUDGEABLE",
                              "confidence": 0.2,
                              "bbox": [0.2, 0.01, 0.3, 0.03],
                              "evidence": "远景盒框过小",
                              "sourceTiles": [9]
                            }]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        try {
            var result = client.analyze(
                    "http://127.0.0.1:" + imageServer.getAddress().getPort() + "/photo",
                    "F006",
                    "photo-1");

            assertThat(result.model()).isEqualTo("qwen-vl");
            assertThat(result.promptVersion()).isEqualTo("label-presence-high-recall-v1");
            assertThat(result.customerReviewRequired()).isFalse();
            assertThat(result.candidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.label()).isEqualTo(LabelQcLabel.MISSING_WHITE_LABEL);
                assertThat(candidate.confidence()).isEqualTo(0.82);
                assertThat(candidate.bbox().xMin()).isEqualTo(0.1);
                assertThat(candidate.bbox().yMax()).isEqualTo(0.5);
                assertThat(candidate.sourceTiles()).containsExactly(3);
            });
            assertThat(result.platformCandidates()).singleElement().satisfies(candidate -> {
                assertThat(candidate.candidateId()).isEqualTo("platform-tray-9");
                assertThat(candidate.label()).isEqualTo(LabelQcLabel.UNJUDGEABLE);
                assertThat(candidate.sourceTiles()).containsExactly(9);
            });
            server.verify();
        } finally {
            imageServer.stop(0);
        }
    }

    @Test
    void retriesOneTransportFailureAndThenReturnsSuccess() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpServer imageServer = startImageServer();
        LabelQcAnalysisClient client = new LabelQcAnalysisClient(
                restTemplate,
                "http://python.test",
                "internal-secret",
                new ObjectMapper(),
                false);

        server.expect(requestTo("http://python.test/api/label-qc/analyze"))
                .andRespond(request -> {
                    throw new ResourceAccessException("stale pooled connection");
                });
        server.expect(requestTo("http://python.test/api/label-qc/analyze"))
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

        try {
            var result = client.analyze(imageUrl(imageServer), "LIUSHANMEN", "photo-transport");

            assertThat(result.verdict()).isEqualTo("NO_DEFECT_FOUND");
            server.verify();
        } finally {
            imageServer.stop(0);
        }
    }

    @Test
    void retriesOneServerErrorAndThenReturnsSuccess() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpServer imageServer = startImageServer();
        LabelQcAnalysisClient client = new LabelQcAnalysisClient(
                restTemplate,
                "http://python.test",
                "internal-secret",
                new ObjectMapper(),
                false);

        server.expect(requestTo("http://python.test/api/label-qc/analyze"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("http://python.test/api/label-qc/analyze"))
                .andRespond(withSuccess(successResponse(), MediaType.APPLICATION_JSON));

        try {
            var result = client.analyze(imageUrl(imageServer), "LIUSHANMEN", "photo-server-error");

            assertThat(result.verdict()).isEqualTo("NO_DEFECT_FOUND");
            server.verify();
        } finally {
            imageServer.stop(0);
        }
    }

    @Test
    void doesNotRetryClientError() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        HttpServer imageServer = startImageServer();
        LabelQcAnalysisClient client = new LabelQcAnalysisClient(
                restTemplate,
                "http://python.test",
                "internal-secret",
                new ObjectMapper(),
                false);

        server.expect(requestTo("http://python.test/api/label-qc/analyze"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        try {
            assertThatThrownBy(() -> client.analyze(
                    imageUrl(imageServer),
                    "LIUSHANMEN",
                    "photo-client-error"))
                    .isInstanceOf(LabelQcAnalysisClient.LabelQcClientException.class)
                    .hasMessage("视觉服务暂时不可用");
            server.verify();
        } finally {
            imageServer.stop(0);
        }
    }

    @Test
    void missingCustomerReviewFlagFailsClosed() {
        LabelQcAnalysisClient client = new LabelQcAnalysisClient(
                new RestTemplate(),
                "http://python.test",
                "internal-secret",
                new ObjectMapper(),
                false);

        LabelQcAnalysisClient.AnalysisResult result = ReflectionTestUtils.invokeMethod(
                client,
                "parse",
                """
                        {
                          "success": true,
                          "data": {
                            "verdict": "NO_DEFECT_FOUND",
                            "model": "legacy",
                            "promptVersion": "legacy-v1",
                            "candidates": []
                          }
                        }
                        """);

        assertThat(result).isNotNull();
        assertThat(result.customerReviewRequired()).isTrue();
    }

    @Test
    void rewritesShanghaiOssHostWithoutChangingSignedPathOrQuery() {
        URI source = URI.create("https://cretas-media.oss-cn-shanghai.aliyuncs.com/LIUSHANMEN/photo.jpg"
                + "?Expires=123&OSSAccessKeyId=test%2Fid&Signature=a%2Bb%3D");

        URI rewritten = LabelQcAnalysisClient.toInternalOssUri(source);

        assertThat(rewritten.getHost())
                .isEqualTo("cretas-media.oss-cn-shanghai-internal.aliyuncs.com");
        assertThat(rewritten.getRawPath()).isEqualTo(source.getRawPath());
        assertThat(rewritten.getRawQuery()).isEqualTo(source.getRawQuery());
    }

    @Test
    void doesNotRewriteNonOssOrOtherRegionHosts() {
        URI customDomain = URI.create("https://media.example.com/photo.jpg?Signature=x");
        URI otherRegion = URI.create("https://bucket.oss-cn-hangzhou.aliyuncs.com/photo.jpg?Signature=x");

        assertThat(LabelQcAnalysisClient.toInternalOssUri(customDomain)).isSameAs(customDomain);
        assertThat(LabelQcAnalysisClient.toInternalOssUri(otherRegion)).isSameAs(otherRegion);
    }

    private static HttpServer startImageServer() {
        final HttpServer imageServer;
        try {
            imageServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception ex) {
            throw new AssertionError("Could not start image fixture server", ex);
        }
        imageServer.createContext("/photo", exchange -> {
            byte[] body = new byte[]{1, 2, 3};
            exchange.getResponseHeaders().set("Content-Type", MediaType.IMAGE_JPEG_VALUE);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        imageServer.start();
        return imageServer;
    }

    private static String imageUrl(HttpServer imageServer) {
        return "http://127.0.0.1:" + imageServer.getAddress().getPort() + "/photo";
    }

    private static String successResponse() {
        return """
                {
                  "success": true,
                  "data": {
                    "verdict": "NO_DEFECT_FOUND",
                    "model": "label-v8",
                    "promptVersion": "label-v8-prompt",
                    "customerReviewRequired": false,
                    "candidates": []
                  }
                }
                """;
    }
}
