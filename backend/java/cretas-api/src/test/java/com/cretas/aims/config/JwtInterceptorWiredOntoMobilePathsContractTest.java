package com.cretas.aims.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RolePermissionsEndpointTenantIsolationContractTest} 证明了「拦截器<b>会</b>拦跨工厂」,
 * 但它<b>不证明拦截器被挂在那条路径上</b> —— 把 {@code WebMvcConfig} 里的
 * {@code addPathPatterns("/api/mobile/**")} 删掉, 那 5 条断言一条都不会红,
 * 而线上的租户闸会整个消失。那是 2026-09-07 刀 D 里我自己登记的盲区。
 *
 * <p>本测试把那个盲区补上: 钉住「JWT 拦截器确实注册在 {@code /api/mobile/**} 上,
 * 且没有被排除名单把工厂作用域路径挖掉」。
 *
 * <h3>⚠️ 代理判据, 写明它看不见什么</h3>
 * 它读的是 {@code WebMvcConfig.java} 的<b>源码文本</b>, 不是 Spring 真实的注册结果。
 * <ul>
 *   <li>看得见: 注册那一段被删 / 路径模式被改窄 / 有人把 roles 加进排除名单。</li>
 *   <li>⛔ 看不见: 注册顺序 (order) 被改、整个 {@code WebMvcConfig} 不再被 Spring 加载、
 *       或换用另一种注册方式。要覆盖那些得起真实 Spring 上下文并问
 *       {@code RequestMappingHandlerMapping}, 那是另一轮的事。</li>
 * </ul>
 * ⇒ 窄而可信 > 宽而被关掉 (形态 E)。这一条只回答「那行注册还在不在」。
 */
@DisplayName("WebMvcConfig — JWT 拦截器必须挂在 /api/mobile/** 上")
class JwtInterceptorWiredOntoMobilePathsContractTest {

    private String webMvcConfigSource() throws IOException {
        Path path = Path.of("src/main/java/com/cretas/aims/config/WebMvcConfig.java");
        assertThat(Files.exists(path))
                .as("INSTRUMENT DEAD — 读不到 WebMvcConfig.java (工作目录变了?): %s", path.toAbsolutePath())
                .isTrue();
        String src = Files.readString(path);
        // 剥注释: 否则被注释掉的注册也会被当成真的 (本仓多次栽在这上面)
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }

    @Test
    @DisplayName("注册那一段还在: addInterceptor(jwtAuthInterceptor) + /api/mobile/**")
    void jwtInterceptorIsRegisteredOnMobilePaths() throws IOException {
        String src = webMvcConfigSource();

        assertThat(src)
                .as("INSTRUMENT DEAD — 源码里连 addInterceptors 都没有, 后面的断言无意义")
                .contains("addInterceptors");

        int at = src.indexOf("addInterceptor(jwtAuthInterceptor)");
        assertThat(at)
                .as("🔴 JWT 拦截器的注册不见了 —— 那是 /api/mobile/{factoryId}/** 上唯一的租户闸 "
                        + "(见 RolePermissionsEndpointTenantIsolationContractTest)")
                .isGreaterThan(-1);

        // 只看这条注册自己的那一段 (到下一个 addInterceptor 为止), ⛔ 不要在整份文件里 contains ——
        // 那样别人的注册带的 /api/mobile/** 会把这条断言喂饱 (变异污染读数的同族)。
        int next = src.indexOf("addInterceptor(", at + 1);
        String block = next > at ? src.substring(at, next) : src.substring(at);
        assertThat(block)
                .as("JWT 拦截器的路径模式不再覆盖 /api/mobile/** ⇒ 工厂作用域路径不再过租户闸")
                .contains("\"/api/mobile/**\"");
    }

    @Test
    @DisplayName("排除名单没有把工厂作用域路径挖掉")
    void factoryScopedPathsAreNotExcluded() throws IOException {
        String src = webMvcConfigSource();
        assertThat(src)
                .as("有人把 roles 加进了排除白名单 ⇒ 那条路径不再过 JWT/租户校验")
                .doesNotContain("/api/mobile/roles")
                .doesNotContain("roles/**");
    }

    @Test
    @DisplayName("判据自身的阳性对照: 同一个提取方式在改坏的源码上会红")
    void thePredicateCanActuallyFail() {
        String broken = "public void addInterceptors(InterceptorRegistry registry) {\n"
                + "  registry.addInterceptor(jwtAuthInterceptor).addPathPatterns(\"/api/platform/**\");\n"
                + "  registry.addInterceptor(permissionInterceptor).addPathPatterns(\"/api/mobile/**\");\n"
                + "}";
        int at = broken.indexOf("addInterceptor(jwtAuthInterceptor)");
        int next = broken.indexOf("addInterceptor(", at + 1);
        String block = broken.substring(at, next);
        assertThat(block)
                .as("🔴 判据失效: 别人的注册带的 /api/mobile/** 不该算到 JWT 拦截器头上")
                .doesNotContain("/api/mobile/**");
    }
}
