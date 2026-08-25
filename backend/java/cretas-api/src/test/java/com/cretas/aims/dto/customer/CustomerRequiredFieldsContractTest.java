package com.cretas.aims.dto.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— 客户创建<b>只有客户名称必填</b>，联系人/电话/收货地址是选填。
 *
 * <h2>为什么有这道闸（2026-08-25 裁定归档）</h2>
 *
 * dispatch 台账长期挂着一条
 * {@code ENH-F006-CUSTOMER-CREATE-PROGRESSIVE-DISCLOSURE-001}，
 * 描述为「Web 渐进表单已存在，但当前后端仅 name 必填，与 ACTIVE 原五项强制冲突，
 * 须先由 Owner 裁定产品真值」。
 *
 * <p><b>产品真值在仓库里已经有了</b>，不需要再裁定一次 —— 四个面全部一致：
 * <ol>
 *   <li>{@link CreateCustomerRequest}：只有 {@code name} 带 {@code @NotBlank}，
 *       且源码里写着来由 ——「T10 / spec P2.1: contact fields are now optional per
 *       docx feedback (some customers don't have a designated contact person)」。</li>
 *   <li>Web 新建/编辑表单：只有 {@code name} / {@code status} 带 required，
 *       由 {@code customerFormProgressive.source.spec.ts} 钉住
 *       （2026-08-12 按 Steve 要求把三个字段改成选填时同步改的）。</li>
 *   <li>Excel 导入：逐行必填校验只有一条「客户名称不能为空」。</li>
 *   <li>销售准入：<b>不存在</b>要求客户资料补齐的门禁。</li>
 * </ol>
 *
 * <p>也就是说台账里那句「原五项强制」是<b>过期描述</b>，不是待裁决的冲突。
 * 本仓形态 B⁵：<b>描述过期比没有描述更糟</b> —— 它让下一个人跳过核实。
 *
 * <h2>钉住什么</h2>
 * 这道闸不允许有人在<b>不改产品裁定</b>的情况下，悄悄把联系人/电话/地址加回必填。
 * 要改回去是可以的，但必须同时改这道闸 —— 那时它就是一次显式的产品决定，而不是漂移。
 */
class CustomerRequiredFieldsContractTest {

    /** 反射取带 {@code @NotBlank}/{@code @NotNull} 的字段名 —— 量结构, 不量源码文本(形态 C⁸)。 */
    private static List<String> mandatoryFieldsOf(Class<?> dto) {
        List<String> names = new ArrayList<>();
        for (Field f : dto.getDeclaredFields()) {
            if (f.isAnnotationPresent(NotBlank.class) || f.isAnnotationPresent(NotNull.class)) {
                names.add(f.getName());
            }
        }
        return names;
    }

    @Test
    @DisplayName("🔴 创建客户只有客户名称必填")
    void onlyNameIsMandatoryOnCreate() {
        assertEquals(List.of("name"), mandatoryFieldsOf(CreateCustomerRequest.class),
                "客户创建的必填字段集变了 —— 这是一次产品决定, 不能靠加注解悄悄发生");
    }

    @Test
    @DisplayName("🔴 联系人 / 联系电话 / 收货地址是选填 (T10 / spec P2.1 docx feedback)")
    void contactFieldsStayOptional() {
        List<String> mandatory = mandatoryFieldsOf(CreateCustomerRequest.class);
        for (String optional : List.of("contactPerson", "phone", "shippingAddress",
                "email", "billingAddress", "taxNumber", "businessLicense")) {
            assertFalse(mandatory.contains(optional),
                    optional + " 被改成了必填 —— 与「有些客户没有指定联系人」这条产品真值冲突");
        }
    }

    @Test
    @DisplayName("阳性对照: 这些字段确实存在 (否则上面那条只是因为字段名拼错了)")
    void theOptionalFieldsActuallyExist() {
        List<String> declared = new ArrayList<>();
        for (Field f : CreateCustomerRequest.class.getDeclaredFields()) {
            declared.add(f.getName());
        }
        for (String field : List.of("name", "contactPerson", "phone", "shippingAddress")) {
            assertTrue(declared.contains(field),
                    field + " 在 DTO 上根本不存在 —— 上面的「不是必填」断言是恒真式");
        }
    }

    @Test
    @DisplayName("编辑客户连 name 都不强制 (局部更新), 但格式校验保留")
    void updateAllowsPartialPayload() {
        assertEquals(List.of(), mandatoryFieldsOf(UpdateCustomerRequest.class),
                "编辑是局部更新, 不该强制任何字段; 要改必须是一次显式产品决定");
    }
}
