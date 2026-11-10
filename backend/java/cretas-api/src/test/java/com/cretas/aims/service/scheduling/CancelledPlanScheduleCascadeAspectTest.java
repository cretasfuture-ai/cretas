package com.cretas.aims.service.scheduling;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * D-S4-CANCEL-GHOST-DAILY 第 2 层的接线: 生产计划取消<b>成功之后</b>, 经 Spring 代理调用的取消入口都会级联排程。
 * 用真实的 Spring 自动代理 (与生产同一个 {@code @Aspect} Bean), 被代理的取消服务是桩;
 * 级联本身 (真 UPDATE) 见 {@code CancelledProductionPlanGhostScheduleJpaContractTest}。
 */
@SpringJUnitConfig(CancelledPlanScheduleCascadeAspectTest.Config.class)
class CancelledPlanScheduleCascadeAspectTest {

    /** 被代理的那个桩本身: 打桩必须打在它上面, 经代理打桩会先触发一次切面。 */
    static final ProductionPlanService RAW_PLANS = mock(ProductionPlanService.class);

    @Configuration
    @EnableAspectJAutoProxy
    static class Config {
        @Bean
        CancelledPlanScheduleCascade cascade() {
            return mock(CancelledPlanScheduleCascade.class);
        }

        @Bean
        ProductionPlanService productionPlanService() {
            return RAW_PLANS;
        }

        @Bean
        CancelledPlanScheduleCascadeAspect aspect(CancelledPlanScheduleCascade cascade) {
            return new CancelledPlanScheduleCascadeAspect(cascade);
        }
    }

    @Autowired ProductionPlanService plans;
    @Autowired CancelledPlanScheduleCascade cascade;

    @BeforeEach
    void clean() {
        reset(cascade, RAW_PLANS);
    }

    @Test
    void theServiceBeanIsActuallyProxied() {
        assertThat(AopUtils.isAopProxy(plans)).as("取消服务被代理, 否则下面的断言全是空的").isTrue();
    }

    @Test
    void aSuccessfulCancelCascadesOnceForThatPlan() {
        plans.cancelProductionPlan("F1", "PP-1", "订单取消", 7L);
        verify(cascade, times(1)).cascade("F1", "PP-1");

        reset(cascade);
        plans.cancelProductionPlan("F1", "PP-2", "订单取消");
        verify(cascade, times(1)).cascade("F1", "PP-2");
    }

    @Test
    void aRejectedCancelDoesNotCascade() {
        doThrow(new BusinessException(409, "已完成的生产计划不能直接取消"))
                .when(RAW_PLANS).cancelProductionPlan(anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> plans.cancelProductionPlan("F1", "PP-1", "x", 7L))
                .isInstanceOf(BusinessException.class);
        verify(RAW_PLANS).cancelProductionPlan("F1", "PP-1", "x", 7L);   // 真的经代理走到了取消
        verify(cascade, never()).cascade(anyString(), anyString());
    }

    @Test
    void theApprovedCancelCallbackAlsoCascades() {
        plans.executeCancelApproved("PP-9");
        verify(cascade, times(1)).cascadeByPlanId("PP-9");
    }

    @Test
    void otherPlanOperationsDoNotCascade() {
        plans.pauseProduction("F1", "PP-1");
        plans.startProduction("F1", "PP-1");
        verifyNoInteractions(cascade);
    }

    /** 生产上代理的是实现类 (CGLIB); 切点必须在实现类的方法上命中, 不只在接口上。 */
    @Test
    void thePointcutsMatchTheRealImplementationMethods() throws Exception {
        AspectJExpressionPointcut cancel = pointcut("afterCancel");
        AspectJExpressionPointcut approved = pointcut("afterApprovedCancel");
        Class<ProductionPlanServiceImpl> impl = ProductionPlanServiceImpl.class;

        assertThat(cancel.matches(impl.getMethod("cancelProductionPlan", String.class, String.class, String.class), impl))
                .isTrue();
        assertThat(cancel.matches(impl.getMethod("cancelProductionPlan", String.class, String.class, String.class,
                Long.class), impl)).isTrue();
        assertThat(approved.matches(impl.getMethod("executeCancelApproved", String.class), impl)).isTrue();
        assertThat(cancel.matches(impl.getMethod("pauseProduction", String.class, String.class), impl)).isFalse();
    }

    private static AspectJExpressionPointcut pointcut(String adviceMethod) {
        Method m = Arrays.stream(CancelledPlanScheduleCascadeAspect.class.getDeclaredMethods())
                .filter(x -> x.getName().equals(adviceMethod)).findFirst().orElseThrow();
        org.aspectj.lang.annotation.AfterReturning a = m.getAnnotation(org.aspectj.lang.annotation.AfterReturning.class);
        AspectJExpressionPointcut pc = new AspectJExpressionPointcut(CancelledPlanScheduleCascadeAspect.class,
                a.argNames().split(","), Arrays.stream(m.getParameterTypes()).toArray(Class[]::new));
        pc.setExpression(a.pointcut());
        return pc;
    }
}
