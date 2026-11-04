package com.cretas.aims.controller;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.cretas.aims.config.OssConfig;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.entity.Attachment.EntityType;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.security.AttachmentPermissionResolver;
import com.cretas.aims.security.PlatformFactoryViewReadPolicy;
import com.cretas.aims.service.OssService;
import com.cretas.aims.service.attachment.impl.AttachmentServiceImpl;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import jakarta.persistence.EntityManager;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({AttachmentController.class, AttachmentServiceImpl.class, AttachmentPermissionResolver.class,
        PermissionServiceImpl.class, PermissionInterceptor.class, AttachmentSopSpringTest.Beans.class})
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
    @MockBean OssService ossService;
    @MockBean OssConfig ossConfig;
    @MockBean OSS ossClient;

    MockMvc mvc;
    String processF1;
    String processF2;
    Long equipmentF1;
    Long equipmentF2;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(permissionInterceptor)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(ossConfig.getMediaUrlPrefix()).thenReturn(MEDIA);
        when(ossService.generatePresignedUrl(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0) + "?Signature=test");
        when(ossClient.generatePresignedUrl(any(GeneratePresignedUrlRequest.class)))
                .thenAnswer(inv -> new URL(MEDIA + "/" + ((GeneratePresignedUrlRequest) inv.getArgument(0)).getKey()
                        + "?Signature=put"));
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

    private long count(String where, Object... args) {
        entityManager.flush();   // JDBC 读之前先把 JPA 里待写的行刷下去, 否则读到的是刷新前的表
        return jdbc.queryForObject("select count(*) from attachments where " + where, Long.class, args);
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

    private static String ownUrl(String factoryId, String name) {
        return MEDIA + "/" + factoryId + "/attachments/2026/11/04/" + name;
    }

    private MvcResult send(MockHttpServletRequestBuilder req, long userId) throws Exception {
        return mvc.perform(req.requestAttr("userId", userId)).andReturn();
    }

    private MvcResult register(String pathFactory, long userId, String body) throws Exception {
        return send(post("/api/mobile/" + pathFactory + "/attachments")
                .contentType(MediaType.APPLICATION_JSON).content(body), userId);
    }

    private String registerOk(String factory, long userId, EntityType type, String entityId, String name)
            throws Exception {
        MvcResult r = register(factory, userId,
                registerBody(type, entityId, ownUrl(factory, name), "application/pdf", 1024));
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
                .content(json.writeValueAsString(Map.of("fileName", "pump.png", "fileType", "image/png",
                        "fileSize", 2048, "entityType", "EQUIPMENT", "entityId", String.valueOf(equipmentF1)))), admin);
        assertThat(upload.getResponse().getStatus()).as(upload.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(body(upload).at("/data/uploadUrl").asText()).contains("?Signature=put");
        verify(ossClient, times(1)).generatePresignedUrl(any(GeneratePresignedUrlRequest.class));

        String id = registerOk(F1, admin, EntityType.EQUIPMENT, String.valueOf(equipmentF1), "pump.pdf");
        assertThat(count("id = ? and entity_type = 'EQUIPMENT' and entity_id = ?", id, String.valueOf(equipmentF1)))
                .isEqualTo(1);
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
                        + " file_type, file_category, file_storage, uploaded_by, uploaded_at, upload_source, created_at, updated_at)"
                        + " values (?,?,?,?,?,?,?,?,?,?,?,now(),?,now(),now())",
                dirty, F1, "WORK_PROCESS", processF2, "d.pdf", ownUrl(F1, "d.pdf"), 1, "application/pdf",
                "DOCUMENT", "OSS", managerF1, "WEB");
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
}
