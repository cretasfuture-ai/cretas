package com.cretas.aims.service.impl;

import com.cretas.aims.dto.user.CreateUserRequest;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * R-1「HR / 工厂超管可以改用户名」+ R-2②「删除员工账号」的行为契约。
 *
 * <p>这个类钉的四件事，全部是 2026-08-20 实现过程中<b>差一点就发出去</b>的半成品：
 *
 * <ol>
 *   <li><b>改名不落库</b> —— 前端解禁了输入框、服务里做了查重，而
 *       {@code UserMapper.updateEntity} 根本不复制 username。净效果是提示「编辑成功」，
 *       下次登录仍然只认旧名字。形态 B：机制在，没接上，而且全程不报错。</li>
 *   <li><b>查重看不见软删账号</b> —— {@code existsByUsername} 是派生查询，
 *       {@code User} 上的 {@code @Where(deleted_at IS NULL)} 会被拼进去，
 *       而库上是<b>全量</b>唯一索引。两个各自正确的东西叠加 ⇒ 查重放行、INSERT 撞索引。
 *       ⚠️ 这个坑本来是潜伏的（prod 软删用户 0），是 R-2② 的删除按钮把它变成可达的。</li>
 *   <li><b>部门不落库</b> —— 与 roleCode(BR-13)、position(W-09) 同一个形状：
 *       新建路径写了、更新路径漏了，第三次。</li>
 *   <li><b>超管保护对一半人不成立</b> —— 闸只看 {@code position}，
 *       而 prod 上 11 个 factory_super_admin 里 6 个 position 为 NULL。</li>
 * </ol>
 *
 * <p>⚠️ 每条正向断言都配了能让它红的反向输入（改名/不改名、软删占用/未占用、
 * roleCode 超管/普通员工），⛔ 避免写出「无论怎么改都绿」的断言。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("员工账号管理 — R-1 改用户名 / R-2② 删除账号")
class UserAccountManagementTest {

    @Mock private UserRepository userRepository;
    @Mock private FactoryRepository factoryRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private ExcelUtil excelUtil;

    private UserServiceImpl service;

    /** ⚠️ 用真的 UserMapper，⛔ 不 mock —— 「更新路径漏字段」的缺陷就长在 mapper 里， */
    /*     mock 掉它等于把被测的东西替换成一个必然正确的替身。 */
    private final UserMapper userMapper = new UserMapper();

