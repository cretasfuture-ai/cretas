package com.cretas.aims.service.voucher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 凭证科目解析 —— 让客户能把生成器里写死的会计科目换成自己账套的。
 *
 * <h2>为什么不复用 {@code voucher_subject_mappings}</h2>
 *
 * <p>那张表的键是 {@code (settlement_type, business_type)}，而
 * {@code SettlementType} 是<b>采购专用</b>枚举（预付/赊销先入库/未到票/月结/账期/现结），
 * 没有中性值。调拨、工资、折旧的科目与结算方式毫无关系 —— 硬塞进去是滥用模型，
 * 下一个人会被那张表误导。⇒ 另建键对的
 * {@code voucher_type_subject_overrides (factory_id, voucher_type, subject_role)}。
 *
 * <h2>🔴 fail-open，不是 fail-closed</h2>
 *
 * <p>查不到覆盖 → 用调用方传进来的默认值（也就是今天硬编码的那个）。
 * ⛔ 不做「没配就拒绝开凭证」：配错科目是<b>数字记错地方</b>，
 * 开不出凭证是<b>业务停摆</b>，后者贵得多。
 *
 * <p>⚠️ 同理，查询本身抛异常也走默认值并打 WARN ——
 * 一张<b>可选</b>的覆盖表不该有能力让凭证生成挂掉。
 *
 * <h2>空表 = 行为不变</h2>
 *
 * <p>迁移刻意不预置任何行。所以这个类接进去之后，在没人配置的工厂上
 * 产出的科目与改动前<b>逐字一致</b> —— 这一点由 {@code VoucherSubjectResolverTest}
 * 的阳性对照钉住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoucherSubjectResolver {

    private final JdbcTemplate jdbcTemplate;

    /** 一个科目 = 代码 + 名称。 */
    public record Subject(String code, String name) { }

    /**
     * 解析某凭证类型下某个角色该用哪个科目。
     *
     * @param factoryId   工厂
     * @param voucherType {@code VoucherType} 枚举名, 例如 SALES_RECEIPT
     * @param subjectRole 该凭证类型内部的角色名, 例如 DEBIT / CREDIT / TAX_OUTPUT
     * @param defaultCode 查不到时用的默认科目代码(即生成器里今天写死的那个)
     * @param defaultName 查不到时用的默认科目名称
     */
    public Subject resolve(String factoryId, String voucherType, String subjectRole,
                           String defaultCode, String defaultName) {
        if (factoryId == null || factoryId.isBlank()) {
            return new Subject(defaultCode, defaultName);
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT subject_code, subject_name FROM voucher_type_subject_overrides "
                            + "WHERE factory_id = ? AND voucher_type = ? AND subject_role = ? "
                            + "  AND deleted_at IS NULL LIMIT 1",
                    factoryId, voucherType, subjectRole);
            if (rows.isEmpty()) {
                return new Subject(defaultCode, defaultName);
            }
            Object code = rows.get(0).get("subject_code");
            if (code == null || code.toString().isBlank()) {
                // 配了一行却没有代码 —— 那不是覆盖, 是脏数据。说出来, 然后走默认值。
                log.warn("[VoucherSubject] 覆盖行的 subject_code 为空, 回落默认值: "
                        + "factory={} type={} role={}", factoryId, voucherType, subjectRole);
                return new Subject(defaultCode, defaultName);
            }
            Object name = rows.get(0).get("subject_name");
            return new Subject(code.toString(),
                    name == null || name.toString().isBlank() ? defaultName : name.toString());
        } catch (Exception e) {
            // ⚠️ 一张可选的覆盖表不该有能力让凭证生成挂掉。
            log.warn("[VoucherSubject] 覆盖表查询失败, 回落默认值: factory={} type={} role={}",
                    factoryId, voucherType, subjectRole, e);
            return new Subject(defaultCode, defaultName);
        }
    }
}
