package com.cretas.aims.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.config.RoleDefinition;
import com.cretas.aims.entity.enums.DataScope;
import com.cretas.aims.repository.RoleDefinitionRepository;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 2026-09-04 prod WARN 排查落地的回归闸.
 *
 * <p>三条主线, 对应交接件的三个要求:
 * <ol>
 *   <li>{@code role_definitions} 查得到行 → 用那一行的 data_scope (正常路径)</li>
 *   <li>查询本身抛异常(表/列缺失等) → fail-closed 到 SELF, 不再按角色区分
 *       (变异: 把 {@link DataScopeResolver} 里 fail-closed 返回值从 SELF 改成 ALL,
 *       {@link #failsClosedToSelfWhenLookupThrows} 必须变红)</li>
 *   <li>{@code yield_operator} 硬默认必须是 SELF, 不是 ALL —— 这是本轮实测发现的
 *       真实越权缺陷(LIUSHANMEN 9 个账号, 该厂最大角色群体), 不只是加固</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DataScopeResolverTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleDefinitionRepository roleDefinitionRepository;

    private DataScopeResolver resolver;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        resolver = new DataScopeResolver(userRepository, roleDefinitionRepository,
                Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC));

        logger = (Logger) LoggerFactory.getLogger(DataScopeResolver.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private List<ILoggingEvent> warnEvents() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private User userWith(Long id, String roleCode) {
        User user = new User();
        user.setId(id);
        user.setFactoryId("LIUSHANMEN");
        user.setRoleCode(roleCode);
        user.setDepartment("production");
        return user;
    }

    // ---- 1. role_definitions 查得到行: 用那一行的值 ----

    @Test
    @DisplayName("role_definitions 有配置行时, 用该行的 data_scope, 不落到硬默认")
    void resolvesConfiguredScopeFromRoleDefinitionsTable() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(userWith(1L, "sales_manager")));
        when(roleDefinitionRepository.findByRoleCode("sales_manager")).thenReturn(Optional.of(
                RoleDefinition.builder().roleCode("sales_manager").dataScope("DEPT_AND_BELOW").build()));

        DataScopeContext ctx = resolver.resolve(1L);

        // 用一个与硬默认(ALL)不同的配置值, 证明真的读了这一行而不是绕过表直接落硬默认.
        assertThat(ctx.getScope()).isEqualTo(DataScope.DEPT_AND_BELOW);
    }

    // ---- 2. 查询本身失败: fail-closed 到 SELF, 与角色无关 ----

    @Test
    @DisplayName("role_definitions 查询抛异常(表/列缺失等) -> fail-closed 到 SELF, 即使角色是 factory_super_admin")
    void failsClosedToSelfWhenLookupThrows() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(userWith(1L, "factory_super_admin")));
        when(roleDefinitionRepository.findByRoleCode("factory_super_admin"))
                .thenThrow(new InvalidDataAccessResourceUsageException(
                        "PreparedStatementCallback; bad SQL grammar [SELECT data_scope FROM role_definitions WHERE role_code = ?]"));

        DataScopeContext ctx = resolver.resolve(1L);

        // 阳性对照: factory_super_admin 的硬默认本来是 ALL (见下面那条测试) ——
        // 这里必须不同, 否则本测试测的可能只是硬默认分支, 没有真的走到 fail-closed 分支.
        assertThat(ctx.getScope())
                .as("查询失败时必须收紧到 SELF, 不能沿用角色硬默认(factory_super_admin 平时是 ALL)")
                .isEqualTo(DataScope.SELF);
    }

    @Test
    @DisplayName("查询失败时 WARN 按 role_code 节流: 窗口内第二次同角色失败不再打 WARN")
    void throttlesWarnLogPerRoleWithinWindow() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(userWith(1L, "factory_super_admin")));
        when(userRepository.findById(2L)).thenReturn(Optional.of(userWith(2L, "factory_super_admin")));
        when(roleDefinitionRepository.findByRoleCode("factory_super_admin"))
                .thenThrow(new InvalidDataAccessResourceUsageException("relation \"role_definitions\" does not exist"));

        resolver.resolve(1L);
        resolver.resolve(2L);
        resolver.resolve(1L);

        // 3 次解析都失败(用同一个可观测量确认变异/仪器没打偏: 3 次都拿到 SELF),
        // 但 WARN 只应该打 1 条 —— 这是"每几秒一条 WARN"这个生产症状本身要修的东西.
        assertThat(warnEvents())
                .as("同一 role_code 在节流窗口内应只打 1 条 WARN, 而不是每次解析都打")
                .hasSize(1);
    }

    @Test
    @DisplayName("节流窗口过后, 同一角色的下一次失败重新 WARN")
    void warnsAgainAfterThrottleWindowElapses() {
        DataScopeResolver advancing = new DataScopeResolver(userRepository, roleDefinitionRepository,
                new AdvanceableClock(Instant.parse("2026-09-04T00:00:00Z")));
        AdvanceableClock clock = (AdvanceableClock) getClockField(advancing);

        when(userRepository.findById(1L)).thenReturn(Optional.of(userWith(1L, "factory_super_admin")));
        when(roleDefinitionRepository.findByRoleCode("factory_super_admin"))
                .thenThrow(new InvalidDataAccessResourceUsageException("relation \"role_definitions\" does not exist"));

        advancing.resolve(1L);
        clock.advance(java.time.Duration.ofMinutes(6)); // > WARN_THROTTLE (5 分钟)
        advancing.resolve(1L);

        assertThat(warnEvents())
                .as("节流窗口(5 分钟)过后应该重新打 WARN, 不能永久静默")
                .hasSize(2);
    }

    private static Object getClockField(DataScopeResolver r) {
        try {
            java.lang.reflect.Field f = DataScopeResolver.class.getDeclaredField("clock");
            f.setAccessible(true);
            return f.get(r);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ---- 3. role_definitions 查得到表但没这一行(EmptyResultDataAccessException-equivalent):
    //         用硬默认. yield_operator 必须是 SELF —— 本轮实测发现的真实缺陷. ----

    @Test
    @DisplayName("role_definitions 没有该角色的行时, factory_super_admin 硬默认是 ALL")
    void hardDefaultForFactorySuperAdminIsAll() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(userWith(1L, "factory_super_admin")));
        when(roleDefinitionRepository.findByRoleCode("factory_super_admin")).thenReturn(Optional.empty());

        assertThat(resolver.resolve(1L).getScope()).isEqualTo(DataScope.ALL);
    }

    @Test
    @DisplayName("回归: yield_operator 硬默认必须是 SELF (此前遗漏, 落进 default 分支拿到了 ALL)")
    void yieldOperatorHardDefaultIsSelfNotAll() {
        when(userRepository.findById(9L)).thenReturn(Optional.of(userWith(9L, "yield_operator")));
        when(roleDefinitionRepository.findByRoleCode("yield_operator")).thenReturn(Optional.empty());

        DataScopeContext ctx = resolver.resolve(9L);

        assertThat(ctx.getScope())
                .as("yield_operator 与 operator 同为一线报工岗(Level 30), 硬默认必须是 SELF")
                .isEqualTo(DataScope.SELF);
    }

    @Test
    @DisplayName("对照组: operator 硬默认已经是 SELF (证明本测试的判据不是恒真式)")
    void operatorHardDefaultIsSelf() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(userWith(2L, "operator")));
        when(roleDefinitionRepository.findByRoleCode("operator")).thenReturn(Optional.empty());

        assertThat(resolver.resolve(2L).getScope()).isEqualTo(DataScope.SELF);
    }

    /** 可推进的测试 {@link Clock} —— java.time.Clock 本身不可变, fixed() 无法在同一实例内推进. */
    private static final class AdvanceableClock extends Clock {
        private Instant now;

        AdvanceableClock(Instant start) {
            this.now = start;
        }

        void advance(java.time.Duration d) {
            this.now = this.now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