    @BeforeEach
    void setUp() {
        service = new UserServiceImpl(userRepository, factoryRepository, userMapper,
                passwordEncoder, excelUtil);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private User existing() {
        User u = new User();
        u.setId(42L);
        u.setFactoryId("F006");
        u.setUsername("zhangsan");
        u.setFullName("张三");
        u.setRoleCode("operator");
        u.setDepartment("sales");
        return u;
    }

    private CreateUserRequest request(String username) {
        CreateUserRequest r = new CreateUserRequest();
        r.setUsername(username);
        r.setFullName("张三");
        return r;
    }

    // ── R-1: 改名要真的落库 ────────────────────────────────────────────

    @Test
    @DisplayName("R-1 改用户名会写进实体 —— ⛔ 不是只校验一下就丢掉")
    void renameIsPersisted() {
        User user = existing();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(userRepository.countByUsernameIncludingDeleted("zhangsan_new")).thenReturn(0L);

        service.updateUser("F006", 42L, request("zhangsan_new"));

        assertEquals("zhangsan_new", user.getUsername(),
                "改名没落到实体上 —— 用户会看到「编辑成功」但登录名没变");
    }

    @Test
    @DisplayName("R-1 阴性对照：没改名时不去查重，也不动用户名")
    void unchangedUsernameIsNotRevalidated() {
        User user = existing();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        service.updateUser("F006", 42L, request("zhangsan"));

        assertEquals("zhangsan", user.getUsername());
        // ⚠️ 用 anyString() 而不是具体值：本仓记过 verify(never()) 在实参为 null 时不匹配，
        //    那样的阴性断言两条路都成立。
        verify(userRepository, never()).countByUsernameIncludingDeleted(anyString());
    }

    @Test
    @DisplayName("R-1 用户名被占用 → 409，且提示里指明是哪个名字")
    void duplicateUsernameIsRejected() {
        User user = existing();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(userRepository.countByUsernameIncludingDeleted("lisi")).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateUser("F006", 42L, request("lisi")));

        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("lisi"),
                "报错里没写是哪个用户名, 用户不知道该换哪个");
        assertEquals("zhangsan", user.getUsername(), "被拒之后不许改掉原用户名");
    }

    @Test
    @DisplayName("R-1/R-2② 查重必须问「含软删」那一路 —— ⛔ 派生查询看不见软删账号")
    void uniquenessCheckIncludesSoftDeletedAccounts() {
        User user = existing();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(userRepository.countByUsernameIncludingDeleted("ghost")).thenReturn(1L);

        assertThrows(BusinessException.class,
                () -> service.updateUser("F006", 42L, request("ghost")));

        // 这条 verify 才是本用例的重点：走的是 native 那一路。
        // 走回 existsByUsername 的话，软删账号占的名字会被判成「可用」，
        // 然后撞上全量唯一索引，用户看到的是「数据已存在，请勿重复提交」。
        verify(userRepository).countByUsernameIncludingDeleted("ghost");
    }

    // ── R-2①: 部门要真的落库 ──────────────────────────────────────────

    @Test
    @DisplayName("R-2① 更新部门会落库 —— 与 roleCode/position 同族的第三次「更新路径漏字段」")
    void departmentIsPersistedOnUpdate() {
        User user = existing();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        CreateUserRequest r = request("zhangsan");
        r.setDepartment("财务部");
        service.updateUser("F006", 42L, r);

        assertEquals("财务部", user.getDepartment(),
                "部门没落库 —— HR 选了部门、点了保存、看到成功, 而库里还是旧值");
    }

    @Test
    @DisplayName("R-2① 自由文本部门不再被枚举卡住（prod 有 15 种取值，只 1 个在枚举里）")
    void freeTextDepartmentIsAccepted() {
        User user = existing();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        CreateUserRequest r = request("zhangsan");
        r.setDepartment("sales");   // ⚠️ 枚举里没有这个值, 但 prod 上有 10 个人是它
        service.updateUser("F006", 42L, r);

        assertEquals("sales", user.getDepartment());
    }

    @Test
    @DisplayName("R-2① 阴性对照：没送 department 时保持原值，⛔ 不许被清空")
    void absentDepartmentKeepsExistingValue() {
        User user = existing();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        service.updateUser("F006", 42L, request("zhangsan"));   // department = null

        assertEquals("sales", user.getDepartment(),
                "没送这个字段却被清空了 —— 编辑姓名会顺手抹掉部门");
    }

    @Test
    @DisplayName("R-2① 空串表示「清空部门」，与 null（没送）是两件事")
    void blankDepartmentClearsIt() {
        User user = existing();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        CreateUserRequest r = request("zhangsan");
        r.setDepartment("   ");
        service.updateUser("F006", 42L, r);

        assertNull(user.getDepartment(), "把部门清空这个动作要做得到");
    }

    // ── R-2②: 删除 ───────────────────────────────────────────────────

    @Test
    @DisplayName("R-2② 删除普通员工走 repository.delete（实体是软删，历史单据保留）")
    void deleteOrdinaryEmployee() {
        User user = existing();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        service.deleteUser("F006", 42L);

        verify(userRepository).delete(user);
    }

    @Test
    @DisplayName("R-2② 超管保护认 roleCode —— prod 上 11 个超管有 6 个 position 为 NULL")
    void superAdminWithoutPositionIsStillProtected() {
        User user = existing();
        user.setRoleCode("factory_super_admin");
        user.setPosition(null);          // ← 这正是 prod 上 6/11 的长相
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteUser("F006", 42L));

        assertEquals(403, ex.getCode());
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    @DisplayName("R-2② 遗留的 position 判据仍然有效（roleCode 没跟上的老数据）")
    void legacyPositionStillProtects() {
        User user = existing();
        user.setRoleCode("operator");
        user.setPosition("factory_super_admin");
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        assertThrows(BusinessException.class, () -> service.deleteUser("F006", 42L));
    }

    @Test
    @DisplayName("R-2② 跨工厂删除 → 403")
    void crossFactoryDeleteIsRejected() {
        User user = existing();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteUser("F007", 42L));

        assertEquals(403, ex.getCode());
        verify(userRepository, never()).delete(any(User.class));
    }
}
