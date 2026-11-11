package com.cretas.aims.config;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列表接口慢 (2026-11-11): 网关 139 -> 后端 47 走公网, 实测 ~140 KB/s, 列表 JSON (16-80 KB) 在网关侧要 1-3 秒。
 * 修法是在后端压缩 JSON。本测试用<b>仓库里真实的 application.properties</b> 绑定 {@code server.*},
 * 起一个真的嵌入式 Tomcat, 读响应头 —— 不是只断言配置文件里有某一行。
 *
 * <ul>
 *   <li>大 JSON + Accept-Encoding: gzip → gzip, 解压后内容一字不差;</li>
 *   <li>阴性对照: SSE (text/event-stream) 不压 (压缩会把流缓冲住, 推送就不实时了);</li>
 *   <li>阴性对照: 客户端不接受 gzip → 明文; 小于阈值的 JSON → 明文。</li>
 * </ul>
 */
class JsonResponseCompressionContractTest {

    private static final String BIG_JSON = "{\"content\":[" + "{\"name\":\"QA-原料\",\"unit\":\"kg\"},".repeat(800)
            + "{\"name\":\"end\"}]}";
    private WebServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private ServerProperties boundFromRepositoryProperties() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        new PropertiesPropertySourceLoader().load("application", new ClassPathResource("application.properties"))
                .forEach(env.getPropertySources()::addLast);
        return Binder.get(env).bind("server", ServerProperties.class).orElseGet(ServerProperties::new);
    }

    private int start() throws IOException {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        factory.setCompression(boundFromRepositoryProperties().getCompression());
        factory.addInitializers(context -> {
            context.addServlet("json", new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                    resp.setContentType("application/json;charset=UTF-8");
                    resp.getWriter().write(req.getRequestURI().endsWith("small") ? "{\"ok\":true}" : BIG_JSON);
                }
            }).addMapping("/json", "/json-small");
            context.addServlet("sse", new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                    resp.setContentType("text/event-stream;charset=UTF-8");
                    resp.getWriter().write("data: " + BIG_JSON + "\n\n");
                }
            }).addMapping("/sse");
        });
        server = factory.getWebServer();
        server.start();
        return server.getPort();
    }

    private HttpResponse<byte[]> get(int port, String path, boolean acceptGzip) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (acceptGzip) request.header("Accept-Encoding", "gzip");
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void largeJsonIsGzippedAndDecodesToTheSameBody() throws Exception {
        int port = start();
        HttpResponse<byte[]> response = get(port, "/json", true);

        assertThat(response.headers().firstValue("Content-Encoding")).hasValue("gzip");
        byte[] plain = new GZIPInputStream(new java.io.ByteArrayInputStream(response.body())).readAllBytes();
        assertThat(new String(plain, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(BIG_JSON);
        System.out.printf("[JSON_COMPRESSION] plain=%d gzip=%d%n", plain.length, response.body().length);
        assertThat(response.body().length).as("压缩后应远小于原文").isLessThan(plain.length / 4);
    }

    @Test
    void sseIsNeverCompressed() throws Exception {
        int port = start();
        HttpResponse<byte[]> response = get(port, "/sse", true);
        assertThat(response.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(new String(response.body(), java.nio.charset.StandardCharsets.UTF_8)).startsWith("data: {");
    }

    @Test
    void clientsWithoutGzipAndSmallJsonGetPlainBodies() throws Exception {
        int port = start();
        HttpResponse<byte[]> noGzip = get(port, "/json", false);
        assertThat(noGzip.headers().firstValue("Content-Encoding")).isEmpty();
        assertThat(new String(noGzip.body(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(BIG_JSON);

        HttpResponse<byte[]> small = get(port, "/json-small", true);
        assertThat(small.headers().firstValue("Content-Encoding")).isEmpty();
    }
}
