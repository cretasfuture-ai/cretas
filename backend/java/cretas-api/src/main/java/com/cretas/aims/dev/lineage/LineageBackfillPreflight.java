package com.cretas.aims.dev.lineage;

import com.cretas.aims.audit.activity.ActivityEventStore;
import com.cretas.aims.client.PythonEfficiencyClient;
import com.cretas.aims.client.PythonErrorAnalysisClient;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.WebSocketConfig;
import com.cretas.aims.engine.DynamicSchedulerService;
import com.cretas.aims.mcp.MCPClientAdapter;
import com.cretas.aims.service.ClassifierIntentMatcher;
import com.cretas.aims.service.auth.impl.PermissionRegistryServiceImpl;
import com.cretas.aims.service.cron.DynamicScheduler;
import com.cretas.aims.service.impl.DialectNormalizationServiceImpl;
import com.cretas.aims.service.impl.GrpcEmbeddingClient;
import com.cretas.aims.service.impl.ToolRouterServiceImpl;
import com.cretas.aims.service.indicator.scheduler.DailyIndicatorRecomputeScheduler;
import com.cretas.aims.service.intent.PurchaseGrantTimerExecutionService;
import com.cretas.aims.service.intent.seed.FuzzyExpressionSeeder;
import com.cretas.aims.service.isapi.IsapiEventSubscriptionService;
import com.cretas.aims.service.mqtt.MqttCommandPublisher;
import com.cretas.aims.service.mqtt.MqttSubscriber;
import com.cretas.aims.service.startup.StartupWarmupCoordinator;
import com.cretas.aims.service.workflow.impl.WorkflowEngineServiceImpl;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回填进程的运行时自检：确认「启动就会动手」的东西真的没开，再允许回填执行。
 *
 * <p>为什么配置之外还要一道运行时闸：{@code application-lineage-backfill.properties} 里的值
 * 会被同名环境变量盖掉（生产机上 {@code .env.prod} 是以环境变量注入的），
 * 「配置文件写了 none」不等于「这个进程没开端口」。这里直接问运行中的上下文。
 *
 * <p>{@link #STARTUP_SIDE_EFFECT_BEANS} 是全仓排查出的启动期副作用组件清单（单一来源，
 * 测试 {@code LineageBackfillProcessIsolationTest} 用同一份断言它们在回填上下文里没被实例化，
 * 并在普通 profile 下做阳性对照）。新加一个「启动即连外部 / 启动即写库」的组件时加进来。
 */
public final class LineageBackfillPreflight {

    /** 启动期副作用组件 → 它在启动时会做什么。 */
    public static final Map<Class<?>, String> STARTUP_SIDE_EFFECT_BEANS = buildSideEffectBeans();

    private LineageBackfillPreflight() {
    }

    private static Map<Class<?>, String> buildSideEffectBeans() {
        Map<Class<?>, String> m = new LinkedHashMap<>();
        // ── 自带调度, 不依赖 @EnableScheduling ──
        m.put(DynamicSchedulerService.class, "@PostConstruct 装载 factory_scheduler_config 并按 cron 执行工具(自建线程池)");
        m.put(DynamicScheduler.class, "Canvas-Cron 调度器, 构造即建线程池");
        // ── 启动即写库 ──
        m.put(ToolRouterServiceImpl.class, "@PostConstruct 生成工具向量并写 tool_embeddings");
        m.put(DialectNormalizationServiceImpl.class, "@PostConstruct 写预置方言映射");
        m.put(FuzzyExpressionSeeder.class, "ApplicationRunner, 启动即写 ai_learned_expressions");
        m.put(PermissionRegistryServiceImpl.class, "ApplicationReadyEvent 扫描并写 permission registry");
        m.put(DailyIndicatorRecomputeScheduler.class, "ApplicationReadyEvent 重算指标并写 indicator_versions");
        m.put(PurchaseGrantTimerExecutionService.class, "ApplicationReadyEvent 对账定时采购授权(改状态) + 重载 Canvas-Cron");
        m.put(WorkflowEngineServiceImpl.class, "ApplicationReadyEvent 用 PG 覆盖写共享 Redis 里的审批流状态");
        // ── 启动即连外部 ──
        m.put(StartupWarmupCoordinator.class, "ApplicationReadyEvent 预热意图向量(调 embedding 服务)");
        m.put(ClassifierIntentMatcher.class, "@PostConstruct 对 Python 分类服务做 HTTP 健康检查");
        m.put(PythonSmartBIClient.class, "@PostConstruct 起线程探测 Python SmartBI");
        m.put(PythonErrorAnalysisClient.class, "@PostConstruct 起线程探测 Python 错误分析");
        m.put(PythonEfficiencyClient.class, "@PostConstruct 起线程探测 Python 效率识别");
        m.put(GrpcEmbeddingClient.class, "@PostConstruct 调 gRPC embedding 服务 (embedding.mode=grpc 时才有)");
        m.put(MCPClientAdapter.class, "@PostConstruct 向外部 MCP 服务做工具发现");
        m.put(IsapiEventSubscriptionService.class, "摄像头 alertStream 长连接订阅 / 心跳重连");
        m.put(MqttSubscriber.class, "订阅 MQTT 设备消息 (mqtt.enabled=true 时才有)");
        m.put(MqttCommandPublisher.class, "向 MQTT 设备下发指令 (mqtt.enabled=true 时才有)");
        m.put(WebSocketConfig.class, "WebSocket 端点");
        // ── 起后台线程 ──
        m.put(ActivityEventStore.class, "@PostConstruct 起审计写线程");
        return Collections.unmodifiableMap(m);
    }

    /**
     * @return 违规项（空 = 可以执行回填）。
     */
    public static List<String> violations(ConfigurableApplicationContext context) {
        List<String> violations = new ArrayList<>();
        if (context instanceof WebServerApplicationContext) {
            violations.add("进程开着 web 容器 (spring.main.web-application-type 被覆盖成了非 none?)");
        }
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        if (beanFactory.containsBeanDefinition(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)) {
            violations.add("调度开着 (存在 " + TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME
                    + "; 是不是有人又在别的类上加了 @EnableScheduling?)");
        }
        violations.addAll(instantiatedSideEffectBeans(beanFactory));
        return violations;
    }

    /** 已被实例化的启动期副作用组件（不会因为查询而实例化任何 bean）。 */
    public static List<String> instantiatedSideEffectBeans(ConfigurableListableBeanFactory beanFactory) {
        List<String> found = new ArrayList<>();
        STARTUP_SIDE_EFFECT_BEANS.forEach((type, what) -> {
            for (String name : beanFactory.getBeanNamesForType(type, true, false)) {
                if (beanFactory.containsSingleton(name)) {
                    found.add(type.getSimpleName() + " 已实例化 (" + what + ")");
                }
            }
        });
        return found;
    }
}
