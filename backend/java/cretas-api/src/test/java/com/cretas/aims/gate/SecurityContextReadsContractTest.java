package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸: src/main 里不许读 Spring Security 的 SecurityContext。
 *
 * <p>本项目排除了 SecurityAutoConfiguration, 全仓没有任何代码填充 SecurityContextHolder, 身份只在
 * JwtAuthInterceptor 写的 request attribute 里(userId/username/factoryId/role, 用 SecurityUtils 读)。
 * 读 SecurityContext 恒得空: 取操作人得 "system", 取角色得 false —— 要么审计失真要么管理员被误拒。
 * 前提(无人填充)由 {@link CurrentUserIdSourceGateContractTest} 钉住; 前提变了请两个闸一起重评。
 */
class SecurityContextReadsContractTest {

    private static final Path MAIN = Paths.get("src/main/java");

    private static final Pattern SECURITY_CONTEXT_READ = Pattern.compile(
            "SecurityContextHolder|org\\.springframework\\.security\\.core\\.Authentication\\b"
                    + "|@AuthenticationPrincipal|\\.getAuthentication\\(\\)");

    static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return Pattern.compile("//.*$", Pattern.MULTILINE).matcher(noBlock).replaceAll("");
    }

    @Test
    @DisplayName("src/main 不读 SecurityContext (剥注释后 0 处)")
    void noSecurityContextReadsInMain() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(MAIN)) {
            offenders = files.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                try {
                    return SECURITY_CONTEXT_READ.matcher(
                            stripComments(Files.readString(p, StandardCharsets.UTF_8))).find();
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }).map(p -> MAIN.relativize(p).toString().replace('\\', '/')).toList();
        }
        assertThat(offenders)
                .as("这些文件读取了 SecurityContext, 而本项目从不填充它, 读到的恒为空。"
                        + "改用 SecurityUtils.currentUserId()/currentUsername()/currentFactoryId()/currentRole()/hasRole()。")
                .isEmpty();
    }

    @Test
    @DisplayName("阳性对照: 扫描器能命中各种读法")
    void scannerFindsReads() {
        assertThat(SECURITY_CONTEXT_READ.matcher("SecurityContextHolder.getContext()").find()).isTrue();
        assertThat(SECURITY_CONTEXT_READ.matcher("x.getAuthentication().getName()").find()).isTrue();
        assertThat(SECURITY_CONTEXT_READ.matcher("@AuthenticationPrincipal User u").find()).isTrue();
        assertThat(SECURITY_CONTEXT_READ.matcher(
                "import org.springframework.security.core.Authentication;").find()).isTrue();
    }

    @Test
    @DisplayName("阴性对照: 注释里的说明文字不算, 密码编码器/AccessDeniedException 不算")
    void scannerIgnoresCommentsAndUnrelatedSecurityClasses() {
        String code = stripComments("// SecurityContextHolder 永空\n/* getAuthentication() */\n"
                + "import org.springframework.security.crypto.password.PasswordEncoder;\n"
                + "import org.springframework.security.access.AccessDeniedException;\n");
        assertThat(SECURITY_CONTEXT_READ.matcher(code).find()).isFalse();
    }
}
