package com.cretas.aims.dev.lineage;

import com.cretas.aims.CretasBackendApplication;
import com.cretas.aims.repository.auth.PermissionRegistryRepository;
import com.cretas.aims.service.auth.impl.PermissionRegistryServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回填进程（profile {@code lineage-backfill}）与线上实例隔离 —— 用<b>产品真实入口</b>验：
 * 和 {@code java -jar} 一样从 {@link CretasBackendApplication} 起一个完整的 {@link SpringApplication}，
 * 读的是真的 {@code application-lineage-backfill.properties}。
 *
 * <p>⚠️ 刻意不用 {@code @SpringBootTest}：它的 {@code webEnvironment} 会替我们决定 web 类型，
 * 「web 应用类型为 NONE」那条断言就会守着测试框架而不是配置文件。
 *
 * <p>两个上下文各用独立的 H2 内存库，不碰全 JVM 共享的 {@code testdb}（见 application-test.properties 顶部注释）。
 *
 * <p>反向用例（普通 profile）是阳性对照：同一批断言在那里必须<b>反过来</b>成立，
 * 否则回填用例里的「没有」可能只是「本来就没有」。
 */
@DisabledIfEnvironmentVariable(named = "SKIP_INTEGRATION_TESTS", matches = "true")
@DisplayName("回填进程隔离: 无调度 / 无 web / 启动期副作用组件不实例化; 普通 profile 下一切照旧")
class LineageBackfillProcessIsolationTest {

    private static final String H2 = ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;"
            + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE";

