package com.cretas.aims.config;

import com.cretas.aims.CretasBackendApplication;
import com.cretas.aims.audit.PlatformDataAccessLogInterceptor;
import com.cretas.aims.audit.PlatformDataAccessLogWriter;
import com.cretas.aims.audit.RequestAuditFilterConfiguration;
import com.cretas.aims.audit.RequestAuditInterceptor;
import com.cretas.aims.audit.RequestAuditWebMvcConfiguration;
import com.cretas.aims.blueprint.platform.PlatformAssemblyAccessConfiguration;
import com.cretas.aims.controller.AiValueSummaryController;
import com.cretas.aims.controller.CustomerCreditController;
import com.cretas.aims.controller.FactoryBlueprintController;
import com.cretas.aims.controller.ProductCostVarianceConfigController;
import com.cretas.aims.controller.UserCountController;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.filter.CorrelationIdFilter;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.bom.ProductCostVarianceConfigRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.DataScopeContext;
import com.cretas.aims.security.DataScopeResolver;
import com.cretas.aims.security.DataScopeRouteInterceptor;
import com.cretas.aims.security.ProcessSecretGuardInterceptor;
import com.cretas.aims.service.FactoryBlueprintService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.aivalue.AiValueSummaryService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.crm.CustomerCreditCheckService;
import com.cretas.aims.service.datacenter.OperationLogService;
import com.cretas.aims.service.mobile.WebLoginSessionService;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.utils.CookieAuthHelper;
import com.cretas.aims.utils.JwtUtil;
import org.apache.catalina.Context;
import org.apache.tomcat.util.descriptor.web.FilterMap;
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
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real embedded Tomcat + the real {@link WebMvcConfig} interceptor chain, driven over a raw
 * socket so that no HTTP client normalises the path before Tomcat sees it. Only repositories
 * and services are stubbed; "the controller ran" is read from the stubbed service each probe
 * route calls.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = AmbiguousRequestPathEmbeddedTomcatContractTest.Harness.class,
        properties = {
                "cretas.jwt.secret=embedded-tomcat-test-secret-0123456789-0123456789-0123456789",
                "spring.main.banner-mode=off"
        })
