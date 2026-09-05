package com.cretas.aims.dto.customer;

/**
 * 客户「引用型下拉」的最小投影 —— <b>只有 id 和 name</b>。
 *
 * <p>owner 裁定 (2026-09-06): 「客户名单只有 销售、财务、工厂管理员、运营 能看。」
 * 收口之后仍然有一个模块<b>必须</b>看到客户名才能干活: 仓储「原料类型字典」页要给包材
 * 填 {@code associatedCustomerId}(关联固定客户) —— 那是个外键，不显示名字就没法选。</p>
 *
 * <p>本 DTO 存在的理由就是把那个例外<b>压到最小</b>: 它是
 * {@link CustomerDTO} 的 2/40 个字段，⛔ 不含联系人、电话、邮箱、地址、税号、
 * 信用额度、当前余额、累计销售额、评级。</p>
 *
 * <p>⛔ <b>不要给它加字段。</b> 需要更多字段就说明调用方不是「引用型下拉」，
 * 该走带名单门的 {@code GET /customers}。这一条是本 DTO 唯一的设计约束，
 * 由 {@code CustomerControllerRemainingReadPathsPermissionTest} 里那条
 * 「字段数恰好为 2」的断言钉住。</p>
 *
 * @see com.cretas.aims.controller.CustomerController#getWarehouseCustomerOptions
 */
public record CustomerOptionDTO(String id, String name) {
}
