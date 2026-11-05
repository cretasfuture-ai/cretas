package com.cretas.aims.service.attachment;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectAcl;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.ObjectPermission;
import com.cretas.aims.config.OssConfig;
import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.attachment.dto.RegisterAttachmentRequest;
import com.cretas.aims.service.attachment.dto.UploadUrlResponse;
import com.cretas.aims.service.attachment.impl.AttachmentServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in proof against a real Aliyun OSS endpoint. It deliberately does not run without the
 * explicit environment switch. The database remains the isolated test H2 database unless the
 * caller selects another already-safe test datasource through the normal test profile.
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
@TestPropertySource(properties = "cretas.sop-attachments.write-enabled=true")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({AttachmentServiceImpl.class, AttachmentSopOssIntegrationContractTest.RealOssConfig.class})
@EnabledIfEnvironmentVariable(named = "CRETAS_SOP_OSS_VERIFY", matches = "true")
@Transactional
class AttachmentSopOssIntegrationContractTest {

    private static final long PLATFORM_ACTOR_ID = 7_300_001L;

    @Autowired AttachmentService attachmentService;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired WorkProcessRepository workProcessRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired OSS ossClient;
    @Autowired DataSource dataSource;

    private String ownedBucket;
    private String ownedKey;

    @Test
    void realSignedPutAclHeadAnonymousGetAndTypedJpaRegistration() throws Exception {
        String factoryId = "QA_SOP_" + UUID.randomUUID().toString().replace("-", "");
        String processId = "qa-sop-process-" + UUID.randomUUID().toString().replace("-", "");
        String fileName = "中文-" + UUID.randomUUID().toString().replace("-", "") + ".pdf";
        byte[] bytes = "real-oss-sop-proof".getBytes(StandardCharsets.UTF_8);
        String fileType = "application/pdf";
        long actorId = PLATFORM_ACTOR_ID;
        String stage = "initialization";

        try {
            stage = "h2-database-guard";
            try (var connection = dataSource.getConnection()) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("H2");
            }

            stage = "qa-fixture";
            createFactory(factoryId);
            createProcess(factoryId, processId);

            stage = "sign-upload-url";
            UploadUrlResponse signed = attachmentService.generateSopUploadUrlResponse(
                    factoryId, EntityType.WORK_PROCESS, processId, fileName, fileType);
            assertThat(signed.getUploadUrl() != null && !signed.getUploadUrl().isBlank())
                    .as("signed URL present")
                    .isTrue();
            assertThat(signed.getRequiredHeaders())
                    .containsEntry("Content-Type", fileType)
                    .containsEntry("x-oss-object-acl", "private");

            stage = "validate-owned-key";
            URI signedUri = URI.create(signed.getUploadUrl());
            URI mediaPrefix = URI.create(requiredEnv("CRETAS_SOP_OSS_MEDIA_URL_PREFIX"));
            assertThat(signedUri.getHost()).isEqualTo(mediaPrefix.getHost());
            assertThat(signedUri.getRawPath()).contains("%");
            String objectPath = signedUri.getPath();
            String prefixPath = mediaPrefix.getPath().replaceAll("/+$", "");
            assertThat(objectPath).startsWith(prefixPath + "/");
            String candidateBucket = requiredEnv("CRETAS_SOP_OSS_MEDIA_BUCKET");
            String candidateKey = objectPath.substring(prefixPath.length() + 1);
            String expectedPrefix = factoryId + "/attachments/sop/WORK_PROCESS/"
                    + sha256Hex(processId) + "/";
            String expectedFileSuffix = "_" + fileName;
            assertThat(candidateKey.startsWith(expectedPrefix)
                    && candidateKey.endsWith(expectedFileSuffix)).isTrue();
            String dateAndNonce = candidateKey.substring(expectedPrefix.length(),
                    candidateKey.length() - expectedFileSuffix.length());
            assertThat(dateAndNonce).matches("\\d{4}/\\d{2}/\\d{2}/[0-9a-f]{16}");
            ownedBucket = candidateBucket;
            ownedKey = candidateKey;

            stage = "http-put";
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
            HttpRequest put = HttpRequest.newBuilder(signedUri)
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", fileType)
                    .header("x-oss-object-acl", "private")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            HttpResponse<Void> putResponse = http.send(put, HttpResponse.BodyHandlers.discarding());
            assertThat(putResponse.statusCode()).isIn(200, 201);

            stage = "acl";
            ObjectAcl acl = ossClient.getObjectAcl(ownedBucket, ownedKey);
            assertThat(acl).isNotNull();
            assertThat(acl.getPermission()).isEqualTo(ObjectPermission.Private);
            // The SDK call above issues the real OSS HEAD used by registration's metadata gate.
            stage = "head-metadata";
            ObjectMetadata metadata = ossClient.getObjectMetadata(ownedBucket, ownedKey);
            assertThat(metadata.getContentLength()).isEqualTo((long) bytes.length);
            assertThat(metadata.getContentType()).isEqualTo(fileType);

            int queryStart = signed.getFileUrl().indexOf('?');
            URI anonymousUri = URI.create(queryStart < 0
                    ? signed.getFileUrl() : signed.getFileUrl().substring(0, queryStart));
            stage = "anonymous-get";
            HttpResponse<Void> anonymousGet = http.send(
                    HttpRequest.newBuilder(anonymousUri).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(anonymousGet.statusCode()).isIn(403, 404);

            stage = "jpa-register-as-actor";
            RegisterAttachmentRequest request = new RegisterAttachmentRequest();
            request.setEntityType(EntityType.WORK_PROCESS);
            request.setEntityId(processId);
            request.setFileName(fileName);
            request.setFileUrl(signed.getFileUrl());
            request.setFileSize((long) bytes.length);
            request.setFileType(fileType);
            request.setFileStorage("OSS");
            request.setDescription("real OSS proof");

            Attachment saved = attachmentService.registerAsActor(factoryId, request,
                    new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, actorId));
            entityManager.flush();
            entityManager.clear();

            Attachment readBack = attachmentRepository.findByFactoryIdAndId(factoryId, saved.getId())
                    .orElseThrow();
            assertThat(readBack.getEntityType()).isEqualTo(EntityType.WORK_PROCESS);
            assertThat(readBack.getEntityId()).isEqualTo(processId);
            assertThat(readBack.getUploadedBy()).isNull();
            assertThat(readBack.getUploadedActorType()).isEqualTo(AuthenticatedActorType.PLATFORM_ADMIN);
            assertThat(readBack.getUploadedActorId()).isEqualTo(actorId);
            assertThat(readBack.getThumbnailUrl()).isNull();
        } catch (AssertionError | Exception failure) {
            throw safeFailure(stage, failure);
        } finally {
            deleteOwnedObject();
        }
    }

    private void createFactory(String factoryId) {
        jdbc.update("insert into factories "
                        + "(id,name,type,level,is_active,manually_verified,ai_weekly_quota,created_at,updated_at) "
                        + "values (?,?,'FACTORY',0,true,false,20,now(),now())",
                factoryId, "OSS QA " + factoryId);
    }

    private void createProcess(String factoryId, String processId) {
        workProcessRepository.saveAndFlush(WorkProcess.builder()
                .id(processId)
                .factoryId(factoryId)
                .processName("OSS QA process")
                .defaultOutputMaterialKind(WorkProcessOutputMaterialKind.SEMI_FINISHED)
                .build());
    }

    private void deleteOwnedObject() {
        if (ownedBucket == null || ownedKey == null) return;
        try {
            ossClient.deleteObject(ownedBucket, ownedKey);
        } catch (Exception ignored) {
            throw new AssertionError("OSS cleanup failed for the owned QA object");
        }
    }

    private static AssertionError safeFailure(String stage, Throwable failure) {
        return new AssertionError("SOP OSS proof failed at " + stage
                + " (" + failure.getClass().getSimpleName() + ")");
    }

    private static String sha256Hex(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new AssertionError("Missing required SOP OSS verification environment: " + name);
        }
        return value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RealOssConfig {
        @Bean
        OssConfig ossConfig() {
            OssConfig config = Mockito.mock(OssConfig.class);
            String mediaBucket = requiredEnv("CRETAS_SOP_OSS_MEDIA_BUCKET");
            String mediaPrefix = requiredEnv("CRETAS_SOP_OSS_MEDIA_URL_PREFIX").replaceAll("/+$", "");
            Mockito.when(config.getMediaBucket()).thenReturn(mediaBucket);
            Mockito.when(config.getMediaUrlPrefix()).thenReturn(mediaPrefix);
            return config;
        }

        @Bean(destroyMethod = "shutdown")
        OSS ossClient() {
            String rawEndpoint = requiredEnv("CRETAS_SOP_OSS_ENDPOINT").trim();
            String endpoint = rawEndpoint.contains("://") ? rawEndpoint : "https://" + rawEndpoint;
            return new OSSClientBuilder().build(endpoint,
                    requiredEnv("CRETAS_SOP_OSS_ACCESS_KEY_ID"),
                    requiredEnv("CRETAS_SOP_OSS_ACCESS_KEY_SECRET"));
        }

        @Bean
        OssService ossService() {
            return Mockito.mock(OssService.class);
        }

        @Bean
        SopAttachmentRollout sopAttachmentRollout() {
            return new SopAttachmentRollout(true);
        }
    }
}
