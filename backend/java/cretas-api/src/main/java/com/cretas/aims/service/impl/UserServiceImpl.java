package com.cretas.aims.service.impl;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.user.CreateUserRequest;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.HireType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.EntityNotFoundException;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
public class UserServiceImpl implements UserService {
    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserRepository userRepository;
    private final FactoryRepository factoryRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final com.cretas.aims.utils.ExcelUtil excelUtil;

    // Manual constructor (Lombok @RequiredArgsConstructor not working)
    public UserServiceImpl(UserRepository userRepository, FactoryRepository factoryRepository,
                          UserMapper userMapper, PasswordEncoder passwordEncoder,
                          com.cretas.aims.utils.ExcelUtil excelUtil) {
        this.userRepository = userRepository;
        this.factoryRepository = factoryRepository;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.excelUtil = excelUtil;
    }

    @Override
    @Transactional
    public UserDTO createUser(String factoryId, CreateUserRequest request) {
        // Apr 20 Bug BR-13 fix: DTO 去 @NotBlank 后, 此处补 create 场景校验
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new BusinessException(400, "用户名不能为空")
                    .withHint("请填写用户名").withHintTarget("username");
        }
        // 检查用户名是否已存在（用户名全局唯一）
        if (userRepository.countByUsernameIncludingDeleted(request.getUsername()) > 0) {
            throw new BusinessException(409, "用户名已存在")
                    .withHint("请使用不同的用户名").withHintTarget("username");
        }

