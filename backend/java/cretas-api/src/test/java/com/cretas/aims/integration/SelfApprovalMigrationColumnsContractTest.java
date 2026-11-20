package com.cretas.aims.integration;

import com.cretas.aims.entity.DecisionAuditLog;
import com.cretas.aims.entity.FactorySettings;
import com.cretas.aims.entity.ReportReversalLog;
import com.cretas.aims.entity.factory.FactoryStocktake;
import com.cretas.aims.entity.finance.ArApTransaction;
import com.cretas.aims.entity.inventory.PaymentRequest;
import com.cretas.aims.entity.inventory.SalesPriceAdjustmentRecord;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本人审批 / 改价阈值 (V20261110_47) 新映射的每一列, 迁移里都必须对<b>同一张表</b> ADD COLUMN。
 *
 * <p>2026-11-10 #1580 CI 红: 实体多了列而某个手写建表的夹具没有 —— 那次是夹具的问题, 但同一类漂移
 * 若发生在迁移本身 (实体映射了、迁移漏了某张表), 生产上第一次插入就炸。本测试按实体注解反推,
 * 不手抄列清单: 加新字段忘了写迁移就红。
 */
class SelfApprovalMigrationColumnsContractTest {

    private static final List<Class<?>> ENTITIES = List.of(
            FactorySettings.class, ReportReversalLog.class, FactoryStocktake.class, DecisionAuditLog.class,
            SalesPriceAdjustmentRecord.class, PaymentRequest.class, ArApTransaction.class);

    /** 本次新增的实体字段名 (按名字挑, 一个实体上可能只有其中几个)。 */
    private static final Set<String> NEW_FIELDS = Set.of(
            "selfApproved", "selfApprovalReason", "selfPaid", "selfPaidReason",
            "allowSelfApproval", "priceDecreaseApprovalPct", "priceIncreaseApprovalPct");

    @Test
    void everyNewlyMappedColumnIsAddedByTheMigrationOnTheSameTable() {
        int checked = 0;
        for (Class<?> entity : ENTITIES) {
            String table = entity.getAnnotation(Table.class).name();
            String alters = String.join("\n", SelfApprovalMigrationFixture.alterStatementsFor(table))
                    .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            for (Field field : entity.getDeclaredFields()) {
                if (!NEW_FIELDS.contains(field.getName())) continue;
                String column = field.getAnnotation(Column.class).name();
                assertThat(alters)
                        .as("%s.%s → %s.%s 必须在 V20261110_47 里 ADD COLUMN", entity.getSimpleName(), field.getName(), table, column)
                        .contains("add column if not exists " + column + " ");
                checked++;
            }
        }
        // 阳性对照: factory_settings 3 列 + 6 张审批记录表 (撤回/盘点/质检/改价/退款申请/退款撤销流水) 各 2 列
        //          + payment_requests 的 self_paid / self_paid_reason 2 列 = 17
        assertThat(checked).isEqualTo(17);
    }
}
