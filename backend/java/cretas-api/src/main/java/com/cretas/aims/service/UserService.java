package com.cretas.aims.service;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.user.CreateUserRequest;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.HireType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
/**
 * 用户服务接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
public interface UserService {
    /**
     * 创建用户
     */
    UserDTO createUser(String factoryId, CreateUserRequest request);

    /**
     * 更新用户信息
     */
    UserDTO updateUser(String factoryId, Long userId, CreateUserRequest request);

    /**
     * 删除用户
     */
    void deleteUser(String factoryId, Long userId);

    /**
     * 获取用户详情
     */
    UserDTO getUserById(String factoryId, Long userId);

    /**
     * 获取用户列表(分页)
     */
    PageResponse<UserDTO> getUserList(String factoryId, PageRequest pageRequest);

    /**
     * 根据角色获取用户列表
     */
    List<UserDTO> getUsersByRole(String factoryId, FactoryUserRole role);

    /**
     * 激活用户
     */
    void activateUser(String factoryId, Long userId);

    /**
     * 停用用户
     */
    void deactivateUser(String factoryId, Long userId);

    /**
     * 更新用户角色
     */
    void updateUserRole(String factoryId, Long userId, FactoryUserRole newRole);

    /**
     * 检查用户名是否存在
     */
    boolean checkUsernameExists(String factoryId, String username);

    /**
     * 检查邮箱是否存在
     */
    boolean checkEmailExists(String factoryId, String email);

    /**
     * 搜索用户
     */
    PageResponse<UserDTO> searchUsers(String factoryId, String keyword, PageRequest pageRequest);

    /**
     * 搜索用户（支持角色筛选）
     */
    PageResponse<UserDTO> searchUsers(String factoryId, String keyword, String role, PageRequest pageRequest);

    /**
     * 批量导入用户
     */
    List<UserDTO> batchImportUsers(String factoryId, List<CreateUserRequest> requests);

    /**
     * 导出用户列表
     */
    byte[] exportUsers(String factoryId);

    /**
     * 生成用户导入模板
     */
    byte[] generateImportTemplate();
     /**
     * 从Excel文件批量导入用户
     */
    com.cretas.aims.dto.common.ImportResult<UserDTO> importUsersFromExcel(String factoryId, java.io.InputStream inputStream);

    /**
     * 根据入职日期范围获取用户列表（分页）
     * HR Dashboard 用于显示"本月入职"统计
     *
     * @param factoryId 工厂ID
     * @param startDate 开始日期
     * @param endDate   结束日期
     * @param page      页码（从1开始）
     * @param size      每页大小
     * @return 分页用户数据
     * @since 2025-12-27
     */
    PageResponse<UserDTO> getUsersByJoinDateRange(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int size);

    // ==================== 调度员模块扩展方法 ====================

    /**
     * 按工号查询用户
     */
    UserDTO getUserByEmployeeCode(String factoryId, String employeeCode);

    /**
     * 更新用户工号
     */
    void updateEmployeeCode(String factoryId, Long userId, String employeeCode);

    /**
     * 获取用户技能
     */
    Map<String, Integer> getUserSkills(String factoryId, Long userId);

    /**
     * 更新用户技能
     */
    void updateUserSkills(String factoryId, Long userId, Map<String, Integer> skillLevels);

    /**
     * 获取合同即将到期的员工
     */
    List<UserDTO> getExpiringContracts(String factoryId, Integer daysAhead);

    /**
     * 按雇用类型获取用户
     */
    List<UserDTO> getUsersByHireType(String factoryId, HireType hireType);

    /**
     * 获取所有临时性质员工
     */
    List<UserDTO> getTemporaryWorkers(String factoryId);

    /**
     * 生成下一个可用工号
     */
    String generateNextEmployeeCode(String factoryId);

    /**
     * 为新工厂自动创建默认用户账号（幂等操作）
     * 在 linkSurveyCompany 时触发
     */
    List<UserDTO> provisionDefaultUsers(String factoryId);

    /**
     * 🔴 2026-08-22: 员工名录用 —— 在职用户, 可按角色过滤。
     *
     * <p>⚠️ 返回**实体**而不是 UserDTO 是刻意的: 名录只投影六个字段,
     * 借道 UserDTO 会让「以后给 DTO 加个敏感字段」自动泄露到名录里。
     * 投影在 controller 里显式写死, 加字段必须有人动那段代码。
     */
    List<User> getActiveUsersForDirectory(String factoryId, String roleCode);

    /**
     * 用户改**自己**的资料 —— 只允许姓名与邮箱。
     *
     * <p>⛔ 与 {@link #updateUser} 分开是刻意的: 后者收 {@code CreateUserRequest},
     * 里面带着角色 / 工厂 / 启用状态 / 用户名 / 密码 —— 把它开给自助修改就是一条提权路径。
     *
     * @param factoryId 当前工厂; 与用户实际所属工厂不一致时拒绝(跨厂改人)
     * @param userId    **从 token 解析的**当前用户 id, ⛔ 不接受请求方传入
     */
    UserDTO updateMyProfile(String factoryId, Long userId, com.cretas.aims.dto.user.UpdateMyProfileRequest request);
}