        // 创建用户实体
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            request.setPassword("123456"); // 默认密码
        }
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            request.setEmail(request.getUsername() + "@cretas.com"); // 默认邮箱
        }
        User user = userMapper.toEntity(request, factoryId);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        // 保存用户
        user = userRepository.save(user);

        log.info("创建用户成功: factoryId={}, username={}", factoryId, user.getUsername());
        return userMapper.toDTO(user);
    }

    @Override
    @Transactional
    public UserDTO updateUser(String factoryId, Long userId, CreateUserRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", String.valueOf(userId)));

        // 验证工厂ID
        if (!user.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该用户")
                    .withHint("当前用户不属于该工厂, 无法操作");
        }

        // ── R-1 (2026-08-20): 允许改用户名, 但它是**登录凭证**, 不是普通字段 ──
        //
        // 之前前端把输入框写死 `:disabled="dialogMode === 'edit'"`, 谁都改不了。
        // 后端其实一直留着口子(CreateUserRequest.username 的注释就写着
        // 「如果 update 场景 frontend 真送了 username, 也该过校验」), 只是没人做查重。
        //
        // ⛔ 不能靠 DB 唯一索引兜底 —— 那时抛出来的是「数据已存在, 请勿重复提交」这类
        //    看不懂的话(本仓 2026-08-19 刚因为这类措辞排查过一整轮软删撞号)。
        //    ⇒ 在这里显式查重, 给出人能看懂的原因。
        //
        // ⚠️ username 改名**不会断链**: prod 实测带 username 列的 5 张业务表
        //    (operation_logs / system_logs / voice_recognition_history /
        //     batch_voice_task / intent_preview_tokens) **每一张都同时存了 user_id**,
        //    且全库没有任何外键引用 username。那些列是**当时的快照**, 历史日志保留旧名字
        //    正是审计想要的行为。⛔ 不要为了「保持一致」去回填历史。
        String newUsername = request.getUsername();
        String oldUsername = user.getUsername();
        boolean usernameChanged = newUsername != null
                && !newUsername.isBlank()
                && !newUsername.equals(oldUsername);
        if (usernameChanged && userRepository.countByUsernameIncludingDeleted(newUsername) > 0) {
            throw new BusinessException(409, "用户名「" + newUsername + "」已被占用")
                    .withHint("换一个用户名再保存; 用户名是登录账号, 全平台唯一")
                    .withHintTarget("username");
        }

        // 更新用户信息
        userMapper.updateEntity(user, request);

        // ⚠️ username 必须在这里落, ⛔ 不能丢进 userMapper.updateEntity ——
        // 那里是「有值就覆盖」的无条件复制, 而改用户名必须先过上面那道查重。
        // 放在同一个方法里, 查重与写入之间不可能漂开(本仓形态 D: 同一件事有两份, 它一定会漂)。
        //
        // 🔴 这一行本身就是 2026-08-20 差点漏掉的那半条: 前端解禁了输入框、后端做了查重,
        //    **而 updateEntity 根本不复制 username** —— 改完会提示「编辑成功」, 下次登录
        //    仍然只认旧名字。典型的「机制在、没接上」, 而且全程不报错。
        if (usernameChanged) {
            user.setUsername(newUsername);
        }

        // 如果提供了新密码，则更新密码
        if (request.getPassword() != null && !request.getPassword().isEmpty()) {
            user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        }

        // Apr 18 2026 Bug #57: isActive 在 MapStruct 的 updateEntity 中可能因
        // @Mapping ignore=true 而不被更新, 显式处理切换在职/离职 UI 路径。
        // null 表示前端没送 (create 场景),true/false 都要应用。
        if (request.getIsActive() != null) {
            user.setIsActive(request.getIsActive());
        }

        user = userRepository.save(user);

        // R-1: 改名必须留痕 —— ⚠️ 这条最容易漏。用户名改完之后, 历史日志里那个旧名字
        // 就对不上人了(那些表存的是快照), 唯一能把 A 和 B 连起来的就是这行记录。
        // ⛔ 不要只打 "更新用户成功" —— 那看不出谁被改成了什么。
        if (usernameChanged) {
            log.warn("用户名变更: factoryId={}, userId={}, {} -> {}",
                    factoryId, userId, oldUsername, newUsername);
        }
        log.info("更新用户成功: userId={}", userId);
        return userMapper.toDTO(user);
    }

    @Override
    @Transactional
    public void deleteUser(String factoryId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", String.valueOf(userId)));

        // 验证工厂ID
        if (!user.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该用户")
                    .withHint("当前用户不属于该工厂, 无法操作");
        }

        // 不允许删除超级管理员。
        //
        // 🔴 2026-08-20: 这道闸原来**只看 position**, 而 position 是个可空的遗留字段 ——
        //    prod 实测 11 个 factory_super_admin 里 **6 个 position 为 NULL**,
        //    也就是说这道闸对其中 6 个人**根本不成立**。
        //    在没有删除入口的年代它是潜伏的; R-2② 加了删除按钮, 它当场变成可达的。
        //    ⇒ 权威字段是 role_code, position 只作为遗留兼容保留。
        //    ⚠️ 与 username 那条同一个形状: 两个各自「正确」的东西叠在一起才出事。
        if (isFactorySuperAdmin(user)) {
            throw new BusinessException(403, "不能删除超级管理员")
                    .withHint("超级管理员账号受系统保护, 无法删除");
        }

        // ⚠️ 这是**软删**(User extends BaseEntity, @SQLDelete 改写成 UPDATE deleted_at)。
        // 所以 36 张引用 users 的外键(全部 ON DELETE NO ACTION)不会炸,
        // 他建过的单据也仍然挂在他名下 —— 前端确认框里承诺的正是这件事。
        userRepository.delete(user);
        log.warn("删除用户: factoryId={}, userId={}, username={} (软删, 历史单据保留)",
                factoryId, userId, user.getUsername());
    }

    /**
     * 是否工厂超级管理员。
     *
     * <p>权威字段是 {@code roleCode}；{@code position} 是遗留字段(prod 上一半为 NULL)，
     * 仅作兼容，⛔ 不能单独作为判据。
     */
    private boolean isFactorySuperAdmin(User user) {
        if ("factory_super_admin".equals(user.getRoleCode())) {
            return true;
        }
        String position = user.getPosition();
        return position != null
                && (position.equals("factory_super_admin") || position.equals("超级管理员"));
    }

    @Override
    public UserDTO getUserById(String factoryId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", String.valueOf(userId)));

        // 验证工厂ID
        if (!user.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权查看该用户")
                    .withHint("当前用户不属于该工厂, 无法查看");
        }

        return userMapper.toDTO(user);
    }

    @Override
    public PageResponse<UserDTO> getUserList(String factoryId, PageRequest pageRequest) {
        Sort sort = Sort.by(
                pageRequest.getSortDirection().equalsIgnoreCase("DESC") ?
                Sort.Direction.DESC : Sort.Direction.ASC,
                pageRequest.getSortBy()
        );

        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                sort
        );

        Page<User> userPage = userRepository.findByFactoryId(factoryId, pageable);

        List<UserDTO> userDTOs = userPage.getContent().stream()
                .map(userMapper::toDTO)
                .collect(Collectors.toList());

        return PageResponse.of(
                userDTOs,
                pageRequest.getPage(),
                pageRequest.getSize(),
                userPage.getTotalElements()
        );
    }

    @Override
    public List<UserDTO> getUsersByRole(String factoryId, FactoryUserRole role) {
        // 使用角色代码查询
        return userRepository.findByFactoryIdAndRoleCode(factoryId, role.name())
                .stream()
                .map(userMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void activateUser(String factoryId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", String.valueOf(userId)));

        // 验证工厂ID
        if (!user.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该用户")
                    .withHint("当前用户不属于该工厂, 无法操作");
        }

        user.setIsActive(true);
        userRepository.save(user);

        log.info("激活用户成功: userId={}", userId);
    }

    @Override
    @Transactional
    public void deactivateUser(String factoryId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", String.valueOf(userId)));

        // 验证工厂ID
        if (!user.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该用户")
                    .withHint("当前用户不属于该工厂, 无法操作");
        }

        // 不允许停用超级管理员（通过职位判断）
        if (user.getPosition() != null &&
            (user.getPosition().equals("factory_super_admin") ||
             user.getPosition().equals("超级管理员"))) {
            throw new BusinessException(403, "不能停用超级管理员")
                    .withHint("超级管理员账号受系统保护, 无法停用");
        }

        user.setIsActive(false);
        userRepository.save(user);

        log.info("停用用户成功: userId={}", userId);
    }

    @Override
    @Transactional
    public void updateUserRole(String factoryId, Long userId, FactoryUserRole newRole) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", String.valueOf(userId)));

        // 验证工厂ID
        if (!user.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该用户")
                    .withHint("当前用户不属于该工厂, 无法操作");
        }

        // 更新职位字段（roleCode已删除）
        user.setPosition(newRole.name());
        userRepository.save(user);

        log.info("更新用户角色成功: userId={}, newRole={}", userId, newRole);
    }

    @Override
    public boolean checkUsernameExists(String factoryId, String username) {
        // 用户名全局唯一，不区分工厂
        return userRepository.countByUsernameIncludingDeleted(username) > 0;
    }

    @Override
    public boolean checkEmailExists(String factoryId, String email) {
        // email字段已删除，返回false
        return false;
    }

    @Override
    public PageResponse<UserDTO> searchUsers(String factoryId, String keyword, PageRequest pageRequest) {
        return searchUsers(factoryId, keyword, null, pageRequest);
    }

    @Override
    public PageResponse<UserDTO> searchUsers(String factoryId, String keyword, String role, PageRequest pageRequest) {
        Sort sort = Sort.by(
                pageRequest.getSortDirection().equalsIgnoreCase("DESC") ?
                Sort.Direction.DESC : Sort.Direction.ASC,
                pageRequest.getSortBy()
        );

        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.getPage() - 1,
                pageRequest.getSize(),
                sort
        );

        Page<User> userPage = userRepository.searchUsers(factoryId,
            com.cretas.aims.util.SqlLikeEscaper.escape(keyword), pageable);

        List<UserDTO> userDTOs = userPage.getContent().stream()
                .filter(u -> role == null || role.isBlank() || role.equals(u.getRoleCode()))
                .map(userMapper::toDTO)
                .collect(Collectors.toList());

        // Recalculate total after role filter (in-memory, factory user lists are typically <50)
        long total = role == null || role.isBlank()
                ? userPage.getTotalElements()
                : userDTOs.size();

        return PageResponse.of(
                userDTOs,
                pageRequest.getPage(),
                pageRequest.getSize(),
                total
        );
    }

    @Override
    @Transactional
    public List<UserDTO> batchImportUsers(String factoryId, List<CreateUserRequest> requests) {
        return requests.stream()
                .map(request -> {
                    try {
                        return createUser(factoryId, request);
                    } catch (Exception e) {
                        log.error("导入用户失败: {}", e.getMessage());
                        return null;
                    }
                })
                .filter(user -> user != null)
                .collect(Collectors.toList());
    }

    @Override
    public byte[] exportUsers(String factoryId) {
        log.info("导出用户列表: factoryId={}", factoryId);

        // 查询所有用户
        List<User> users = userRepository.findByFactoryId(factoryId);

        // 转换为DTO
        List<UserDTO> userDTOs = users.stream()
                .map(userMapper::toDTO)
                .collect(Collectors.toList());

        // 转换为Excel导出DTO
        List<com.cretas.aims.dto.user.UserExportDTO> exportDTOs = userDTOs.stream()
                .map(com.cretas.aims.dto.user.UserExportDTO::fromUserDTO)
                .collect(Collectors.toList());

        // 生成Excel文件
        byte[] excelBytes = excelUtil.exportToExcel(
                exportDTOs,
                com.cretas.aims.dto.user.UserExportDTO.class,
                "用户列表"
        );

        log.info("用户列表导出成功: factoryId={}, count={}", factoryId, users.size());
        return excelBytes;
    }

    @Override
    public byte[] generateImportTemplate() {
        log.info("生成用户导入模板");

        // 使用ExcelUtil生成空模板
        byte[] templateBytes = excelUtil.generateTemplate(
                com.cretas.aims.dto.user.UserExportDTO.class,
                "用户导入模板"
        );

        log.info("用户导入模板生成成功");
        return templateBytes;
    }

    @Override
    // 不使用@Transactional，让每个save操作独立进行，避免单行失败导致整体回滚
    public com.cretas.aims.dto.common.ImportResult<UserDTO> importUsersFromExcel(
            String factoryId,
            java.io.InputStream inputStream) {
        log.info("开始从Excel批量导入用户: factoryId={}", factoryId);

        // 1. 解析Excel文件
        List<com.cretas.aims.dto.user.UserExportDTO> excelData;
        try {
            excelData = excelUtil.importFromExcel(inputStream,
                    com.cretas.aims.dto.user.UserExportDTO.class);
        } catch (Exception e) {
            log.error("Excel文件解析失败: factoryId={}", factoryId, e);
            throw new RuntimeException("Excel文件格式错误或无法解析: " + e.getMessage());
        }

        com.cretas.aims.dto.common.ImportResult<UserDTO> result =
                com.cretas.aims.dto.common.ImportResult.create(excelData.size());

        // 2. 逐行验证并导入
        for (int i = 0; i < excelData.size(); i++) {
            com.cretas.aims.dto.user.UserExportDTO exportDTO = excelData.get(i);
            int rowNumber = i + 2; // Excel行号（从2开始，1是表头）

            try {
                // 2.1 验证必填字段
                if (exportDTO.getUsername() == null || exportDTO.getUsername().trim().isEmpty()) {
                    result.addFailure(rowNumber, "用户名不能为空", toJsonString(exportDTO));
                    continue;
                }

                // 2.2 验证用户名唯一性（全局唯一）
                if (userRepository.countByUsernameIncludingDeleted(exportDTO.getUsername()) > 0) {
                    result.addFailure(rowNumber, "用户名已存在: " + exportDTO.getUsername(),
                            toJsonString(exportDTO));
                    continue;
                }

                // 2.3 转换为Entity
                User user = convertFromExportDTO(exportDTO, factoryId);

                // 2.4 保存
                User saved = userRepository.save(user);

                // 2.5 转换为DTO并记录成功
                UserDTO dto = userMapper.toDTO(saved);
                result.addSuccess(dto);

                log.debug("成功导入用户: row={}, username={}", rowNumber, exportDTO.getUsername());

            } catch (Exception e) {
                log.error("导入用户失败: factoryId={}, row={}, data={}", factoryId, rowNumber, exportDTO, e);
                result.addFailure(rowNumber, "保存失败: " + e.getMessage(), toJsonString(exportDTO));
            }
        }

        log.info("用户批量导入完成: factoryId={}, total={}, success={}, failure={}",
                factoryId, result.getTotalCount(), result.getSuccessCount(), result.getFailureCount());
        return result;
    }

    /**
     * 从UserExportDTO转换为User实体
     */
    private User convertFromExportDTO(com.cretas.aims.dto.user.UserExportDTO dto, String factoryId) {
        User user = new User();
        // User实体的ID是Integer自增类型，不需要手动设置
        user.setFactoryId(factoryId);
        user.setUsername(dto.getUsername());

        // 生成默认密码: username123
        String defaultPassword = dto.getUsername() + "123";
        user.setPasswordHash(passwordEncoder.encode(defaultPassword));

        user.setFullName(dto.getFullName());
        // User实体没有email字段，跳过
        user.setPhone(dto.getPhone());
        user.setDepartment(dto.getDepartmentDisplayName());
        user.setPosition(dto.getPosition());

        // 解析角色（roleDisplayName转roleCode）
        user.setRoleCode(parseRoleCode(dto.getRoleDisplayName()));

        user.setMonthlySalary(dto.getMonthlySalary());
        user.setExpectedWorkMinutes(dto.getExpectedWorkMinutes());
        user.setIsActive("激活".equals(dto.getStatus()));
        user.setCreatedAt(java.time.LocalDateTime.now());
        user.setUpdatedAt(java.time.LocalDateTime.now());

        return user;
    }

    /**
     * 将角色显示名称转换为角色代码
     */
    private String parseRoleCode(String roleDisplayName) {
        if (roleDisplayName == null || roleDisplayName.trim().isEmpty()) {
            return "factory_operator"; // 默认角色
        }

        // 根据显示名称映射到角色代码
        switch (roleDisplayName.trim()) {
            case "工厂超级管理员":
                return "factory_super_admin";
            case "工厂管理员":
                return "factory_admin";
            case "部门管理员":
                return "department_admin";
            case "生产主管":
                return "production_supervisor";
            case "质检员":
                return "quality_inspector";
            case "操作员":
                return "factory_operator";
            default:
                log.warn("未识别的角色显示名称: {}, 使用默认角色", roleDisplayName);
                return "factory_operator";
        }
    }

    /**
     * 将对象转换为JSON字符串
     */
    private String toJsonString(Object obj) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    @Override
    public PageResponse<UserDTO> getUsersByJoinDateRange(
            String factoryId,
            LocalDate startDate,
            LocalDate endDate,
            int page,
            int size) {
        log.info("查询入职日期范围内用户: factoryId={}, startDate={}, endDate={}, page={}, size={}",
                factoryId, startDate, endDate, page, size);

        // 转换为 LocalDateTime（开始时间 00:00:00，结束时间为次日 00:00:00）
        LocalDateTime startDateTime = startDate.atStartOfDay();
        LocalDateTime endDateTime = endDate.plusDays(1).atStartOfDay();

        // 创建分页请求
        org.springframework.data.domain.PageRequest pageable = org.springframework.data.domain.PageRequest.of(
                page - 1,  // Spring Data 页码从0开始
                size
        );

        // 查询数据
        Page<User> userPage = userRepository.findByFactoryIdAndCreatedAtBetween(
                factoryId,
                startDateTime,
                endDateTime,
                pageable
        );

        // 转换为 DTO
        List<UserDTO> userDTOs = userPage.getContent().stream()
                .map(userMapper::toDTO)
                .collect(Collectors.toList());

        log.info("查询入职日期范围内用户成功: factoryId={}, totalElements={}",
                factoryId, userPage.getTotalElements());

        return PageResponse.of(
                userDTOs,
                page,
                size,
                userPage.getTotalElements()
        );
    }

    // ==================== 调度员模块扩展实现 ====================

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public UserDTO getUserByEmployeeCode(String factoryId, String employeeCode) {
        User user = userRepository.findByFactoryIdAndEmployeeCode(factoryId, employeeCode)
                .orElseThrow(() -> new EntityNotFoundException("User", employeeCode));
        return userMapper.toDTO(user);
    }

    @Override
    @Transactional
    public void updateEmployeeCode(String factoryId, Long userId, String employeeCode) {
        // 验证工号格式 (001-999)
        if (!employeeCode.matches("^\\d{3}$")) {
            throw new BusinessException(400, "工号格式错误，必须是3位数字 (001-999)")
                    .withHint("请输入3位数字, 如 001 / 042 / 100").withHintTarget("employeeCode");
        }

        int code = Integer.parseInt(employeeCode);
        if (code < 1 || code > 999) {
            throw new BusinessException(400, "工号必须在 001-999 范围内")
                    .withHint("有效范围 001-999").withHintTarget("employeeCode");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", String.valueOf(userId)));

        // 验证工厂ID
        if (!user.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该用户")
                    .withHint("当前用户不属于该工厂, 无法操作");
        }

        // 检查工号是否已被其他用户使用
        userRepository.findByEmployeeCode(employeeCode).ifPresent(existingUser -> {
            if (!existingUser.getId().equals(userId)) {
                throw new BusinessException(409, "工号已被使用: " + employeeCode)
                        .withHint("请使用其他工号, 或先停用占用该工号的员工").withHintTarget("employeeCode");
            }
        });

        user.setEmployeeCode(employeeCode);
        userRepository.save(user);
        log.info("更新用户工号成功: userId={}, employeeCode={}", userId, employeeCode);
    }

    @Override
    public Map<String, Integer> getUserSkills(String factoryId, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", String.valueOf(userId)));

        // 验证工厂ID
        if (!user.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权查看该用户")
                    .withHint("当前用户不属于该工厂, 无法查看");
        }

        return parseSkillLevels(user.getSkillLevels());
    }

    @Override
    @Transactional
    public void updateUserSkills(String factoryId, Long userId, Map<String, Integer> skillLevels) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", String.valueOf(userId)));

        // 验证工厂ID
        if (!user.getFactoryId().equals(factoryId)) {
            throw new BusinessException(403, "无权操作该用户")
                    .withHint("当前用户不属于该工厂, 无法操作");
        }

        // 验证技能等级范围 (1-5)
        for (Map.Entry<String, Integer> entry : skillLevels.entrySet()) {
            if (entry.getValue() < 1 || entry.getValue() > 5) {
                throw new BusinessException(400, "技能等级必须在 1-5 范围内: " + entry.getKey())
                        .withHint("请将该技能等级调整为 1-5 之间的整数").withHintTarget("skillLevel");
            }
        }

        // 转换为JSON
        String skillLevelsJson = serializeSkillLevels(skillLevels);
        user.setSkillLevels(skillLevelsJson);
        userRepository.save(user);

        log.info("更新用户技能成功: userId={}, skills={}", userId, skillLevels);
    }

    @Override
    public List<UserDTO> getExpiringContracts(String factoryId, Integer daysAhead) {
        LocalDate warningDate = LocalDate.now().plusDays(daysAhead);

        List<User> users = userRepository.findByFactoryIdAndContractEndDateBefore(factoryId, warningDate);

        return users.stream()
                .filter(u -> u.getContractEndDate() != null && u.getContractEndDate().isAfter(LocalDate.now()))
                .map(userMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<UserDTO> getUsersByHireType(String factoryId, HireType hireType) {
        List<User> users = userRepository.findByFactoryIdAndHireType(factoryId, hireType);
        return users.stream()
                .map(userMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<UserDTO> getTemporaryWorkers(String factoryId) {
        // 临时性质的雇用类型: PART_TIME, DISPATCH, INTERN, TEMPORARY
        List<HireType> temporaryTypes = List.of(
                HireType.PART_TIME,
                HireType.DISPATCH,
                HireType.INTERN,
                HireType.TEMPORARY
        );

        List<User> users = userRepository.findByFactoryIdAndHireTypeIn(factoryId, temporaryTypes);
        return users.stream()
                .map(userMapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public String generateNextEmployeeCode(String factoryId) {
        // 获取当前工厂最大的工号
        String maxCode = userRepository.findMaxEmployeeCodeByFactoryId(factoryId);

        int nextCode;
        if (maxCode == null || maxCode.isEmpty()) {
            nextCode = 1;
        } else {
            nextCode = Integer.parseInt(maxCode) + 1;
        }

        if (nextCode > 999) {
            throw new BusinessException(409, "工号已用尽 (001-999)")
                    .withHint("请联系管理员清理离职员工或扩展工号位数");
        }

        return String.format("%03d", nextCode);
    }

    @Override
    @Transactional
    public List<UserDTO> provisionDefaultUsers(String factoryId) {
        // Idempotent: skip if factory already has users
        List<User> existingUsers = userRepository.findByFactoryId(factoryId);
        if (!existingUsers.isEmpty()) {
            log.info("Factory {} already has {} users, skipping provisioning", factoryId, existingUsers.size());
            return Collections.emptyList();
        }

        // Get factory info for contact details
        com.cretas.aims.entity.Factory factory = factoryRepository.findById(factoryId)
                .orElseThrow(() -> new EntityNotFoundException("Factory", factoryId));

        String prefix = factoryId.toLowerCase();
        // 安全：为每个默认用户生成随机初始密码，不使用弱默认密码
        // 管理员需通过密码重置流程获取访问权限
        String initialPassword = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String encodedPassword = passwordEncoder.encode(initialPassword);
        log.info("Provisioned default users for factory {} with random initial password (admin must reset)", factoryId);
        LocalDateTime now = LocalDateTime.now();

        // Define default users: {suffix, roleCode, level, fullName}
        String[][] defaults = {
                {"_admin", "factory_super_admin", "0", factory.getContactName() != null ? factory.getContactName() : "管理员"},
                {"_workshop", "workshop_supervisor", "20", "车间主管"},
                {"_worker1", "operator", "30", "操作员"}
        };

        List<UserDTO> created = new ArrayList<>();
        for (String[] def : defaults) {
            String username = prefix + def[0];

            if (userRepository.countByUsernameIncludingDeleted(username) > 0) {
                log.warn("Username {} already exists globally, skipping", username);
                continue;
            }

            User user = new User();
            user.setFactoryId(factoryId);
            user.setUsername(username);
            user.setPasswordHash(encodedPassword);
            user.setRoleCode(def[1]);
            user.setLevel(Integer.parseInt(def[2]));
            user.setFullName(def[3]);
            user.setIsActive(true);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);

            // Set contact phone for admin user
            if ("_admin".equals(def[0]) && factory.getContactPhone() != null) {
                user.setPhone(factory.getContactPhone());
            }

            User saved = userRepository.save(user);
            created.add(userMapper.toDTO(saved));
            log.info("Provisioned user: {} (role={}) for factory {}", username, def[1], factoryId);
        }

        return created;
    }

    /**
     * 解析技能等级 JSON 为 Map
     */
    private Map<String, Integer> parseSkillLevels(String skillLevelsJson) {
        if (skillLevelsJson == null || skillLevelsJson.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            return objectMapper.readValue(skillLevelsJson, new TypeReference<Map<String, Integer>>() {});
        } catch (Exception e) {
            log.warn("解析技能等级JSON失败: {}", skillLevelsJson, e);
            return Collections.emptyMap();
        }
    }

    /**
     * 序列化技能等级 Map 为 JSON
     */
    private String serializeSkillLevels(Map<String, Integer> skillLevels) {
        if (skillLevels == null || skillLevels.isEmpty()) {
            return "{}";
        }

        try {
            return objectMapper.writeValueAsString(skillLevels);
        } catch (Exception e) {
            log.warn("序列化技能等级失败", e);
            return "{}";
        }
    }

    /**
     * 🔴 2026-08-22: 员工名录 —— 在职用户, 可按角色过滤。
     *
     * <p>⚠️ 两条 finder 都是仓储层现成的, ⛔ 没有新写 JPQL ——
     * 本仓的软删是全局 @Where, 手写查询很容易漏掉 deleted_at 条件。
     * ⚠️ 按角色过滤那条要**再筛一次 isActive**: findByFactoryIdAndRoleCode 不带在职条件,
     * 少这一步就会让停用账号从角色入口漏进选人框(而不带 roleCode 时不会) ——
     * 同一个方法两条路给出不一致的结果, 正是最难发现的那种。
     */
    @Override
    @Transactional(readOnly = true)
    public List<User> getActiveUsersForDirectory(String factoryId, String roleCode) {
        if (roleCode != null && !roleCode.isBlank()) {
            return userRepository.findByFactoryIdAndRoleCode(factoryId, roleCode).stream()
                    .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                    .collect(java.util.stream.Collectors.toList());
        }
        return userRepository.findByFactoryIdAndIsActive(factoryId, true);
    }
}
