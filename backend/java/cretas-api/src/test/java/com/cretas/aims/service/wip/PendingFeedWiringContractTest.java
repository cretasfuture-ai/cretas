package com.cretas.aims.service.wip;

import com.cretas.aims.service.wip.impl.WipInventoryServiceImpl;
import com.cretas.aims.service.yield.PendingInterimFeedService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 🔴 <b>预扣到底接上了没有</b> —— 唯一能回答这个问题的断言。
 *
 * <h2>为什么 Mockito 那三个类不算</h2>
 *
 * <p>{@code WipPickerPendingFeedVisibilityContractTest} 等三个类跑在 {@code MockitoExtension} 上，
 * 那个字段是被 {@code ReflectionTestUtils} <b>手工塞进去</b>的，<b>永远非 null</b>。
 * 它们证明的是「拿到待扣量之后算得对不对」，⛔ 证明不了「生产上真的拿得到」。
 *
 * <p>而这条线上「没接上」与「没东西可扣」的读数<b>完全相同</b>：
 * 2026-09-06 prod 实测<b>零条</b>未小结行携带 {@code semiFinished} 投料引用，
 * 于是预扣今天在生产上减的就是 0。<b>接线断了也是 0。</b> 两者在任何下游读数上都分不开。
 *
 * <p>而这个注入点本身是<b>脆</b>的，不是理论风险：
 * {@code PendingInterimFeedServiceImpl} 反向依赖 {@code WipInventoryService}（它要
 * {@code resolveSemiFeedQtyInSourceUnit} 做折算），两者构成真实的 bean 环。
 * 实测把 {@code @Lazy} 去掉，Spring 直接点名这个环：
 *
 * <pre>
 *   wipInventoryServiceImpl (field ... WipInventoryServiceImpl.pendingInterimFeedService)
 *      ↕
 *   pendingInterimFeedServiceImpl
 * </pre>
 *
 * 而 {@code @Autowired(required = false)} 的语义就是<b>注不上也不报错</b> ——
 * 两件事叠在一起，正是「机制在、没接上」最典型的长相（形态 B）。
 *
 * <p>⚠️ 必须是 {@code @SpringBootTest}：{@code @DataJpaTest} <b>不加载 {@code @Service} bean</b>，
 * 拿它当接线证据是无效的（2026-09-06 实测确认过，别再用它省那 100 秒）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@DisplayName("预扣接线：真实 Spring 上下文里那个 optional 字段必须真的注上")
class PendingFeedWiringContractTest {

    /** 只用来把协作者问一次, 与库里有没有数据无关。 */
    private static final String PROBE_FACTORY_ID = "F006";

    @Autowired
    private WipInventoryService wipInventoryService;

    @Autowired
    private PendingInterimFeedService pendingInterimFeedService;

    private Object injectedCollaborator() {
        // 服务是 @Transactional 代理，字段挂在被代理的目标对象上 —— 先剥代理再读字段。
        WipInventoryServiceImpl target = AopTestUtils.getUltimateTargetObject(wipInventoryService);
        return ReflectionTestUtils.getField(target, "pendingInterimFeedService");
    }

    @Test
    @DisplayName("🔴 那个 optional 字段必须真的【能用】—— 非 null 不够, 要问得动它")
    void optionalCollaboratorIsActuallyUsable() {
        Object injected = injectedCollaborator();

        // 形态 C″: 先把读到的东西打出来再看断言红绿 ——
        // 变异之后若这条不红, 要能分清是「断言没用」还是「变异没打进去」。
        // ⛔ 不能打 injected 本身: toString() 会触发 @Lazy 代理解析, 变异下当场抛异常,
        //    读数就变成一个 error 而不是一条能读的断言。
        System.out.println("WIRING_PROBE pendingInterimFeedService.class="
                + (injected == null ? "null" : injected.getClass().getName()));

        // 第一层: 字段本身在不在。
        assertThat(injected)
                .as("@Autowired(required = false) 的语义是【注不上也不报错】—— 字段直接是 null")
                .isNotNull();

        // 🔴 第二层, 这一层才是要害: @Lazy 注进来的是【代理】, 目标 bean 不存在时
        //    字段照样非 null, 要到【第一次真的问它】才抛 NoSuchBeanDefinition。
        //    2026-09-06 实测: 给这个注入点加一个匹配不到任何 bean 的 @Qualifier,
        //    上面那条 isNotNull 【纹丝不动】—— 只有下面这次真调用才红。
        //    ⇒ 只断言非 null 对这个注入点几乎是恒真式 (形态 B′)。
        PendingInterimFeedService collaborator = (PendingInterimFeedService) injected;
        assertThat(catchThrowable(() -> collaborator.pendingSemiFeedSnapshot(PROBE_FACTORY_ID)))
                .as("""
                        问不动这个协作者 = 选料下拉根本没在减待扣量, 跨计划超领会一路留到结算才炸。
                        而这条线上「没接上」与「没东西可扣」的下游读数完全相同
                        (prod 今天零条未小结 semi 引用) ⇒ 只有真调用一次分得开。""")
                .isNull();
    }

    @Test
    @DisplayName("阳性对照: 这次调用真的返回了一个可读的快照 (不是靠「没抛异常」蒙过去)")
    void collaboratorReturnsAUsableSnapshot() {
        PendingInterimFeedService.Snapshot snapshot =
                pendingInterimFeedService.pendingSemiFeedSnapshot(PROBE_FACTORY_ID);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.pendingByBatchNo()).isNotNull();
        assertThat(snapshot.unresolvedBatchNos()).isNotNull();
        // ⚠️ 空测试库里这两个集合就是空的 —— 这条断言只证明【仪器活着】,
        //    ⛔ 不证明预扣算得对。算得对由 WipPickerPendingFeedVisibilityContractTest 守。
    }

    @Test
    @DisplayName("阳性对照: 注进去的就是容器里那个 bean 本身 (不是别的实现 / 不是空壳)")
    void injectedCollaboratorIsTheContainerBean() {
        Object injected = injectedCollaborator();

        assertThat(injected).isInstanceOf(PendingInterimFeedService.class);
        // @Lazy 注的是代理, ⛔ 不能直接比 identity; 两侧都剥到最终目标再比。
        // 显式声明成 Object: getUltimateTargetObject 是 <T> T, 直接塞进 assertThat 会推不出类型。
        Object injectedTarget = AopTestUtils.getUltimateTargetObject(injected);
        Object containerTarget = AopTestUtils.getUltimateTargetObject(pendingInterimFeedService);
        assertThat(injectedTarget)
                .as("剥掉 @Lazy 代理之后必须就是容器里那一个 PendingInterimFeedService")
                .isSameAs(containerTarget);
    }

    @Test
    @DisplayName("阳性对照: 这个上下文真的把两个 bean 都建起来了 (否则上面两条无从谈起)")
    void bothBeansExistInThisContext() {
        assertThat(wipInventoryService).isNotNull();
        assertThat(pendingInterimFeedService).isNotNull();
    }
}
