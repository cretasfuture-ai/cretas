package com.cretas.aims.gate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份取法棘轮闸 —— 不许再长出新的 {@code SecurityUtils.getCurrentUserId()} 调用点。
 *
 * <h3>为什么需要它</h3>
 * 本项目<b>从不填充</b> Spring 的 {@code SecurityContextHolder}
 * （全仓 {@code setAuthentication} 出现 <b>0 次</b>），身份由 {@code JwtAuthInterceptor}
 * 写进 request attribute。于是 {@code getCurrentUserId()} 在生产上<b>恒为 null</b>。
 *
 * <p>这件事仓里<b>早就有人知道</b> —— FactoryBlueprintController / FactoryNetworkController /
 * WarehouseInventoryController / ProductTypeController 四处注释、
 * 三个测试类的 Javadoc 都白纸黑字写着「本项目永返 null」。
 * <b>但每一次都是就地绕开，没有人建闸</b>，于是 2026-08-29 又踩了一次
 * （{@code PUT /users/me/profile} 上线后返 401，而部署三条判据全绿）。
 *
 * <p>写进注释拦不住下一个人，写成闸才拦得住。
 *
 * <h3>当天量到的实际损害（都是生产上打出来的，不是推断）</h3>
 * <ul>
 *   <li>{@code GET /api/mobile/devices} → <b>500</b>
 *       （{@code ConcurrentHashMap.get(null)} NPE；而同一条日志行写着 {@code [u:1552]} ——
 *       请求<b>知道</b>你是谁，处理方法拿到 null）</li>
 *   <li>{@code GET /api/mobile/F006/reminders} → 恒 <b>0 条</b>，
 *       而库里 F006 有 <b>3 条</b>活提醒；铃铛 {@code badge.pending} 永远是 0</li>
 *   <li>{@code GET /api/mobile/F006/customer-price-history} → 拿着有权限的 admin token
 *       也返 <b>401「用户未登录」</b>（{@code FactoryAccessValidator.validateAccess(factoryId)} 单参版）</li>
 * </ul>
 */
class CurrentUserIdSourceGateContractTest {

    private static final Path MAIN = Paths.get("src/main/java/com/cretas/aims");

    /**
     * 登记表：仍然使用旧取法的地方 + <b>为什么故意不改</b>。
     *
     * <p>登记是留痕，不是豁免 —— 往这里加一行必须写清理由。
     */
    private static final Map<String, String> DELIBERATELY_NOT_MIGRATED = Map.of();

    private static final Pattern DEPRECATED_CALL =
            Pattern.compile("SecurityUtils\\.getCurrentUser(?:Id|name)\\(\\)");

    private static Stream<Path> javaSources() throws IOException {
        return Files.walk(MAIN).filter(p -> p.toString().endsWith(".java"));
    }

    /** 不数注释里的 —— 否则闸会把自己的说明文字数进去（本仓记过这条）。 */
    private static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return Pattern.compile("//.*$", Pattern.MULTILINE).matcher(noBlock).replaceAll("");
    }

    private static Map<String, Integer> deprecatedCallSites() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        try (Stream<Path> sources = javaSources()) {
            for (Path p : sources.toList()) {
                if (p.endsWith("SecurityUtils.java")) {
                    continue; // 实现本身
                }
                String code = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                Matcher m = DEPRECATED_CALL.matcher(code);
                int n = 0;
                while (m.find()) {
                    n++;
                }
                if (n > 0) {
                    found.put(MAIN.relativize(p).toString().replace('\\', '/'), n);
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("棘轮: 只有登记过的文件还能用 getCurrentUserId() —— 新增一处就红")
    void noNewDeprecatedCallSites() throws IOException {
        Map<String, Integer> found = deprecatedCallSites();

        assertThat(found.keySet())
                .as("发现未登记的 SecurityUtils.getCurrentUserId() / getCurrentUsername() 调用点。"
                        + " 这两个方法在本项目恒返回 null —— 本项目不填充 SecurityContextHolder,"
                        + " 身份由 JwtAuthInterceptor 写在 request attribute 里。"
                        + " 改用 SecurityUtils.currentUserId() / currentUsername() / currentFactoryId()。"
                        + " 如果确实要保留旧取法, 把文件加进 DELIBERATELY_NOT_MIGRATED 并写清理由 ——"
                        + " 登记是留痕, 不是豁免。")
                .containsExactlyInAnyOrderElementsOf(DELIBERATELY_NOT_MIGRATED.keySet());
    }

    @Test
    @DisplayName("阳性对照: 扫描器真的能在源码里找到这个调用 —— 否则「0 处」只是仪器瞎了")
    void scannerCanActuallyFindTheCall() {
        String syntheticViolation = "Long id = SecurityUtils.getCurrentUserId();";
        assertThat(DEPRECATED_CALL.matcher(syntheticViolation).find())
                .as("阳性对照必须命中旧身份取法，否则全仓 0 处只是扫描器瞎了")
                .isTrue();
    }

    @Test
    @DisplayName("阴性对照: 扫描器会剥掉注释 —— 不许把说明文字数成调用点")
    void scannerStripsComments() {
        String withComments = "class X {\n"
                + "    // SecurityUtils.getCurrentUserId() 在本项目永返 null\n"
                + "    /* 也不要用 SecurityUtils.getCurrentUsername() */\n"
                + "    void f() { Long id = SecurityUtils.currentUserId(); }\n"
                + "}\n";
        String stripped = stripComments(withComments);
        assertThat(stripped).doesNotContain("getCurrentUserId", "getCurrentUsername");
        assertThat(stripped).contains("currentUserId()");
    }

    @Test
    @DisplayName("前提: 全仓 setAuthentication 出现 0 次 —— 这是「恒返 null」成立的原因")
    void nothingEverPopulatesTheSpringSecurityContext() throws IOException {
        try (Stream<Path> sources = javaSources()) {
            List<String> offenders = sources
                    .filter(p -> {
                        try {
                            return stripComments(Files.readString(p, StandardCharsets.UTF_8))
                                    .contains("SecurityContextHolder.getContext().setAuthentication");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(p -> MAIN.relativize(p).toString().replace('\\', '/'))
                    .toList();

            assertThat(offenders)
                    .as("有人开始填充 SecurityContextHolder 了。那么 getCurrentUserId() 就不再恒为 null, "
                            + "本闸的前提变了 —— 请重新评估: 是要统一走 SecurityContext, 还是维持 request attribute。"
                            + " 不要两套并存, 那是形态 D(同一个东西有两份, 它一定会漂)。")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("登记表每一条都要有理由 —— 不许空着")
    void everyRegisteredExemptionHasAReason() {
        assertThat(DELIBERATELY_NOT_MIGRATED).allSatisfy((file, reason) ->
                assertThat(reason)
                        .as("登记 %s 时必须写清为什么不改", file)
                        .isNotBlank()
                        .hasSizeGreaterThan(30));
    }

    @Test
    @DisplayName("登记表指向的文件必须真的存在 —— 防止文件改名后登记项变成永远绿的空条目")
    void registeredFilesStillExist() {
        Set<String> registered = DELIBERATELY_NOT_MIGRATED.keySet();
        assertThat(registered).allSatisfy(rel ->
                assertThat(Files.exists(MAIN.resolve(rel)))
                        .as("登记表里的 %s 已不存在 —— 请删掉这条登记", rel)
                        .isTrue());
    }
}
