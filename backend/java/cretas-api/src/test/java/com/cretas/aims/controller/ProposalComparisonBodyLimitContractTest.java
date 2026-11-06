package com.cretas.aims.controller;

import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.ProposalComparisonService;
import com.cretas.aims.utils.FactoryAccessValidator;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real embedded HTTP transport budget; role/permission/factory enforcement is covered by the MVC suite. */
@SpringBootTest(classes = {ProposalComparisonController.class, ProposalComparisonBodyLimitContractTest.BudgetConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.banner-mode=off", "spring.jmx.enabled=false",
                "spring.main.web-application-type=servlet", "server.address=127.0.0.1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProposalComparisonBodyLimitContractTest {
    private static final int CONTRACT_LIMIT = 64 * 1024;
    @LocalServerPort int port;
    @Autowired Observation observed;
    @MockBean ProposalComparisonService comparison;
    @MockBean FactoryAccessValidator access;

    @BeforeEach void resetCounters() {
        observed.length.set(-2); observed.bytesRead.set(0); observed.transferEncoding.set(null);
    }

    @Test void declaredOversizeIs413BeforeApplicationReadsTheBody() throws Exception {
        HttpResponse<String> response = send(HttpRequest.BodyPublishers.ofByteArray(oversized()));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(observed.length.get()).isGreaterThan(CONTRACT_LIMIT);
        assertThat(observed.bytesRead.get()).isZero();
        verifyNoInteractions(comparison);
    }

    @Test void realChunkedUnknownLengthIsBoundedBeforeJackson() throws Exception {
        byte[] bytes = oversized();
        HttpResponse<String> response = send(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)));
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(observed.length.get()).isEqualTo(-1);
        assertThat(observed.transferEncoding.get()).isEqualToIgnoringCase("chunked");
        assertThat(observed.bytesRead.get()).isEqualTo(CONTRACT_LIMIT + 1);
        verifyNoInteractions(comparison);
    }

    @Test void exactByteLimitChunkedJsonStillParsesAndReachesTheService() throws Exception {
        String original = ProposalComparisonRoleGateMvcTest.BODY;
        byte[] bytes = (original + " ".repeat(CONTRACT_LIMIT
                - original.getBytes(StandardCharsets.UTF_8).length)).getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> response = send(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(observed.length.get()).isEqualTo(-1);
        assertThat(observed.bytesRead.get()).isEqualTo(CONTRACT_LIMIT);
        verify(comparison).compare(argThat(request -> request != null && request.candidates().size() == 2));
    }

    private HttpResponse<String> send(HttpRequest.BodyPublisher publisher) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                            + "/api/mobile/F006/proposal-comparisons/evaluate"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").POST(publisher).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static byte[] oversized() {
        String base = ProposalComparisonRoleGateMvcTest.BODY.strip();
        return (base.substring(0, base.length() - 1) + ",\"padding\":\""
                + "x".repeat(CONTRACT_LIMIT) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    static final class Observation {
        final AtomicLong length = new AtomicLong(-2);
        final AtomicInteger bytesRead = new AtomicInteger();
        final AtomicReference<String> transferEncoding = new AtomicReference<>();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            JpaRepositoriesAutoConfiguration.class, RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class,
            SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class})
    @Import(GlobalExceptionHandler.class)
    static class BudgetConfiguration {
        @Bean Observation observation() { return new Observation(); }
        @Bean Filter observeInput(Observation observed) {
            return (request, response, chain) -> {
                HttpServletRequest http = (HttpServletRequest) request;
                observed.length.set(http.getContentLengthLong());
                observed.transferEncoding.set(http.getHeader("Transfer-Encoding"));
                chain.doFilter(new HttpServletRequestWrapper(http) {
                    @Override public ServletInputStream getInputStream() throws IOException {
                        ServletInputStream input = http.getInputStream();
                        return new ServletInputStream() {
                            @Override public boolean isFinished() { return input.isFinished(); }
                            @Override public boolean isReady() { return input.isReady(); }
                            @Override public void setReadListener(ReadListener listener) { input.setReadListener(listener); }
                            @Override public int read() throws IOException {
                                int value = input.read(); if (value >= 0) observed.bytesRead.incrementAndGet(); return value;
                            }
                            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                                int count = input.read(bytes, offset, length);
                                if (count > 0) observed.bytesRead.addAndGet(count); return count;
                            }
                        };
                    }
                }, response);
            };
        }
    }
}
