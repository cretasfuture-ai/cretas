package com.cretas.aims.repository;

import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.Department;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.HireType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
/**
 * 用户数据访问接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * 根据用户名查找用户（统一登录）
     */
    Optional<User> findByUsername(String username);

    /**
     * 根据用户名查找所有用户（用于智能推断factoryId）
     */
    List<User> findAllByUsername(String username);

    /**
     * 根据工厂ID和用户名查找用户
     */
    Optional<User> findByFactoryIdAndUsername(String factoryId, String username);

    /**
     * 根据用户 ID 和工厂 ID 查找用户。
     *
     * <p>用于所有以外部 employeeId 为对象标识的租户内查询，避免先按全局主键
     * 取出其他工厂用户后再拼接当前工厂业务数据。</p>
     */
    Optional<User> findByIdAndFactoryId(Long id, String factoryId);

    /**
     * 根据工厂ID和手机号查找用户
     */
    Optional<User> findByFactoryIdAndPhone(String factoryId, String phone);
    /**
     * 根据手机号查找用户（用于忘记密码功能）
     */
    Optional<User> findByPhone(String phone);

    /**
     * 根据手机号查找所有用户（用于忘记密码时更新多个账户）
     */
    List<User> findAllByPhone(String phone);

    /**
     * 查找工厂的所有用户
     */
    List<User> findByFactoryId(String factoryId);
    /**
     * 分页查找工厂的用户
     */
    Page<User> findByFactoryId(String factoryId, Pageable pageable);
    /**
     * 查找工厂的激活用户
     */
    List<User> findByFactoryIdAndIsActive(String factoryId, Boolean isActive);
    /**
     * 根据职位查找用户
     */
    List<User> findByFactoryIdAndPosition(String factoryId, String position);

    /**
     * 根据角色代码查找用户
     */
    List<User> findByFactoryIdAndRoleCode(String factoryId, String roleCode);

    /**
     * Sprint 6 W2-B (RBAC DataScope SELF_AND_BELOW) — 直接下属 (reports_to = managerUserId).
     * 用于递归构造下属链. Resolver iterative BFS until 闭合.
     */
    @Query("SELECT u.id FROM User u WHERE u.factoryId = :factoryId AND u.reportsTo = :managerUserId AND u.isActive = true")
    List<Long> findSubordinateIdsByReportsTo(@Param("factoryId") String factoryId,
                                             @Param("managerUserId") Long managerUserId);

    /**
     * Sprint 6 W2-B (RBAC DataScope DEPT_AND_BELOW) — 同 department 的所有 user.
     * DEPT_AND_BELOW MVP: 仅本部门. Sprint 7 再考虑 sub-department 递归.
     */
    @Query("SELECT u.id FROM User u WHERE u.factoryId = :factoryId AND u.department = :department AND u.isActive = true")
    List<Long> findUserIdsByDepartment(@Param("factoryId") String factoryId,
                                       @Param("department") String department);
    /**
     * 模糊搜索用户
     * 注意：username/phone使用右模糊（可使用索引），fullName使用双向模糊（无法使用索引）
     */
    @Query("SELECT u FROM User u WHERE u.factoryId = :factoryId AND " +
           "(LOWER(u.username) LIKE LOWER(CONCAT(:keyword, '%')) ESCAPE '\\' OR " +
           "LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
           "u.phone LIKE CONCAT(:keyword, '%') ESCAPE '\\')")
    Page<User> searchUsers(@Param("factoryId") String factoryId,
                          @Param("keyword") String keyword,
                          Pageable pageable);

    /**
     * 关键词 + 角色联合筛选（两者都可为空 = 该维不过滤）。
     *
     * <p><b>2026-09-01 新增的理由</b>：账号管理页的搜索此前<b>完全不生效</b>
     * —— 前端把 {@code username}/{@code roleCode} 传给 {@code GET /{factoryId}/users}，
     * 而那个接口只绑 {@code PageRequest}（page/size/sortBy/sortDirection/keyword/status），
     * 两个名字一个都绑不上，被 Spring 静默丢弃；即便改名成 {@code keyword} 也没用，
     * 因为 {@code UserServiceImpl.getUserList} 从头到尾没读过它，直接走
     * {@code findByFactoryId}。owner 在 LIUSHANMEN 上搜 {@code lsm}，拿回的仍是全部 33 条。
     *
     * <p>🔴 <b>为什么不是让前端改调 {@code /users/search} 就完事</b>：那条路的角色筛选
     * 是<b>在内存里对当前页做的</b>（{@code userDTOs.stream().filter(role::equals)}），
     * 并且把 {@code total} 重算成过滤后的条数。于是按角色筛选时，第 1 页只会从
     * 「全厂前 10 个人」里挑出符合的，翻页与总数全错。⇒ 两个维度都下推到 SQL，
     * 并让两个入口共用<b>这一个</b>查询，免得再长出两份会漂的过滤逻辑（形态 D）。
     *
     * <p>⚠️ 参数<b>不接受 null</b>，空串即「该维不过滤」。这样 {@code :keyword} 与
     * {@code :role} 在 JPQL 里始终有明确的 String 类型上下文 —— 本仓踩过
     * 「原生 SQL 里没类型上下文的参数前 4 次能过、第 5 次起每次必炸」
     * （pgjdbc {@code prepareThreshold=5}），不给它复发的机会。
     *
     * <p>匹配口径与 {@link #searchUsers} 保持一致：username/phone 右模糊（可走索引），
     * fullName 双向模糊。
     */
    @Query("SELECT u FROM User u WHERE u.factoryId = :factoryId AND " +
           "(:keyword = '' OR " +
           " LOWER(u.username) LIKE LOWER(CONCAT(:keyword, '%')) ESCAPE '\\' OR " +
           " LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
           " u.phone LIKE CONCAT(:keyword, '%') ESCAPE '\\') AND " +
           "(:role = '' OR u.roleCode = :role)")
    Page<User> searchUsersByKeywordAndRole(@Param("factoryId") String factoryId,
                                           @Param("keyword") String keyword,
                                           @Param("role") String role,
                                           Pageable pageable);
    /**
     * 更新用户最后登录时间
     */
    @Modifying
    @Query("UPDATE User u SET u.lastLogin = :lastLogin WHERE u.id = :userId")
    void updateLastLogin(@Param("userId") Long userId, @Param("lastLogin") LocalDateTime lastLogin);
    /**
     * 统计工厂用户数量
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.factoryId = :factoryId AND u.isActive = true")
    Long countActiveUsersByFactory(@Param("factoryId") String factoryId);
    /**
     * 检查用户名是否被占用（全局唯一，不区分工厂）—— <b>含已软删的账号</b>。
     *
     * <p>🔴 2026-08-20: 这里原来是派生查询 {@code boolean existsByUsername(String)}。
     * {@code User} 上有类级 {@code @Where(deleted_at IS NULL)}，Hibernate 会把它拼进
     * <b>每一条</b> JPQL —— 于是「这个用户名被占用了吗」被答成
     * 「<b>有没有一个还活着的人叫这个名字</b>」，这是两个问题。
     *
     * <p>而库上是 <b>全量</b>唯一索引({@code ukr43af9ap4edm43mmtq01oddj6}，非部分索引，
     * 软删行照样占位)。两者错位的净效果是: 查重说「没人用」→ 放行 → INSERT 撞唯一索引 →
     * 用户看到「数据已存在，请勿重复提交」，而他<b>一次都没重复提交</b>。
     * 本仓 2026-08-19 已经为同一个成因排查过一整轮发号器。
     *
     * <p>⚠️ 当时这个坑还是<b>潜伏</b>的（prod 软删用户数 = 0），是同一天新增的
     * 「删除员工账号」入口(R-2②)把它变成可达的 —— 删一个人就造出一条软删行。
     * <b>两个各自正确的改动叠加出的缺陷</b>，单看任何一边都没问题。
     *
     * <p>⛔ 不要改成 JPQL 加 {@code OR deletedAt IS NOT NULL} 绕 ——
     * {@code @Where} 是 AND 进去的，绕不开。⛔ 也不要把索引改成部分索引:
     * 用户名是登录凭证，且历史日志表存的是<b>当时的用户名快照</b>，
     * 放开回收会让旧日志指向另一个人。<b>号发出去就不该回收。</b>
     *
     * <p>⚠️ 故意<b>不</b>包一层 {@code default boolean isUsernameTaken(..)}: 那样调用点会写成
     * {@code isUsernameTaken(x)}，<b>看不出它查的是哪一路</b>——而「查的是哪一路」正是这里
     * 唯一要守的事。而且 Mockito 会拦截接口的 default 方法，对底层 native 方法的打桩会被
     * 静默忽略，测试将以错误的理由变绿。⇒ 调用点直接写 {@code ... > 0}，方法名自己说清含义。
     */
    @Query(value = "SELECT COUNT(*) FROM users WHERE username = :username", nativeQuery = true)
    long countByUsernameIncludingDeleted(@Param("username") String username);

    /**
     * 获取有薪资信息的用户
     */
    @Query("SELECT u FROM User u WHERE u.factoryId = :factoryId AND u.monthlySalary IS NOT NULL")
    List<User> findUsersWithSalary(@Param("factoryId") String factoryId);
    /**
     * 统计工厂的用户总数
     */
    long countByFactoryId(String factoryId);
    /**
     * 统计工厂的激活用户数
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.factoryId = :factoryId AND u.isActive = true")
    long countActiveUsers(@Param("factoryId") String factoryId);
    /**
     * 按部门统计用户数量
     */
    @Query("SELECT u.department, COUNT(u) FROM User u WHERE u.factoryId = :factoryId GROUP BY u.department")
    List<Object[]> countByDepartment(@Param("factoryId") String factoryId);

    /**
     * 统计指定工厂和部门的用户数量
     */
    long countByFactoryIdAndDepartment(String factoryId, String department);

    /**
     * 统计指定激活状态的用户总数（跨所有工厂）
     *
     * @param isActive 是否激活
     * @return 用户数量
     * @since 2025-11-20
     */
    long countByIsActive(Boolean isActive);

    /**
     * 根据入职日期范围查询用户（分页）
     * 使用 createdAt 字段作为入职日期
     *
     * @param factoryId 工厂ID
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @param pageable  分页参数
     * @return 用户分页数据
     * @since 2025-12-27
     */
    @Query("SELECT u FROM User u WHERE u.factoryId = :factoryId " +
           "AND u.createdAt >= :startDate AND u.createdAt < :endDate " +
           "ORDER BY u.createdAt DESC")
    Page<User> findByFactoryIdAndCreatedAtBetween(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable);

    /**
     * 统计指定日期范围内入职的用户数量
     *
     * @param factoryId 工厂ID
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @return 用户数量
     * @since 2025-12-27
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.factoryId = :factoryId " +
           "AND u.createdAt >= :startDate AND u.createdAt < :endDate")
    long countByFactoryIdAndCreatedAtBetween(
            @Param("factoryId") String factoryId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    // ==================== 调度员模块扩展方法 ====================

    /**
     * 根据工厂ID和工号查找用户
     *
     * @param factoryId 工厂ID
     * @param employeeCode 工号
     * @return 用户
     * @since 2025-12-28
     */
    Optional<User> findByFactoryIdAndEmployeeCode(String factoryId, String employeeCode);

    /**
     * 根据工号查找用户 (全局唯一)
     *
     * @param employeeCode 工号
     * @return 用户
     * @since 2025-12-28
     */
    Optional<User> findByEmployeeCode(String employeeCode);

    /**
     * 查找合同即将到期的员工
     *
     * @param factoryId 工厂ID
     * @param warningDate 预警日期
     * @return 用户列表
     * @since 2025-12-28
     */
    @Query("SELECT u FROM User u WHERE u.factoryId = :factoryId " +
           "AND u.contractEndDate IS NOT NULL " +
           "AND u.contractEndDate <= :warningDate " +
           "ORDER BY u.contractEndDate ASC")
    List<User> findByFactoryIdAndContractEndDateBefore(
            @Param("factoryId") String factoryId,
            @Param("warningDate") LocalDate warningDate);

    /**
     * 统计合同即将到期的员工数量 (30天内)
     *
     * @param factoryId 工厂ID
     * @param warningDate 预警日期 (当前日期 + 30天)
     * @return 即将到期的合同数量
     * @since 2026-01-03
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.factoryId = :factoryId " +
           "AND u.contractEndDate IS NOT NULL " +
           "AND u.contractEndDate >= CURRENT_DATE " +
           "AND u.contractEndDate <= :warningDate " +
           "AND u.isActive = true")
    long countExpiringContracts(
            @Param("factoryId") String factoryId,
            @Param("warningDate") LocalDate warningDate);

    /**
     * 根据雇用类型查找用户
     *
     * @param factoryId 工厂ID
     * @param hireType 雇用类型
     * @return 用户列表
     * @since 2025-12-28
     */
    List<User> findByFactoryIdAndHireType(String factoryId, HireType hireType);

    /**
     * 根据多个雇用类型查找用户
     *
     * @param factoryId 工厂ID
     * @param hireTypes 雇用类型列表
     * @return 用户列表
     * @since 2025-12-28
     */
    List<User> findByFactoryIdAndHireTypeIn(String factoryId, List<HireType> hireTypes);

    /**
     * 获取工厂最大的工号
     *
     * @param factoryId 工厂ID
     * @return 最大工号
     * @since 2025-12-28
     */
    @Query("SELECT MAX(u.employeeCode) FROM User u WHERE u.factoryId = :factoryId AND u.employeeCode IS NOT NULL")
    String findMaxEmployeeCodeByFactoryId(@Param("factoryId") String factoryId);

    /**
     * 统计工厂临时工数量
     *
     * @param factoryId 工厂ID
     * @param hireTypes 临时性质的雇用类型列表
     * @return 临时工数量
     * @since 2025-12-28
     */
    @Query("SELECT COUNT(u) FROM User u WHERE u.factoryId = :factoryId " +
           "AND u.hireType IN :hireTypes AND u.isActive = true")
    long countTemporaryWorkers(
            @Param("factoryId") String factoryId,
            @Param("hireTypes") List<HireType> hireTypes);

    /**
     * 模糊搜索用户（支持工号搜索）
     *
     * @param factoryId 工厂ID
     * @param keyword 关键词
     * @param pageable 分页参数
     * @return 用户分页数据
     * @since 2025-12-28
     */
    @Query("SELECT u FROM User u WHERE u.factoryId = :factoryId AND " +
           "(LOWER(u.username) LIKE LOWER(CONCAT(:keyword, '%')) ESCAPE '\\' OR " +
           "LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) ESCAPE '\\' OR " +
           "u.phone LIKE CONCAT(:keyword, '%') ESCAPE '\\' OR " +
           "u.employeeCode LIKE CONCAT(:keyword, '%') ESCAPE '\\')")
    Page<User> searchUsersWithEmployeeCode(@Param("factoryId") String factoryId,
                                           @Param("keyword") String keyword,
                                           Pageable pageable);

    /**
     * 批量查询多个用户 - 解决 N+1 查询问题
     * @param ids 用户ID集合
     * @return 用户列表
     */
    List<User> findByIdIn(Collection<Long> ids);

    /**
     * 统计工厂内指定角色的管理员数量（排除软删除用户）
     *
     * 用于 A-3 数据质量队列 4-eye 降级判断：当工厂只有 1 个管理员时，
     * 自动降级为单人审核模式。platform_admin 不计入（跨工厂角色）。
     *
     * @param factoryId 工厂ID
     * @param roleCodes 角色代码列表（factory_super_admin / permission_admin / factory_admin）
     * @return 符合条件的用户数
     * @since 2026-04-28
     */
    long countByFactoryIdAndRoleCodeIn(String factoryId, List<String> roleCodes);

    /**
     * R-2① (2026-08-20 手动测试台账): 按 id 取姓名，**含软删**。
     *
     * <p>⚠️ 必须 {@code nativeQuery = true}：{@code User} 类级
     * {@code @Where(clause = "deleted_at IS NULL")} 由 Hibernate 在 HQL/JPQL 层拼装，
     * 原生 SQL 不经过它。改回 JPQL（或普通 Spring Data 派生查询）会静默恢复
     * 「账号一软删，历史单据的申请人/审批人姓名就变回空白」这个 bug ——
     * 见 {@link com.cretas.aims.repository.SoftDeleteBlindSpotContractTest}。
     *
     * <p>这条查询的语义是「这个 id 曾经是谁」（历史审计展示），不是「这个用户现在还在不在」——
     * 后者仍然应该走会被 {@code @Where} 过滤的普通查询（软删的账号不该出现在任何
     * 「当前有效用户」的列表/选择器里）。
     *
     * @return 姓名为空时回退用户名，都取不到时 {@code null}（调用方决定怎么显示）
     */
    @Query(value = "SELECT COALESCE(NULLIF(full_name, ''), username) FROM users WHERE id = :id",
            nativeQuery = true)
    String findDisplayNameByIdIncludingDeleted(@Param("id") Long id);
}
