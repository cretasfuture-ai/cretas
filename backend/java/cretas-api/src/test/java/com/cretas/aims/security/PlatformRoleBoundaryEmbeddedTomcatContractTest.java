package com.cretas.aims.security;

import com.cretas.aims.audit.PlatformDataAccessLogInterceptor;
import com.cretas.aims.audit.PlatformDataAccessLogWriter;
import com.cretas.aims.config.DemoReadOnlyInterceptor;
import com.cretas.aims.config.JwtAuthInterceptor;
import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.config.WebMvcConfig;
import com.cretas.aims.controller.AiValueSummaryController;
import com.cretas.aims.controller.MobileController;
import com.cretas.aims.controller.ScheduledTaskController;
import com.cretas.aims.controller.UserController;
import com.cretas.aims.controller.share.ShareLinkController;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.exception.AuthorizationException;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.cron.ScheduledTaskRepository;
import com.cretas.aims.repository.cron.ScheduledTaskRunLogRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.inventory.PurchaseOrderRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.aivalue.AiValueSummaryService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.impl.UserServiceImpl;
import com.cretas.aims.service.mobile.WebLoginSessionService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.cron.DynamicSchedulerService;
import com.cretas.aims.service.share.ShareLinkService;
import com.cretas.aims.utils.CookieAuthHelper;
import com.cretas.aims.utils.ExcelUtil;
import com.cretas.aims.utils.FactoryAccessValidator;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 「平台管理员不应该在工厂下面」, through real Tomcat and the real interceptor chain over a raw
 * socket. Repositories and collaborating services are stubbed; user create and update run the
 * real {@link UserServiceImpl} and {@link UserMapper}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PlatformRoleBoundaryEmbeddedTomcatContractTest.Harness.class,
        properties = {
                "cretas.jwt.secret=embedded-tomcat-test-secret-0123456789-0123456789-0123456789",
                "spring.main.banner-mode=off"
        })
