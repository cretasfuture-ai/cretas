package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.customer.CreateCustomerRequest;
import com.cretas.aims.dto.customer.UpdateCustomerRequest;
import com.cretas.aims.dto.customer.CustomerDTO;
import com.cretas.aims.dto.customer.CustomerOptionDTO;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.CustomerService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.utils.TokenUtils;
import com.cretas.aims.util.ErrorSanitizer;
import com.cretas.aims.exception.BusinessException;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 客户管理控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
/**
 * Bug #318 fix (2026-04-18): @RequirePermission added only on write methods
 * (POST/PUT/DELETE) — reads stay open to any authenticated user (matches
 * pre-fix behavior, no regression). Prevents dispatcher/operator越权 POST/PUT/DELETE.
 *
 * <p>⚠️ 上面那句「reads stay open」<b>对本类已经不成立了</b>。owner 裁定
 * 「客户名单只有 销售/财务/工厂管理员/运营 能看」(2026-09-06) 分两票落地:
 * PR #615 关了 {@link #getCustomerList} 这一扇；本票
 * ({@code SEC-CUSTOMER-ROSTER-REMAINING-PATHS-20260906}) 关掉<b>其余全部吐名单的读端点</b>。</p>
 *
 * <h3>本类 18 个 GET 端点的收口台账 (硬约束 8: 改共享结构前先数, 改完再数)</h3>
 * <table border="1">
 *   <caption>18 = 12 已关 + 6 显式登记「故意不关」</caption>
 *   <tr><th>档</th><th>端点</th><th>门</th></tr>
 *   <tr><td rowspan="8">A 整份名单</td><td>{@code GET /}(#615)</td>
 *       <td rowspan="12">{@code sales:read_write|sales:read|finance:read_write|finance:read}</td></tr>
 *   <tr><td>{@code /export} (整表 Excel, 最重)</td></tr>
 *   <tr><td>{@code /active}</td></tr>
 *   <tr><td>{@code /search}</td></tr>
 *   <tr><td>{@code /by-type}</td></tr>
 *   <tr><td>{@code /by-industry}</td></tr>
 *   <tr><td>{@code /vip}</td></tr>
 *   <tr><td>{@code /outstanding-balance}</td></tr>
 *   <tr><td rowspan="4">B 单条但敏感</td><td>{@code /{id}/price-memory} (议价历史)</td></tr>
 *   <tr><td>{@code /{id}/purchase-history}</td></tr>
 *   <tr><td>{@code /{id}/statistics}</td></tr>
 *   <tr><td>{@code /{id}}</td></tr>
 *   <tr><td rowspan="6">C <b>故意不关</b>: 一个客户名/联系人/金额都不吐</td>
 *       <td>{@code /check-code} (返 boolean; 建客户前查重, 关了写流程反而断)</td>
 *       <td rowspan="6">⛔ 无门 —— <b>登记而非豁免</b></td></tr>
 *   <tr><td>{@code /export/template} (空模板, 与本厂数据无关)</td></tr>
 *   <tr><td>{@code /rating-distribution} (评级→计数)</td></tr>
 *   <tr><td>{@code /type-distribution} (类型→计数)</td></tr>
 *   <tr><td>{@code /industry-distribution} (行业→计数)</td></tr>
 *   <tr><td>{@code /overall-statistics} (总数/汇总额)</td></tr>
 * </table>
 *
 * <p>🔴 {@code /vip} 与 {@code /outstanding-balance} <b>不在派活时那张分档表里</b> ——
 * 是本票按硬约束 8 把本类 {@code @GetMapping} 从头数了一遍才发现的 (它们同样返回
 * {@code List<CustomerDTO>})。#615 的方法注释里其实列过它们，分档表漏了。
 * ⇒ <b>数的是 18 个 {@code @GetMapping}，不是别人给的清单。</b></p>
 *
 * <p>⚠️ 唯一的例外走的是 {@link #getWarehouseCustomerOptions}: 仓储「原料类型字典」页
 * 需要客户名来填「包材关联固定客户」，而仓储角色 L1 上 {@code sales='-' finance='-'}。
 * ⛔ 那不构成「把 {@code /active} 留着开」的理由 —— 见该方法的注释。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/customers")
@RequiredArgsConstructor
@Tag(name = "客户管理", description = "客户管理相关接口，包括客户的创建、查询、更新、删除，状态管理、评级评分、信用额度及余额管理，购买历史统计、VIP客户查询、Excel导入导出，以及客户类型/行业分布统计等功能")
public class CustomerController {

    private final CustomerService customerService;
    private final MobileService mobileService;
    private final PriceMaskResolver priceMaskResolver;
    // Sprint 4 W2 S-PRICE-1
    private final com.cretas.aims.service.CustomerPriceMemoryService customerPriceMemoryService;

    // ================================================================
    // owner 裁定 (2026-09-06) 的四个权限码 —— 客户名单可见性的**唯一**口径。
    //
    // 🔴 为什么是常量而不是每处写四个字面量 (形态 D「同一个东西有两份，它一定会漂」):
    //    本类有 12 处要挂这四个码。#615 那一处是字面量还看不出问题, 12 处字面量
    //    等于现场制造 11 份漂移源 —— 下一个人只改其中一处, git 一个字都不会说。
    //    Java 注解只接受编译期常量, 所以拆成 4 个 String 常量而不是一个 String[]。
    //    ⚠️ 常量在字节码里被折叠成字面量, 所以 javap 仍然看得到这四个串 (部署核验照旧)。
    //
    // 角色表 (L1 platform_role_permissions, 2026-09-06 cretas_prod_db 实测) 见
    // getCustomerList 的注释; ⛔ 不看 PermissionServiceImpl.PERMISSION_MATRIX。
    // ================================================================
    /** 销售 (sales_manager L1 sales=rw)。 */
    public static final String ROSTER_SALES_RW = "sales:read_write";
    /** 销售只读 + 运营 (operations_coordinator L1 sales=r) 靠它覆盖。 */
    public static final String ROSTER_SALES_R = "sales:read";
    /** 财务 (finance_manager / cashier L1 finance=rw)。 */
    public static final String ROSTER_FINANCE_RW = "finance:read_write";
    /** 财务只读。 */
    public static final String ROSTER_FINANCE_R = "finance:read";

    /**
     * 仓储模块<b>写</b>权限码 —— 只给 {@link #getWarehouseCustomerOptions} 用。
     *
     * <p>🔴 为什么是 {@code read_write} 而不是 {@code read}: L1 上 {@code warehouse='r'} 的角色里
     * 有 {@code production_manager} —— 而 #615 <b>刻意</b>让它看不到客户名单
     * (它的生产计划客户下拉因此变空, owner 被告知且未推翻)。
     * 挂 {@code warehouse:read} 会让它从这个侧门把名字拿回去, <b>等于把 #615 撤销掉</b>。</p>
     *
     * <p>而 {@code read_write} 不会误伤任何真实用户: 用到这个下拉的是「新建/编辑原料类型」
     * 弹窗, 而那个弹窗由 {@code MATERIAL_TYPE_WRITE_ROLES} + 后端 {@code @RequireRole} 限定为
     * {@code warehouse_manager} + 平台/工厂管理员 —— 前者 L1 {@code warehouse='rw'},
     * 后者走超管分支。⇒ <b>能打开那个表单的角色 100% 覆盖, 一个都不少。</b></p>
     *
     * <p>⛔ 不要拿它去开别的客户端点。</p>
     */
    public static final String WAREHOUSE_RW = "warehouse:read_write";

    /**
     * 创建客户
     */
    @RequirePermission({"sales:read_write", "finance:read_write"})
    @PostMapping
    @Operation(summary = "创建客户", description = "在指定工厂下创建新客户，包括客户基本信息、联系方式、信用额度等")
    @com.cretas.aims.annotation.Loggable(module = "CUSTOMER", action = "CREATE",
            entityType = "Customer", summary = "'创建客户 ' + #request.name")
    public ApiResponse<CustomerDTO> createCustomer(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "访问令牌", example = "Bearer eyJhbGciOiJIUzI1NiJ9...", required = true)
            @RequestHeader("Authorization") String authorization,
            @Parameter(description = "客户创建请求体")
            @Valid @RequestBody CreateCustomerRequest request) {

        // 获取当前用户ID
        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        log.info("创建客户: factoryId={}, name={}", factoryId, request.getName());
        CustomerDTO customer = customerService.createCustomer(factoryId, request, userId.longValue());
        return ApiResponse.success("客户创建成功", customer);
    }

    /**
     * 更新客户
     */
    @RequirePermission({"sales:read_write", "finance:read_write"})
    @PutMapping("/{customerId}")
    @Operation(summary = "更新客户", description = "更新指定客户的信息 (partial update: 所有字段可选)")
    @com.cretas.aims.annotation.Loggable(module = "CUSTOMER", action = "UPDATE",
            entityType = "Customer", entityIdParam = "customerId")
    public ApiResponse<CustomerDTO> updateCustomer(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "客户ID", example = "CUST-001", required = true)
            @PathVariable @NotBlank String customerId,
            @Parameter(description = "客户更新请求体")
            @Valid @RequestBody UpdateCustomerRequest request) {

        log.info("更新客户: factoryId={}, customerId={}", factoryId, customerId);
        CustomerDTO customer = customerService.updateCustomer(factoryId, customerId, request);
        return ApiResponse.success("客户更新成功", customer);
    }

    /**
     * 删除客户
     */
    @RequirePermission({"sales:read_write", "finance:read_write"})
    @DeleteMapping("/{customerId}")
    @Operation(summary = "删除客户", description = "删除指定客户记录（软删除），有关联订单的客户不可删除")
    @com.cretas.aims.annotation.Loggable(module = "CUSTOMER", action = "DELETE",
            entityType = "Customer", entityIdParam = "customerId")
    public ApiResponse<Void> deleteCustomer(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "客户ID", example = "CUST-001", required = true)
            @PathVariable @NotBlank String customerId) {

        log.info("删除客户: factoryId={}, customerId={}", factoryId, customerId);
        customerService.deleteCustomer(factoryId, customerId);
        return ApiResponse.success("客户删除成功", null);
    }

    /**
     * 获取客户详情
     *
     * <p>档 B (单条但敏感): 返回整份 {@code CustomerDTO} —— 联系人/电话/邮箱/税号/
     * 信用额度/当前余额/累计销售额。owner 裁定收口 (2026-09-06)。</p>
     *
     * <p>消费方普查: web-admin {@code api/customer.ts#getCustomerDetail} 与
     * {@code views/sales/finished-goods/useFinishedGoodsOwnership.ts} —— 两者所在路由
     * {@code meta.module} 都是 {@code sales}, 而 guards 的模块闸要求 {@code sales} 可读,
     * 所以能走到那两个页面的角色<b>必然</b>持有 {@code sales:read}, 本门不改变它们的行为。</p>
     */
    @RequirePermission({ROSTER_SALES_RW, ROSTER_SALES_R, ROSTER_FINANCE_RW, ROSTER_FINANCE_R})
    @GetMapping("/{customerId}")
    @Operation(summary = "获取客户详情", description = "根据客户ID获取客户详细信息，包括联系方式、信用状况、交易统计等")
    public ApiResponse<CustomerDTO> getCustomerById(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "客户ID", example = "CUST-001", required = true)
            @PathVariable @NotBlank String customerId) {

        CustomerDTO customer = customerService.getCustomerById(factoryId, customerId);
        return ApiResponse.success(customer);
    }

    /**
     * 获取客户列表（分页）
     *
     * <p><b>owner 裁定 (2026-09-06, 原文)</b>: 「客户名单只有<b>销售、财务、工厂管理员、运营</b>能看。」
     * 本方法此前<b>对同厂任何已登录用户开放</b> (Bug #318 「读保持开放」，见类头注释)，
     * PR #590 提过这道门又撤回了；这次按上面的裁定补上，撤回时那段「为什么不补」的说明
     * 已被裁定取代，不再保留 (裁定的内容与代价见下)。</p>
     *
     * <h3>四类业务角色 → L1 权限码</h3>
     * <p>口径: {@code platform_role_permissions} (L1) 是权威；⛔ 不看
     * {@code PermissionServiceImpl.PERMISSION_MATRIX} —— 那只是 L1 查不到行时的兜底，
     * 且与 L1 有漂移。2026-09-06 在 {@code cretas_prod_db} 上实测:</p>
     * <ul>
     *   <li><b>销售</b> — {@code sales_manager} L1 {@code sales=rw} ⇒ {@code sales:read_write|sales:read}</li>
     *   <li><b>财务</b> — {@code finance_manager} L1 {@code finance=rw}、{@code cashier} L1
     *       {@code finance=rw} ⇒ {@code finance:read_write|finance:read}</li>
     *   <li><b>工厂管理员</b> — {@code factory_super_admin} 在
     *       {@link com.cretas.aims.service.impl.PermissionServiceImpl#hasPermission} 里
     *       「超级管理员拥有所有权限」那一支<b>直接 return true</b>，压根不查 L1 ⇒
     *       ⛔ <b>不需要、也没有</b>为它加任何权限码。{@code platform_admin} 走同一支。</li>
     *   <li><b>运营</b> — {@code operations_coordinator} ({@link
     *       com.cretas.aims.entity.enums.FactoryUserRole}，唯一写模块是 {@code operations})
     *       L1 实测 {@code sales=r} ⇒ <b>已被 {@code sales:read} 覆盖</b>。
     *       ⛔ 因此<b>不加</b> {@code operations:read_write|operations:read}: 加了会顺带放行
     *       {@code permission_admin} (L1 {@code operations=rw}, 而它 sales/finance 都是 {@code '-'}),
     *       那是裁定四类之外的角色。</li>
     * </ul>
     *
     * <h3>🔴 生产角色因此看不到客户名单 —— 这是裁定的直接后果，不是回归</h3>
     * <p>{@code production_manager} L1 {@code sales='-'} / {@code finance='-'} ⇒ 本端点 403。
     * prod 实测它建的 <b>20 个生产计划里 7 个挂了客户</b> (2026-09-06 重新数过，数字未变)，
     * 所以 {@code ProductionPlanManagementScreen} 的客户下拉会变空。organizer 已把这条后果
     * 报给 owner，owner 未推翻。屏本身不会白 —— #590 的
     * {@code settleAllIndependently} 让三路下拉互不连坐。
     * ⚠️ 谁想「顺手修好它」，请先看
     * {@code CustomerControllerGetCustomerListPermissionTest} 里那条钉住 403 的测试。</p>
     *
     * <h3>✅ 其余同类读端点已在 SEC-CUSTOMER-ROSTER-REMAINING-PATHS-20260906 一并关上</h3>
     * <p>本方法的上一版注释在这里登记过「还有 8 个吐名单的读端点开着」。那 8 个
     * ({@code /active} {@code /search} {@code /by-type} {@code /by-industry} {@code /vip}
     * {@code /outstanding-balance} {@code /export} {@code /{customerId}}) 加上 3 个档 B 端点
     * 已全部收口，台账见<b>类头注释</b>那张 18 行的表 —— ⛔ 别在这里维护第二份清单
     * (两份一定会漂)。</p>
     *
     * @see MaterialBatchController#getMaterialBatchList 同一批「有 @DataScope 却无权限门」的另一半 (PR #590 已补)
     */
    @RequirePermission({ROSTER_SALES_RW, ROSTER_SALES_R, ROSTER_FINANCE_RW, ROSTER_FINANCE_R})
    @GetMapping
    @Operation(summary = "获取客户列表（分页）", description = "分页查询指定工厂的客户列表，支持排序和筛选")
    public ApiResponse<PageResponse<CustomerDTO>> getCustomerList(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "分页参数")
            @Valid PageRequest pageRequest,
            @Parameter(description = "关键词过滤 (name / customerCode / contactPerson 模糊匹配)")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "name alias (Bug #6 R10: 兼容前端传 ?name= 而非 ?keyword=, 否则静默忽略)")
            @RequestParam(required = false) String name,
            @Parameter(description = "Sprint 4 W2 S-CRM-FULL-1: 客户生命周期状态过滤 (LEAD / RECURRING / LOST 等 11 阶段)")
            @RequestParam(required = false) com.cretas.aims.entity.enums.CustomerStatus customerStatus,
            @Parameter(description = "Sprint 4 W2 S-CRM-FULL-1: 客户重要程度过滤 (VIP / IMPORTANT / NORMAL / LOW)")
            @RequestParam(required = false) com.cretas.aims.entity.enums.CustomerImportance importance,
            @Parameter(description = "Sprint 4 W2 S-CRM-FULL-1: 客户来源渠道过滤 (EXHIBITION / WEBSITE 等 11 渠道)")
            @RequestParam(required = false) com.cretas.aims.entity.enums.CustomerSource source) {

        // Bug #6 fix (R10 2026-04-16): 接受 ?name= 作为 ?keyword= 的别名
        // 防止前端/集成方误传 name 时静默返全表 (假阳性结果)
        String effectiveKeyword = keyword != null && !keyword.isBlank() ? keyword : name;
        PageResponse<CustomerDTO> response = customerService.getCustomerList(
                factoryId, pageRequest, effectiveKeyword, customerStatus, importance, source);
        return ApiResponse.success(response);
    }

    /**
     * 获取活跃客户列表
     *
     * <h3>🔴 这个端点是本轮收口里唯一有真实消费方冲突的一个</h3>
     * <p>两个消费方 (2026-09-06 全仓普查):</p>
     * <ol>
     *   <li>RN {@code SalesOrderCreateScreen.tsx:117} —— 只有 {@code sales_manager} 走得到
     *       ({@code AppNavigator} 按角色串分派, 该屏挂在 {@code SalesManagerNavigator} 下),
     *       L1 {@code sales=rw} ⇒ <b>本门不影响它</b>。</li>
     *   <li>web-admin {@code views/warehouse/material-types/list.vue} —— 「包材关联固定客户」
     *       下拉。该页 {@code meta.module='warehouse'}, 走得到它的角色里
     *       {@code warehouse_manager}/{@code warehouse_worker} 在 L1 上
     *       {@code sales='-' finance='-'} ⇒ <b>四个码会把这页的下拉打成 403</b>。</li>
     * </ol>
     *
     * <p>⚠️ 那个页面里原本写着一段「改调 {@code /active} 就是修复」的注释，理由原话是
     * <b>「无 DataScope 限制…两种角色都稳定返回全部 17 条」</b> —— 也就是说，它当初正是
     * <b>因为这个端点没有门</b>才被选中的。⛔ 那不能反过来当作「所以要把门继续开着」的理由:
     * 那等于让一个仓储页面的下拉决定全厂客户名单的可见性。</p>
     *
     * <p><b>处理方式</b>: 本端点照裁定关到四个码；仓储页改调
     * {@link #getWarehouseCustomerOptions} —— 只吐 {@code {id, name}}、只认仓储模块权限。
     * 对仓储角色来说这是<b>两个维度同时收窄</b>: 字段从整份 {@code CustomerDTO}
     * (电话/邮箱/税号/信用额度/余额/累计销售额) 收到 2 个; 角色从「同厂任何已登录用户」
     * 收到「能打开那个页面的仓储角色」。</p>
     */
    @RequirePermission({ROSTER_SALES_RW, ROSTER_SALES_R, ROSTER_FINANCE_RW, ROSTER_FINANCE_R})
    @GetMapping("/active")
    @Operation(summary = "获取活跃客户列表", description = "获取所有状态为活跃的客户列表，用于下拉选择等场景")
    public ApiResponse<List<CustomerDTO>> getActiveCustomers(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId) {

        List<CustomerDTO> customers = customerService.getActiveCustomers(factoryId);
        return ApiResponse.success(customers);
    }

    /**
     * 仓储模块专用: 活跃客户的<b>最小引用下拉</b> ({@code id} + {@code name}, 别的一个字段都没有)。
     *
     * <h3>它为什么存在</h3>
     * <p>仓储「原料类型字典」页 ({@code views/warehouse/material-types/list.vue}) 要填
     * {@code RawMaterialType.associatedCustomerId} (「包材关联固定客户」)。那是一个
     * <b>引用型外键</b>: 不看到客户名就没法选。而 owner 裁定 (2026-09-06) 把客户名单收给
     * 销售/财务/工厂管理员/运营，仓储角色 L1 上 {@code sales='-' finance='-'}。</p>
     *
     * <h3>为什么不是别的做法</h3>
     * <ul>
     *   <li>❌ <b>把 {@code /active} 继续开着</b> —— 那不是「仓储需要」的最小满足，
     *       是把全厂客户的电话/税号/信用额度/余额继续对<b>同厂任何已登录用户</b>开放。</li>
     *   <li>❌ <b>改调已经带门的 {@code GET /customers}</b> —— 仓储角色现在拿到的是 403
     *       (从前是 {@code @DataScope} 下的 0 条), 页面上的下拉照样是空的, 只是坏得更响。</li>
     *   <li>❌ <b>{@code warehouse:read}</b> (= 与那个页面自己的模块闸同宽) —— 差一点就这么写了。
     *       实测 L1: {@code warehouse='r'} 的角色里有 <b>{@code production_manager}</b>,
     *       而 #615 <b>刻意</b>让它看不到客户名单 (它的生产计划客户下拉因此变空)。
     *       挂 read 等于给它开一个侧门, <b>把 #615 撤销掉</b>。</li>
     *   <li>✅ <b>{@code warehouse:read_write} 单码</b> —— 判据不是「谁能打开这个页面」，
     *       而是<b>「谁能打开那个含下拉的弹窗」</b>: 新建/编辑原料类型受
     *       {@code MATERIAL_TYPE_WRITE_ROLES} + 后端 {@code @RequireRole} 限定为
     *       {@code warehouse_manager} + 平台/工厂管理员。前者 L1 {@code warehouse='rw'},
     *       后者走超管分支 ⇒ <b>能打开表单的角色 100% 覆盖</b>,
     *       而 {@code production_manager}/{@code production_clerk}/{@code team_leader}/
     *       {@code viewer}/{@code dispatcher}/{@code workshop_supervisor} 等
     *       {@code warehouse='r'} 的角色一个都进不来。</li>
     * </ul>
     *
     * <h3>⚠️ 这是裁定的一个显式例外，登记在此而不是豁免</h3>
     * <p>{@code warehouse_manager} 仍然会看到<b>客户名</b>。这一步无法再用权限码收窄 ——
     * 数据里不存在「不显示名字也能选对客户」这种状态。
     * ⚠️ 另有一处已知的宽余: {@code warehouse_worker} L1 也是 {@code 'rw'}, 所以它<b>能调到
     * 这个端点</b>, 尽管它打不开那个表单 (被 {@code @RequireRole} 白名单挡在写侧)。
     * 再收窄需要引入角色白名单 —— 那是另一套机制, 不在本票范围内, <b>登记而非豁免</b>。</p>
     *
     * <p>⛔ 谁想把这个端点扩到别的模块/别的字段, 请先回去读裁定: 它现在的宽度是
     * 「一个仓储数据录入字段所需的最小值」, 不是一个通用客户下拉。</p>
     */
    @RequirePermission({WAREHOUSE_RW})
    @GetMapping("/warehouse-options")
    @Operation(summary = "仓储模块客户下拉 (最小字段)",
            description = "只返回 {id, name}。供仓储「原料类型字典」页的「包材关联固定客户」下拉使用。"
                    + "RBAC: warehouse:read_write | warehouse:read —— 与该页面自身的模块闸同宽。")
    public ApiResponse<List<CustomerOptionDTO>> getWarehouseCustomerOptions(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId) {

        List<CustomerDTO> customers = customerService.getActiveCustomers(factoryId);
        if (customers == null) {
            return ApiResponse.success(List.of());
        }
        // ⛔ 只投影 id/name。这里**不要**图省事直接返 CustomerDTO —— 收窄字段是本端点
        //    存在的一半理由 (另一半是收窄角色)。
        List<CustomerOptionDTO> options = customers.stream()
                .map(c -> new CustomerOptionDTO(c.getId(), c.getName()))
                .toList();
        return ApiResponse.success(options);
    }

    /**
     * 搜索客户
     *
     * <p>档 A: 关键词可枚举 ⇒ 等价于整份名单 (空串/单字母就能把表捞出来)。</p>
     *
     * <p>消费方普查: RN {@code CustomerManagementScreen.tsx:144}。该屏挂在
     * {@code SalesManagerNavigator} (sales_manager ✅) 和 {@code MainNavigator}
     * ({@code platform_admin} ✅ 超管分支 / {@code equipment_admin}、{@code quality_manager}、
     * {@code permission_admin} ❌ L1 三个都是 {@code sales='-' finance='-'})。
     * 🔴 后三个会 403 —— 而那是<b>裁定的既定后果</b>而非本票新增: 同一个屏的主列表
     * ({@code getCustomers} → {@code GET /customers}) 在 #615 就已经对它们 403 了,
     * 搜索框是同一张列表上的过滤器。</p>
     */
    @RequirePermission({ROSTER_SALES_RW, ROSTER_SALES_R, ROSTER_FINANCE_RW, ROSTER_FINANCE_R})
    @GetMapping("/search")
    @Operation(summary = "搜索客户", description = "根据关键词搜索客户，支持按名称、代码、联系人模糊匹配")
    public ApiResponse<List<CustomerDTO>> searchCustomers(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "搜索关键词", example = "海鲜批发", required = true)
            @RequestParam @NotBlank String keyword) {

        List<CustomerDTO> customers = customerService.searchCustomersByName(factoryId, keyword);
        return ApiResponse.success(customers);
    }

    /**
     * 按客户类型获取客户
     *
     * <p>档 A: 类型是一个 8 值枚举 (DEALER/RETAILER/…/OTHER)，逐个取并集 = 整份名单。
     * 消费方普查: web-admin / RN <b>零调用点</b> (2026-09-06 全仓 grep)，只有 AI 工具
     * {@code customer_by_type} 走这条语义。</p>
     */
    @RequirePermission({ROSTER_SALES_RW, ROSTER_SALES_R, ROSTER_FINANCE_RW, ROSTER_FINANCE_R})
    @GetMapping("/by-type")
    @Operation(summary = "按客户类型获取客户", description = "根据客户类型筛选客户列表，如：经销商、零售商、餐饮企业等")
    public ApiResponse<List<CustomerDTO>> getCustomersByType(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "客户类型: DEALER/RETAILER/RESTAURANT/ENTERPRISE", example = "DEALER", required = true)
            @RequestParam @NotBlank String type) {

        List<CustomerDTO> customers = customerService.getCustomersByType(factoryId, type);
        return ApiResponse.success(customers);
    }

    /**
     * 按行业获取客户
     *
     * <p>档 A: 同 {@code /by-type} —— 按行业逐个取并集 = 整份名单。
     * 消费方普查: web-admin / RN <b>零调用点</b> (2026-09-06 全仓 grep)。</p>
     */
    @RequirePermission({ROSTER_SALES_RW, ROSTER_SALES_R, ROSTER_FINANCE_RW, ROSTER_FINANCE_R})
    @GetMapping("/by-industry")
    @Operation(summary = "按行业获取客户", description = "根据客户所属行业筛选客户列表")
    public ApiResponse<List<CustomerDTO>> getCustomersByIndustry(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "行业类型", example = "餐饮", required = true)
            @RequestParam @NotBlank String industry) {

        List<CustomerDTO> customers = customerService.getCustomersByIndustry(factoryId, industry);
        return ApiResponse.success(customers);
    }

    /**
     * Sprint 4 W2 S-PRICE-1 — 查询客户 × 产品的最近成交记忆价 (供 SO Item 行 R1 hint).
     *
     * 返回 null = 该客户该产品从未成交 (SO Item 此时仅显 PriceList 标准价 + 系统默认).
     *
     * 防呆 R1: dialog 创建 SO Item 时, 此 API 返回的 hint 让用户**预先看到**上次成交价,
     * 而不是输完后再被告知"价格偏离上次成交 X%".
     *
     * <p>档 B —— 本轮 12 个收口端点里<b>最敏感</b>的一个: 它吐的是某客户某产品的
     * <b>议价历史</b> (成交单价 + 来源订单号 + 日期)。消费方普查: web-admin
     * {@code views/sales/orders/list.vue:1235}, 路由 {@code meta.module='sales'} ⇒
     * 走得到的角色必然持 {@code sales:read}, 本门不改变它的行为。</p>
     */
    @RequirePermission({ROSTER_SALES_RW, ROSTER_SALES_R, ROSTER_FINANCE_RW, ROSTER_FINANCE_R})
    @GetMapping("/{customerId}/price-memory")
    @Operation(summary = "查询客户记忆价 (S-PRICE-1)",
            description = "客户 × 产品的最近成交单价 + 来源订单号 + 日期. 用于 SO Item 创建时 R1 hint.")
    public ApiResponse<Map<String, Object>> getCustomerPriceMemory(
            @Parameter(description = "工厂ID", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "客户ID", required = true)
            @PathVariable @NotBlank String customerId,
            @Parameter(description = "产品类型ID (NOT materialId)", required = true)
            @RequestParam @NotBlank String productTypeId) {
        return customerPriceMemoryService.getLatest(factoryId, customerId, productTypeId)
                .map(row -> ApiResponse.success(Map.<String, Object>of(
                        "unitPrice", row.getUnitPrice(),
                        "sourceOrderNumber", row.getSourceOrderNumber() != null ? row.getSourceOrderNumber() : "",
                        "sourceSalesOrderId", row.getSourceSalesOrderId(),
                        "orderDate", row.getOrderDate().toString()
                )))
                .orElseGet(() -> ApiResponse.success(null));
    }

    /**
     * 切换客户状态
     * 支持两种参数格式：
     * 1. URL参数: PUT /{customerId}/status?isActive=true
     * 2. Request Body: PUT /{customerId}/status {"isActive": true}
     */
    @RequirePermission({"sales:read_write", "finance:read_write"})
    @PutMapping("/{customerId}/status")
    @Operation(summary = "切换客户状态", description = "切换客户的激活/停用状态，支持URL参数或RequestBody两种方式传参")
    public ApiResponse<CustomerDTO> toggleCustomerStatus(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "客户ID", example = "CUST-001", required = true)
            @PathVariable @NotBlank String customerId,
            @Parameter(description = "激活状态 (URL参数)", example = "true")
            @RequestParam(required = false) Boolean isActive,
            @Parameter(description = "激活状态 (Body参数): {\"isActive\": true}")
            @RequestBody(required = false) Map<String, Boolean> body) {

        // 优先使用URL参数，如果没有则从body获取
        Boolean activeStatus = isActive;
        if (activeStatus == null && body != null) {
            activeStatus = body.get("isActive");
        }
        if (activeStatus == null) {
            throw new BusinessException(400, "参数错误: isActive 是必需的");
        }

        log.info("切换客户状态: factoryId={}, customerId={}, isActive={}",
                factoryId, customerId, activeStatus);
        CustomerDTO customer = customerService.toggleCustomerStatus(factoryId, customerId, activeStatus);
        return ApiResponse.success("客户状态更新成功", customer);
    }

    /**
     * 更新客户评级
     */
    @RequirePermission({"sales:read_write", "finance:read_write"})
    @PutMapping("/{customerId}/rating")
    @Operation(summary = "更新客户评级", description = "更新客户的评级等级（1-5星），可附加评级说明备注")
    public ApiResponse<CustomerDTO> updateCustomerRating(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "客户ID", example = "CUST-001", required = true)
            @PathVariable @NotBlank String customerId,
            @Parameter(description = "评级(1-5)", example = "4", required = true)
            @RequestParam @NotNull Integer rating,
            @Parameter(description = "评级说明", example = "订单量稳定，回款及时")
            @RequestParam(required = false) String notes) {

        log.info("更新客户评级: factoryId={}, customerId={}, rating={}",
                factoryId, customerId, rating);
        CustomerDTO customer = customerService.updateCustomerRating(factoryId, customerId, rating, notes);
        return ApiResponse.success("客户评级更新成功", customer);
    }

    /**
     * 更新客户信用额度
     */
    @RequirePermission({"sales:read_write", "finance:read_write"})
    @PutMapping("/{customerId}/credit-limit")
    @Operation(summary = "更新客户信用额度", description = "更新客户的信用额度上限，用于控制客户的赊账额度")
    public ApiResponse<CustomerDTO> updateCreditLimit(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "客户ID", example = "CUST-001", required = true)
            @PathVariable @NotBlank String customerId,
            @Parameter(description = "信用额度（元）", example = "100000.00", required = true)
            @RequestParam @NotNull BigDecimal creditLimit) {

        log.info("更新客户信用额度: factoryId={}, customerId={}, creditLimit={}",
                factoryId, customerId, creditLimit);
        CustomerDTO customer = customerService.updateCreditLimit(factoryId, customerId, creditLimit);
        return ApiResponse.success("信用额度更新成功", customer);
    }

    /**
     * 更新客户当前余额
     */
    @RequirePermission({"sales:read_write", "finance:read_write"})
    @PutMapping("/{customerId}/balance")
    @Operation(summary = "更新客户当前余额", description = "更新客户账户的当前余额，用于记录客户的预付款或欠款情况")
    public ApiResponse<CustomerDTO> updateCurrentBalance(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "客户ID", example = "CUST-001", required = true)
            @PathVariable @NotBlank String customerId,
            @Parameter(description = "当前余额（元），正数表示预付款，负数表示欠款", example = "5000.00", required = true)
            @RequestParam @NotNull BigDecimal balance) {

        log.info("更新客户当前余额: factoryId={}, customerId={}, balance={}",
                factoryId, customerId, balance);
        CustomerDTO customer = customerService.updateCurrentBalance(factoryId, customerId, balance);
        return ApiResponse.success("客户余额更新成功", customer);
    }

    /**
     * 获取客户统计信息
     *
     * <p>档 B: 单个客户的订单数/总金额/平均单价 —— 是「这家客户给我们贡献了多少钱」。
     * 消费方普查: web-admin / RN <b>零调用点</b> (2026-09-06 全仓 grep), AI 侧由
     * {@code customer_stats(customerId=…)} 走同一条语义。</p>
     */
    @RequirePermission({ROSTER_SALES_RW, ROSTER_SALES_R, ROSTER_FINANCE_RW, ROSTER_FINANCE_R})
    @GetMapping("/{customerId}/statistics")
    @Operation(summary = "获取客户统计信息", description = "获取指定客户的统计数据，包括订单总数、总金额、平均订单金额等")
    public ApiResponse<Map<String, Object>> getCustomerStatistics(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "客户ID", example = "CUST-001", required = true)
            @PathVariable @NotBlank String customerId) {

        Map<String, Object> statistics = customerService.getCustomerStatistics(factoryId, customerId);
        return ApiResponse.success(statistics);
    }

    /**
     * 获取客户购买历史
     *
     * <p>档 B: 单个客户的历史订单明细 (时间/产品/数量/金额)。
     * 消费方普查: web-admin / RN <b>零调用点</b> (2026-09-06 全仓 grep), AI 侧是
     * {@code customer_purchase_history}。</p>
     */
    @RequirePermission({ROSTER_SALES_RW, ROSTER_SALES_R, ROSTER_FINANCE_RW, ROSTER_FINANCE_R})
    @GetMapping("/{customerId}/purchase-history")
    @Operation(summary = "获取客户购买历史", description = "获取指定客户的历史购买记录列表，包括订单时间、产品、数量、金额等")
    public ApiResponse<List<Map<String, Object>>> getCustomerPurchaseHistory(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "客户ID", example = "CUST-001", required = true)
            @PathVariable @NotBlank String customerId) {

        List<Map<String, Object>> history = customerService.getCustomerPurchaseHistory(factoryId, customerId);
        return ApiResponse.success(history);
    }

    /**
     * 检查客户代码是否存在
     *
     * <p>🔵 档 C —— <b>显式登记「故意不关」</b>(⛔ 不是漏了):</p>
     * <ul>
     *   <li>它返回的是一个 {@code boolean}, 一个客户名/联系人/金额都不吐;</li>
     *   <li>调用方必须<b>先知道</b>那个 code 才问得出来 —— 它回答的是「我要建的这个
     *       code 撞不撞」, 不是「都有哪些客户」;</li>
     *   <li>它服务的是<b>写</b>流程 (建客户前查重), 而写侧已经有
     *       {@code sales|finance:read_write} 的门。关它只会让建客户流程多一个 403,
     *       安全上一分不涨。</li>
     * </ul>
     * <p>⚠️ 残余风险登记在案: 逐个 code 暴力试探能问出「某个 code 存不存在」。
     * 那是一个 <b>oracle</b> 而不是名单, 且没有名字/联系方式伴随。判为可接受。</p>
     */
    @GetMapping("/check-code")
    @Operation(summary = "检查客户代码是否存在", description = "检查指定客户代码在工厂内是否已存在，用于创建客户前的唯一性校验")
    public ApiResponse<Boolean> checkCustomerCode(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "客户代码", example = "KH20250101001", required = true)
            @RequestParam @NotBlank String customerCode) {

        boolean exists = customerService.checkCustomerCodeExists(factoryId, customerCode);
        return ApiResponse.success(exists);
    }

    /**
     * 导出客户列表
     *
     * <p>🔴 档 A 里<b>最重</b>的一个: 它把整张客户表以 Excel 落到本地磁盘 (不是屏幕上翻页),
     * 一次调用 = 一份可离线传播的全厂客户名录。消费方普查: web-admin / RN
     * <b>零调用点</b> (2026-09-06 全仓 grep) —— 也就是说这道门<b>不会打断任何现有流程</b>。</p>
     *
     * <p>⚠️ 下面那句 {@code maskPrice} 只挡「信用额度 / 当前余额」两列, 客户名 / 联系人 /
     * 电话 / 税号照吐 —— ⛔ 它<b>不是</b>一道名单门, 别把它读成「已经有 RBAC 了」。</p>
     */
    @RequirePermission({ROSTER_SALES_RW, ROSTER_SALES_R, ROSTER_FINANCE_RW, ROSTER_FINANCE_R})
    @GetMapping("/export")
    @Operation(summary = "导出客户列表",
            description = "将工厂的所有客户数据导出为Excel文件，文件名包含时间戳。"
                    + "RBAC: 无 procurement:price:view 权限的角色, Excel 中 信用额度 / 当前余额 列显示 '—'.")
    public ResponseEntity<byte[]> exportCustomerList(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @RequestHeader("Authorization") String authorization) {

        // RBAC defense-in-depth (P0-C sweep, 2026-05-12): PriceFieldResponseAdvice (PR #423)
        // 只处理 JSON body, 不 walk byte[] Excel — chat2 PR #450 sweep matrix marked this
        // endpoint vulnerable (creditLimit + currentBalance cols 9/10). Mirror PR #450 pattern:
        // resolve user, gate via PermissionService, pass mask flag to service.
        boolean maskPrice = priceMaskResolver.shouldMaskPrice(authorization);

        log.info("导出客户列表: factoryId={}, maskPrice={}", factoryId, maskPrice);
        byte[] excelBytes = customerService.exportCustomerList(factoryId, maskPrice);

        // 生成文件名（包含时间戳）
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = "客户列表_" + timestamp + ".xlsx";

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
     * 下载客户导入模板
     *
     * <p>🔵 档 C —— <b>显式登记「故意不关」</b>: {@code generateImportTemplate()} 不接
     * {@code factoryId}, 产出是一份<b>空表头 + 示例行</b>的静态 xlsx, 与本厂数据零关系。
     * ⚠️ 判据不是「名字里带 export」而是「它读不读客户表」—— 读的是
     * {@code /export}(已关), 这个不读。</p>
     */
    @GetMapping("/export/template")
    @Operation(summary = "下载客户导入模板", description = "下载用于批量导入客户的Excel模板文件，包含字段说明和示例数据")
    public ResponseEntity<byte[]> downloadCustomerTemplate(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId) {

        log.info("下载客户导入模板: factoryId={}", factoryId);
        byte[] templateBytes = customerService.generateImportTemplate();

        // 设置文件名
        String filename = "客户导入模板.xlsx";

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
     * 从Excel文件批量导入客户
     */
    @RequirePermission({"sales:read_write", "finance:read_write"})
    @PostMapping("/import")
    @Operation(summary = "从Excel文件批量导入客户", description = "通过上传Excel文件批量导入客户数据，支持.xlsx格式，文件大小限制10MB")
    public ApiResponse<com.cretas.aims.dto.common.ImportResult<CustomerDTO>> importCustomersFromExcel(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "Excel文件（.xlsx格式，最大10MB）", required = true)
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {

        log.info("从Excel批量导入客户: factoryId={}, filename={}", factoryId, file.getOriginalFilename());

        // 验证文件类型
        if (file.getOriginalFilename() == null || !file.getOriginalFilename().endsWith(".xlsx")) {
            throw new BusinessException(400, "只支持.xlsx格式的Excel文件");
        }

        // 验证文件大小（10MB限制）
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new BusinessException(400, "文件大小不能超过10MB");
        }

        try {
            com.cretas.aims.dto.common.ImportResult<CustomerDTO> result =
                    customerService.importCustomersFromExcel(factoryId, file.getInputStream());

            if (result.getIsFullSuccess()) {
                log.info("客户批量导入完全成功: factoryId={}, count={}", factoryId, result.getSuccessCount());
                return ApiResponse.success("导入成功", result);
            } else {
                log.warn("客户批量导入部分失败: factoryId={}, success={}, failure={}",
                        factoryId, result.getSuccessCount(), result.getFailureCount());
                return ApiResponse.success(
                        String.format("导入完成：成功%d条，失败%d条", result.getSuccessCount(), result.getFailureCount()),
                        result);
            }
        } catch (BusinessException be) {
            throw be;

        } catch (Exception e) {
            log.error("客户批量导入失败: factoryId={}", factoryId, e);
            throw new BusinessException(500, "导入失败: " + ErrorSanitizer.sanitize(e), e);
        }
    }

    /**
     * 批量导入客户（旧版本，使用JSON）
     */
    @RequirePermission({"sales:read_write", "finance:read_write"})
    @PostMapping("/import/json")
    @Operation(summary = "批量导入客户（JSON格式）", description = "通过JSON数组格式批量导入客户数据，适用于API对接场景")
    public ApiResponse<List<CustomerDTO>> importCustomersFromJson(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "访问令牌", example = "Bearer eyJhbGciOiJIUzI1NiJ9...", required = true)
            @RequestHeader("Authorization") String authorization,
            @Parameter(description = "客户创建请求列表")
            @Valid @RequestBody List<CreateCustomerRequest> requests) {

        // 获取当前用户ID
        String token = TokenUtils.extractToken(authorization);
        Long userId = mobileService.getUserFromToken(token).getId();

        log.info("批量导入客户(JSON): factoryId={}, count={}", factoryId, requests.size());
        List<CustomerDTO> customers = customerService.importCustomers(factoryId, requests, userId.longValue());
        return ApiResponse.success(String.format("成功导入%d个客户", customers.size()), customers);
    }

    /**
     * 获取客户评级分布
     *
     * <p>🔵 档 C —— <b>显式登记「故意不关」</b>: 返回 {@code 评级 -> 计数}, 没有任何一行
     * 带客户身份。判据是「能不能从返回值里认出某一个客户」, 不是「它是不是客户接口」。</p>
     */
    @GetMapping("/rating-distribution")
    @Operation(summary = "获取客户评级分布", description = "统计工厂内各评级（1-5星）的客户数量分布情况")
    public ApiResponse<Map<Integer, Long>> getCustomerRatingDistribution(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId) {

        Map<Integer, Long> distribution = customerService.getCustomerRatingDistribution(factoryId);
        return ApiResponse.success(distribution);
    }

    /**
     * 获取有欠款的客户
     *
     * <p>🔴 档 A，而且<b>不在派活时那张分档表里</b> —— 硬约束 8 (把 18 个
     * {@code @GetMapping} 从头数一遍) 才把它和 {@code /vip} 捞出来。它返回
     * {@code List<CustomerDTO>}: 客户名 + 欠款金额, 比一般名单更敏感 (应收账款)。
     * 消费方普查: web-admin / RN <b>零调用点</b>。</p>
     */
    @RequirePermission({ROSTER_SALES_RW, ROSTER_SALES_R, ROSTER_FINANCE_RW, ROSTER_FINANCE_R})
    @GetMapping("/outstanding-balance")
    @Operation(summary = "获取有欠款的客户", description = "获取工厂内所有存在欠款（余额为负）的客户列表，用于应收账款管理")
    public ApiResponse<List<CustomerDTO>> getCustomersWithOutstandingBalance(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId) {

        List<CustomerDTO> customers = customerService.getCustomersWithOutstandingBalance(factoryId);
        return ApiResponse.success(customers);
    }

    /**
     * 获取VIP客户
     *
     * <p>🔴 档 A，同样是硬约束 8 逐个数 {@code @GetMapping} 才发现的 (不在分档表里)。
     * 返回 {@code List<CustomerDTO>}, {@code limit} 最大不设上限 ⇒ 一次调用就能把
     * 按重要度排序的名单整段拿走。消费方普查: web-admin / RN <b>零调用点</b>。</p>
     */
    @RequirePermission({ROSTER_SALES_RW, ROSTER_SALES_R, ROSTER_FINANCE_RW, ROSTER_FINANCE_R})
    @GetMapping("/vip")
    @Operation(summary = "获取VIP客户", description = "获取工厂内的VIP客户列表，按重要程度排序")
    public ApiResponse<List<CustomerDTO>> getVIPCustomers(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId,
            @Parameter(description = "返回数量限制", example = "10")
            @RequestParam(defaultValue = "10") Integer limit) {

        List<CustomerDTO> customers = customerService.getVIPCustomers(factoryId, limit);
        return ApiResponse.success(customers);
    }

    /**
     * 获取客户类型分布
     *
     * <p>🔵 档 C —— <b>显式登记「故意不关」</b>: 返回 {@code 类型 -> 计数}, 无客户身份。</p>
     */
    @GetMapping("/type-distribution")
    @Operation(summary = "获取客户类型分布", description = "统计工厂内各类型（经销商/零售商/餐饮/企业等）的客户数量分布")
    public ApiResponse<Map<String, Long>> getCustomerTypeDistribution(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId) {

        Map<String, Long> distribution = customerService.getCustomerTypeDistribution(factoryId);
        return ApiResponse.success(distribution);
    }

    /**
     * 获取客户行业分布
     *
     * <p>🔵 档 C —— <b>显式登记「故意不关」</b>: 返回 {@code 行业 -> 计数}, 无客户身份。</p>
     */
    @GetMapping("/industry-distribution")
    @Operation(summary = "获取客户行业分布", description = "统计工厂内各行业（餐饮/零售/批发/食品加工等）的客户数量分布")
    public ApiResponse<Map<String, Long>> getCustomerIndustryDistribution(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId) {

        Map<String, Long> distribution = customerService.getCustomerIndustryDistribution(factoryId);
        return ApiResponse.success(distribution);
    }

    /**
     * 获取客户总体统计
     *
     * <p>🔵 档 C —— <b>显式登记「故意不关」</b>。
     *
     * <p>⚠️ 返回类型是 {@code Map<String, Object>} —— 这个签名<b>装得下任何东西</b>，
     * 「它只是聚合」<b>读不出来</b>，必须去读实现。
     * {@link com.cretas.aims.service.impl.CustomerServiceImpl#getOverallCustomerStatistics}
     * 实测<b>恰好 8 个 key</b>:</p>
     * <pre>
     *   totalCustomers           long                客户总数
     *   activeCustomers          long                活跃客户数
     *   averageRating            Double              平均评级
     *   totalOutstandingBalance  BigDecimal          总欠款 (全厂合计, 不分客户)
     *   totalCreditLimit         BigDecimal          总信用额度 (全厂合计)
     *   typeDistribution         Map(String,Long)    客户类型 -&gt; 计数
     *   industryDistribution     Map(String,Long)    行业     -&gt; 计数
     *   ratingDistribution       Map(Integer,Long)   评级     -&gt; 计数
     * </pre>
     * <p>⇒ <b>没有客户名 / 客户 id / top-N 客户</b>。三张嵌套分布表实测也都只 put
     * 「维度 -&gt; 计数」(type/industry 为 null 时归到字面量 {@code "未分类"}，
     * rating 为 null 时归到 {@code 0}，⛔ 不带任何客户身份)。故留在档 C。</p>
     *
     * <p>🔴 这个 key 集合由 {@code CustomerOverallStatisticsKeysContractTest} 钉住。
     * 谁加了 {@code topCustomers} / {@code largestCustomerName} 之类的 key，那条会当场红 ——
     * ⛔ 那时请把本端点<b>改判档 A 并挂上裁定四码</b>，而不是把新 key 加进断言。</p>
     *
     * <p>⚠️ 与 {@code /{customerId}/statistics}(档 B, 已关) 的区别是<b>有没有 customerId 这一维</b>:
     * 有, 就是「这一家客户给我们贡献了多少钱」; 没有, 就是一个总数。</p>
     */
    @GetMapping("/overall-statistics")
    @Operation(summary = "获取客户总体统计", description = "获取工厂客户的综合统计数据，包括总客户数、活跃客户数、信用额度汇总等")
    public ApiResponse<Map<String, Object>> getOverallCustomerStatistics(
            @Parameter(description = "工厂ID", example = "F001", required = true)
            @PathVariable @NotBlank String factoryId) {

        Map<String, Object> statistics = customerService.getOverallCustomerStatistics(factoryId);
        return ApiResponse.success(statistics);
    }
}