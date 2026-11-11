package com.cretas.aims.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads compiled bytecode, so comments and strings in documentation cannot satisfy or trip it.
 *
 * <p>Proxy, stated plainly: a platform role literal in a class is the stand-in for "this code may
 * trust a role string". It sees a new literal, not a role read from configuration, and it cannot
 * tell what a listed class does with the literal. Listed classes were classified from the lines
 * that use it, not read whole. {@code ai/tool/} is exempt as a package and has not been audited.
 */
@DisplayName("Gate: platform roles stay off factory users, and role strings grant no platform authority")
class PlatformRoleBoundaryGateContractTest {

    private static final Path CLASSES = Path.of("target/classes/com/cretas/aims");
    private static final String USER = "com/cretas/aims/entity/User";
    private static final String BOUNDARY = "com/cretas/aims/security/PlatformRoleBoundary";
    private static final Set<String> SETTERS = Set.of("setRoleCode", "setPosition");
    private static final Set<String> ROLE_LITERALS =
            Set.of("platform_admin", "super_admin", "developer", "platform_super_admin");
    private static final String AI_TOOLS = "ai/tool/";

    /**
     * Writes a factory user's role or position from input, with the number of boundary calls the
     * method must make: one per value it writes, so losing one of two cannot hide behind the other.
     */
    private static final Map<String, Integer> GUARDED_WRITERS = Map.of(
            "mapper/UserMapper#toEntity", 2,
            "mapper/UserMapper#updateEntity", 2,
            "service/impl/UserServiceImpl#convertFromExportDTO", 2,
            "service/impl/UserServiceImpl#updateUserRole", 1,
            "service/mobile/impl/MobileAuthServiceImpl#registerPhaseTwo", 2);

    /** Writes only fixed values, or a throwaway object that is never stored. */
    private static final Map<String, String> FIXED_WRITERS = Map.of(
            "service/impl/UserServiceImpl#provisionDefaultUsers", "fixed factory roles only",
            "service/platform/PlatformFactoryAccessService#roleLevels", "synthetic User, never stored");

    private static final String DEFINITION = "defines the role set or enum";
    private static final String PLATFORM_IDENTITY_FIRST = "behind a typed platform actor or /api/platform";
    private static final String IN_FACTORY = "role allow-list inside the caller's own factory, crosses no factory";

    /** Classes outside AI tools that name a platform role, classified from the lines that use it. */
    private static final Map<String, String> ROLE_LITERAL_CLASSES = Map.ofEntries(
            Map.entry("entity/enums/FactoryUserRole", DEFINITION),
            Map.entry("entity/enums/PlatformRole", DEFINITION),
            Map.entry("security/AuthenticatedActor", DEFINITION),
            Map.entry("security/PlatformRoleBoundary", DEFINITION),
            Map.entry("config/JwtAuthInterceptor", "role consulted only when the token is an explicit platform token"),
            Map.entry("config/RequireRoleInterceptor", "platform set consulted only for a typed platform actor"),
            Map.entry("utils/JwtUtil", "view-token check also requires actorType PLATFORM_ADMIN and factoryId PLATFORM"),
            Map.entry("client/PythonSmartBIClient", "constant sent on a platform-only call"),
            Map.entry("controller/PlatformAssemblyInterviewController", PLATFORM_IDENTITY_FIRST),
            Map.entry("controller/TemplatePackageController", PLATFORM_IDENTITY_FIRST),
            Map.entry("controller/platform/PlatformFactoryMenuController", PLATFORM_IDENTITY_FIRST),
            Map.entry("controller/vision/PlatformVisionTrainingController", PLATFORM_IDENTITY_FIRST),
            Map.entry("service/factorydata/FactoryDataService", PLATFORM_IDENTITY_FIRST),
            Map.entry("service/platform/PlatformFactoryControlAuthority", PLATFORM_IDENTITY_FIRST),
            Map.entry("service/platform/PlatformFactoryAccessService", PLATFORM_IDENTITY_FIRST),
            Map.entry("service/permission/PlatformRolePermissionWriteService", PLATFORM_IDENTITY_FIRST),
            Map.entry("service/permission/PermissionMatrixViewService", "labels a role in a matrix view, grants nothing"),
            Map.entry("controller/FormAssistantController", IN_FACTORY),
            Map.entry("security/DataScopeResolver", IN_FACTORY),
            Map.entry("security/RoleDataScopeService", IN_FACTORY),
            Map.entry("service/DisposalRecordService", IN_FACTORY),
            Map.entry("service/agentops/AgentOpsService", IN_FACTORY),
            Map.entry("service/factory/impl/FactoryStocktakeServiceImpl", IN_FACTORY),
            Map.entry("service/factory/impl/SemiFinishedStocktakeServiceImpl", IN_FACTORY),
            Map.entry("service/impl/MaterialBatchServiceImpl", IN_FACTORY),
            Map.entry("service/yield/impl/InterimSettleReversalRequestServiceImpl", IN_FACTORY));