@DisplayName("Ambiguous request paths through real Tomcat and the real gates")
class AmbiguousRequestPathEmbeddedTomcatContractTest {

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
            AmbiguousRequestPathFilter.class,
            PriceSensitiveContextFilter.class,
            PackagePrivateFilters.class,
            WebMvcConfig.class,
            JwtAuthInterceptor.class,
            DemoReadOnlyInterceptor.class,
            PermissionInterceptor.class,
            RequireRoleInterceptor.class,
            ModuleEnabledInterceptor.class,
            DataScopeRouteInterceptor.class,
            ProcessSecretGuardInterceptor.class,
            PlatformDataAccessLogInterceptor.class,
            WebLoginSessionFilter.class,
            CorrelationIdFilter.class,
            RequestAuditFilterConfiguration.class,
            RequestAuditWebMvcConfiguration.class,
            RequestAuditInterceptor.class,
            PlatformAssemblyAccessConfiguration.class,
            PlatformAssemblyAccessConfiguration.CapabilitiesController.class,
            JwtUtil.class,
            CookieAuthHelper.class,
            FactoryBlueprintController.class,
            GlobalExceptionHandler.class,
            AiValueSummaryController.class,
            CustomerCreditController.class,
            ProductCostVarianceConfigController.class,
            UserCountController.class
    })
    static class Harness {
    }

    /** ClientErrorBodyLimitFilter is package-private, so it can only be named. */
    static class PackagePrivateFilters implements ImportSelector {
        @Override
        public String[] selectImports(AnnotationMetadata metadata) {
            return new String[]{CLIENT_ERROR_FILTER};
        }
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
    @MockBean private FactoryBlueprintService blueprintService;
    @MockBean private MobileService mobileService;
    @MockBean private CustomerCreditCheckService creditCheckService;
    @MockBean private AiValueSummaryService aiValueSummaryService;
    @MockBean private OperationLogService operationLogService;
    @MockBean private ProductCostVarianceConfigRepository varianceConfigRepository;
    @MockBean private PlatformAdminRepository platformAdminRepository;

    @Autowired private JwtUtil jwtUtil;
    @Autowired private ServletWebServerApplicationContext serverContext;
    @LocalServerPort private int port;

    private static final String CLIENT_ERROR_FILTER = "com.cretas.aims.audit.client.ClientErrorBodyLimitFilter";
    private static final long FACTORY_USER_ID = 7001L;
    private static final String PLATFORM_TAIL = "/blueprints";
    /** Not in DataScopeRouteInterceptor.RESOURCES, no annotation guard: the JWT factory check is all it has. */
    private static final String TENANT_TAIL = "/ai/value-summary";
    private static final String INTERNAL_TAIL = "/users/admin-count?factoryId=F002";

    private String factoryToken;
    private String platformToken;

    record Response(int status, String body) {
    }

    @BeforeEach
    void identities() {
        User user = new User();
        user.setId(FACTORY_USER_ID);
        user.setFactoryId("F001");
        user.setIsActive(true);
        when(userRepository.findById(FACTORY_USER_ID)).thenReturn(Optional.of(user));
        when(userRepository.countByFactoryIdAndRoleCodeIn(anyString(), any())).thenReturn(3L);
        when(dataScopeResolver.resolve(FACTORY_USER_ID)).thenReturn(
                DataScopeContext.builder().factoryId("F001").scope(DataScope.ALL).build());
        factoryToken = jwtUtil.generateToken(FACTORY_USER_ID, "F001", "factory_probe", "factory_super_admin");
        platformToken = jwtUtil.generatePlatformToken(1L, "platform_probe", "super_admin");
    }

    private Response send(String rawTarget, String token) throws IOException {
        return send("GET", rawTarget, token, null);
    }

    private Response send(String method, String rawTarget, String token, String json) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(10_000);
            StringBuilder request = new StringBuilder()
                    .append(method).append(' ').append(rawTarget).append(" HTTP/1.1\r\n")
                    .append("Host: 127.0.0.1\r\n")
                    .append("Connection: close\r\n");
            if (token != null) {
                request.append("Authorization: Bearer ").append(token).append("\r\n");
            }
            if (json != null) {
                request.append("Content-Type: application/json\r\n")
                        .append("Content-Length: ").append(json.getBytes(StandardCharsets.UTF_8).length).append("\r\n");
            }
            request.append("\r\n");
            if (json != null) request.append(json);
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            in.transferTo(all);
            String text = all.toString(StandardCharsets.UTF_8);
            int firstSpace = text.indexOf(' ');
            assertThat(firstSpace).as("INSTRUMENT DEAD: no status line for %s", rawTarget).isGreaterThan(0);
            int status = Integer.parseInt(text.substring(firstSpace + 1, firstSpace + 4));
            int bodyAt = text.indexOf("\r\n\r\n");
            return new Response(status, bodyAt < 0 ? "" : text.substring(bodyAt + 4));
        }
    }

    private int calls(Object mock, String method) {
        return (int) Mockito.mockingDetails(mock).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals(method))
                .count();
    }

    private static String layer(Response response, boolean ran) {
        String body = response.body();
        if (ran) return "NONE-controller-ran";
        if (body.contains("请求路径不规范")) return "AmbiguousRequestPathFilter";
        if (body.contains("无权访问平台管理API")) return "JwtAuthInterceptor.platform";
        if (body.contains("无权访问该工厂数据")) return "JwtAuthInterceptor.factory";
        if (body.contains("内部API认证失败")) return "JwtAuthInterceptor.internal";
        if (body.contains("未授权")) return "JwtAuthInterceptor.401";
        if (body.contains("Apache Tomcat") || body.contains("<html")) return "Tomcat";
        if (response.status() == 404) return "Spring-no-handler";
        return "other";
    }

    private record Outcome(String target, Response response, boolean ran) {
    }

    private Outcome probe(String target, String token, Object mock, String method) throws IOException {
        int before = calls(mock, method);
        Response response = send(target, token);
        return new Outcome(target, response, calls(mock, method) > before);
    }

    private Outcome platform(String target) throws IOException {
        return probe(target, factoryToken, blueprintService, "getAllBlueprints");
    }

    private Outcome tenant(String target, String token) throws IOException {
        return probe(target, token, aiValueSummaryService, "summarize");
    }

    private Outcome internal(String target) throws IOException {
        return probe(target, null, userRepository, "countByFactoryIdAndRoleCodeIn");
    }

    private static void assertRefusedByFilter(Outcome outcome) {
        assertThat(outcome.ran()).as("controller must not run: %s", outcome.target()).isFalse();
        assertThat(outcome.response().status()).as("status of %s", outcome.target()).isEqualTo(400);
        assertThat(outcome.response().body()).as("body of %s", outcome.target()).contains("请求路径不规范");
    }

    private static List<String> variantsOf(String area, String tail) {
        String first = area.substring(0, 1);
        String rest = area.substring(1);
        String hex = Integer.toHexString(first.charAt(0));
        String upper = area.toUpperCase(Locale.ROOT);
        String title = first.toUpperCase(Locale.ROOT) + rest;
        return List.of(
                "/api/%" + hex + rest + tail,
                "/%61pi/" + area + tail,
                "/api/%25" + hex + rest + tail,
                "/api%2F" + area + tail,
                "/api%5C" + area + tail,
                "/API/" + upper + tail,
                "/Api/" + title + tail,
                "//api/" + area + tail,
                "/api//" + area + tail,
                "/api/" + area + "/" + tail,
                "/api;x=1/" + area + tail,
                "/api/" + area + ";x=1" + tail,
                "/api/./" + area + tail,
                "/api/x/../" + area + tail,
                "/api/%2e/" + area + tail,
                "/api/x/%2e%2e/" + area + tail);
    }

    @Test
    @DisplayName("positive controls: plain paths are judged by the gates, so a refusal or a pass means something")
    void plainPathsAreJudgedByTheGates() throws IOException {
        assertThat(serverContext.getWebServer())
                .as("INSTRUMENT DEAD: not the real servlet container").isInstanceOf(TomcatWebServer.class);

        Outcome factoryOnPlatform = platform("/api/platform" + PLATFORM_TAIL);
        assertThat(factoryOnPlatform.response().status()).isEqualTo(403);
        assertThat(factoryOnPlatform.response().body()).contains("无权访问平台管理API");
        assertThat(factoryOnPlatform.ran()).isFalse();

        Outcome platformOnPlatform = probe("/api/platform" + PLATFORM_TAIL, platformToken, blueprintService, "getAllBlueprints");
        assertThat(platformOnPlatform.response().status()).isEqualTo(200);
        assertThat(platformOnPlatform.ran()).isTrue();

        Outcome otherFactory = tenant("/api/mobile/F002" + TENANT_TAIL, factoryToken);
        assertThat(otherFactory.response().status()).isEqualTo(403);
        assertThat(otherFactory.response().body()).contains("无权访问该工厂数据");
        assertThat(otherFactory.ran()).isFalse();

        Outcome ownFactory = tenant("/api/mobile/F001" + TENANT_TAIL, factoryToken);
        assertThat(ownFactory.response().status()).isEqualTo(200);
        assertThat(ownFactory.ran()).isTrue();

        assertThat(tenant("/api/mobile/F002" + TENANT_TAIL, null).response().status()).isEqualTo(401);

        Outcome noKey = internal("/api/internal" + INTERNAL_TAIL);
        assertThat(noKey.response().status()).isEqualTo(403);
        assertThat(noKey.response().body()).contains("内部API认证失败");
        assertThat(noKey.ran()).isFalse();
    }

    @Test
    @DisplayName("every variant that reached a controller before the fix is refused with 400")
    void reproducedVariantsAreRefused() throws IOException {
        for (String target : List.of("/api/%70latform", "/api/%70LATFORM", "/%61pi/platform", "/api;x=1/platform")) {
            assertRefusedByFilter(platform(target + PLATFORM_TAIL));
        }
        for (String target : List.of("/api/%6dobile", "/api/%6Dobile", "/%61pi/mobile",
                "/api;x=1/mobile", "/api/mobile;x=1")) {
            assertRefusedByFilter(tenant(target + "/F002" + TENANT_TAIL, factoryToken));
            assertRefusedByFilter(tenant(target + "/F002" + TENANT_TAIL, null));
        }
        for (String target : List.of("/api/%69nternal", "/%61pi/internal", "/api;x=1/internal", "/api/internal;x=1")) {
            assertRefusedByFilter(internal(target + INTERNAL_TAIL));
        }
    }

    @Test
    @DisplayName("no variant reaches a controller: %2F, case, double slashes, semicolons, dot segments")
    void noVariantReachesAController() throws IOException {
        List<String> rows = new ArrayList<>();
        rows.add("gate\ttoken\ttarget\tstatus\tcontroller_ran\trefused_by");
        List<Outcome> outcomes = new ArrayList<>();
        for (String target : variantsOf("platform", PLATFORM_TAIL)) {
            Outcome outcome = platform(target);
            outcomes.add(outcome);
            rows.add(row("platform", "factory", outcome));
        }
        List<String> tenantTargets = new ArrayList<>(variantsOf("mobile", "/F002" + TENANT_TAIL));
        tenantTargets.addAll(List.of(
                "/api/mobile/%46002" + TENANT_TAIL,
                "/api/mobile/F002;x=1" + TENANT_TAIL,
                "/api/mobile//F002" + TENANT_TAIL,
                "/api/mobile/F001/../F002" + TENANT_TAIL,
                "/api/mobile/F001/%2e%2e/F002" + TENANT_TAIL,
                "/api/mobile/F001;/../F002" + TENANT_TAIL));
        for (String target : tenantTargets) {
            Outcome withToken = tenant(target, factoryToken);
            Outcome anonymous = tenant(target, null);
            outcomes.add(withToken);
            outcomes.add(anonymous);
            rows.add(row("tenant", "factory-F001", withToken));
            rows.add(row("tenant", "anonymous", anonymous));
        }
        for (String target : variantsOf("internal", INTERNAL_TAIL)) {
            Outcome outcome = internal(target);
            outcomes.add(outcome);
            rows.add(row("internal", "anonymous", outcome));
        }

        String report = System.getProperty("pathenc.report");
        if (report != null) {
            Files.write(Path.of(report), rows, StandardCharsets.UTF_8);
        }
        assertThat(outcomes).as("INSTRUMENT DEAD: no variants were sent").hasSizeGreaterThan(50);
        for (Outcome outcome : outcomes) {
            assertThat(outcome.ran()).as("controller must not run: %s", outcome.target()).isFalse();
            assertThat(outcome.response().status()).as("status of %s", outcome.target()).isIn(400, 401, 403, 404);
        }
    }

    private static String row(String gate, String token, Outcome outcome) {
        return String.join("\t", gate, token, outcome.target(), String.valueOf(outcome.response().status()),
                String.valueOf(outcome.ran()), layer(outcome.response(), outcome.ran()));
    }

    @Test
    @DisplayName("encoded values real clients send still reach the controller, decoded")
    void legalEncodedValuesStillWork() throws IOException {
        // %3B reaches Java as %3B only through a raw-passing vhost; see the filter's javadoc.
        Outcome encodedId = probe("/api/mobile/F001/customers/A%3Bb%20c%2B%25%E4%B8%AD/credit-status",
                factoryToken, creditCheckService, "checkCreditAvailable");
        assertThat(encodedId.response().status()).isEqualTo(200);
        assertThat(encodedId.ran()).isTrue();
        verify(creditCheckService).checkCreditAvailable("F001", "A;b c+%中", BigDecimal.ZERO);

        Outcome encodedQuery = probe("/api/platform/blueprints?industryType=%70;%E9%A3%9F%E5%93%81",
                platformToken, blueprintService, "getBlueprintsByIndustryType");
        assertThat(encodedQuery.response().status()).isEqualTo(200);
        assertThat(encodedQuery.ran()).isTrue();
    }

    @Test
    @DisplayName("encoded control bytes in a path are refused and never reach a controller")
    void encodedControlBytesAreRefused() throws IOException {
        for (String target : List.of("/api/mobile/F001%00/ai/value-summary", "/api/mobile/F001%0a/ai/value-summary",
                "/api/mobile/F001%0D/ai/value-summary", "/api/mobile/F001%7f/ai/value-summary")) {
            Outcome outcome = tenant(target, factoryToken);
            assertThat(outcome.ran()).as("controller must not run: %s", target).isFalse();
            assertThat(outcome.response().status()).as("status of %s", target).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("a write is logged on the plain path and cannot run unlogged on an ambiguous one")
    void writesCannotEscapeTheOperationLog() throws IOException {
        String body = "{\"remark\":\"probe\"}";
        int logsBefore = calls(operationLogService, "record");
        int readsBefore = calls(varianceConfigRepository, "findById");
        send("PUT", "/api/mobile/F001/bom/variance-configs/1", factoryToken, body);
        assertThat(calls(varianceConfigRepository, "findById")).isEqualTo(readsBefore + 1);
        verify(operationLogService, timeout(2_000).times(logsBefore + 1)).record(any());

        for (String target : List.of("/api/%6dobile", "/api;x=1/mobile", "/api/mobile;x=1")) {
            Response response = send("PUT", target + "/F001/bom/variance-configs/1", factoryToken, body);
            assertThat(response.status()).as("status of %s", target).isEqualTo(400);
        }
        assertThat(calls(varianceConfigRepository, "findById")).isEqualTo(readsBefore + 1);
        assertThat(calls(operationLogService, "record")).isEqualTo(logsBefore + 1);
    }

    @Test
    @DisplayName("the path rule itself, including what Tomcat happens to stop first today")
    void pathRule() {
        for (String ambiguous : List.of("/a;b", "/a/;", "/%41", "/%7a", "/%30", "/%2d", "/%2E", "/%5f",
                "/x%2Fy", "/x%2fy", "/x%5Cy", "/x%5cy", "/x%20/%70latform",
                "/a%00b", "/a%0Ab", "/a%0db", "/a%1Fb", "/a%7Fb")) {
            assertThat(AmbiguousRequestPathFilter.isAmbiguous(ambiguous)).as(ambiguous).isTrue();
        }
        for (String legal : List.of("/api/mobile/F001/orders", "/a%3Bb", "/a%20b", "/a%25b", "/a%2Bb",
                "/%E4%B8%AD", "/a%7Eb", "/a%21b", "/a%", "/a%4", "/a%zz", "/a.b-c_d")) {
            assertThat(AmbiguousRequestPathFilter.isAmbiguous(legal)).as(legal).isFalse();
        }
        assertThat(AmbiguousRequestPathFilter.isAmbiguous(null)).isFalse();
    }

    @Test
    @DisplayName("wiring: picked up by the application's component scan, ahead of filters that read the raw path")
    void filterIsWiredIntoTheRealApplication() throws ClassNotFoundException {
        assertThat(AnnotationUtils.findAnnotation(AmbiguousRequestPathFilter.class, Component.class))
                .as("the harness imports this class by hand; only @Component registers it in production")
                .isNotNull();
        assertThat(AmbiguousRequestPathFilter.class.getPackageName())
                .startsWith(CretasBackendApplication.class.getPackageName());
        assertThat(OrderUtils.getOrder(AmbiguousRequestPathFilter.class))
                .isGreaterThan(Ordered.HIGHEST_PRECEDENCE)
                .isLessThan(OrderUtils.getOrder(Class.forName(CLIENT_ERROR_FILTER)))
                .isLessThan(OrderUtils.getOrder(WebLoginSessionFilter.class));
    }

    @Test
    @DisplayName("the running Tomcat context maps this filter on /*, behind the correlation filter, ahead of raw-path readers")
    void runningContextMapsTheFilter() {
        Context context = (Context) ((TomcatWebServer) serverContext.getWebServer())
                .getTomcat().getHost().findChildren()[0];
        List<FilterMap> maps = Arrays.asList(context.findFilterMaps());
        int mine = position(maps, "AmbiguousRequestPathFilter");
        assertThat(maps.get(mine).getURLPatterns()).containsExactly("/*");
        assertThat(mine).isGreaterThan(position(maps, "correlationAuditFilter"));
        assertThat(mine).isGreaterThan(position(maps, "PriceSensitiveContextFilter"));
        assertThat(mine).isLessThan(position(maps, "ClientErrorBodyLimitFilter"));
        assertThat(mine).isLessThan(position(maps, "WebLoginSessionFilter"));
    }

    private static int position(List<FilterMap> maps, String name) {
        for (int i = 0; i < maps.size(); i++) {
            if (maps.get(i).getFilterName().toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) return i;
        }
        throw new AssertionError("INSTRUMENT DEAD: no filter map for " + name + " in "
                + maps.stream().map(FilterMap::getFilterName).toList());
    }
}
