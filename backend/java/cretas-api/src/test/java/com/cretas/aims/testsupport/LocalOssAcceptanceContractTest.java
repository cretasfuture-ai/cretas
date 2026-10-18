package com.cretas.aims.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Date;
import static org.assertj.core.api.Assertions.*;

class LocalOssAcceptanceContractTest {
    @TempDir Path directory;

    @Test void explicitNewFactoryUsesRealSignedBytesAndRejectsOtherFactoriesAndOrigins() throws Exception {
        try (var store = new LocalOssAcceptanceConfiguration.LocalObjects(directory, 0,
                "FOOD_3101_001", "http://127.0.0.1:31764")) {
            Date expiry = new Date(System.currentTimeMillis() + 60_000);
            String key = "FOOD_3101_001/attachments/new-receipt.png";
            byte[] bytes = "new-factory-receipt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            HttpClient client = HttpClient.newHttpClient();
            URI put = store.url(key, "PUT", expiry).toURI();
            assertThat(client.send(HttpRequest.newBuilder(put).header("Origin", "http://127.0.0.1:31764")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode()).isEqualTo(200);
            var read = client.send(HttpRequest.newBuilder(store.url(key, "GET", expiry).toURI()).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(read.body()).isEqualTo(bytes);
            assertThat(client.send(HttpRequest.newBuilder(put).header("Origin", "http://127.0.0.1:31761")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode()).isEqualTo(403);
            assertThatThrownBy(() -> store.url("VISION-ENTRY-A/foreign.png", "PUT", expiry))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.url("FOOD_3101_001/../escape.png", "PUT", expiry))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void refusesBroadFactoryKeysAndNonLoopbackWebOrigins() {
        assertThatThrownBy(() -> new LocalOssAcceptanceConfiguration.LocalObjects(directory, 0,
                "../FOOD", "http://127.0.0.1:31764")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalOssAcceptanceConfiguration.LocalObjects(directory, 0,
                "FOOD_3101_001", "https://example.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void signedLocalBytesAreRealAndUntrustedKeysOrSignaturesAreRejected() throws Exception {
        try (var store = new LocalOssAcceptanceConfiguration.LocalObjects(directory, 0)) {
            String key = "VISION-ENTRY-A/attachments/test-receipt.png";
            Date expiry = new Date(System.currentTimeMillis() + 60_000);
            byte[] bytes = "local-synthetic-receipt".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            HttpClient client = HttpClient.newHttpClient();
            URI put = store.url(key, "PUT", expiry).toURI();
            assertThat(client.send(HttpRequest.newBuilder(put).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(200);
            URI get = store.url(key, "GET", expiry).toURI();
            var readback = client.send(HttpRequest.newBuilder(get).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(readback.statusCode()).isEqualTo(200); assertThat(readback.body()).isEqualTo(bytes);
            var mediaRead = client.send(HttpRequest.newBuilder(URI.create(store.baseUrl() + "/" + key)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(mediaRead.statusCode()).isEqualTo(200); assertThat(mediaRead.body()).isEqualTo(bytes);
            assertThat(client.send(HttpRequest.newBuilder(URI.create(store.baseUrl() + "/" + key)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
            assertThat(client.send(HttpRequest.newBuilder(put).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
            assertThatThrownBy(() -> store.url("VISION-ENTRY-B/foreign.png", "GET", expiry)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.url("VISION-ENTRY-A/../escape.png", "PUT", expiry)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