    private record Scan(int classes, Map<String, Integer> writers, Set<String> roleLiteralClasses, Set<String> aiTools) {
    }

    private Scan scan() throws IOException {
        Map<String, Integer> writers = new TreeMap<>();
        Set<String> literals = new TreeSet<>();
        Set<String> tools = new TreeSet<>();
        int[] classes = {0};
        List<Path> files;
        try (Stream<Path> walk = Files.walk(CLASSES)) {
            files = walk.filter(path -> path.toString().endsWith(".class")).toList();
        }
        for (Path file : files) {
            classes[0]++;
            String relative = CLASSES.relativize(file).toString().replace('\\', '/').replace(".class", "");
            try (InputStream in = Files.newInputStream(file)) {
                new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            boolean writes;
                            int guards;

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String callee, String desc, boolean itf) {
                                if (owner.equals(USER) && SETTERS.contains(callee)) writes = true;
                                if (owner.equals(BOUNDARY) && callee.equals("refuseOnFactoryUser")) guards++;
                            }

                            @Override
                            public void visitLdcInsn(Object value) {
                                if (value instanceof String text && ROLE_LITERALS.contains(text)) {
                                    (relative.startsWith(AI_TOOLS) ? tools : literals).add(relative);
                                }
                            }

                            @Override
                            public void visitEnd() {
                                if (writes) writers.put(relative + "#" + name, guards);
                            }
                        };
                    }
                }, 0);
            }
        }
        return new Scan(classes[0], writers, literals, tools);
    }

    @Test
    @DisplayName("every path that writes a user's role or position is registered, and guarded unless its value is fixed")
    void everyRoleWriteIsAccountedFor() throws IOException {
        Scan scan = scan();
        assertThat(scan.classes()).as("INSTRUMENT DEAD: run after compile").isGreaterThan(2000);
        assertThat(scan.writers().keySet()).as("a new path writes a user's role: guard it and register it here")
                .containsExactlyInAnyOrderElementsOf(union());
        GUARDED_WRITERS.forEach((writer, guards) -> assertThat(scan.writers().get(writer))
                .as("%s must call PlatformRoleBoundary.refuseOnFactoryUser once per value it writes", writer)
                .isEqualTo(guards));
    }

    private static Set<String> union() {
        Set<String> all = new TreeSet<>(GUARDED_WRITERS.keySet());
        all.addAll(FIXED_WRITERS.keySet());
        return all;
    }

    @Test
    @DisplayName("no new class names a platform role outside AI tools")
    void noNewRoleStringTrust() throws IOException {
        Scan scan = scan();
        assertThat(scan.roleLiteralClasses()).as("a new class names a platform role: use PlatformRoleBoundary or AuthenticatedActor")
                .containsExactlyInAnyOrderElementsOf(ROLE_LITERAL_CLASSES.keySet());
        assertThat(scan.aiTools()).as("INSTRUMENT DEAD: AI tools were not seen").isNotEmpty();
    }
}