@DisplayName("A platform role cannot sit under a factory")
class PlatformRoleBoundaryEmbeddedTomcatContractTest {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            PropertyPlaceholderAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class
    })
    @Import({
            WebMvcConfig.class,
            JwtAuthInterceptor.class,
            DemoReadOnlyInterceptor.class,
            PermissionInterceptor.class,
            RequireRoleInterceptor.class,
            ModuleEnabledInterceptor.class,
            DataScopeRouteInterceptor.class,
            ProcessSecretGuardInterceptor.class,
            PlatformDataAccessLogInterceptor.class,
            GlobalExceptionHandler.class,
            JwtUtil.class,
            CookieAuthHelper.class,
            FactoryAccessValidator.class,
            UserMapper.class,
            UserServiceImpl.class,
            UserController.class,
            MobileController.class,
            ScheduledTaskController.class,
            ShareLinkController.class,
            AiValueSummaryController.class
    })
    static class Harness {
    }

    @MockBean private TokenBlacklistService tokenBlacklistService;
    @MockBean private WebLoginSessionService webLoginSessionService;
    @MockBean private FactoryRepository factoryRepository;
    @MockBean private UserRepository userRepository;
    @MockBean private PermissionService permissionService;
    @MockBean private FactoryConfigService factoryConfigService;
    @MockBean private UserModuleAccessService userModuleAccessService;
    @MockBean private DataScopeResolver dataScopeResolver;
    @MockBean private JdbcTemplate jdbcTemplate;
    @MockBean private ProcessSecretAccessService processSecretAccessService;
    @MockBean private BomRecipeRepository bomRecipeRepository;
    @MockBean private BomRecipeItemRepository bomRecipeItemRepository;
    @MockBean private ProductProcessWorkflowRepository productProcessWorkflowRepository;
    @MockBean private BomVersionRepository bomVersionRepository;
    @MockBean private ProductionBatchRepository productionBatchRepository;
    @MockBean private ProductionPlanRepository productionPlanRepository;
    @MockBean private FactoryMaterialRequisitionRepository factoryMaterialRequisitionRepository;
    @MockBean private PlatformDataAccessLogWriter platformDataAccessLogWriter;
    @MockBean private EquipmentAlertRepository equipmentAlertRepository;
    @MockBean private PurchaseOrderRepository purchaseOrderRepository;
    @MockBean private MobileService mobileService;
    @MockBean private PasswordEncoder passwordEncoder;
    @MockBean private ExcelUtil excelUtil;
    @MockBean private DynamicSchedulerService dynamicSchedulerService;
    @MockBean private ScheduledTaskRepository scheduledTaskRepository;
    @MockBean private ScheduledTaskRunLogRepository scheduledTaskRunLogRepository;
    @MockBean private ShareLinkService shareLinkService;
    @MockBean private AiValueSummaryService aiValueSummaryService;

    @Autowired private JwtUtil jwtUtil;
    @Autowired private FactoryAccessValidator factoryAccessValidator;
    @Autowired private UserServiceImpl userService;
    @Autowired private ServletWebServerApplicationContext serverContext;
    @LocalServerPort private int port;

    private static final long ACTOR = 7001L;
    private static final long TARGET = 9001L;
    private static final long LEGACY = 9002L;
    private static final String RESET = "/api/mobile/auth/reset-password?username=victim&newPassword=Abc12345&factoryId=";

    private String hrAdmin;
    private String factoryAdmin;
    private String factoryHoldingPlatformRole;
    private String realPlatform;

    private record Reply(int status, String body) {
    }

    private static User user(long id, String role) {
        User user = new User();
        user.setId(id);
        user.setFactoryId("F001");
        user.setUsername("user" + id);
        user.setRoleCode(role);
        user.setPosition(role);
        user.setIsActive(true);
        return user;
    }

    @BeforeEach
    void identities() {
        when(userRepository.findById(ACTOR)).thenReturn(Optional.of(user(ACTOR, "hr_admin")));
        when(userRepository.findById(TARGET)).thenReturn(Optional.of(user(TARGET, "operator")));
        when(userRepository.findById(LEGACY)).thenReturn(Optional.of(user(LEGACY, "platform_admin")));
        when(userRepository.save(any(User.class))).thenAnswer(call -> {
            User saved = call.getArgument(0);
            if (saved.getId() == null) saved.setId(9100L);
            return saved;
        });
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(permissionService.hasAnyPermission(any(), any(String[].class))).thenReturn(true);
        when(permissionService.hasAllPermissions(any(), any(String[].class))).thenReturn(true);
        when(factoryConfigService.isModuleEnabled(anyString(), anyString())).thenReturn(true);
        when(userModuleAccessService.canAccessModule(any(), anyString())).thenReturn(true);
        when(dataScopeResolver.resolve(ACTOR)).thenReturn(
                DataScopeContext.builder().factoryId("F001").scope(DataScope.ALL).build());
        hrAdmin = jwtUtil.generateToken(ACTOR, "F001", "hr", "hr_admin");
        factoryAdmin = jwtUtil.generateToken(ACTOR, "F001", "boss", "factory_super_admin");
        factoryHoldingPlatformRole = jwtUtil.generateToken(ACTOR, "F001", "legacy", "platform_admin");
        realPlatform = jwtUtil.generatePlatformToken(1L, "platform_probe", "super_admin");
    }

    private Reply send(String verb, String target, String token, String json) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(10_000);
            byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
            StringBuilder request = new StringBuilder(verb).append(' ').append(target)
                    .append(" HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n");
            if (token != null) request.append("Authorization: Bearer ").append(token).append("\r\n");
            if (json != null) {
                request.append("Content-Type: application/json\r\nContent-Length: ").append(body.length).append("\r\n");
            }
            request.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(all);
            String text = all.toString(StandardCharsets.UTF_8);
            assertThat(text.length()).as("INSTRUMENT DEAD: no reply for %s", target).isGreaterThan(12);
            return new Reply(Integer.parseInt(text.substring(9, 12)), text.substring(Math.max(0, text.indexOf("\r\n\r\n"))));
        }
    }

    private int calls(Object mock, String method) {
        return (int) Mockito.mockingDetails(mock).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(method))
                .count();
    }

    private boolean received(Object mock, String value) {
        return Mockito.mockingDetails(mock).getInvocations().stream()
                .anyMatch(invocation -> Arrays.asList(invocation.getArguments()).contains(value));
    }

    @Test
    @DisplayName("factory HR cannot create a user holding a platform role, by roleCode or by position")
    void createCannotGrant() throws IOException {
        assertThat(serverContext.getWebServer())
                .as("INSTRUMENT DEAD: not the real servlet container").isInstanceOf(TomcatWebServer.class);
        String base = "{\"username\":\"new_user\",\"fullName\":\"n\",\"password\":\"Abc12345\",\"phone\":\"13800000000\",";

        assertThat(send("POST", "/api/mobile/F001/users", hrAdmin, base + "\"roleCode\":\"platform_admin\"}").status())
                .isEqualTo(403);
        assertThat(send("POST", "/api/mobile/F001/users", hrAdmin, base + "\"roleCode\":null,\"position\":\"super_admin\"}").status())
                .isEqualTo(403);
        assertThat(calls(userRepository, "save")).as("nothing may be stored").isZero();

        assertThat(send("POST", "/api/mobile/F001/users", hrAdmin, base + "\"roleCode\":\"operator\"}").status())
                .as("positive control: an ordinary factory role is created").isEqualTo(200);
        assertThat(calls(userRepository, "save")).isEqualTo(1);
    }

    @Test
    @DisplayName("factory HR cannot turn a user into a platform role, but can edit one that already holds it")
    void updateCannotGrant() throws IOException {
        assertThat(send("PUT", "/api/mobile/F001/users/" + TARGET, hrAdmin, "{\"roleCode\":\"platform_admin\"}").status())
                .isEqualTo(403);
        assertThat(send("PUT", "/api/mobile/F001/users/" + TARGET, hrAdmin, "{\"position\":\"super_admin\"}").status())
                .isEqualTo(403);
        assertThat(send("PUT", "/api/mobile/F001/users/" + TARGET + "/role?newRole=platform_admin", hrAdmin, null).status())
                .isEqualTo(403);
        assertThat(calls(userRepository, "save")).as("nothing may be stored").isZero();

        assertThat(send("PUT", "/api/mobile/F001/users/" + TARGET + "/role?newRole=operator", hrAdmin, null).status())
                .as("positive control: an ordinary role change works").isEqualTo(200);
        assertThat(send("PUT", "/api/mobile/F001/users/" + LEGACY, hrAdmin,
                "{\"phone\":\"13800000001\",\"roleCode\":\"platform_admin\"}").status())
                .as("keeping the value it already holds is not a grant").isEqualTo(200);
    }

    @Test
    @DisplayName("Unicode case aliases cannot become platform roles through either factory create field")
    void unicodeCaseAliasesCannotBeGranted() throws IOException {
        String base = "{\"username\":\"new_user\",\"fullName\":\"n\",\"password\":\"Abc12345\",\"phone\":\"13800000000\",";
        for (String role : new String[]{"platform_adm\u0131n", "\u017Fuper_admin", "\u017Fystem_admin", "operat\u0131on_admin", "aud\u0131tor"}) {
            assertThat(send("POST", "/api/mobile/F001/users", hrAdmin, base + "\"roleCode\":\"" + role + "\"}").status())
                    .as("roleCode alias %s must be refused by enum binding or the boundary", role).isIn(400, 403);
            assertThat(send("POST", "/api/mobile/F001/users", hrAdmin, base + "\"roleCode\":null,\"position\":\"" + role + "\"}").status())
                    .as("position alias %s must be refused", role).isEqualTo(403);
        }
        assertThat(calls(userRepository, "save")).isZero();
    }

    @Test
    @DisplayName("a legacy factory token with a Unicode platform alias cannot pass a platform role check")
    void legacyUnicodeRoleCannotGainPlatformAuthority() throws IOException {
        for (String role : new String[]{"platform_adm\u0131n", "\u017Fuper_admin", "factory_\u017Fuper_admin", "permi\u017Fsion_admin"}) {
            String token = jwtUtil.generateToken(ACTOR, "F001", "legacy", role);
            assertThat(send("POST", "/api/mobile/F001/share-links", token, "{}").body())
                    .as("case-insensitive alias %s must remain an untrusted factory role", role)
                    .contains("当前角色无权访问此接口");
        }
        assertThat(send("POST", "/api/mobile/F001/share-links", factoryAdmin, "{}").body())
                .as("positive control: ordinary factory admin still passes the role check")
                .doesNotContain("当前角色无权访问此接口");
    }

    @Test
    @DisplayName("reset-password: identity decides, not the role string")
    void resetPasswordFollowsIdentity() throws IOException {
        assertThat(send("POST", RESET + "F002", factoryHoldingPlatformRole, null).status())
                .as("a factory token holding platform_admin cannot reach another factory").isEqualTo(403);
        assertThat(send("POST", RESET + "F001", factoryHoldingPlatformRole, null).status())
                .as("that role is not a factory admin role either").isEqualTo(403);
        assertThat(send("POST", RESET + "F002", factoryAdmin, null).status()).isEqualTo(403);
        assertThat(send("POST", RESET + "F002", null, null).status()).isIn(401, 403);
        assertThat(calls(mobileService, "resetPassword")).as("no password may change").isZero();

        assertThat(send("POST", RESET + "F001", factoryAdmin, null).status())
                .as("a factory admin resets their own factory").isEqualTo(200);
        assertThat(calls(mobileService, "resetPassword")).isEqualTo(1);
        assertThat(send("POST", RESET + "F001", hrAdmin, null).status()).isEqualTo(200);
        assertThat(calls(mobileService, "resetPassword")).isEqualTo(2);
        assertThat(send("POST", RESET + "F002", realPlatform, null).status())
                .as("a real platform token still can").isEqualTo(200);
        assertThat(calls(mobileService, "resetPassword")).isEqualTo(3);
    }

    @Test
    @DisplayName("an existing account holding the role keeps working in its factory and gains nothing outside it")
    void holdingTheRoleGivesNoPlatformAuthority() throws IOException {
        assertThat(send("GET", "/api/mobile/F001/ai/value-summary", factoryHoldingPlatformRole, null).status())
                .as("its token is still accepted in its own factory").isEqualTo(200);
        assertThat(send("GET", "/api/mobile/F002/ai/value-summary", factoryHoldingPlatformRole, null).status()).isEqualTo(403);

        String share = "/api/mobile/F001/share-links";
        assertThat(send("POST", share, factoryHoldingPlatformRole, "{}").body())
                .as("@RequireRole gives a role-named factory token nothing").contains("当前角色无权访问此接口");
        assertThat(send("POST", share, factoryAdmin, "{}").body())
                .as("positive control: a listed factory role passes the role check").doesNotContain("当前角色无权访问此接口");

        send("GET", "/api/mobile/scheduled-tasks?factoryId=F002&enabled=true", factoryHoldingPlatformRole, null);
        assertThat(received(scheduledTaskRepository, "F002")).as("it stays on its own factory's tasks").isFalse();
        send("GET", "/api/mobile/scheduled-tasks?factoryId=F002&enabled=true", realPlatform, null);
        assertThat(received(scheduledTaskRepository, "F002")).as("positive control: a real platform token reads it").isTrue();
    }

    @Test
    @DisplayName("a spreadsheet import cannot carry a platform role in the position column")
    void importCannotGrant() {
        com.cretas.aims.dto.user.UserExportDTO row = new com.cretas.aims.dto.user.UserExportDTO();
        row.setUsername("imported");
        row.setPosition("super_admin");
        assertThatThrownBy(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                userService, "convertFromExportDTO", row, "F001"))
                .hasMessageContaining("平台角色不能分配给工厂账号");
        row.setPosition("班组长");
        assertThatCode(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                userService, "convertFromExportDTO", row, "F001"))
                .as("positive control: an ordinary job title imports").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FactoryAccessValidator: a role on the users row grants nothing, a platform identity does")
    void accessValidatorFollowsIdentity() {
        when(userRepository.findById(LEGACY)).thenReturn(Optional.of(user(LEGACY, "platform_admin")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, LEGACY, "F001", "platform_admin"));
            assertThatThrownBy(() -> factoryAccessValidator.validateAccess("F002", LEGACY))
                    .isInstanceOf(AuthorizationException.class);
            assertThatCode(() -> factoryAccessValidator.validateAccess("F001", LEGACY)).doesNotThrowAnyException();

            request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                    new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "super_admin"));
            assertThatCode(() -> factoryAccessValidator.validateAccess("F002", 1L)).doesNotThrowAnyException();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("the public spreadsheet importer fails restricted rows and continues with ordinary rows")
    void spreadsheetImportKeepsTheRoleBoundary() throws Exception {
        var restricted = new com.cretas.aims.dto.user.UserExportDTO();
        restricted.setUsername("restricted_import");
        restricted.setPosition("platform_adm\u0131n");
        var ordinary = new com.cretas.aims.dto.user.UserExportDTO();
        ordinary.setUsername("ordinary_import");
        ordinary.setPosition("班组长");
        when(excelUtil.importFromExcel(any(java.io.InputStream.class),
                org.mockito.ArgumentMatchers.eq(com.cretas.aims.dto.user.UserExportDTO.class)))
                .thenReturn(java.util.List.of(restricted, ordinary));
        var result = userService.importUsersFromExcel("F001", new java.io.ByteArrayInputStream(new byte[0]));
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        var saved = org.mockito.ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("ordinary_import");
    }
}
