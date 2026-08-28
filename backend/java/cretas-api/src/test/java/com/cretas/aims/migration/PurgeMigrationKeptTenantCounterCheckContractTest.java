package com.cretas.aims.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 清租户/清数据的迁移，必须自带一条「保留名单反查 = 0 行」。
 *
 * <h2>为什么</h2>
 *
 * <p>{@code V20261029_68__purge_nonkept_factory_tenants.sql} 头部白纸黑字写着
 * 「工厂侧只保留 F006 + LIUSHANMEN」，用**显式 id 名单**选要删的租户，
 * 再顺着外键做不动点扩散算出删除集。
 *
 * <p>2026-08-28 实测：备份 schema {@code tenant_purge_68} 里，
 * <b>169 张带 factory_id 的表</b>中恰好有 <b>3 行属于被保留的租户</b>：
 * <pre>
 * F006        PP-AUTO-20260803-0006  PENDING     2026-08-03
 * F006        PP-AUTO-20260803-0007  PENDING     2026-08-03
 * LIUSHANMEN  PP-AUTO-20260804-0003  CANCELLED   2026-08-04
 * </pre>
 * 三条在 {@code public.production_plans} 里<b>都已经不存在</b>（物理删）。
 * 数据没丢（整行备份还在），但<b>「闭包扩散会把保留租户的行卷进来」这件事之前没人知道</b>——
 * 因为没有任何一步去反查。
 *
 * <h2>约定</h2>
 *
 * <p>新增的 purge / clear 类迁移，算完删除集之后必须加一段反查，并用
 * {@code -- KEPT_TENANT_COUNTERCHECK} 标出来，形如：
 *
 * <pre>
 * -- KEPT_TENANT_COUNTERCHECK
 * DO $$
 * DECLARE n bigint;
 * BEGIN
 *     SELECT count(*) INTO n FROM &lt;删除集表&gt; WHERE factory_id IN ('F006','LIUSHANMEN');
 *     IF n &gt; 0 THEN
 *         RAISE EXCEPTION '删除集里混进了保留租户的 % 行, 拒绝执行', n;
 *     END IF;
 * END $$;
 * </pre>
 *
 * <p>⛔ 关键是 {@code RAISE EXCEPTION} —— 只 SELECT 出来打个日志不算，
 * 那是「看见了但照删」。反查必须<b>能把迁移拦下来</b>。
 *
 * <h2>⚠️ 代理判据，标出来</h2>
 *
 * <p>它扫的是源码文本（文件名含 purge/clear + 有没有那个标记和 RAISE EXCEPTION），
 * 不验证反查的 SQL 逻辑对不对。已知盲区：反查写错表、写错名单，它看不出来。
 * ⇒ 它拦的是「压根没有反查」这一种，不是「反查写错了」。
 */
class PurgeMigrationKeptTenantCounterCheckContractTest {

    private static final Path FLYWAY = Path.of("src/main/resources/db/flyway");

    private static final String MARKER = "KEPT_TENANT_COUNTERCHECK";

    /**
     * ⛔ 已经执行过的 purge 迁移 —— Flyway 校验和锁死，<b>改不了</b>。
     *
     * <p>登记是留痕，不是豁免：名单只许变短（有新的 purge 迁移时不许往里加）。
     * 这三份的实际后果已经查清并写在本类 javadoc 里。
     */
    private static final List<String> APPLIED_BEFORE_THIS_RULE = List.of(
            "V20261028_81__clear_frozen_legacy_shipment_test_data.sql",
            "V20261029_68__purge_nonkept_factory_tenants.sql",   // <- 就是它删掉了 3 行活租户数据
            "V20261029_71__f006_clear_transactional_data.sql",
            "V20261029_73__liushanmen_clear_business_code.sql",
            "V20261029_78__purge_legacy_production_batches.sql");

    private List<Path> purgeMigrations() throws IOException {
        try (var s = Files.list(FLYWAY)) {
            return s.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".sql") && (n.contains("purge") || n.contains("clear"));
            }).toList();
        }
    }

    @Test
    @DisplayName("阳性对照: 真的找得到 purge 类迁移(找不到最像「一切正常」)")
    void positiveControl() throws IOException {
        assertThat(FLYWAY).as("迁移目录搬家了, 本闸路径要跟着改").exists();
        assertThat(purgeMigrations())
                .as("一个 purge/clear 迁移都没扫到 ⇒ 仪器坏了, 不是仓里没有")
                .isNotEmpty();
    }

    @Test
    @DisplayName("🔴 新增的 purge/clear 迁移必须带保留名单反查, 且反查要能拦住迁移")
    void newPurgeMigrationsMustCounterCheckKeptTenants() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path p : purgeMigrations()) {
            String name = p.getFileName().toString();
            if (APPLIED_BEFORE_THIS_RULE.contains(name)) {
                continue;
            }
            String sql = Files.readString(p, StandardCharsets.UTF_8);
            if (!sql.contains(MARKER)) {
                missing.add(name + " (没有 " + MARKER + " 反查)");
            } else if (!sql.contains("RAISE EXCEPTION")) {
                // 只 SELECT 不 RAISE = 「看见了但照删」, 那不叫反查
                missing.add(name + " (有反查标记但不会 RAISE EXCEPTION, 拦不住)");
            }
        }
        assertThat(missing)
                .as("清租户/清数据的迁移必须在删之前反查保留名单 —— "
                        + "V20261029_68 就是因为没有这一步, 把 3 行活租户数据顺着外键闭包删掉了")
                .isEmpty();
    }

    @Test
    @DisplayName("⛔ 欠账名单只许变短: 名单里的文件必须真的还存在")
    void debtListMustNotRot() {
        for (String name : APPLIED_BEFORE_THIS_RULE) {
            assertThat(FLYWAY.resolve(name))
                    .as(name + " 已经不在了 —— 欠账名单该划掉这一条")
                    .exists();
        }
    }
}
