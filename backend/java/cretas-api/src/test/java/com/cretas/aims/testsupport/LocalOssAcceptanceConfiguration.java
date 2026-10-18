package com.cretas.aims.testsupport;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import com.cretas.aims.config.OssConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;

/** Test-classpath only. Real local bytes, signed PUT/GET, no cloud access or business-response mocks. */
@Configuration(proxyBeanMethods = false)
@Profile("p0-local-acceptance")
public class LocalOssAcceptanceConfiguration {
    @Bean(destroyMethod = "close")
    public LocalObjects localAcceptanceObjects(@Value("${p0.local-objects.root}") String root,
                                               @Value("${p0.local-objects.port:0}") int port,
                                               @Value("${p0.local-objects.factory-id:VISION-ENTRY-A}") String factoryId,
                                               @Value("${p0.local-objects.web-origin:http://127.0.0.1:31761}") String webOrigin) throws Exception {
        Path path = Path.of(root).toAbsolutePath().normalize();
        if (path.startsWith(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Local acceptance objects must stay outside the repository");
        }
        return new LocalObjects(path, port, factoryId, webOrigin);
    }

    @Bean
    @Primary
    public OSS localAcceptanceOssClient(LocalObjects objects) {
        return objects.client();
    }

    @Bean
    @Primary
    public OssConfig localAcceptanceOssProperties(LocalObjects objects) {
        OssConfig config = new OssConfig() {
            @Override public String getMediaUrlPrefix() { return objects.baseUrl(); }
            @Override public String getAudioUrlPrefix() { return objects.baseUrl(); }
        };
        config.setEnabled(true);
        config.setMediaBucket("local-acceptance");
        config.setAudioBucket("local-acceptance");
        return config;
    }

    public static final class LocalObjects implements AutoCloseable {
        private static final int MAX_BYTES = 50 * 1024 * 1024;
        private final Path root;
        private final String factoryPrefix;
        private final String webOrigin;
        private final byte[] secret = new byte[32];
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);

        public LocalObjects(Path root, int port) throws Exception {
            this(root, port, "VISION-ENTRY-A", "http://127.0.0.1:31761");
        }

        public LocalObjects(Path root, int port, String factoryId, String webOrigin) throws Exception {
            if (factoryId == null || !factoryId.matches("[A-Z0-9][A-Z0-9_-]{0,49}")) {
                throw new IllegalArgumentException("Explicit single acceptance factory required");
            }
            URI origin = URI.create(webOrigin);
            if (!"http".equals(origin.getScheme()) || !"127.0.0.1".equals(origin.getHost())
                    || origin.getPort() < 1 || origin.getPort() > 65535 || origin.getRawUserInfo() != null
                    || !origin.getRawPath().isEmpty() || origin.getRawQuery() != null || origin.getRawFragment() != null) {
                throw new IllegalArgumentException("Owned loopback Web origin required");
            }
            this.factoryPrefix = factoryId + "/";
            this.webOrigin = webOrigin;
            this.root = root.toAbsolutePath().normalize();
            Files.createDirectories(this.root);
            new SecureRandom().nextBytes(secret);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
        }

        private Path file(String key) {
            if (key == null || !key.startsWith(factoryPrefix) || key.contains("..") || key.contains("\\") || key.contains("%")) {
                throw new IllegalArgumentException("Only the configured acceptance factory object keys are allowed");
            }
            Path target = root.resolve(key).normalize();
            if (!target.startsWith(root)) throw new IllegalArgumentException("Object path escaped acceptance root");
            return target;
        }

        private String sign(String method, String key, long expires) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((method + "\n" + key + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
        }

        public URL url(String key, String method, Date expiry) throws Exception {
            file(key);
            long expires = expiry.toInstant().getEpochSecond();
            String query = "expires=" + expires + "&signature=" + sign(method, key, expires);
            return new URI("http", null, "127.0.0.1", server.getAddress().getPort(), "/" + key, query, null).toURL();
        }

        public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        private void handle(HttpExchange exchange) {
            try {
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                if (origin != null && !origin.equals(webOrigin)) {
                    exchange.sendResponseHeaders(403, -1); return;
                }
                if (origin != null) exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "PUT, GET, HEAD, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
                if (exchange.getRequestMethod().equals("OPTIONS")) { exchange.sendResponseHeaders(204, -1); return; }
                String key = exchange.getRequestURI().getPath().substring(1);
                Path target = file(key);
                Map<String, String> params = new java.util.HashMap<>();
                String rawQuery = exchange.getRequestURI().getRawQuery();
                if (rawQuery != null) for (String part : rawQuery.split("&")) {
                    String[] pair = part.split("=", 2); if (pair.length == 2) params.put(pair[0], pair[1]);
                }
                long expires = Long.parseLong(params.getOrDefault("expires", "0"));
                String method = exchange.getRequestMethod();
                // Mirrors the existing public media URL used by preview/thumbnail code.
                // Only the explicitly configured factory's synthetic bytes exist in this external fixture.
                boolean localMediaRead = method.equals("GET") && rawQuery == null;
                if (!localMediaRead && (expires < java.time.Instant.now().getEpochSecond()
                        || !MessageDigest.isEqual(sign(method, key, expires).getBytes(StandardCharsets.UTF_8),
                        params.getOrDefault("signature", "").getBytes(StandardCharsets.UTF_8)))) {
                    exchange.sendResponseHeaders(403, -1); return;
                }
                if (method.equals("PUT")) {
                    byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BYTES + 1);
                    if (bytes.length > MAX_BYTES) { exchange.sendResponseHeaders(413, -1); return; }
                    Files.createDirectories(target.getParent()); Files.write(target, bytes);
                    exchange.sendResponseHeaders(200, -1);
                } else if (method.equals("GET") && Files.isRegularFile(target)) {
                    exchange.getResponseHeaders().set("Content-Type", java.util.Objects.requireNonNullElse(Files.probeContentType(target), "application/octet-stream"));
                    exchange.sendResponseHeaders(200, Files.size(target));
                    Files.copy(target, exchange.getResponseBody());
                } else exchange.sendResponseHeaders(404, -1);
            } catch (Exception error) {
                try { exchange.sendResponseHeaders(400, -1); } catch (Exception ignored) { /* response already closed */ }
            } finally { exchange.close(); }
        }

        public OSS client() {
            return (OSS) Proxy.newProxyInstance(OSS.class.getClassLoader(), new Class<?>[]{OSS.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "generatePresignedUrl" -> {
                        if (args[0] instanceof GeneratePresignedUrlRequest request) yield url(request.getKey(), request.getMethod().toString(), request.getExpiration());
                        yield url((String) args[1], "GET", (Date) args[2]);
                    }
                    case "getObject" -> {
                        OSSObject object = new OSSObject(); object.setObjectContent(Files.newInputStream(file((String) args[1]))); yield object;
                    }
                    case "getObjectMetadata" -> {
                        ObjectMetadata metadata = new ObjectMetadata(); metadata.setContentLength(Files.size(file((String) args[1]))); yield metadata;
                    }
                    case "doesObjectExist" -> Files.isRegularFile(file((String) args[1]));
                    case "putObject" -> {
                        String key; InputStream input;
                        if (args[0] instanceof PutObjectRequest request) { key = request.getKey(); input = request.getInputStream(); }
                        else { key = (String) args[1]; input = (InputStream) args[2]; }
                        Path target = file(key); byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Acceptance object exceeds file limit");
                        Files.createDirectories(target.getParent()); Files.write(target, bytes); yield new PutObjectResult();
                    }
                    case "shutdown" -> null;
                    case "toString" -> "OwnedLoopbackAcceptanceObjectClient";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("Local acceptance OSS operation: " + method.getName());
                };
            });
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