    @Test
    @DisplayName("lineage-backfill: 调度未启用、web 类型 NONE、启动期副作用组件一个都没实例化、runner 跑完给出退出码 0")
    void backfillProfileStartsNothingButTheBackfill() {
        SpringApplication app = new SpringApplication(CretasBackendApplication.class);
        ConfigurableApplicationContext context = app.run(
                "--spring.profiles.active=test," + LineageBackfillMode.PROFILE,
                "--spring.datasource.url=jdbc:h2:mem:lineage_backfill_isolation" + H2,
                "--LINEAGE_BACKFILL_FACTORIES=ISO_TEST_F",
                "--LINEAGE_BACKFILL_DRY_RUN=true");
        boolean closed = false;
        try {
            Observed o = observe(context, true);
            System.out.println("[backfill-isolation] lineage-backfill observed: " + o);

            // 1) 调度: 没有 @Scheduled 处理器, 也就没有任何注册的定时任务
            assertThat(o.schedulingProcessorDefined)
                    .as("回填进程里不该有 %s", TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)
                    .isFalse();
            assertThat(o.scheduledTaskCount).as("回填进程里注册的定时任务数").isZero();

            // 2) web: 由配置文件决定的类型是 NONE, 上下文不是 web 容器
            assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);

            // 3) 启动期副作用组件: 一个都没被实例化 (此刻 ApplicationReadyEvent 已经发过)
            assertThat(o.instantiatedSideEffectBeans).as("回填进程里被实例化的启动期副作用组件").isEmpty();
            // 启动扫描没写库 —— 直接读它的产出 (监听器条件真的拦住了, 不只是 bean 没创建)
            assertThat(context.getBean(PermissionRegistryRepository.class).count())
                    .as("回填进程里 permission registry 不该被启动扫描写入").isZero();

            // 4) runner 真跑完了 (阳性: 这一条说明上面的「没有」不是因为根本没走到 runner)
            LineageBackfillRunner runner = context.getBean(LineageBackfillRunner.class);
            assertThat(runner.summaryLine()).startsWith("[lineage-backfill] SUMMARY exit=0 ");
            assertThat(runner.getExitCode()).isEqualTo(LineageBackfillRunner.EXIT_OK);
            assertThat(LineageBackfillPreflight.violations(context)).isEmpty();

            // 5) 进程收尾: 关上下文并给出退出码
            int exit = LineageBackfillRunner.closeAndReport(context);
            closed = true;
            assertThat(exit).isEqualTo(LineageBackfillRunner.EXIT_OK);
            assertThat(context.isActive()).isFalse();
        } finally {
            if (!closed) {
                context.close();
            }
        }
    }

    @Test
    @DisplayName("反向: 普通 profile 下调度仍启用、启动监听器仍然执行、运行时自检会报红")
    void normalProfileKeepsSchedulingAndStartupHooks() {
        SpringApplication app = new SpringApplication(CretasBackendApplication.class);
        ConfigurableApplicationContext context = app.run(
                "--spring.profiles.active=test",
                // 只是不起 Tomcat; 与调度 / 监听器无关
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=jdbc:h2:mem:lineage_backfill_isolation_normal" + H2);
        try {
            Observed o = observe(context, false);
            System.out.println("[backfill-isolation] normal observed: " + o);

            assertThat(o.schedulingProcessorDefined).isTrue();
            assertThat(o.scheduledTaskCount).as("普通 profile 下注册的定时任务数").isGreaterThan(20);

            assertThat(context.getBeanNamesForType(LineageBackfillRunner.class))
                    .as("普通 profile 下回填 runner 不该存在").isEmpty();

            // 阳性对照: 清单里每个在本上下文有定义的组件都被实例化了 —— 否则回填用例里的「没实例化」可能是空话
            ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
            List<String> definedButNotInstantiated = new ArrayList<>();
            List<String> notDefinedHere = new ArrayList<>();
            LineageBackfillPreflight.STARTUP_SIDE_EFFECT_BEANS.keySet().forEach(type -> {
                String[] names = beanFactory.getBeanNamesForType(type, true, false);
                if (names.length == 0) {
                    notDefinedHere.add(type.getSimpleName());
                }
                for (String name : names) {
                    if (!beanFactory.containsSingleton(name)) {
                        definedButNotInstantiated.add(type.getSimpleName());
                    }
                }
            });
            System.out.println("[backfill-isolation] normal: not defined under test profile (property-conditional) = "
                    + notDefinedHere);
            assertThat(definedButNotInstantiated).isEmpty();
            // 只允许「按属性条件、test profile 本来就关着」的那几个缺席
            assertThat(Set.copyOf(notDefinedHere))
                    .isSubsetOf(Set.of("GrpcEmbeddingClient", "MqttSubscriber", "MqttCommandPublisher"));
            assertThat(o.instantiatedSideEffectBeans).contains(
                    "PermissionRegistryServiceImpl", "WorkflowEngineServiceImpl",
                    "PurchaseGrantTimerExecutionService", "StartupWarmupCoordinator",
                    "DailyIndicatorRecomputeScheduler", "FuzzyExpressionSeeder", "DynamicSchedulerService");

            // 监听器条件在普通 profile 下求值为 true: 启动扫描真的写了库
            assertThat(context.getBean(PermissionRegistryRepository.class).count())
                    .as("普通 profile 下启动扫描应写入 permission registry").isPositive();
            assertThat(context.getBean(PermissionRegistryServiceImpl.class)).isNotNull();

            // 运行时自检在这里必须报红 (证明那道闸能红)
            assertThat(LineageBackfillPreflight.violations(context))
                    .anyMatch(v -> v.contains("调度开着"))
                    .anyMatch(v -> v.contains("PermissionRegistryServiceImpl"));
        } finally {
            context.close();
        }
    }

    /** 一次读齐本测试关心的读数, 打印出来作为变异是否生效的可观测量。 */
    private static Observed observe(ConfigurableApplicationContext context, boolean explainInstantiated) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        boolean defined = beanFactory.containsBeanDefinition(
                TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME);
        int tasks = 0;
        for (ScheduledAnnotationBeanPostProcessor p
                : context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class, false, false).values()) {
            Set<ScheduledTask> scheduled = p.getScheduledTasks();
            tasks += scheduled.size();
        }
        List<String> instantiated = new ArrayList<>();
        LineageBackfillPreflight.STARTUP_SIDE_EFFECT_BEANS.keySet().forEach(type -> {
            for (String name : beanFactory.getBeanNamesForType(type, true, false)) {
                if (beanFactory.containsSingleton(name)) {
                    instantiated.add(type.getSimpleName());
                    if (explainInstantiated) {
                        // 红的时候直接告诉你是谁把它拖起来的 (依赖它的 bean 链)
                        System.out.println("[backfill-isolation] " + name + " 被实例化, 依赖链: "
                                + chain(beanFactory, name, 0));
                    }
                }
            }
        });
        return new Observed(defined, tasks, instantiated,
                context instanceof WebServerApplicationContext, beanFactory.getSingletonCount());
    }

    private static String chain(ConfigurableListableBeanFactory bf, String name, int depth) {
        String[] deps = bf.getDependentBeans(name);
        if (deps.length == 0 || depth > 6) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (String d : deps) {
            sb.append(d).append(" <- ").append(chain(bf, d, depth + 1)).append("; ");
        }
        return sb.append("]").toString();
    }

    private record Observed(boolean schedulingProcessorDefined, int scheduledTaskCount,
                            List<String> instantiatedSideEffectBeans, boolean webServerContext,
                            int singletonCount) {
    }
}
