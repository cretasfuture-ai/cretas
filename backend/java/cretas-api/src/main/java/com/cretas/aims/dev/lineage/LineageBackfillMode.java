package com.cretas.aims.dev.lineage;

import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.util.StringUtils;

/**
 * 「一次性回填进程」模式的单一来源：profile 名、排除表达式、启动监听器的守卫条件。
 *
 * <p>回填用 {@code java -jar} 在生产机上另起一个进程跑，它加载的是<b>完整应用</b>的 bean 定义。
 * 凡是「启动就会动手」的东西（调度、对外连接、启动即写库的监听器）都必须在这个 profile 下关掉，
 * 否则这个进程会和线上实例各跑一份。三种关法，按「谁注入它」选：
 *
 * <ul>
 *   <li>没人注入的叶子组件 / 配置类 ⇒ 类上 {@code @Profile(LineageBackfillMode.NOT_ACTIVE)}；</li>
 *   <li>挂在被别人注入的大服务上的启动监听方法 ⇒
 *       {@code @EventListener(value = ..., condition = LineageBackfillMode.NOT_ACTIVE_CONDITION)}
 *       —— 条件为假时 Spring 根本不去取目标 bean（配合懒加载，宿主 bean 都不会被实例化）；</li>
 *   <li>其余 {@code @PostConstruct} 副作用 ⇒ {@code spring.main.lazy-initialization=true}
 *       （见 {@code application-lineage-backfill.properties}），不在回填依赖图里的 bean 一律不创建。</li>
 * </ul>
 *
 * <p>⛔ 不要把这些字面量在别处再抄一份：改 profile 名只改这里。
 */
public final class LineageBackfillMode {

    /** 回填 profile 名。 */
    public static final String PROFILE = "lineage-backfill";

    /** 给 {@code @Profile} 用：回填进程里不创建。 */
    public static final String NOT_ACTIVE = "!" + PROFILE;

    /**
     * 给 {@code @EventListener(condition = ...)} 用：回填进程里不触发。
     *
     * <p>{@code @environment} 是上下文自带的单例 bean；{@code matchesProfiles} 是 Spring 6.1 的
     * profile 表达式匹配（与 {@code @Profile} 同一套语义）。正常 profile 下它求值为 {@code true}，
     * 监听器行为与改动前完全一致 —— 这一点由 {@code LineageBackfillProcessIsolationTest}
     * 的反向用例（普通 profile 下启动扫描真的写了库）钉住。
     */
    public static final String NOT_ACTIVE_CONDITION =
            "!@environment.matchesProfiles('" + PROFILE + "')";

    private LineageBackfillMode() {
    }

    /**
     * 在 Spring 上下文建起来<b>之前</b>判断这次启动是不是回填进程。
     *
     * <p>只给 {@code main} 用：上下文启动失败时拿不到 {@code Environment}，
     * 但回填进程仍必须以非 0 退出码结束（否则残留的非守护线程会让进程挂着不退）。
     * 读取顺序与 Spring Boot 一致：命令行参数 &gt; JVM 系统属性 &gt; 环境变量
     * （{@code SPRING_PROFILES_ACTIVE} 经松散绑定映射到 {@code spring.profiles.active}）。
     */
    public static boolean requested(String[] args) {
        StandardEnvironment environment = new StandardEnvironment();
        if (args != null && args.length > 0) {
            environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        }
        String active = environment.getProperty("spring.profiles.active");
        return StringUtils.commaDelimitedListToSet(active == null ? "" : active.replace(" ", ""))
                .contains(PROFILE);
    }
}
