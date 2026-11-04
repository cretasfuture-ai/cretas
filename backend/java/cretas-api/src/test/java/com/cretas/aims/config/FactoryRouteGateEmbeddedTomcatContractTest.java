package com.cretas.aims.config;

import com.cretas.aims.CretasBackendApplication;
import com.cretas.aims.audit.PlatformDataAccessLogInterceptor;
import com.cretas.aims.audit.PlatformDataAccessLogWriter;
import com.cretas.aims.controller.AiValueSummaryController;
import com.cretas.aims.controller.MobileController;
import com.cretas.aims.controller.ShipmentController;
import com.cretas.aims.controller.share.PublicShareController;
import com.cretas.aims.dto.MobileDTO;
import com.cretas.aims.entity.ShipmentRecord;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.EquipmentAlertRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.security.DataScopeRouteInterceptor;
import com.cretas.aims.security.ProcessSecretGuardInterceptor;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.ShipmentRecordService;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.aivalue.AiValueSummaryService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.mobile.WebLoginSessionService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.share.ShareLinkService;
import com.cretas.aims.utils.CookieAuthHelper;
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
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A plain path must never hand a handler a factory the caller was not checked against. Real
 * embedded Tomcat and the real {@link WebMvcConfig} chain over a raw socket; only repositories
 * and services are stubbed, and "the handler ran" is read from the stubbed service it calls.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = FactoryRouteGateEmbeddedTomcatContractTest.Harness.class,
        properties = {
                "cretas.jwt.secret=embedded-tomcat-test-secret-0123456789-0123456789-0123456789",
                "spring.main.banner-mode=off"
        })
@DisplayName("The factory gate judges the factory the handler receives")
class FactoryRouteGateEmbeddedTomcatContractTest {

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
            AiValueSummaryController.class,
            MobileController.class,
            PublicShareController.class,
            ShipmentController.class
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
    @MockBean private MobileService mobileService;
    @MockBean private ShipmentRecordService shipmentRecordService;
    @MockBean private AiValueSummaryService aiValueSummaryService;
    @MockBean private ShareLinkService shareLinkService;

    @Autowired private JwtUtil jwtUtil;
    @Autowired private ServletWebServerApplicationContext serverContext;
    @LocalServerPort private int port;

    private static final long FACTORY_USER_ID = 7001L;
    private static final String MARKER = "ship-F002";
    private static final List<String> FIRST_SEGMENT_WORDS = List.of("public", "edge", "auth", "activation", "health",
            "upload", "voice", "ai", "scale-protocols", "dashboard", "workflow", "admin", "smartbi-config",
            "system", "scheduled-tasks");

    private String factoryToken;
    private String platformToken;

    private record Reply(int status, String body) {
    }

    @BeforeEach
    void identities() {
        User user = new User();
        user.setId(FACTORY_USER_ID);
        user.setFactoryId("F001");
        user.setIsActive(true);
        when(userRepository.findById(FACTORY_USER_ID)).thenReturn(Optional.of(user));
        when(dataScopeResolver.resolve(FACTORY_USER_ID)).thenReturn(
                DataScopeContext.builder().factoryId("F001").scope(DataScope.ALL).build());
        ShipmentRecord row = new ShipmentRecord();
        row.setId(MARKER);
        row.setFactoryId("F002");
        when(shipmentRecordService.getById(MARKER)).thenReturn(Optional.of(row));
        when(mobileService.getDashboardData(eq("F002"), any())).thenReturn(new MobileDTO.DashboardData());
        factoryToken = jwtUtil.generateToken(FACTORY_USER_ID, "F001", "factory_probe", "factory_super_admin");
        platformToken = jwtUtil.generatePlatformToken(1L, "platform_probe", "super_admin");
    }

