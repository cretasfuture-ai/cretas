package com.cretas.aims.mapper;

import com.cretas.aims.dto.user.CreateUserRequest;
import com.cretas.aims.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「新建路径写了、更新路径漏了」的登记闸。
 *
 * <p>🔴 <b>本文件存在的唯一理由是它已经发生了三次</b>：
 *
 * <table>
 *   <tr><th>次</th><th>字段</th><th>编号</th><th>症状</th></tr>
 *   <tr><td>1</td><td>{@code roleCode}</td><td>Apr 20 BR-13</td><td>「角色变更保存, 更新失败」</td></tr>
 *   <tr><td>2</td><td>{@code position}</td><td>W-09</td><td>静默丢弃</td></tr>
 *   <tr><td>3</td><td>{@code department}</td><td>2026-08-20 M-16</td><td>保存成功但没生效</td></tr>
 * </table>
 *
 * <p>三次都不报错 —— 用户看到「编辑成功」，库里没变。写注释拦不住第四次
 * （前两次的教训<b>就写在那个方法的注释里</b>，第三次照样发生），所以做成闸。
 *
 * <p><b>做法</b>：对每个可更新字段，喂一个「只改这一个字段」的请求跑
 * {@code updateEntity}，断言实体上那个字段真的变了。
 * ⛔ 不用反射去数「有几处 setXxx」—— 那是字符串/结构计数，答的是
 * 「这个名字出现了几次」，而要问的是「这个字段改得动吗」。
 *
 * <p><b>故意不更新的字段必须显式登记</b>（{@link #DELIBERATELY_NOT_UPDATED}），
 * 登记是<b>留痕不是豁免</b>：不登记就分不清「漏了」和「有意跳过」。
 */
@DisplayName("UserMapper — 更新路径字段齐备性（第三次同形之后建的闸）")
class UserMapperUpdateFieldParityTest {

    private final UserMapper mapper = new UserMapper();

    /**
     * 每项 = 字段名 → (怎么在请求上设值, 怎么从实体上读值)。
     * 值都取「与 baseline 不同」的，这样「没变」一定意味着没落库。
     */
    private static Map<String, FieldCase> updatableFields() {
        Map<String, FieldCase> cases = new LinkedHashMap<>();
        cases.put("email", new FieldCase(r -> r.setEmail("new@cretas.com"), User::getEmail, "new@cretas.com"));
        cases.put("phone", new FieldCase(r -> r.setPhone("13900000001"), User::getPhone, "13900000001"));
        cases.put("fullName", new FieldCase(r -> r.setFullName("新名字"), User::getFullName, "新名字"));
        cases.put("employeeCode", new FieldCase(r -> r.setEmployeeCode("E999"), User::getEmployeeCode, "E999"));
        cases.put("position", new FieldCase(r -> r.setPosition("新岗位"), User::getPosition, "新岗位"));
        cases.put("monthlySalary",
                new FieldCase(r -> r.setMonthlySalary(new BigDecimal("8888")), User::getMonthlySalary, new BigDecimal("8888")));
        cases.put("expectedWorkMinutes",
                new FieldCase(r -> r.setExpectedWorkMinutes(999), User::getExpectedWorkMinutes, 999));
        cases.put("ccrRate",
                new FieldCase(r -> r.setCcrRate(new BigDecimal("1.75")), User::getCcrRate, new BigDecimal("1.75")));
        // 🔴 第三次那个。它在 2026-08-20 之前一直不在这个列表里 —— 因为列表也不存在。
        cases.put("department", new FieldCase(r -> r.setDepartment("财务部"), User::getDepartment, "财务部"));
        return cases;
    }

    /**
     * 故意<b>不</b>在 {@code updateEntity} 里更新的字段 —— 每条都要写清为什么。
     * ⚠️ 往这里加东西是个决定，不是打扫卫生。
     */
    private static final Map<String, String> DELIBERATELY_NOT_UPDATED = Map.of(
            "username", "登录凭证。改名要先查重(含软删账号)，在 UserServiceImpl.updateUser 里做，"
                    + "⛔ 不能落到这个「有值就覆盖」的方法里",
            "password", "要过 PasswordEncoder，在 service 层处理",
            "roleCode", "更新时由 service 决定；这里已有 BR-13 的修复，见方法内注释",
            "isActive", "Apr 18 Bug #57：null/true/false 三态语义，显式放在 service 层",
            "reportsTo", "由 UserService 在工厂锁内验证同厂、启用状态、循环与深度后更新；组织契约覆盖写入和显式清空"
    );

    private static User baseline() {
        User u = new User();
        u.setId(1L);
        u.setFactoryId("F006");
        u.setUsername("zhangsan");
        u.setEmail("old@cretas.com");
        u.setPhone("13800000000");
        u.setFullName("张三");
        u.setEmployeeCode("E001");
        u.setPosition("旧岗位");
        u.setDepartment("sales");
        u.setMonthlySalary(new BigDecimal("5000"));
        u.setExpectedWorkMinutes(480);
        u.setCcrRate(new BigDecimal("1.00"));
        return u;
    }

    @Test
    @DisplayName("每个可更新字段单独送一次，都必须真的落到实体上")
    void everyUpdatableFieldActuallyLands() {
        StringBuilder dropped = new StringBuilder();
        for (Map.Entry<String, FieldCase> entry : updatableFields().entrySet()) {
            User user = baseline();
            CreateUserRequest request = new CreateUserRequest();
            request.setRoleCode(null);          // 只让被测字段是唯一变量
            entry.getValue().set.accept(request);

            mapper.updateEntity(user, request);

            Object actual = entry.getValue().read.apply(user);
            if (!equalsLoose(entry.getValue().expected, actual)) {
                dropped.append("\n  - ").append(entry.getKey())
                        .append(": 送了 ").append(entry.getValue().expected)
                        .append(", 实体上还是 ").append(actual);
            }
        }
        assertEquals("", dropped.toString(),
                "updateEntity 静默丢弃了字段 —— 用户会看到「编辑成功」而库里没变:" + dropped);
    }

    @Test
    @DisplayName("阳性对照：故意不写某个字段时，这道闸真的会红")
    void gateCanGoRed() {
        // 用一个「请求里没送」的场景模拟「更新路径没接」：期望值与实体现值不同，
        // 而 mapper 不会去改它 ⇒ 判等必须失败。这证明上面那条断言不是恒真式。
        User user = baseline();
        CreateUserRequest request = new CreateUserRequest();
        request.setRoleCode(null);
        // ⛔ 故意不 setDepartment
        mapper.updateEntity(user, request);
        assertNotEquals("财务部", user.getDepartment(),
                "没送 department 却变了？那说明这道闸的判等方式有问题，它给不出可信的红");
    }

    @Test
    @DisplayName("CreateUserRequest 上的每个字段，要么可更新、要么显式登记为不更新")
    void everyRequestFieldIsEitherUpdatableOrRegistered() {
        Set<String> known = new LinkedHashSet<>(updatableFields().keySet());
        known.addAll(DELIBERATELY_NOT_UPDATED.keySet());

        Set<String> unaccounted = new TreeSet<>();
        for (Method m : CreateUserRequest.class.getDeclaredMethods()) {
            if (!m.getName().startsWith("get") || m.getParameterCount() != 0) continue;
            String field = Character.toLowerCase(m.getName().charAt(3)) + m.getName().substring(4);
            if (!known.contains(field)) unaccounted.add(field);
        }

        assertTrue(unaccounted.isEmpty(),
                "CreateUserRequest 新增了字段但没决定它更新时怎么办: " + unaccounted
                        + "\n⇒ 要么加进 updatableFields() 并让 updateEntity 真的写它,"
                        + "\n  要么加进 DELIBERATELY_NOT_UPDATED 并写清为什么。"
                        + "\n⛔ 不写等于第四次「保存成功但没生效」的种子。");
    }

    @Test
    @DisplayName("department 的三态：null=不动 / 空白=清空 / 有值=覆盖")
    void departmentTriState() {
        User u1 = baseline();
        mapper.updateEntity(u1, new CreateUserRequest());
        assertEquals("sales", u1.getDepartment(), "没送就不该动");

        User u2 = baseline();
        CreateUserRequest blank = new CreateUserRequest();
        blank.setDepartment("   ");
        mapper.updateEntity(u2, blank);
        assertNull(u2.getDepartment(), "空白应当表示「清空部门」");

        User u3 = baseline();
        CreateUserRequest set = new CreateUserRequest();
        set.setDepartment("  财务部  ");
        mapper.updateEntity(u3, set);
        assertEquals("财务部", u3.getDepartment(), "两侧空格要去掉, 否则「财务部」和「财务部 」是两个部门");
    }

    private static boolean equalsLoose(Object expected, Object actual) {
        if (expected instanceof BigDecimal && actual instanceof BigDecimal) {
            return ((BigDecimal) expected).compareTo((BigDecimal) actual) == 0;
        }
        return expected == null ? actual == null : expected.equals(actual);
    }

    private static final class FieldCase {
        final java.util.function.Consumer<CreateUserRequest> set;
        final Function<User, Object> read;
        final Object expected;

        FieldCase(java.util.function.Consumer<CreateUserRequest> set, Function<User, Object> read, Object expected) {
            this.set = set;
            this.read = read;
            this.expected = expected;
        }
    }
}
