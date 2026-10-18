package p10.clock;

import net.bytebuddy.asm.MemberSubstitution;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** ByteBuddy premain for the P10 actual-app clock seam. */
public final class P10ClockAgent {
    private static final String[] TARGETS = {
            "com.cretas.aims.service.inventory.ReturnRefundService",
            "com.cretas.aims.service.voucher.impl.VoucherServiceImpl",
            "com.cretas.aims.service.finance.impl.ArApServiceImpl",
            "com.cretas.aims.service.finance.impl.AccountingPeriodServiceImpl",
            "com.cretas.aims.entity.BaseEntity",
            "com.cretas.aims.entity.finance.AccountingPeriod",
            "org.springframework.data.auditing.CurrentDateTimeProvider"
    };
    private static final AtomicInteger TRANSFORMATIONS = new AtomicInteger();
    private static final AtomicInteger ERRORS = new AtomicInteger();

    private P10ClockAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        P10BusinessClock.initializeFromSystemProperties();
        Method localDate = method("localDateNow");
        Method localDateTime = method("localDateTimeNow");
        ElementMatcher.Junction<MethodDescription> localDateCalls = named("now")
                .and(isStatic()).and(takesArguments(0)).and(isDeclaredBy(named("java.time.LocalDate")));
        ElementMatcher.Junction<MethodDescription> localDateTimeCalls = named("now")
                .and(isStatic()).and(takesArguments(0)).and(isDeclaredBy(named("java.time.LocalDateTime")));

        AgentBuilder.Transformer transformer = (builder, type, classLoader, module, protectionDomain) ->
                // Relaxed resolution is required for Spring Data generic repository
                // invocations whose optional signatures are not available to the
                // platform-entry TypePool. The class matcher and clock-call
                // matchers remain exact; unresolved unrelated calls are skipped.
                builder.visit(MemberSubstitution.relaxed().failIfNoMatch(false)
                        .method(localDateCalls).replaceWith(localDate)
                        .method(localDateTimeCalls).replaceWith(localDateTime)
                        .on(net.bytebuddy.matcher.ElementMatchers.any()));

        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.Listener() {
                    @Override
                    public void onDiscovery(String typeName, ClassLoader classLoader, JavaModule module,
                                            boolean loaded) {
                    }

                    @Override
                    public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                                 JavaModule module, boolean loaded, DynamicType dynamicType) {
                        int count = TRANSFORMATIONS.incrementAndGet();
                        System.out.println("P10_CLOCK_TRANSFORM class=" + typeDescription.getName()
                                + " count=" + count);
                    }

                    @Override
                    public void onIgnored(TypeDescription typeDescription, ClassLoader classLoader, JavaModule module,
                                          boolean loaded) {
                    }

                    @Override
                    public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                                        boolean loaded, Throwable throwable) {
                        int count = ERRORS.incrementAndGet();
                        System.err.println("P10_CLOCK_TRANSFORM_ERROR class=" + typeName + " count=" + count
                                + " error=" + throwable.getClass().getName()
                                + " message=" + String.valueOf(throwable.getMessage()).replace('\n', ' '));
                        throwable.printStackTrace(System.err);
                    }

                    @Override
                    public void onComplete(String typeName, ClassLoader classLoader, JavaModule module,
                                           boolean loaded) {
                    }
                })
                .type(named(TARGETS[0]).or(named(TARGETS[1])).or(named(TARGETS[2])))
                .transform(transformer)
                .type(named(TARGETS[3]).or(named(TARGETS[4])).or(named(TARGETS[5])))
                .transform(transformer)
                .type(named(TARGETS[6]))
                .transform(transformer)
                .installOn(instrumentation);
        System.out.println("P10_CLOCK_AGENT_READY targets=" + TARGETS.length + " errors=" + ERRORS.get());
    }

    private static Method method(String name) {
        try {
            return P10BusinessClock.class.getMethod(name);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("P10_CLOCK_HELPER_METHOD_MISSING_" + name, ex);
        }
    }
}