    private Reply get(String target, String token) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(10_000);
            StringBuilder request = new StringBuilder("GET ").append(target)
                    .append(" HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n");
            if (token != null) {
                request.append("Authorization: Bearer ").append(token).append("\r\n");
            }
            request.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(all);
            String text = all.toString(StandardCharsets.UTF_8);
            int firstSpace = text.indexOf(' ');
            assertThat(firstSpace).as("INSTRUMENT DEAD: no status line for %s", target).isGreaterThan(0);
            return new Reply(Integer.parseInt(text.substring(firstSpace + 1, firstSpace + 4)),
                    text.substring(Math.max(0, text.indexOf("\r\n\r\n"))));
        }
    }

    private int calls(Object mock, String method) {
        return (int) Mockito.mockingDetails(mock).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(method))
                .count();
    }

    @Test
    @DisplayName("positive controls: plain factory paths, and every anonymous route that must stay open")
    void plainPathsAndRealAnonymousRoutes() throws IOException {
        assertThat(serverContext.getWebServer())
                .as("INSTRUMENT DEAD: not the real servlet container").isInstanceOf(TomcatWebServer.class);

        assertThat(get("/api/mobile/F001/ai/value-summary", factoryToken).status()).isEqualTo(200);
        assertThat(calls(aiValueSummaryService, "summarize")).isEqualTo(1);
        assertThat(get("/api/mobile/F002/ai/value-summary", factoryToken).status()).isEqualTo(403);
        assertThat(get("/api/mobile/F002/ai/value-summary", null).status()).isEqualTo(401);
        assertThat(calls(aiValueSummaryService, "summarize")).isEqualTo(1);

        // No route, so no routed factory: only the check on the URL's first segment can answer.
        assertThat(get("/api/mobile/F002/no-such-route", null).status()).isEqualTo(401);
        assertThat(get("/api/mobile/F002/no-such-route", factoryToken).status()).isEqualTo(403);

        assertThat(get("/api/mobile/public/share/any-token", null).status()).isEqualTo(200);
        assertThat(calls(shareLinkService, "resolveShareLink")).isEqualTo(1);
        assertThat(get("/api/mobile/health", null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("public and every first-segment word are refused as a factory id, anonymously and with a token")
    void noFirstSegmentWordPassesAsAFactory() throws IOException {
        assertThat(FIRST_SEGMENT_WORDS).as("INSTRUMENT DEAD: no words to send").hasSizeGreaterThan(10);
        for (String word : FIRST_SEGMENT_WORDS) {
            String target = "/api/mobile/" + word + "/shipments/" + MARKER;
            Reply anonymous = get(target, null);
            assertThat(anonymous.status()).as("anonymous %s", target).isEqualTo(401);
            assertThat(anonymous.body()).as("anonymous %s", target).doesNotContain(MARKER);
            Reply otherFactory = get(target, factoryToken);
            assertThat(otherFactory.status()).as("token %s", target).isEqualTo(403);
            assertThat(otherFactory.body()).as("token %s", target).doesNotContain(MARKER);
        }
        assertThat(calls(shipmentRecordService, "getById")).as("the handler must never run").isZero();
    }

    @Test
    @DisplayName("a reserved word in front of a real factory id: /api/mobile/dashboard/{factoryId}")
    void dashboardJudgesTheRoutedFactory() throws IOException {
        assertThat(get("/api/mobile/dashboard/F002", null).status()).isEqualTo(401);
        assertThat(get("/api/mobile/dashboard/F002", factoryToken).status()).isEqualTo(403);
        // A factory token that merely names a platform role is still a factory token.
        String namedPlatformRole = jwtUtil.generateToken(FACTORY_USER_ID, "F001", "factory_probe", "super_admin");
        assertThat(get("/api/mobile/dashboard/F002", namedPlatformRole).status()).isEqualTo(403);
        assertThat(calls(mobileService, "getDashboardData")).as("the handler must never run").isZero();

        assertThat(get("/api/mobile/dashboard/F001", factoryToken).status()).isEqualTo(200);
        assertThat(calls(mobileService, "getDashboardData")).isEqualTo(1);
        assertThat(get("/api/mobile/dashboard/F002", platformToken).status()).isEqualTo(200);
        assertThat(calls(mobileService, "getDashboardData")).isEqualTo(2);
    }

    @Test
    @DisplayName("the anonymous whitelist covers the share route and nothing that could be a factory route")
    void whitelistIsTheShareRouteOnly() throws Exception {
        assertThat(get("/api/mobile/public/share/a/b", null).status())
                .as("deeper than the share token is not whitelisted").isEqualTo(401);

        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<String> publicPatterns = new ArrayList<>();
        List<String> shareUnderFactory = new ArrayList<>();
        int scanned = 0;
        for (var candidate : scanner.findCandidateComponents(CretasBackendApplication.class.getPackageName())) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            RequestMapping onClass = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] bases = onClass == null || onClass.path().length == 0 ? new String[]{""} : onClass.path();
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping onMethod = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (onMethod == null) continue;
                String[] tails = onMethod.path().length == 0 ? new String[]{""} : onMethod.path();
                for (String base : bases) {
                    for (String tail : tails) {
                        scanned++;
                        String pattern = base + tail;
                        if (pattern.startsWith("/api/mobile/public/")) publicPatterns.add(pattern);
                        if (pattern.startsWith("/api/mobile/{factoryId}/share/")) shareUnderFactory.add(pattern);
                    }
                }
            }
        }
        assertThat(scanned).as("INSTRUMENT DEAD: controller scan found nothing").isGreaterThan(2000);
        assertThat(publicPatterns).containsExactly("/api/mobile/public/share/{token}");
        assertThat(shareUnderFactory)
                .as("POST /api/mobile/public/share/x would land here with the gates excluded").isEmpty();
    }
}
