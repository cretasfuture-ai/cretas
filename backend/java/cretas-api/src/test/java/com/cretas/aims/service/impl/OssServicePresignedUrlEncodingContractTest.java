package com.cretas.aims.service.impl;

import com.aliyun.oss.OSS;
import com.cretas.aims.config.OssConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.net.URL;
import java.util.Date;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OssServicePresignedUrlEncodingContractTest {

    private static final String BUCKET = "media";
    private static final String ENDPOINT = "oss-cn-shanghai.aliyuncs.com";

    @Mock
    private OSS ossClient;

    @Mock
    private OssConfig ossConfig;

    private OssServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = new OssServiceImpl(ossClient, ossConfig);
        when(ossClient.generatePresignedUrl(anyString(), anyString(), any(Date.class)))
                .thenReturn(new URL("https://signed.example.invalid/download"));
    }

    @ParameterizedTest(name = "canonical URL path is decoded once: {0}")
    @MethodSource("canonicalUrls")
    void canonicalAbsoluteUrlUsesDecodedSdkKey(String ossUrl, String expectedKey) {
        assertCapturedKey(ossUrl, expectedKey);
    }

    static Stream<Arguments> canonicalUrls() {
        return Stream.of(
                Arguments.of(
                        "https://media." + ENDPOINT
                                + "/docs/%E4%B8%AD%E6%96%87%20file/literal+plus/encoded%2Bplus/%25/%252F.txt?download=1#fragment",
                        "docs/中文 file/literal+plus/encoded+plus/%/%2F.txt"),
                Arguments.of(
                        "https://media." + ENDPOINT + "/plain/ascii.pdf?signature=old#ignored",
                        "plain/ascii.pdf"));
    }

    @ParameterizedTest(name = "relative key remains raw: {0}")
    @MethodSource("relativeKeys")
    void relativeKeyIsNeverDecoded(String rawKey) {
        when(ossConfig.getMediaBucket()).thenReturn(BUCKET);
        assertCapturedKey(rawKey, rawKey);
    }

    static Stream<String> relativeKeys() {
        return Stream.of(
                "relative/中文 file+literal%25/%252F.pdf",
                "plain%2Frelative+key.txt");
    }

    @ParameterizedTest(name = "legacy raw URL preserves object identity: {0}")
    @MethodSource("legacyRawUrls")
    void legacyRawAbsoluteUrlPreservesRawKey(String rawUrl, String expectedKey) {
        assertCapturedKey(rawUrl, expectedKey);
    }

    static Stream<Arguments> legacyRawUrls() {
        return Stream.of(Arguments.of(
                "https://media." + ENDPOINT + "/legacy folder/中文+raw%25/%252F.pdf?legacy=1#fragment",
                "legacy folder/中文+raw%25/%252F.pdf"),
                Arguments.of("https://media." + ENDPOINT + "/legacy/100%.pdf", "legacy/100%.pdf"),
                Arguments.of("https://media." + ENDPOINT + "/legacy/中文+raw.pdf", "legacy/中文+raw.pdf"));
    }

    private void assertCapturedKey(String ossPath, String expectedKey) {
        String signed = service.generatePresignedUrl(ossPath, 300);
        assertNotNull(signed);

        ArgumentCaptor<String> bucketCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(ossClient).generatePresignedUrl(bucketCaptor.capture(), keyCaptor.capture(), any(Date.class));
        assertEquals(BUCKET, bucketCaptor.getValue());
        assertEquals(expectedKey, keyCaptor.getValue());
    }
}
