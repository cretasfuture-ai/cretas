package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.user.CreateUserRequest;
import com.cretas.aims.dto.user.UpdateSkillsRequest;
import com.cretas.aims.dto.user.UpdateMyProfileRequest;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.utils.DepartmentDisplayNames;
import com.cretas.aims.entity.enums.HireType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.utils.SecurityUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.cretas.aims.entity.User;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import com.cretas.aims.util.ErrorSanitizer;
import com.cretas.aims.annotation.RequireModule;

/**
 * 用户管理控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/users")
@RequiredArgsConstructor
@org.springframework.validation.annotation.Validated  // Issue #816: enable @Min(1) on @RequestParam page/size for join-date-range
@Tag(name = "用户管理", description = "用户管理相关接口")
public class UserController {

    private final UserService userService;
    private final com.cretas.aims.service.MobileService mobileService;
    private final com.cretas.aims.repository.inventory.PurchaseOrderRepository purchaseOrderRepository;

    /**
     * 创建用户
     */
    @RequireModule("hr_employee")
    @PostMapping
    @Operation(summary = "创建用户")
    @RequirePermission("hr:read_write")
    public ApiResponse<UserDTO> createUser(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Valid @RequestBody CreateUserRequest request) {
        log.info("创建用户: factoryId={}, username={}", factoryId, request.getUsername());
        UserDTO user = userService.createUser(factoryId, request);
        return ApiResponse.success("用户创建成功", user);
    }

    /**
     * 更新用户信息
     */
    /**
     * 改**自己**的资料 —— 只认姓名与邮箱。
     *
     * <h3>为什么不复用 {@code PUT /{userId}}（2026-08-29 逐屏走查）</h3>
     *
     * 「我的 → 编辑 → 保存」对每个角色都可点，而它调的是 {@code PUT /users/{userId}}，
     * 那个接口由 {@code @RequireModule("hr_employee")} + {@code hr:read_write} 守着。
     * 仓储主管点保存实测拿到 <b>403</b>：
     * <pre>您的角色 [仓储主管] 在 [人事管理] 模块无 [读写] 权限
     * 请联系工厂管理员…开通 [人事管理] 的 [读写] 权限</pre>
     *
     * ▎按那句提示去做是<b>错的</b> —— 为了让人改自己的名字而开通整个人事模块的写权限，
     * ▎等于把员工名册的增删改一起给出去。改自己的名字是自助行为，不是人事管理。
     *
     * <h3>安全</h3>
     * <ul>
     *   <li>userId <b>从 token 解析</b>，⛔ 不从路径或 body 取 —— 否则就是「改任意人」</li>
     *   <li>请求体是 {@link UpdateMyProfileRequest}，<b>只有</b>两个字段；
     *       ⛔ 不复用 {@code CreateUserRequest}（它带着角色/工厂/启用状态/用户名/密码）</li>
     *   <li>仍校验 token 里的用户属于路径上的 factoryId（跨厂保护）</li>
     * </ul>
     */
    @PutMapping("/me/profile")
    @Operation(summary = "修改本人资料（仅姓名与邮箱）")
    public ApiResponse<UserDTO> updateMyProfile(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Valid @RequestBody UpdateMyProfileRequest request) {
        // ⛔ 不用 SecurityUtils.getCurrentUserId() —— 本项目从不填充 SecurityContextHolder,
        //    那个方法恒返 null(2026-08-29 上线实测: 拿到 401 未登录)。见 SecurityUtils#currentUserId。
        // ⚠️ A platform admin's id is from platform_admins, an independent sequence: passing it on
        // would edit the factory user who happens to share that id. A platform admin has no profile here.
        if (AuthenticatedActor.currentPlatformAdmin().isPresent()) {
            throw new BusinessException(403, "平台管理员没有工厂账号资料可修改")
                    .withHint("请用该工厂的账号登录后修改本人资料。");
        }
        Long userId = com.cretas.aims.utils.SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录").withHint("请重新登录后再试");
        }
        log.info("修改本人资料: factoryId={}, userId={}", factoryId, userId);
        return ApiResponse.success("个人信息已保存", userService.updateMyProfile(factoryId, userId, request));
    }

    @RequireModule("hr_employee")
    @PutMapping("/{userId}")
    @Operation(summary = "更新用户信息")
    @RequirePermission("hr:read_write")
    public ApiResponse<UserDTO> updateUser(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "用户ID", required = true, example = "1")
            @PathVariable @NotNull Long userId,
            @Valid @RequestBody CreateUserRequest request) {
        log.info("更新用户: factoryId={}, userId={}", factoryId, userId);
        UserDTO user = userService.updateUser(factoryId, userId, request);
        return ApiResponse.success("用户更新成功", user);
    }

    /**
     * 删除用户
     */
    @RequireModule("hr_employee")
    @DeleteMapping("/{userId}")
    @Operation(summary = "删除用户")
    @RequirePermission("hr:read_write")
    public ApiResponse<Void> deleteUser(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "用户ID", required = true, example = "1")
            @PathVariable @NotNull Long userId) {
        log.info("删除用户: factoryId={}, userId={}", factoryId, userId);
        userService.deleteUser(factoryId, userId);
        return ApiResponse.success("用户删除成功", null);
    }

    /**
     * R-2① (2026-08-20 手动测试台账): 删除前告诉操作者这个账号至少建过多少张采购单 ——
     * 只统计采购单一种单据类型 (⚠️ 不是全量单据数, 命名/返回结构都刻意留了扩展空间,
     * 别把它当成"这个账号建过的全部单据数"来用)。软删不会移除这些历史归属，
     * 只是让这个人不再出现在有效用户列表里。
     */
    @RequireModule("hr_employee")
    @GetMapping("/{userId}/deletion-impact")
    @Operation(summary = "R-2①: 删除该用户前的影响预览（目前只统计采购单）")
    @RequirePermission("hr:read_write")
    public ApiResponse<Map<String, Object>> getDeletionImpact(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotNull Long userId) {
        long purchaseOrderCount = purchaseOrderRepository
                .findByFactoryIdAndCreatedByOrderByCreatedAtDesc(
                        factoryId, userId, org.springframework.data.domain.Pageable.ofSize(1))
                .getTotalElements();
        return ApiResponse.success(Map.of("purchaseOrderCount", purchaseOrderCount));
    }

    /**
     * 🔴 2026-08-22 新增: 员工名录 —— 只读, 只返回「显示或挑选一个人」所需的最小字段。
     *
     * <p><b>为什么要单开一个端点, 而不是把 hr 权限放宽</b>:
     * 销售订单列表(选业务员)、CRM 商机(选负责人)、研发样品(选负责人)、排班(选工人)
     * 这 4 个页面都只需要「谁是谁」, 却都在调 {@code GET /users} ——
     * 那个端点要 {@code hr:read}, 于是 8 个角色**看得见选人框、一点就 403**。
     *
     * <p>⛔ 把 {@code hr} 开给这 8 个角色是错的修法: {@code GET /users} 返回完整
     * {@code UserDTO}(含手机号/邮箱等), 为了填一个下拉框而泄露全厂通讯录。
     * ⇒ 只暴露 {@code id / fullName / username / department / roleCode / isActive} 六个字段。
     * ⛔ 手机、邮箱、雇佣信息一律不出现在这里 —— 要那些请走 {@code GET /users}(仍要 hr)。
     *
     * <p>⚠️ **无 {@code @RequirePermission} 是刻意的**, 与本仓「读放开、写收紧」的既有约定
     * 一致(参见 {@code CustomerController} 头部 Bug #318 的修法)。仍受 JWT +
     * {@code factoryId} 租户隔离约束, ⛔ 不是匿名可访问。
     *
     * <p>⚠️ 只返回**在职**用户: 选人框里不该出现已停用的账号。
     */
    @GetMapping("/directory")
    @Operation(summary = "员工名录（仅 id/姓名/部门/角色，供各模块选人器使用）",
            description = "只读最小字段集，不含手机/邮箱。需要完整用户信息请用 GET /users（要 hr 权限）")
    public ApiResponse<List<Map<String, Object>>> getUserDirectory(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "可选：只要某个角色的人") @RequestParam(required = false) String roleCode) {
        List<User> users = userService.getActiveUsersForDirectory(factoryId, roleCode);
        List<Map<String, Object>> directory = users.stream()
                .map(u -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", u.getId());
                    row.put("fullName", u.getFullName());
                    row.put("username", u.getUsername());
                    // 缺陷 15: 名录同样要给显示名 —— 请购单详情的「请购部门」
                    // 和审批人选择器都读这里。⛔ 不在前端各写一份翻译。
                    row.put("department", DepartmentDisplayNames.display(u.getDepartment()));
                    row.put("roleCode", u.getRoleCode());
                    row.put("isActive", u.getIsActive());
                    return row;
                })
                .collect(java.util.stream.Collectors.toList());
        log.debug("员工名录: factoryId={}, roleCode={}, size={}", factoryId, roleCode, directory.size());
        return ApiResponse.success(directory);
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/current")
    @Operation(summary = "获取当前登录用户信息")
    public ApiResponse<UserDTO> getCurrentUser(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "访问令牌", required = true, example = "Bearer eyJhbGciOiJIUzI1NiJ9...")
            @RequestHeader("Authorization") String authorization) {
        String token = com.cretas.aims.utils.TokenUtils.extractToken(authorization);
        UserDTO user = mobileService.getUserFromToken(token);
        log.debug("获取当前用户信息: factoryId={}, userId={}", factoryId, user.getId());
        return ApiResponse.success(user);
    }

    /**
     * 获取用户详情
     */
    @GetMapping("/{userId}")
    @Operation(summary = "获取用户详情")
    @RequirePermission({"hr:read_write", "hr:read"})
    public ApiResponse<UserDTO> getUserById(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "用户ID", required = true, example = "1")
            @PathVariable @NotNull Long userId) {
        UserDTO user = userService.getUserById(factoryId, userId);
        return ApiResponse.success(user);
    }

    /**
     * 获取用户列表
     */
    @GetMapping
    @Operation(summary = "获取用户列表（分页）")
    @RequirePermission({"hr:read_write", "hr:read"})
    public ApiResponse<PageResponse<UserDTO>> getUserList(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            // 2026-09-01: 角色筛选。keyword 走 PageRequest 自带的那个字段, 这里只补 role。
            // ⚠️ Spring 对**没有声明**的请求参数是静默忽略的 —— 前端此前传的
            //    username / roleCode 就是这么被丢掉的, 搜索框看着能点、结果永远是全量。
            @Parameter(description = "角色筛选，为空时不筛选", required = false, example = "yield_operator")
            @RequestParam(required = false) String role,
            @Valid PageRequest pageRequest) {
        PageResponse<UserDTO> response = userService.getUserList(factoryId, role, pageRequest);
        return ApiResponse.success(response);
    }

    /**
     * 按角色获取用户
     */
    @GetMapping("/role/{roleCode}")
    @Operation(summary = "按角色获取用户列表")
    @RequirePermission({"hr:read_write", "hr:read"})
    public ApiResponse<List<UserDTO>> getUsersByRole(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "角色代码", required = true, example = "OPERATOR")
            @PathVariable FactoryUserRole roleCode) {
        List<UserDTO> users = userService.getUsersByRole(factoryId, roleCode);
        return ApiResponse.success(users);
    }

    /**
     * 激活用户
     */
    @RequireModule("hr_employee")
    @PostMapping("/{userId}/activate")
    @Operation(summary = "激活用户")
    @RequirePermission("hr:read_write")
    public ApiResponse<Void> activateUser(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "用户ID", required = true, example = "1")
            @PathVariable @NotNull Long userId) {
        log.info("激活用户: factoryId={}, userId={}", factoryId, userId);
        userService.activateUser(factoryId, userId);
        return ApiResponse.success("用户激活成功", null);
    }

    /**
     * 停用用户
     */
    @RequireModule("hr_employee")
    @PostMapping("/{userId}/deactivate")
    @Operation(summary = "停用用户")
    @RequirePermission("hr:read_write")
    public ApiResponse<Void> deactivateUser(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "用户ID", required = true, example = "1")
            @PathVariable @NotNull Long userId) {
        log.info("停用用户: factoryId={}, userId={}", factoryId, userId);
        userService.deactivateUser(factoryId, userId);
        return ApiResponse.success("用户停用成功", null);
    }

    /**
     * 更新用户角色
     */
    @RequireModule("hr_employee")
    @PutMapping("/{userId}/role")
    @Operation(summary = "更新用户角色")
    @RequirePermission("hr:read_write")
    public ApiResponse<Void> updateUserRole(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "用户ID", required = true, example = "1")
            @PathVariable @NotNull Long userId,
            @Parameter(description = "新角色", required = true, example = "OPERATOR")
            @RequestParam FactoryUserRole newRole) {
        log.info("更新用户角色: factoryId={}, userId={}, newRole={}", factoryId, userId, newRole);
        userService.updateUserRole(factoryId, userId, newRole);
        return ApiResponse.success("角色更新成功", null);
    }

    /**
     * 检查用户名是否存在
     */
    @GetMapping("/check/username")
    @Operation(summary = "检查用户名是否存在")
    public ApiResponse<Boolean> checkUsernameExists(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "用户名", required = true, example = "zhangsan")
            @RequestParam @NotBlank String username) {
        boolean exists = userService.checkUsernameExists(factoryId, username);
        return ApiResponse.success(exists);
    }

    /**
     * 检查邮箱是否存在
     */
    @GetMapping("/check/email")
    @Operation(summary = "检查邮箱是否存在")
    public ApiResponse<Boolean> checkEmailExists(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "邮箱", required = true, example = "zhangsan@example.com")
            @RequestParam @NotBlank String email) {
        boolean exists = userService.checkEmailExists(factoryId, email);
        return ApiResponse.success(exists);
    }

    /**
     * 搜索用户
     */
    @GetMapping("/search")
    @Operation(summary = "搜索用户")
    @RequirePermission({"hr:read_write", "hr:read"})
    public ApiResponse<PageResponse<UserDTO>> searchUsers(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "搜索关键词，为空时返回所有", required = false, example = "张三")
            @RequestParam(required = false, defaultValue = "") String keyword,
            @Parameter(description = "角色筛选，为空时不筛选", required = false, example = "salesperson")
            @RequestParam(required = false) String role,
            @Valid PageRequest pageRequest) {
        PageResponse<UserDTO> response = userService.searchUsers(factoryId, keyword, role, pageRequest);
        return ApiResponse.success(response);
    }


    /**
     * 导出用户列表
     */
    @GetMapping("/export")
    @Operation(summary = "导出用户列表")
    @RequirePermission("hr:read_write")
    public ResponseEntity<byte[]> exportUsers(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {
        log.info("导出用户列表: factoryId={}", factoryId);
        byte[] excelBytes = userService.exportUsers(factoryId);

        // 生成文件名（包含时间戳）
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "用户列表_" + timestamp + ".xlsx";

        // 设置响应头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(excelBytes.length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(excelBytes);
    }

    /**
     * 从Excel文件批量导入用户
     */
    @RequireModule("hr_employee")
    @PostMapping("/import")
    @Operation(summary = "从Excel文件批量导入用户")
    @RequirePermission("hr:read_write")
    public ApiResponse<com.cretas.aims.dto.common.ImportResult<UserDTO>> importUsersFromExcel(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "Excel文件 (.xlsx)", required = true)
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {

        log.info("从Excel批量导入用户: factoryId={}, filename={}", factoryId, file.getOriginalFilename());

        // R77: 验证文件类型 — throw BusinessException(400) + actionHint 替代 ApiResponse.error
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().endsWith(".xlsx")) {
            throw new BusinessException(400, "只支持.xlsx格式的Excel文件")
                    .withHint("请使用模板下载按钮获取正确格式的 Excel 文件 (.xlsx)");
        }

        // 验证文件大小（10MB限制）
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new BusinessException(400, "文件大小不能超过10MB")
                    .withHint("请压缩或拆分后再次导入,单批 ≤ 10MB");
        }

        try {
            com.cretas.aims.dto.common.ImportResult<UserDTO> result =
                    userService.importUsersFromExcel(factoryId, file.getInputStream());

            if (result.getIsFullSuccess()) {
                log.info("用户批量导入完全成功: factoryId={}, count={}", factoryId, result.getSuccessCount());
                return ApiResponse.success("导入成功", result);
            } else {
                log.warn("用户批量导入部分失败: factoryId={}, success={}, failure={}",
                        factoryId, result.getSuccessCount(), result.getFailureCount());
                return ApiResponse.success(
                        String.format("导入完成：成功%d条，失败%d条",
                                result.getSuccessCount(), result.getFailureCount()),
                        result);
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("用户批量导入失败: factoryId={}", factoryId, e);
            // R77: catch return ApiResponse.error → throw BusinessException(500)
            // GlobalExceptionHandler maps to HTTP 500 + severity:"error" (R73-FIX-A default).
            throw new BusinessException(500, "导入失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    /**
     * 下载用户导入模板
     */
    @GetMapping("/export/template")
    @Operation(summary = "下载用户导入模板")
    public ResponseEntity<byte[]> downloadUserTemplate(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {

        log.info("下载用户导入模板: factoryId={}", factoryId);
        byte[] templateBytes = userService.generateImportTemplate();

        // 设置文件名
        String filename = "用户导入模板.xlsx";

        // 设置响应头
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        headers.setContentDispositionFormData("attachment", filename);
        headers.setContentLength(templateBytes.length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(templateBytes);
    }

    /**
     * 根据入职日期范围获取用户列表
     * HR Dashboard 用于显示"本月入职"统计
     */
    @GetMapping("/join-date-range")
    @Operation(summary = "根据入职日期范围获取用户列表")
    @RequirePermission({"hr:read_write", "hr:read"})
    public ApiResponse<PageResponse<UserDTO>> getUsersByJoinDateRange(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "开始日期 (yyyy-MM-dd)", required = true, example = "2025-01-01")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "结束日期 (yyyy-MM-dd)", required = true, example = "2025-01-31")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @Parameter(description = "页码 (1-indexed)", example = "1")
            @RequestParam(defaultValue = "1") @Min(value = 1, message = "页码必须大于0") Integer page,
            @Parameter(description = "每页大小", example = "20")
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "每页大小必须大于0") Integer size) {

        log.info("获取入职日期范围内用户: factoryId={}, startDate={}, endDate={}, page={}, size={}",
                factoryId, startDate, endDate, page, size);

        PageResponse<UserDTO> response = userService.getUsersByJoinDateRange(
                factoryId, startDate, endDate, page, size);

        return ApiResponse.success(response);
    }

    // ==================== 调度员模块扩展 API ====================

    /**
     * 按工号查询用户
     */
    @GetMapping("/by-employee-code/{employeeCode}")
    @Operation(summary = "按工号查询用户")
    public ApiResponse<UserDTO> getUserByEmployeeCode(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "工号", required = true, example = "001")
            @PathVariable @NotBlank String employeeCode) {
        UserDTO user = userService.getUserByEmployeeCode(factoryId, employeeCode);
        return ApiResponse.success(user);
    }

    /**
     * 绑定/更新工号
     */
    @RequireModule("hr_employee")
    @PutMapping("/{userId}/employee-code")
    @Operation(summary = "绑定或更新用户工号")
    @RequirePermission("hr:read_write")
    public ApiResponse<Void> updateEmployeeCode(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "用户ID", required = true, example = "1")
            @PathVariable @NotNull Long userId,
            @Parameter(description = "新工号 (001-999)", required = true, example = "001")
            @RequestParam @NotBlank String employeeCode) {
        log.info("更新用户工号: factoryId={}, userId={}, employeeCode={}", factoryId, userId, employeeCode);
        userService.updateEmployeeCode(factoryId, userId, employeeCode);
        return ApiResponse.success("工号更新成功", null);
    }

    /**
     * 获取用户技能
     */
    @GetMapping("/{userId}/skills")
    @Operation(summary = "获取用户技能等级")
    public ApiResponse<Map<String, Integer>> getUserSkills(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "用户ID", required = true, example = "1")
            @PathVariable @NotNull Long userId) {
        Map<String, Integer> skills = userService.getUserSkills(factoryId, userId);
        return ApiResponse.success(skills);
    }

    /**
     * 更新用户技能
     */
    @RequireModule("hr_employee")
    @PutMapping("/{userId}/skills")
    @Operation(summary = "更新用户技能等级")
    @RequirePermission("hr:read_write")
    public ApiResponse<Void> updateUserSkills(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "用户ID", required = true, example = "1")
            @PathVariable @NotNull Long userId,
            @Valid @RequestBody UpdateSkillsRequest request) {
        log.info("更新用户技能: factoryId={}, userId={}", factoryId, userId);
        userService.updateUserSkills(factoryId, userId, request.getSkillLevels());
        return ApiResponse.success("技能更新成功", null);
    }

    /**
     * 获取合同即将到期的员工
     */
    @GetMapping("/expiring-contracts")
    @Operation(summary = "获取合同即将到期的员工列表")
    @RequirePermission({"hr:read_write", "hr:read"})
    public ApiResponse<List<UserDTO>> getExpiringContracts(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "提前预警天数", example = "30")
            @RequestParam(defaultValue = "30") Integer daysAhead) {
        log.info("获取合同即将到期员工: factoryId={}, daysAhead={}", factoryId, daysAhead);
        List<UserDTO> users = userService.getExpiringContracts(factoryId, daysAhead);
        return ApiResponse.success(users);
    }

    /**
     * 按雇用类型获取用户
     */
    @GetMapping("/hire-type/{hireType}")
    @Operation(summary = "按雇用类型获取用户列表")
    @RequirePermission({"hr:read_write", "hr:read"})
    public ApiResponse<List<UserDTO>> getUsersByHireType(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "雇用类型", required = true, example = "FULL_TIME")
            @PathVariable HireType hireType) {
        List<UserDTO> users = userService.getUsersByHireType(factoryId, hireType);
        return ApiResponse.success(users);
    }

    /**
     * 获取临时工列表
     */
    @GetMapping("/temporary-workers")
    @Operation(summary = "获取所有临时性质员工")
    @RequirePermission({"hr:read_write", "hr:read"})
    public ApiResponse<List<UserDTO>> getTemporaryWorkers(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {
        List<UserDTO> users = userService.getTemporaryWorkers(factoryId);
        return ApiResponse.success(users);
    }

    /**
     * 自动生成下一个可用工号
     */
    @GetMapping("/next-employee-code")
    @Operation(summary = "获取下一个可用工号")
    public ApiResponse<String> getNextEmployeeCode(
            @Parameter(description = "工厂ID", required = true, example = "F001")
            @PathVariable @NotBlank String factoryId) {
        String nextCode = userService.generateNextEmployeeCode(factoryId);
        return ApiResponse.success(nextCode);
    }
}