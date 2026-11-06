package com.cretas.aims.controller;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectAcl;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.ObjectPermission;
import com.cretas.aims.config.OssConfig;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.security.AttachmentPermissionResolver;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.SopAttachmentAccessResolver;
import com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAuditRecorder;
import com.cretas.aims.security.PlatformFactoryViewReadPolicy;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.attachment.SopAttachmentRollout;
import com.cretas.aims.service.attachment.impl.AttachmentServiceImpl;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import com.cretas.aims.service.secrecy.FactorySecrecyConfig;
import com.cretas.aims.service.secrecy.SecrecyItem;
import com.cretas.aims.entity.processsecret.ProcessSecretPolicy;
import com.cretas.aims.entity.processsecret.ProcessSecretGrant;
import com.cretas.aims.entity.processsecret.ProcessSecretStorageMode;
import com.cretas.aims.entity.processsecret.ProcessSecretGranteeType;
import com.cretas.aims.repository.processsecret.ProcessSecretPolicyRepository;
import com.cretas.aims.repository.processsecret.ProcessSecretGrantRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import jakarta.persistence.EntityManager;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 工序 SOP / 设备 SOP 附件: 真实 Spring 容器 (H2 + 真 JPA + 真 AttachmentController / Service / 权限解析 /
 * PermissionInterceptor / PermissionServiceImpl 硬编码矩阵), 只有 OSS 客户端与 OssService 打桩。
 * 所有断言从 HTTP 入口进去, 再用 JDBC 读表看「该落的落了、不该落的一行没动」。
 *
 * <p>每个「X 没发生」的读数都配一个「Y 一定发生」: 拒绝用例里 attachments 行数不变之前, 同一个测试先让一次正向
 * 注册成功 (行数确实 +1), 证明仪器 (表 / 路由 / 用户) 是通的; OSS 打桩的 verify(never) 同理配 verify(times(1))。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "cretas.sop-attachments.write-enabled=true")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({AttachmentController.class, AttachmentServiceImpl.class, AttachmentPermissionResolver.class,
        SopAttachmentAccessResolver.class, SopAttachmentRollout.class,
        PermissionServiceImpl.class, PermissionInterceptor.class, ProcessSecretRecipeGuard.class,
        ProcessSecretAccessService.class, ProcessSecretAuditRecorder.class, AttachmentSopSpringTest.Beans.class})
@DisplayName("工序/设备 SOP 附件 — 真 Spring 经 controller 入口")
class AttachmentSopSpringTest {

    @TestConfiguration
    static class Beans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }

    static final String F1 = "SOP-F1";
    static final String F2 = "SOP-F2";
    static final String MEDIA = "https://media.sop-test.invalid";
    static final AtomicLong IDS = new AtomicLong(910_000);

    @Autowired AttachmentController controller;
    @Autowired PermissionInterceptor permissionInterceptor;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired WorkProcessRepository processes;
    @Autowired EquipmentRepository equipments;
    @SpyBean AttachmentServiceImpl attachmentService;
    @MockBean OssService ossService;
    @MockBean OssConfig ossConfig;
    @MockBean OSS ossClient;
    @MockBean FactoryConfigService factoryConfigService;
    @MockBean UserModuleAccessService userModuleAccessService;
    @MockBean FactorySecrecyConfig secrecy;
    @SpyBean ProcessSecretAuditRecorder secretAudit;
    @SpyBean PermissionServiceImpl permissionService;
    @Autowired ProcessSecretPolicyRepository policies;
    @Autowired ProcessSecretGrantRepository grants;
    @Autowired ProcessSecretAccessService secretAccess;

    MockMvc mvc;
    String processF1;
    String processF2;
    Long equipmentF1;
    Long equipmentF2;
    final Map<Long, Set<String>> exactPermissionGrants = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        exactPermissionGrants.clear();
        // 真实 PermissionServiceImpl / PermissionInterceptor 仍运行；仅为本测试用户提供
        // 精确的权限集合，避免 warehouse_worker 的默认 warehouse 权限掩盖 inventory 闸。
        doAnswer(invocation -> {
            User principal = invocation.getArgument(0, User.class);
            Set<String> granted = principal == null ? null : exactPermissionGrants.get(principal.getId());
            if (granted == null) return invocation.callRealMethod();
            // Mockito may expose Java varargs as either one String[] or expanded raw arguments.
            for (int i = 1; i < invocation.getRawArguments().length; i++) {
                Object raw = invocation.getRawArguments()[i];
                if (raw instanceof String[] required) {
                    for (String permission : required) {
                        if (granted.contains(permission)) return true;
                    }
                } else if (raw instanceof String permission && granted.contains(permission)) {
                    return true;
                }
            }
            return false;
        }).when(permissionService).hasAnyPermission(any(), any(String[].class));
        when(secrecy.isOn(anyString(), any(SecrecyItem.class))).thenReturn(true);
        secretAccess.invalidate(F1);
        secretAccess.invalidate(F2);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(permissionInterceptor)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(ossConfig.getMediaUrlPrefix()).thenReturn(MEDIA);
        when(ossConfig.getMediaBucket()).thenReturn("sop-test-bucket");
        when(factoryConfigService.isModuleEnabled(anyString(), eq("equipment"))).thenReturn(true);
        when(userModuleAccessService.canAccessModule(any(User.class), eq("equipment"))).thenReturn(true);
        when(ossService.generatePresignedUrl(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0) + "?Signature=test");
        when(ossClient.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenAnswer(inv -> {
                    String key = ((GeneratePresignedUrlRequest) inv.getArgument(0)).getKey();
                    return new URL(java.net.URI.create(MEDIA + "/" + key).toASCIIString() + "?Signature=put");
                });
        ObjectAcl privateAcl = new ObjectAcl();
        privateAcl.setPermission(ObjectPermission.Private);
        when(ossClient.getObjectAcl(anyString(), anyString())).thenReturn(privateAcl);
        factory(F1);
        factory(F2);
        processF1 = process(F1, "切片");
        processF2 = process(F2, "腌制");
        equipmentF1 = equipment(F1, "EQ-A");
        equipmentF2 = equipment(F2, "EQ-B");
    }

    // ------------------------------------------------------------------ fixtures

    private void factory(String id) {
        jdbc.update("insert into factories (id,name,type,level,is_active,manually_verified,ai_weekly_quota,created_at,updated_at)"
                + " values (?,?,'FACTORY',0,true,false,20,now(),now())", id, "SOP测试工厂-" + id);
    }

    private String process(String factoryId, String name) {
        String id = "wp-" + IDS.incrementAndGet();
        processes.saveAndFlush(WorkProcess.builder().id(id).factoryId(factoryId).processName(name)
                .defaultOutputMaterialKind(com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind.SEMI_FINISHED).build());
        return id;
    }

    private Long equipment(String factoryId, String code) {
        FactoryEquipment e = new FactoryEquipment();
        e.setFactoryId(factoryId);
        e.setCode(code + "-" + IDS.incrementAndGet());
        e.setEquipmentCode(e.getCode());
        e.setEquipmentName("设备" + code);
        e.setStatus("idle");
        e.setCreatedBy(1L);
        return equipments.saveAndFlush(e).getId();
    }

    private long user(String factoryId, String role) {
        long id = IDS.incrementAndGet();
        jdbc.update("insert into users (id, factory_id, username, password_hash, role_code, is_active, created_at, updated_at)"
                + " values (?,?,?,?,?,true,now(),now())", id, factoryId, role + "-" + id, "x", role);
        return id;
    }

    private void grantOnly(long userId, String... permissions) {
        exactPermissionGrants.put(userId, Set.of(permissions));
    }

    private long platformAdmin() {
        long id = IDS.incrementAndGet();
        jdbc.update("insert into platform_admins (id, username, password_hash, real_name, platform_role, status, created_at, updated_at)"
                + " values (?,?,?,?,?,'active',now(),now())", id, "platform-" + id, "x", "SOP平台管理员",
                "super_admin");
        return id;
    }

    private long count(String where, Object... args) {
        entityManager.flush();   // JDBC 读之前先把 JPA 里待写的行刷下去, 否则读到的是刷新前的表
        return jdbc.queryForObject("select count(*) from attachments where " + where, Long.class, args);
    }

    private static Object column(Map<String, Object> row, String name) {
        Map.Entry<String, Object> entry = row.entrySet().stream()
                .filter(candidate -> candidate.getKey().equalsIgnoreCase(name))
                .findFirst().orElse(null);
        return entry == null ? null : entry.getValue();
    }

    private long liveSops(String factoryId) {
        return count("factory_id = ? and entity_type in ('WORK_PROCESS','EQUIPMENT') and deleted_at is null", factoryId);
    }

    private String registerBody(EntityType type, String entityId, String fileUrl, String fileType, long size)
            throws Exception {
        return json.writeValueAsString(Map.of(
                "entityType", type.name(), "entityId", entityId, "fileName", "sop.bin", "fileUrl", fileUrl,
                "thumbnailUrl", fileUrl, "fileSize", size, "fileType", fileType));
    }

    private String ownUrl(String factoryId, String name) {
        return sopUrl(factoryId, EntityType.WORK_PROCESS, F1.equals(factoryId) ? processF1 : processF2, name);
    }

    private static String sopUrl(String factoryId, EntityType type, String targetId, String name) {
        try {
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(targetId.getBytes(StandardCharsets.UTF_8)));
            return MEDIA + "/" + factoryId + "/attachments/sop/" + type.name() + "/" + digest + "/2026/11/04/" + name;
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private MvcResult send(MockHttpServletRequestBuilder req, long userId) throws Exception {
        Map<String, Object> user;
        boolean platform;
        try {
            user = jdbc.queryForMap("select platform_role from platform_admins where id = ?", userId);
            platform = true;
        } catch (EmptyResultDataAccessException absentPlatformAdmin) {
            user = jdbc.queryForMap("select factory_id, role_code from users where id = ?", userId);
            platform = false;
        }
        String role = platform ? user.get("platform_role").toString() : user.get("role_code").toString();
        AuthenticatedActor actor = new AuthenticatedActor(platform ? AuthenticatedActorType.PLATFORM_ADMIN
                : AuthenticatedActorType.FACTORY_USER, userId, platform ? null : user.get("factory_id").toString(), role);
        return mvc.perform(req.requestAttr("userId", userId)
                .requestAttr(AuthenticatedActor.REQUEST_ATTRIBUTE, actor)).andReturn();
    }

    private MvcResult register(String pathFactory, long userId, String body) throws Exception {
        JsonNode payload = json.readTree(body);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(payload.path("fileSize").asLong());
        metadata.setContentType(payload.path("fileType").asText());
        return registerWithMetadata(pathFactory, userId, body, metadata);
    }

    private MvcResult registerWithMetadata(String pathFactory, long userId, String body,
                                           ObjectMetadata metadata) throws Exception {
        when(ossClient.getObjectMetadata(anyString(), anyString())).thenReturn(metadata);
        return send(post("/api/mobile/" + pathFactory + "/attachments")
                .contentType(MediaType.APPLICATION_JSON).content(body), userId);
    }

    private String registerOk(String factory, long userId, EntityType type, String entityId, String name)
            throws Exception {
        MvcResult r = register(factory, userId,
                registerBody(type, entityId, sopUrl(factory, type, entityId, name), "application/pdf", 1024));
        assertThat(r.getResponse().getStatus()).as("正向注册应成功: " + r.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).at("/data/id").asText();
    }

    private JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 正向

    @Test
    @DisplayName("正向: 生产经理给本厂工序上传 PDF → 落库; operator 能列出并拿到下载地址 (读口含 operator)")
    void managerUploadsAndOperatorReads() throws Exception {
        long manager = user(F1, "production_manager");
        long operator = user(F1, "operator");
        long before = liveSops(F1);

        String id = registerOk(F1, manager, EntityType.WORK_PROCESS, processF1, "sop-slicing.pdf");

        assertThat(liveSops(F1)).isEqualTo(before + 1);
        assertThat(count("id = ? and entity_type = 'WORK_PROCESS' and entity_id = ? and factory_id = ?",
                id, processF1, F1)).isEqualTo(1);

        MvcResult list = send(get("/api/mobile/" + F1 + "/attachments")
                .param("entityType", "WORK_PROCESS").param("entityId", processF1), operator);
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        JsonNode data = body(list).path("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("id").asText()).isEqualTo(id);
        assertThat(data.get(0).has("fileUrl")).isFalse();
        assertThat(data.get(0).has("thumbnailUrl")).isFalse();

        MvcResult url = send(get("/api/mobile/" + F1 + "/attachments/" + id + "/download-url"), operator);
        assertThat(url.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(url).at("/data/url").asText()).isEqualTo(ownUrl(F1, "sop-slicing.pdf") + "?Signature=test");
        verify(ossService).generatePresignedUrl(ownUrl(F1, "sop-slicing.pdf"), 3600);
    }

    @Test
    @DisplayName("正向: 设备管理员给本厂设备上传图片; 取签名上传地址带 entityType 时走 SOP 校验并真的发出签名")
    void equipmentAdminUploadsEquipmentSop() throws Exception {
        long admin = user(F1, "equipment_admin");

        MvcResult upload = send(post("/api/mobile/" + F1 + "/attachments/upload-url")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fileName", "操作标准.png", "fileType", "image/png",
                        "fileSize", 2048, "entityType", "EQUIPMENT", "entityId", String.valueOf(equipmentF1)))), admin);
        assertThat(upload.getResponse().getStatus()).as(upload.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(upload).at("/data/uploadUrl").asText()).contains("?Signature=put");
        assertThat(body(upload).at("/data/uploadUrl").asText()).contains("%E6");
        assertThat(body(upload).at("/data/requiredHeaders/x-oss-object-acl").asText()).isEqualTo("private");
        ArgumentCaptor<GeneratePresignedUrlRequest> requestCaptor = ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(ossClient, times(1)).generatePresignedUrl(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getHeaders().get("x-oss-object-acl")).isEqualTo("private");
        assertThat(requestCaptor.getValue().getAdditionalHeaderNames()).contains("x-oss-object-acl");

        String id = registerOk(F1, admin, EntityType.EQUIPMENT, String.valueOf(equipmentF1), "pump.pdf");
        assertThat(count("id = ? and entity_type = 'EQUIPMENT' and entity_id = ?", id, String.valueOf(equipmentF1)))
                .isEqualTo(1);
        verify(ossClient, never()).putObject(anyString(), anyString(), any(java.io.InputStream.class),
                any(ObjectMetadata.class));
    }

    @Test
    @DisplayName("设备 SOP 动态入口: 工厂模块关闭时列表/详情/下载/签名/注册全部拒绝且不再触发 OSS")
    void equipmentModuleDisabledClosesEveryDynamicAttachmentEntryPoint() throws Exception {
        long admin = user(F1, "equipment_admin");
        String entityId = String.valueOf(equipmentF1);
        String attachmentId = registerOk(F1, admin, EntityType.EQUIPMENT, entityId, "before-disable.pdf");
        long before = liveSops(F1);
        org.mockito.Mockito.clearInvocations(attachmentService, ossService, ossClient);
        when(factoryConfigService.isModuleEnabled(F1, "equipment")).thenReturn(false);

        assertThat(send(get("/api/mobile/" + F1 + "/attachments")
                .param("entityType", "EQUIPMENT").param("entityId", entityId), admin)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(get("/api/mobile/" + F1 + "/attachments/" + attachmentId), admin)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(get("/api/mobile/" + F1 + "/attachments/" + attachmentId + "/download-url"), admin)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(post("/api/mobile/" + F1 + "/attachments/upload-url")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fileName", "blocked.pdf", "fileType", "application/pdf",
                        "fileSize", 10, "entityType", "EQUIPMENT", "entityId", entityId))), admin)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(register(F1, admin, registerBody(EntityType.EQUIPMENT, entityId,
                sopUrl(F1, EntityType.EQUIPMENT, entityId, "blocked.pdf"), "application/pdf", 10)).getResponse().getStatus()).isEqualTo(403);

        assertThat(liveSops(F1)).isEqualTo(before);
        verify(attachmentService, never()).registerAsActor(anyString(), any(), any());
        verify(ossClient, never()).getObjectAcl(anyString(), anyString());
        verify(ossClient, never()).getObjectMetadata(anyString(), anyString());
        verify(ossService, never()).generatePresignedUrl(anyString(), anyInt());
        verify(ossClient, never()).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));
    }

    @Test
    @DisplayName("设备 SOP 动态入口: 账号显式模块拒绝不能被设备角色权限绕过")
    void equipmentAccountModuleDenyClosesDynamicAttachmentAccess() throws Exception {
        long admin = user(F1, "equipment_admin");
        String entityId = String.valueOf(equipmentF1);
        String attachmentId = registerOk(F1, admin, EntityType.EQUIPMENT, entityId, "account-deny.pdf");
        long before = liveSops(F1);
        org.mockito.Mockito.clearInvocations(attachmentService, ossService, ossClient);
        when(userModuleAccessService.canAccessModule(any(User.class), eq("equipment"))).thenReturn(false);

        assertThat(send(get("/api/mobile/" + F1 + "/attachments")
                .param("entityType", "EQUIPMENT").param("entityId", entityId), admin)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(get("/api/mobile/" + F1 + "/attachments/" + attachmentId), admin)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(get("/api/mobile/" + F1 + "/attachments/" + attachmentId + "/download-url"), admin)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(post("/api/mobile/" + F1 + "/attachments/upload-url")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fileName", "blocked.pdf", "fileType", "application/pdf",
                        "fileSize", 10, "entityType", "EQUIPMENT", "entityId", entityId))), admin)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(register(F1, admin, registerBody(EntityType.EQUIPMENT, entityId,
                sopUrl(F1, EntityType.EQUIPMENT, entityId, "blocked.pdf"), "application/pdf", 10))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(liveSops(F1)).isEqualTo(before);
        verify(attachmentService, never()).registerAsActor(anyString(), any(), any());
        verify(ossClient, never()).getObjectAcl(anyString(), anyString());
        verify(ossClient, never()).getObjectMetadata(anyString(), anyString());
        verify(ossService, never()).generatePresignedUrl(anyString(), anyInt());
        verify(ossClient, never()).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));
    }

    @Test
    @DisplayName("设备 SOP 动态入口: 已刷新且有效的平台管理员在模块开启时可写，关闭后同一入口拒绝")
    void activePlatformEquipmentAccessStillRequiresFactoryModule() throws Exception {
        long platform = platformAdmin();
        String entityId = String.valueOf(equipmentF1);
        String id = registerOk(F1, platform, EntityType.EQUIPMENT, entityId, "platform-equipment.pdf");
        assertThat(count("id = ? and uploaded_actor_type = 'PLATFORM_ADMIN' and uploaded_actor_id = ?", id, platform))
                .isEqualTo(1);

        when(factoryConfigService.isModuleEnabled(F1, "equipment")).thenReturn(false);
        assertThat(send(get("/api/mobile/" + F1 + "/attachments")
                .param("entityType", "EQUIPMENT").param("entityId", entityId), platform)
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("设备 SOP 动态入口: 模块/账号查询异常与停用账号均 fail-closed，未触发签名")
    void equipmentGateLookupFailuresAndInactiveActorAreClosed() throws Exception {
        long inactive = user(F1, "equipment_admin");
        jdbc.update("update users set is_active=false where id = ?", inactive);
        assertThat(send(get("/api/mobile/" + F1 + "/attachments")
                .param("entityType", "EQUIPMENT").param("entityId", String.valueOf(equipmentF1)), inactive)
                .getResponse().getStatus()).isEqualTo(401);

        long admin = user(F1, "equipment_admin");
        when(factoryConfigService.isModuleEnabled(F1, "equipment"))
                .thenThrow(new IllegalStateException("module lookup unavailable"));
        assertThat(send(get("/api/mobile/" + F1 + "/attachments")
                .param("entityType", "EQUIPMENT").param("entityId", String.valueOf(equipmentF1)), admin)
                .getResponse().getStatus()).isEqualTo(503);

        org.mockito.Mockito.doReturn(true).when(factoryConfigService).isModuleEnabled(F1, "equipment");
        when(userModuleAccessService.canAccessModule(any(User.class), eq("equipment")))
                .thenThrow(new IllegalStateException("account module lookup unavailable"));
        assertThat(send(get("/api/mobile/" + F1 + "/attachments")
                .param("entityType", "EQUIPMENT").param("entityId", String.valueOf(equipmentF1)), admin)
                .getResponse().getStatus()).isEqualTo(503);
        verify(ossService, never()).generatePresignedUrl(anyString(), anyInt());
        verify(ossClient, never()).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));
    }

    @Test
    @DisplayName("中文 SOP 对象使用严格一次 decode，ACL/HEAD 收到原始 Unicode key")
    void chineseSopObjectUsesDecodedUnicodeKeyForAclAndHead() throws Exception {
        long manager = user(F1, "production_manager");
        String fileUrl = java.net.URI.create(ownUrl(F1, "操作标准.pdf")).toASCIIString();
        String body = registerBody(EntityType.WORK_PROCESS, processF1, fileUrl, "application/pdf", 1024);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(1024);
        metadata.setContentType("application/pdf");

        MvcResult result = registerWithMetadata(F1, manager, body, metadata);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String expectedKey = java.net.URI.create(fileUrl).getPath().substring(1);
        verify(ossClient, times(1)).getObjectAcl(eq("sop-test-bucket"), eq(expectedKey));
        verify(ossClient, times(1)).getObjectMetadata(eq("sop-test-bucket"), eq(expectedKey));
    }

    // ------------------------------------------------------------------ 跨厂

    @Test
    @DisplayName("跨厂: 在 F1 的路径下给 F2 的工序/设备注册 SOP → 403, 一行没落; 同测试里本厂注册成功 (行数 +1) 证明仪器通")
    void crossFactoryRegisterIsRefused() throws Exception {
        long manager = user(F1, "production_manager");
        long equipAdmin = user(F1, "equipment_admin");

        long beforeAll = liveSops(F1) + liveSops(F2);
        registerOk(F1, manager, EntityType.WORK_PROCESS, processF1, "own.pdf");           // Y: 必然 +1
        assertThat(liveSops(F1) + liveSops(F2)).isEqualTo(beforeAll + 1);

        MvcResult foreignProcess = register(F1, manager,
                registerBody(EntityType.WORK_PROCESS, processF2, ownUrl(F1, "x.pdf"), "application/pdf", 10));
        MvcResult foreignEquipment = register(F1, equipAdmin,
                registerBody(EntityType.EQUIPMENT, String.valueOf(equipmentF2), ownUrl(F1, "y.pdf"), "application/pdf", 10));

        assertThat(foreignProcess.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(foreignProcess).path("message").asText()).contains("跨厂");
        assertThat(foreignEquipment.getResponse().getStatus()).isEqualTo(403);
        assertThat(liveSops(F2)).as("F2 一行都不该多").isZero();
        assertThat(liveSops(F1)).as("F1 只有那条正向的").isEqualTo(1);
    }

    @Test
    @DisplayName("跨厂: 取签名上传地址时目标是别家的设备 → 403, 且 OSS 一次签名都没发 (正向那次发了 1 次)")
    void crossFactoryUploadUrlNeverReachesOss() throws Exception {
        long admin = user(F1, "equipment_admin");
        Map<String, Object> own = Map.of("fileName", "a.pdf", "fileType", "application/pdf",
                "entityType", "EQUIPMENT", "entityId", String.valueOf(equipmentF1));
        Map<String, Object> foreign = Map.of("fileName", "a.pdf", "fileType", "application/pdf",
                "entityType", "EQUIPMENT", "entityId", String.valueOf(equipmentF2));

        assertThat(send(post("/api/mobile/" + F1 + "/attachments/upload-url")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(own)), admin)
                .getResponse().getStatus()).isEqualTo(200);
        verify(ossClient, times(1)).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));   // Y

        assertThat(send(post("/api/mobile/" + F1 + "/attachments/upload-url")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(foreign)), admin)
                .getResponse().getStatus()).isEqualTo(403);
        verify(ossClient, times(1)).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));   // X: 没再涨
    }

    @Test
    @DisplayName("跨厂: F2 的路径拿不到 F1 的附件 (404), 删不掉 (行还在); 目标已迁到别厂的脏数据在 F1 路径下也删不掉 (403)")
    void crossFactoryDeleteIsRefused() throws Exception {
        long managerF1 = user(F1, "production_manager");
        long managerF2 = user(F2, "production_manager");
        String id = registerOk(F1, managerF1, EntityType.WORK_PROCESS, processF1, "own.pdf");

        MvcResult asF2 = send(delete("/api/mobile/" + F2 + "/attachments/" + id), managerF2);
        assertThat(asF2.getResponse().getStatus()).isEqualTo(404);
        assertThat(count("id = ? and deleted_at is null", id)).isEqualTo(1);

        // 脏数据: F1 的附件行指向 F2 的工序 (历史迁移 / 手工改库造成). F1 路径下删除也不许放行。
        String dirty = java.util.UUID.randomUUID().toString();
        jdbc.update("insert into attachments (id, factory_id, entity_type, entity_id, file_name, file_url, file_size,"
                        + " file_type, file_category, file_storage, uploaded_by, uploaded_actor_type, uploaded_actor_id,"
                        + " uploaded_at, upload_source, created_at, updated_at)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,now(),?,now(),now())",
                dirty, F1, "WORK_PROCESS", processF2, "d.pdf", ownUrl(F1, "d.pdf"), 1, "application/pdf",
                "DOCUMENT", "OSS", managerF1, "FACTORY_USER", managerF1, "WEB");
        MvcResult dirtyDelete = send(delete("/api/mobile/" + F1 + "/attachments/" + dirty), managerF1);
        assertThat(dirtyDelete.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("id = ? and deleted_at is null", dirty)).isEqualTo(1);

        // Y: 同一个 F1 经理删自己厂正常的 SOP 必然成功, 证明上面的 403 来自归属校验而不是路由 / 用户不通
        MvcResult ok = send(delete("/api/mobile/" + F1 + "/attachments/" + id), managerF1);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(count("id = ? and deleted_at is not null", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("跨厂: fileUrl 指向别家上传目录的对象 → 403 (否则能把别家文件注册成自己的 SOP 再拿签名下载)")
    void fileUrlMustBeInsideOwnFactoryDirectory() throws Exception {
        long manager = user(F1, "production_manager");
        registerOk(F1, manager, EntityType.WORK_PROCESS, processF1, "own.pdf");
        long before = liveSops(F1);

        for (String evil : new String[]{
                ownUrl(F2, "secret.pdf"),
                MEDIA + "/" + F1 + "/attachments/../" + F2 + "/attachments/secret.pdf",
                "https://other-bucket.invalid/" + F1 + "/attachments/x.pdf"}) {
            MvcResult r = register(F1, manager, registerBody(EntityType.WORK_PROCESS, processF1, evil,
                    "application/pdf", 10));
            assertThat(r.getResponse().getStatus()).as(evil).isEqualTo(403);
        }
        assertThat(liveSops(F1)).isEqualTo(before);
    }

    @Test
    @DisplayName("SOP 注册必须验证对象私有 ACL 与服务端元数据，缺对象/公开对象/大小类型不符均拒绝")
    void sopRegisterRequiresPrivateAclAndAuthoritativeMetadata() throws Exception {
        long manager = user(F1, "production_manager");
        String body = registerBody(EntityType.WORK_PROCESS, processF1, ownUrl(F1, "acl.pdf"),
                "application/pdf", 1024);
        long before = liveSops(F1);

        ObjectAcl publicAcl = new ObjectAcl();
        publicAcl.setPermission(ObjectPermission.PublicRead);
        when(ossClient.getObjectAcl(anyString(), anyString())).thenReturn(publicAcl);
        assertThat(register(F1, manager, body).getResponse().getStatus()).isEqualTo(403);
        assertThat(liveSops(F1)).isEqualTo(before);

        ObjectAcl privateAcl = new ObjectAcl();
        privateAcl.setPermission(ObjectPermission.Private);
        when(ossClient.getObjectAcl(anyString(), anyString())).thenReturn(privateAcl);
        ObjectMetadata wrongSize = new ObjectMetadata();
        wrongSize.setContentLength(2048);
        wrongSize.setContentType("application/pdf");
        assertThat(registerWithMetadata(F1, manager, body, wrongSize).getResponse().getStatus()).isEqualTo(400);
        assertThat(liveSops(F1)).isEqualTo(before);

        ObjectMetadata wrongType = new ObjectMetadata();
        wrongType.setContentLength(1024);
        wrongType.setContentType("image/png");
        assertThat(registerWithMetadata(F1, manager, body, wrongType).getResponse().getStatus()).isEqualTo(400);
        assertThat(liveSops(F1)).isEqualTo(before);

        assertThat(registerWithMetadata(F1, manager, body, null).getResponse().getStatus()).isEqualTo(400);
        assertThat(liveSops(F1)).isEqualTo(before);
    }

    @Test
    @DisplayName("设备 SOP entityId 必须是无前导零/符号/空白的规范十进制字符串")
    void equipmentEntityIdMustBeCanonicalDecimal() throws Exception {
        long admin = user(F1, "equipment_admin");
        String canonical = String.valueOf(equipmentF1);
        for (String raw : new String[]{"0" + canonical, "+" + canonical, " " + canonical + " "}) {
            MvcResult upload = send(post("/api/mobile/" + F1 + "/attachments/upload-url")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("fileName", "pump.png", "fileType", "image/png",
                            "fileSize", 2048, "entityType", "EQUIPMENT", "entityId", raw))), admin);
            assertThat(upload.getResponse().getStatus()).as(raw).isEqualTo(400);
        }
        verify(ossClient, never()).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));
    }

    // ------------------------------------------------------------------ 权限

    @Test
    @DisplayName("无权限: operator / 小组长 能读工序 SOP 但不能传、不能删; 车间主任能删别人传的 (上传者不是自己)")
    void writeRolesAreNarrowerThanReadRoles() throws Exception {
        long manager = user(F1, "production_manager");
        long supervisor = user(F1, "workshop_supervisor");
        long operator = user(F1, "operator");
        long groupLeader = user(F1, "group_leader");
        String id = registerOk(F1, manager, EntityType.WORK_PROCESS, processF1, "own.pdf");   // Y
        long before = liveSops(F1);

        for (long denied : new long[]{operator, groupLeader}) {
            MvcResult up = register(F1, denied, registerBody(EntityType.WORK_PROCESS, processF1,
                    ownUrl(F1, "n.pdf"), "application/pdf", 10));
            assertThat(up.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(up).path("message").asText()).contains("无权管理工序 SOP");
            MvcResult del = send(delete("/api/mobile/" + F1 + "/attachments/" + id), denied);
            assertThat(del.getResponse().getStatus()).isEqualTo(403);
            MvcResult read = send(get("/api/mobile/" + F1 + "/attachments")
                    .param("entityType", "WORK_PROCESS").param("entityId", processF1), denied);
            assertThat(read.getResponse().getStatus()).isEqualTo(200);
        }
        assertThat(liveSops(F1)).isEqualTo(before);

        assertThat(send(delete("/api/mobile/" + F1 + "/attachments/" + id), supervisor).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(liveSops(F1)).isEqualTo(before - 1);
    }

    @Test
    @DisplayName("无权限: 设备 SOP 与工序 SOP 的权限互不串用 (生产经理不能管设备 SOP, 设备管理员不能管工序 SOP, operator 读不了设备 SOP)")
    void equipmentAndProcessPermissionsDoNotCross() throws Exception {
        long manager = user(F1, "production_manager");
        long equipAdmin = user(F1, "equipment_admin");
        long operator = user(F1, "operator");
        registerOk(F1, equipAdmin, EntityType.EQUIPMENT, String.valueOf(equipmentF1), "e.pdf");  // Y
        registerOk(F1, manager, EntityType.WORK_PROCESS, processF1, "p.pdf");                    // Y
        long before = liveSops(F1);

        assertThat(register(F1, manager, registerBody(EntityType.EQUIPMENT, String.valueOf(equipmentF1),
                ownUrl(F1, "z.pdf"), "application/pdf", 10)).getResponse().getStatus()).isEqualTo(403);
        assertThat(register(F1, equipAdmin, registerBody(EntityType.WORK_PROCESS, processF1,
                ownUrl(F1, "z.pdf"), "application/pdf", 10)).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(get("/api/mobile/" + F1 + "/attachments").param("entityType", "EQUIPMENT")
                .param("entityId", String.valueOf(equipmentF1)), operator).getResponse().getStatus()).isEqualTo(403);
        assertThat(liveSops(F1)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 文件类型 / 大小

    @Test
    @DisplayName("文件类型: PDF 与 png/jpeg/webp 放行; xlsx / docx / mp4 / 可执行文件 / 超 10MB 一律 400 且不落库")
    void onlyPdfAndImagesWithinTenMegabytes() throws Exception {
        long manager = user(F1, "production_manager");
        long before = liveSops(F1);
        int accepted = 0;
        for (String ok : new String[]{"application/pdf", "image/png", "image/jpeg", "image/webp"}) {
            MvcResult r = register(F1, manager, registerBody(EntityType.WORK_PROCESS, processF1,
                    ownUrl(F1, "f" + accepted + ".bin"), ok, 100));
            assertThat(r.getResponse().getStatus()).as(ok).isEqualTo(200);
            accepted++;
        }
        assertThat(liveSops(F1)).isEqualTo(before + 4);   // Y

        for (String bad : new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "video/mp4", "application/x-msdownload", "text/html"}) {
            MvcResult r = register(F1, manager, registerBody(EntityType.WORK_PROCESS, processF1,
                    ownUrl(F1, "bad.bin"), bad, 100));
            assertThat(r.getResponse().getStatus()).as(bad).isEqualTo(400);
            if (bad.length() <= 50) {   // 超长 MIME (xlsx / docx) 先被 DTO 的 @Size(50) 挡下, 是另一道闸, 同样 400
                assertThat(body(r).path("message").asText()).contains("PDF");
            }
        }
        MvcResult big = register(F1, manager, registerBody(EntityType.WORK_PROCESS, processF1,
                ownUrl(F1, "big.pdf"), "application/pdf", 10L * 1024 * 1024 + 1));
        assertThat(big.getResponse().getStatus()).isEqualTo(400);
        assertThat(liveSops(F1)).isEqualTo(before + 4);

        // 取签名上传地址同样不对 xlsx 开口, 且 OSS 没被调用
        MvcResult upload = send(post("/api/mobile/" + F1 + "/attachments/upload-url")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "fileName", "a.xlsx", "fileType", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "entityType", "WORK_PROCESS", "entityId", processF1))), manager);
        assertThat(upload.getResponse().getStatus()).isEqualTo(400);
        verify(ossClient, never()).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));
    }

    @Test
    @DisplayName("不存在的工序 → 404, 不能给临时/伪造 ID 绑 SOP")
    void unknownProcessIsRefused() throws Exception {
        long manager = user(F1, "production_manager");
        MvcResult r = register(F1, manager, registerBody(EntityType.WORK_PROCESS, "wp-does-not-exist",
                ownUrl(F1, "a.pdf"), "application/pdf", 10));
        assertThat(r.getResponse().getStatus()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ 保密 / 平台账号

    @Test
    @DisplayName("平台只读查看令牌: 附件路径不在已评审的只读白名单里 → 默认拒绝 (SOP 属工艺资料, 不经「查看工厂」泄露)")
    void platformFactoryViewTokenCannotReachSopFiles() {
        String base = "/api/mobile/" + F1;
        // 阳性对照: 白名单确实在工作 (已评审路径放行)
        assertThat(PlatformFactoryViewReadPolicy.allows(base + "/product-types/active", F1)).isTrue();
        assertThat(PlatformFactoryViewReadPolicy.allows(base + "/attachments", F1)).isFalse();
        assertThat(PlatformFactoryViewReadPolicy.allows(base + "/attachments/abc/download-url", F1)).isFalse();
        assertThat(PlatformFactoryViewReadPolicy.allows(base + "/attachments/abc/download", F1)).isFalse();
    }

    private ProcessSecretPolicy protect(long owner) {
        ProcessSecretPolicy policy = policies.saveAndFlush(ProcessSecretPolicy.builder()
                .factoryId(F1).productTypeId("SOP-PROTECTED-" + IDS.incrementAndGet())
                .storageMode(ProcessSecretStorageMode.CLOUD_RESTRICTED).ownerUserId(owner).createdBy(owner).build());
        secretAccess.invalidate(F1);
        return policy;
    }

    @Test
    void protectedSopUsesTrustedActorPlatformSwitchAndFailClosedAudit() throws Exception {
        long manager = user(F1, "production_manager");
        long platform = platformAdmin();
        jdbc.update("insert into users (id, factory_id, username, password_hash, role_code, is_active, created_at, updated_at)"
                + " values (?,?,?,?,?,true,now(),now())", platform, F1, "collision-" + platform, "x", "operator");
        String id = registerOk(F1, manager, EntityType.WORK_PROCESS, processF1, "protected.pdf");
        ProcessSecretPolicy policy = protect(manager);
        String base = "/api/mobile/" + F1 + "/attachments";

        // A raw legacy role/user id is not a validated JWT actor.
        assertThat(mvc.perform(get(base + "/" + id).requestAttr("userId", platform))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
        org.mockito.Mockito.clearInvocations(attachmentService);
        assertThat(send(get(base).param("entityType", "WORK_PROCESS").param("entityId", processF1), platform)
                .getResponse().getStatus()).isEqualTo(403);
        verify(attachmentService, never()).queryByEntity(F1, EntityType.WORK_PROCESS, processF1);
        assertThat(send(get(base + "/" + id + "/download-url"), platform).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(post(base + "/upload-url").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fileName", "p.pdf", "fileType", "application/pdf",
                        "entityType", "WORK_PROCESS", "entityId", processF1))), platform)
                .getResponse().getStatus()).isEqualTo(403);
        verify(ossClient, never()).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));

        when(secrecy.isOn(F1, SecrecyItem.PLATFORM_RECIPE_BLOCK)).thenReturn(false);
        assertThat(send(get(base).param("entityType", "WORK_PROCESS").param("entityId", processF1), platform)
                .getResponse().getStatus()).isEqualTo(200);
        verify(attachmentService, times(1)).queryByEntity(F1, EntityType.WORK_PROCESS, processF1);
        assertThat(send(get(base + "/" + id + "/download-url"), platform).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM process_secret_access_events "
                + "WHERE factory_id=? AND product_type_id=? AND actor_type='PLATFORM_ADMIN' AND actor_id=? "
                + "AND decision='ALLOW'", Long.class, F1, policy.getProductTypeId(), platform)).isPositive();

        org.mockito.Mockito.doThrow(new IllegalStateException("test audit unavailable")).when(secretAudit)
                .record(org.mockito.ArgumentMatchers.eq(F1), org.mockito.ArgumentMatchers.eq(policy.getProductTypeId()),
                        any(), any(), anyString(), anyString(), anyString());
        assertThat(send(get(base + "/" + id + "/download-url"), platform).getResponse().getStatus()).isEqualTo(403);
        verify(ossService, times(1)).generatePresignedUrl(anyString(), anyInt());
    }

    @Test
    @DisplayName("平台管理员上传 SOP 使用独立 actor namespace，回读不冒充工厂用户")
    void platformUploadPersistsTypedActorNamespace() throws Exception {
        long platform = platformAdmin();
        String id = registerOk(F1, platform, EntityType.WORK_PROCESS, processF1, "platform.pdf");

        entityManager.flush();
        Map<String, Object> persisted = jdbc.queryForMap(
                "select uploaded_by, uploaded_actor_type, uploaded_actor_id from attachments where id = ?", id);
        assertThat(column(persisted, "uploaded_by")).isNull();
        assertThat(column(persisted, "uploaded_actor_type")).isEqualTo("PLATFORM_ADMIN");
        assertThat(((Number) column(persisted, "uploaded_actor_id")).longValue()).isEqualTo(platform);
        JsonNode row = body(send(get("/api/mobile/" + F1 + "/attachments")
                .param("entityType", "WORK_PROCESS").param("entityId", processF1), platform)).at("/data/0");
        assertThat(row.at("/uploadedByActor/type").asText()).isEqualTo("PLATFORM_ADMIN");
        assertThat(row.at("/uploadedByActor/id").asLong()).isEqualTo(platform);
        assertThat(row.has("fileUrl")).isFalse();
        assertThat(row.has("thumbnailUrl")).isFalse();
    }

    @Test
    @DisplayName("真实 PermissionInterceptor: inventory 权限覆盖报损读写与两个批量入口")
    void inventoryPermissionCoversWastageReadWriteAndBatch() throws Exception {
        long worker = user(F1, "warehouse_worker");
        grantOnly(worker, "inventory:read", "inventory:read_write");
        String entityId = "wastage-" + IDS.incrementAndGet();
        String base = "/api/mobile/" + F1 + "/attachments";

        MvcResult upload = send(post(base + "/upload-url").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fileName", "wastage.pdf", "fileType", "application/pdf",
                        "fileSize", 10, "entityType", "WASTAGE_RECORD", "entityId", entityId))), worker);
        assertThat(upload.getResponse().getStatus()).isEqualTo(200);
        MvcResult registered = register(F1, worker, registerBody(EntityType.WASTAGE_RECORD, entityId,
                MEDIA + "/" + F1 + "/attachments/wastage/" + entityId + ".pdf", "application/pdf", 10));
        assertThat(registered.getResponse().getStatus()).isEqualTo(200);
        String attachmentId = body(registered).at("/data/id").asText();

        assertThat(send(put(base + "/" + attachmentId).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("description", "报损凭证已复核"))), worker)
                .getResponse().getStatus()).isEqualTo(200);

        assertThat(send(get(base).param("entityType", "WASTAGE_RECORD").param("entityId", entityId), worker)
                .getResponse().getStatus()).isEqualTo(200);
        String batchBody = json.writeValueAsString(Map.of("entityType", "WASTAGE_RECORD",
                "entityIds", List.of(entityId)));
        assertThat(send(post(base + "/batch-by-entity").contentType(MediaType.APPLICATION_JSON)
                .content(batchBody), worker).getResponse().getStatus()).isEqualTo(200);
        assertThat(send(post(base + "/batch-3chip-counts").contentType(MediaType.APPLICATION_JSON)
                .content(batchBody), worker).getResponse().getStatus()).isEqualTo(200);
        assertThat(send(delete(base + "/" + attachmentId), worker).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("真实 PermissionInterceptor: inventory 写权限可通过采购/客供收货凭证上传闸")
    void inventoryWritePermissionCoversReceiptUploadGates() throws Exception {
        long legacyGenericUser = user(F1, "warehouse_worker");
        MvcResult legacyUpload = send(post("/api/mobile/" + F1 + "/attachments/upload-url")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fileName", "legacy.pdf", "fileType", "application/pdf",
                        "fileSize", 10))), legacyGenericUser);
        assertThat(legacyUpload.getResponse().getStatus()).as("旧三参数 generic upload-url 仍兼容")
                .isEqualTo(200);

        long worker = user(F1, "warehouse_worker");
        grantOnly(worker, "inventory:write");
        String base = "/api/mobile/" + F1 + "/attachments/upload-url";
        for (String entityType : List.of("PURCHASE_RECEIPT", "CUSTOMER_SUPPLIED_RECEIPT")) {
            MvcResult result = send(post(base).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("fileName", "receipt.pdf", "fileType", "application/pdf",
                            "fileSize", 10, "entityType", entityType,
                            "entityId", "receipt-" + IDS.incrementAndGet()))), worker);
            assertThat(result.getResponse().getStatus()).as(entityType).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("真实 PermissionInterceptor: restaurant-only 与跨模块角色不能借用 inventory 附件入口")
    void nonInventoryPermissionCannotBorrowInventoryAttachmentRoutes() throws Exception {
        long restaurant = user(F1, "restaurant_manager");
        long worker = user(F1, "warehouse_worker");
        grantOnly(worker, "inventory:write");
        String entityId = "wastage-denied-" + IDS.incrementAndGet();
        String base = "/api/mobile/" + F1 + "/attachments";

        assertThat(send(get(base).param("entityType", "WASTAGE_RECORD").param("entityId", entityId), restaurant)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(post(base + "/upload-url").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fileName", "wastage.pdf", "fileType", "application/pdf",
                        "fileSize", 10, "entityType", "WASTAGE_RECORD", "entityId", entityId))), restaurant)
                .getResponse().getStatus()).isEqualTo(403);

        long reader = user(F1, "warehouse_worker");
        grantOnly(reader, "inventory:read", "inventory:read_write");
        String existingId = body(register(F1, reader, registerBody(EntityType.WASTAGE_RECORD, entityId,
                MEDIA + "/" + F1 + "/attachments/wastage/" + entityId + ".pdf", "application/pdf", 10)))
                .at("/data/id").asText();
        assertThat(register(F1, restaurant, registerBody(EntityType.WASTAGE_RECORD, "restaurant-write-" + IDS.incrementAndGet(),
                MEDIA + "/restaurant.pdf", "application/pdf", 10)).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(put(base + "/" + existingId).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("description", "restaurant"))), restaurant)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(delete(base + "/" + existingId), restaurant).getResponse().getStatus()).isEqualTo(403);

        // inventory:read passes the route's OR gate but cannot read procurement attachments.
        grantOnly(worker, "inventory:read");
        org.mockito.Mockito.clearInvocations(attachmentService);
        assertThat(send(get(base).param("entityType", "PURCHASE_ORDER").param("entityId", "po-1"), worker)
                .getResponse().getStatus()).isEqualTo(403);
        verify(attachmentService, never()).queryByEntity(F1, EntityType.PURCHASE_ORDER, "po-1");
        grantOnly(worker, "inventory:write");
        assertThat(send(get(base).param("entityType", "WASTAGE_RECORD").param("entityId", entityId), worker)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(post(base + "/upload-url").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fileName", "sop.pdf", "fileType", "application/pdf",
                        "fileSize", 10, "entityType", "WORK_PROCESS", "entityId", processF1))), worker)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(post(base + "/upload-url").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fileName", "wastage.pdf", "fileType", "application/pdf",
                        "fileSize", 10, "entityType", "WASTAGE_RECORD", "entityId", entityId))), worker)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(register(F1, worker, registerBody(EntityType.WASTAGE_RECORD, "writer-register-" + IDS.incrementAndGet(),
                MEDIA + "/writer.pdf", "application/pdf", 10)).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(put(base + "/" + existingId).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("description", "inventory write only"))), worker)
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(send(delete(base + "/" + existingId), worker).getResponse().getStatus()).isEqualTo(403);
        assertThat(send(post(base + "/upload-url").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fileName", "po.pdf", "fileType", "application/pdf",
                        "fileSize", 10, "entityType", "PURCHASE_ORDER", "entityId", "po-1"))), worker)
                .getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void protectedSopRequiresSeparateViewAndExportGrantAndRejectsCustomerHeld() throws Exception {
        long manager = user(F1, "production_manager");
        long operator = user(F1, "operator");
        String id = registerOk(F1, manager, EntityType.WORK_PROCESS, processF1, "private.pdf");
        ProcessSecretPolicy policy = protect(manager);
        String base = "/api/mobile/" + F1 + "/attachments/" + id;
        assertThat(send(get(base), operator).getResponse().getStatus()).isEqualTo(403);
        ProcessSecretGrant grant = grants.saveAndFlush(ProcessSecretGrant.builder().factoryId(F1)
                .productTypeId(policy.getProductTypeId()).granteeType(ProcessSecretGranteeType.FACTORY_USER)
                .granteeId(operator).canViewFull(true).canExport(false).canEdit(false).purpose("SOP test")
                .expiresAt(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).plusHours(1))
                .grantedBy(manager).build());
        assertThat(send(get(base), operator).getResponse().getStatus()).isEqualTo(200);
        assertThat(send(get(base + "/download-url"), operator).getResponse().getStatus()).isEqualTo(403);
        grant.setCanExport(true);
        grants.saveAndFlush(grant);
        assertThat(send(get(base + "/download-url"), operator).getResponse().getStatus()).isEqualTo(200);

        policy.setStorageMode(ProcessSecretStorageMode.CUSTOMER_HELD);
        policies.saveAndFlush(policy);
        secretAccess.invalidate(F1);
        assertThat(send(get(base), manager).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void sopUploadIsBoundToTargetAndCannotLaunderOtherModuleFiles() throws Exception {
        long manager = user(F1, "production_manager");
        String base = "/api/mobile/" + F1 + "/attachments";
        MvcResult upload = send(post(base + "/upload-url").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fileName", "操作标准.pdf", "fileType", "application/pdf",
                        "entityType", "WORK_PROCESS", "entityId", processF1))), manager);
        assertThat(upload.getResponse().getStatus()).isEqualTo(200);
        String url = java.net.URI.create(body(upload).at("/data/fileUrl").asText()).toASCIIString();
        assertThat(url).contains("/attachments/sop/WORK_PROCESS/");
        assertThat(url).contains("%"); // Actual OSS URLs encode Chinese filenames; this is not traversal.
        assertThat(register(F1, manager, registerBody(EntityType.WORK_PROCESS, processF1, url,
                "application/pdf", 100)).getResponse().getStatus()).isEqualTo(200);
        long before = liveSops(F1);
        String otherProcess = process(F1, "另一个工序");
        assertThat(register(F1, manager, registerBody(EntityType.WORK_PROCESS, otherProcess, url,
                "application/pdf", 100)).getResponse().getStatus()).isEqualTo(403);

        com.cretas.aims.entity.Attachment purchase = new com.cretas.aims.entity.Attachment();
        purchase.setFactoryId(F1); purchase.setEntityType(EntityType.PURCHASE_ORDER); purchase.setEntityId("SOP-PO");
        purchase.setFileName("restricted-purchase.pdf"); purchase.setFileType("application/pdf"); purchase.setFileSize(100L);
        purchase.setFileCategory(com.cretas.aims.entity.Attachment.FileCategory.DOCUMENT); purchase.setUploadedBy(manager);
        purchase.setFileUrl(MEDIA + "/" + F1 + "/attachments/2026/11/04/purchase.pdf");
        entityManager.persist(purchase); entityManager.flush();
        assertThat(send(get(base + "/" + purchase.getId()), manager).getResponse().getStatus()).isEqualTo(200);
        assertThat(register(F1, manager, registerBody(EntityType.WORK_PROCESS, processF1, purchase.getFileUrl(),
                "application/pdf", 100)).getResponse().getStatus()).isEqualTo(403);
        assertThat(liveSops(F1)).isEqualTo(before);
    }
}
