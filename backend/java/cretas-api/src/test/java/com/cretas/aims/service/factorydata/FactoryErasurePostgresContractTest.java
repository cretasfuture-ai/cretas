package com.cretas.aims.service.factorydata;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.factorydata.FactoryDataService.AttachmentMode;
import com.cretas.aims.service.factorydata.FactoryErasureService.EraseBody;
import com.cretas.aims.service.factorydata.FactoryErasureService.ErasureRequestBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 PostgreSQL: 彻底删除。
 *
 * <p>阴性对照贯穿每个用例: 另一个工厂 FB 在两个库的每张表里各有行, 删除 FA 前后 FB 的整表快照 (逐表行数 +
 * 行内容哈希) 必须完全相同。SmartBI 库用一个非超级用户连接 (超级用户绕过行级安全, 测不出 GUC 处理),
 * LLM 外发审计表只给它 SELECT / INSERT, 与 prod 一样只能经定义者函数删除。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class FactoryErasurePostgresContractTest {

    static final String FA = "ERASETEST_A";
    static final String FB = "FB_TEST";
    static final AuthenticatedActor OPERATOR =
            new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "super_admin");

    String schema;
    String smartbiDb;
    String appRole;
    PGSimpleDataSource main;
    PGSimpleDataSource smartbiAdmin;
    PGSimpleDataSource smartbiApp;
    FactoryDataScopePostgresContractTest.FakeStore store;
    /** 时钟 = 真实时间 + 偏移 (用来把时间拨到 7 天后)。 */
    final AtomicReference<Duration> clockOffset = new AtomicReference<>(Duration.ZERO);

    @BeforeEach
    void setUp() throws Exception {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        schema = "fe_" + tag;
        appRole = "fe_app_" + tag;
        main = ds(url(), user(), password());
        exec(main, "CREATE SCHEMA " + schema);
        exec(main, Files.readString(Path.of("src/main/resources/db/flyway/V20261103_87__factory_data_requests.sql")));
        exec(main, FactoryDataScopePostgresContractTest.mainSchema(schema) + auditTablesMain(schema));
        smartbiDb = schema + "_sb";
        exec(main, "CREATE DATABASE " + smartbiDb);
        smartbiAdmin = ds(url().replaceAll("/[^/?]+(\\?|$)", "/" + smartbiDb + "$1"), user(), password());
        exec(smartbiAdmin, "CREATE SCHEMA " + schema);
        exec(smartbiAdmin, FactoryDataScopePostgresContractTest.smartbiSchema(schema) + smartbiExtras(schema));
        // 与 prod 相同的权限形状: 应用账号非超级用户; LLM 外发审计只给 SELECT / INSERT; 删除经定义者函数
        exec(smartbiAdmin, "CREATE ROLE " + appRole + " LOGIN PASSWORD 'x' NOSUPERUSER NOBYPASSRLS;"
                + "GRANT USAGE ON SCHEMA " + schema + " TO " + appRole + ";"
                + "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA " + schema + " TO " + appRole + ";"
                + "REVOKE UPDATE, DELETE ON " + schema + ".smart_bi_llm_egress_audit FROM " + appRole + ";");
        // 定义者函数直接用真实的 SmartBI 迁移, 只把 public 换成本用例的 schema (⛔ 不在测试里另写一份)
        exec(smartbiAdmin, Files.readString(Path.of(
                "../../python/smartbi/database/migrations/V20261101_19__factory_erasure_grants.sql"))
                .replace("public.", schema + ".").replace("search_path = public", "search_path = " + schema));
        exec(smartbiAdmin, "GRANT EXECUTE ON FUNCTION " + schema + ".factory_erase_llm_egress_audit(text) TO " + appRole + ";"
                + "GRANT EXECUTE ON FUNCTION " + schema + ".factory_erasure_tenant_ids() TO " + appRole + ";");
        smartbiApp = ds(url().replaceAll("/[^/?]+(\\?|$)", "/" + smartbiDb + "$1"), appRole, "x");
        store = new FactoryDataScopePostgresContractTest.FakeStore();
        store.put("cretas-media", FA + "/attachments/a.jpg", "A-PHOTO");
        store.put("cretas-audio", FA + "/voice/a.mp3", "A-VOICE");
        store.put("cretas-media", FB + "/attachments/b.jpg", "B-PHOTO");
        seed();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (main == null) return;
        exec(main, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        exec(main, "DROP SCHEMA IF EXISTS " + copySchema() + " CASCADE");
        exec(main, "DROP SCHEMA IF EXISTS " + otherSchema() + " CASCADE");
        exec(main, "DELETE FROM factory_data_requests WHERE factory_id IN ('" + FA + "','" + FB + "','F006','NOTDRILL_X','ERASETEST_OLD')");
        exec(main, "DELETE FROM factory_erasure_audit_retention WHERE factory_id IN ('" + FA + "','" + FB + "')");
        exec(main, "DROP DATABASE IF EXISTS " + smartbiDb + " WITH (FORCE)");
        exec(main, "DROP ROLE IF EXISTS " + appRole);
    }

    // ------------------------------------------------------------------ cases

    @Test
    void erasureRemovesEveryRowOfTheFactoryAndLeavesOtherFactoriesByteForByteUnchanged() throws Exception {
        Map<String, String> fbMain = snapshot(main, FB);
        Map<String, String> fbSb = snapshot(smartbiAdmin, FB);
        FactoryErasureService erasure = erasure();

        long requestId = requestAndWait(erasure, true);
        long previewId = data().preview(OPERATOR, FA).previewId();
        FactoryErasureService.Receipt receipt = erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, previewId));

        // 本厂: 两个库按同一口径重算, 一行不剩; 工厂主记录没了; 附件没了
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".factories WHERE id = '" + FA + "'")).isZero();
        for (String t : new String[]{"users", "sales_orders", "activity_events", "internal_transfers",
                "platform_data_access_log", "operation_logs", "backup_sales_orders_20260801"}) {
            assertThat(count(main, "SELECT count(*) FROM " + schema + "." + t + " WHERE "
                    + (t.equals("internal_transfers") ? "source_factory_id" : t.equals("platform_data_access_log")
                    ? "target_factory_id" : "factory_id") + " = '" + FA + "'")).as(t).isZero();
        }
        // 不带 factory_id 的子表: 只剩 FB 的那一行明细, FA 的两行明细和一行批次都没了
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".sales_order_items")).isEqualTo(1);
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".sales_item_lots")).isZero();
        // 平台共享表的引用被置空, 行还在
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".notes WHERE author_id IS NULL")).isEqualTo(1);
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + schema + ".smart_bi_uploads WHERE factory_id = '" + FA + "'")).isZero();
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + schema + ".smart_bi_field_defs WHERE upload_id = 1")).isZero();
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + schema + ".smart_bi_llm_egress_audit WHERE factory_id = '" + FA + "'")).isZero();
        assertThat(store.list(FA + "/")).isEmpty();
        assertThat(store.list(FB + "/")).hasSize(1);
        // 严格行级安全的表 (只认 app.factory_id = 本厂): 空变量下看不见, 也必须删到
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + schema + ".agg_supplier_price WHERE factory_id = '" + FA + "'")).isZero();
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + schema + ".agg_supplier_price WHERE factory_id = '" + FB + "'")).isEqualTo(1);
        // 登记过的副本 schema: 本厂的行删掉, 别的工厂的留着
        assertThat(count(main, "SELECT count(*) FROM " + copySchema() + ".sales_orders WHERE factory_id = '" + FA + "'")).isZero();
        assertThat(count(main, "SELECT count(*) FROM " + copySchema() + ".sales_orders WHERE factory_id = '" + FB + "'")).isEqualTo(1);

        // 口径 Q4: 脱敏样本保留且断开工厂编号; 原样存的样本删掉
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + schema + ".smart_bi_distillation_samples WHERE factory_id = '" + FA + "'")).isZero();
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + schema + ".smart_bi_distillation_samples WHERE factory_id IS NULL AND input_text = 'A-DESENSITIZED'")).isEqualTo(1);
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + schema + ".smart_bi_distillation_samples WHERE input_text = 'A-RAW'")).isZero();
        assertThat(receipt.smartbi().trainingSamplesRetained()).isEqualTo(1);

        // 口径 Q3: 审计挪进留存区, 只有四个字段, 6 个月后到期
        try (Connection c = main.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT source_database, source_table, actor, action, object_ref, retain_until "
                     + "FROM factory_erasure_audit_retention WHERE factory_id = '" + FA + "' ORDER BY source_table")) {
            Map<String, String> rows = new TreeMap<>();
            while (rs.next()) {
                rows.put(rs.getString(1) + "." + rs.getString(2), rs.getString(3) + " | " + rs.getString(4) + " | " + rs.getString(5));
                assertThat(rs.getTimestamp(6).toLocalDateTime()).isAfter(LocalDateTime.now().plusMonths(5));
            }
            assertThat(rows).containsOnlyKeys("CRETAS.operation_logs", "CRETAS.platform_data_access_log",
                    "SMARTBI.smart_bi_llm_egress_audit");
            assertThat(rows.get("CRETAS.operation_logs")).isEqualTo("user:1 a_admin | sales/create | sales_order:10");
            assertThat(String.join(" ", rows.values())).doesNotContain("SECRET-OLD-VALUE").doesNotContain("10.0.0.1");
        }

        // 阴性对照: FB 在两个库里逐表逐行不变
        assertThat(snapshot(main, FB)).isEqualTo(fbMain);
        assertThat(snapshot(smartbiAdmin, FB)).isEqualTo(fbSb);
        // 回执还在 (台账不随工厂删除)
        assertThat(count(main, "SELECT count(*) FROM factory_data_requests WHERE factory_id = '" + FA
                + "' AND request_type = 'ERASURE' AND status = 'SUCCEEDED'")).isEqualTo(1);
        assertThat(receipt.attachmentsDeleted()).isEqualTo(2);
        assertThat(receipt.cretas().otherFactoriesChecked()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void coolingOffBlocksErasureAndCancelRestoresTheFactory() throws Exception {
        FactoryErasureService erasure = erasure();
        long exportId = export();
        var view = erasure.request(OPERATOR, FA, body(exportId, false));
        assertThat(view.notBefore()).isAfter(LocalDateTime.now().plusDays(6));
        assertThat(isActive(FA)).isFalse();                           // 申请当天停用
        long previewId = data().preview(OPERATOR, FA).previewId();
        assertThatThrownBy(() -> erasure.erase(OPERATOR, FA, new EraseBody(FA, view.requestId(), previewId)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("冷静期未满"));
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".users WHERE factory_id = '" + FA + "'")).isEqualTo(1);

        erasure.cancel(OPERATOR, FA, view.requestId());
        assertThat(isActive(FA)).isTrue();                            // 撤销恢复
        assertThatThrownBy(() -> erasure.erase(OPERATOR, FA, new EraseBody(FA, view.requestId(), previewId)))
                .isInstanceOf(BusinessException.class);

        // 第 8 天起才允许 (时钟拨到 7 天后)
        var again = erasure.request(OPERATOR, FA, body(exportId, false));
        clockOffset.set(Duration.ofDays(7).plusSeconds(60));
        long preview2 = data().preview(OPERATOR, FA).previewId();
        erasure.erase(OPERATOR, FA, new EraseBody(FA, again.requestId(), preview2));
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".factories WHERE id = '" + FA + "'")).isZero();
    }

    @Test
    void erasureRefusesWhenDataChangedSincePreviewAndDeletesNothing() throws Exception {
        FactoryErasureService erasure = erasure();
        long requestId = requestAndWait(erasure, true);
        long previewId = data().preview(OPERATOR, FA).previewId();
        exec(main, "INSERT INTO " + schema + ".sales_orders (id, factory_id, created_by, note) VALUES (12, '" + FA + "', 1, 'late')");
        Map<String, String> faBefore = snapshot(main, FA);

        assertThatThrownBy(() -> erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, previewId)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("与预演不一致"));
        assertThat(snapshot(main, FA)).isEqualTo(faBefore);
        assertThat(store.list(FA + "/")).hasSize(2);
    }

    @Test
    void crossTenantReferenceStopsErasureAndRollsBackEveryDatabase() throws Exception {
        FactoryErasureService erasure = erasure();
        long requestId = requestAndWait(erasure, true);
        exec(main, "INSERT INTO " + schema + ".sales_orders (id, factory_id, created_by, note) VALUES (40, '" + FB + "', 1, 'B-USES-A')");
        var preview = data().preview(OPERATOR, FA);
        assertThat(preview.blockers()).anyMatch(b -> b.contains("跨租户"));
        Map<String, String> faSb = snapshot(smartbiAdmin, FA);

        assertThatThrownBy(() -> erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, preview.previewId())))
                .isInstanceOf(RuntimeException.class);
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".users WHERE factory_id = '" + FA + "'")).isEqualTo(1);
        assertThat(snapshot(smartbiAdmin, FA)).isEqualTo(faSb);
    }

    @Test
    void protectedTenantsWrongConfirmationAndNonDrillFactoriesAreRefused() throws Exception {
        FactoryErasureService erasure = erasure();
        long exportId = export();
        assertThatThrownBy(() -> erasure.request(OPERATOR, "F006", new ErasureRequestBody("F006", exportId, "x", "x", "x", false)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        assertThatThrownBy(() -> erasure.request(OPERATOR, FA, new ErasureRequestBody("ERASETEST_B", exportId, "x", "x", "x", true)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        // 没有导出就不能申请
        assertThatThrownBy(() -> erasure.request(OPERATOR, FA, new ErasureRequestBody(FA, null, "x", "x", "x", true)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("导出"));
        // 非演练编号 / 超过 3 天的演练工厂不能免冷静期。各自先有一次本厂导出, 这样拒绝只能来自演练这道闸
        // (之前复用 FA 的导出编号, 被「导出不是本厂的」先拦住, 删掉演练闸也照样绿)
        exec(main, "INSERT INTO " + schema + ".factories (id, name, type, created_at) VALUES "
                + "('NOTDRILL_X', 'X', 'FACTORY', now()), ('ERASETEST_OLD', 'O', 'FACTORY', now() - interval '10 days')");
        for (String f : List.of("NOTDRILL_X", "ERASETEST_OLD")) {
            long own = export(f);
            assertThatThrownBy(() -> erasure.request(OPERATOR, f, new ErasureRequestBody(f, own, "x", "x", "x", true)))
                    .as(f)
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(400);
                        assertThat(e.getMessage()).contains("免冷静期");
                    });
            assertThat(isActive(f)).as(f).isTrue();
            // 归因对照: 同一个申请不要求免冷静期就能登记 (工厂随即停用)
            erasure.request(OPERATOR, f, new ErasureRequestBody(f, own, "x", "x", "x", false));
            assertThat(isActive(f)).as(f).isFalse();
        }
        assertThat(isActive(FA)).isTrue();
    }

    @Test
    void retentionJobPurgesOnlyExpiredRows() throws Exception {
        FactoryErasureService erasure = erasure();
        long requestId = requestAndWait(erasure, true);
        erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, data().preview(OPERATOR, FA).previewId()));
        long kept = count(main, "SELECT count(*) FROM factory_erasure_audit_retention WHERE factory_id = '" + FA + "'");
        assertThat(kept).isEqualTo(3);
        // 删除操作之后才写进来的平台访问记录 (预演 / 删除接口本身): 已删工厂的才清, 别的工厂的不动
        exec(main, "INSERT INTO " + schema + ".platform_data_access_log (id, target_factory_id, created_at) VALUES "
                + "(90, '" + FA + "', now()), (91, '" + FB + "', now() - interval '300 days')");
        FactoryErasureRetentionJob job = new FactoryErasureRetentionJob(main,
                Clock.fixed(Instant.now().plus(Duration.ofDays(150)), ZoneId.systemDefault()), schema);
        assertThat(job.purgeExpired()).isEqualTo(new FactoryErasureRetentionJob.Result(0, 0));
        job = new FactoryErasureRetentionJob(main, Clock.fixed(Instant.now().plus(Duration.ofDays(200)), ZoneId.systemDefault()),
                schema);
        assertThat(job.purgeExpired()).isEqualTo(new FactoryErasureRetentionJob.Result(3, 1));
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".platform_data_access_log WHERE id = 91")).isEqualTo(1);
    }

    @Test
    void exportUnderTheAppAccountIncludesRowsOfStrictRlsTables() throws Exception {
        FactoryDataService d = data();
        long id = d.startExport(OPERATOR, FA);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        d.writeExport(id, OPERATOR, FA, AttachmentMode.INDEX_ONLY, Set.of(FactoryDataService.Format.JSON), out);
        String all = new String(out.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(all).contains("smartbi/json/agg_supplier_price.json");
        var preview = d.preview(OPERATOR, FA);
        assertThat(preview.databases().get(1).tables())
                .anyMatch(t -> t.table().equals("agg_supplier_price") && t.rows() == 2);
    }

    @Test
    void linkRowThatAlsoReferencesAnotherFactoryBlocksErasure() throws Exception {
        exec(main, "INSERT INTO " + schema + ".order_links (id, order_id, other_order_id) VALUES (1, 10, 30);"
                + "INSERT INTO " + schema + ".factory_links (id, from_factory, to_factory) VALUES (1, '" + FA + "', '" + FB + "')");
        FactoryErasureService erasure = erasure();
        long requestId = requestAndWait(erasure, true);
        var preview = data().preview(OPERATOR, FA);
        assertThat(preview.databases().get(0).foreignHits())
                .anyMatch(h -> h.table().equals("order_links") && h.kind() == FactoryDataScope.ForeignKind.OTHER_TENANT)
                // 直接挂在 factories 上的关联行: 父行主键就是工厂编号
                .anyMatch(h -> h.table().equals("factory_links") && h.kind() == FactoryDataScope.ForeignKind.OTHER_TENANT);
        Map<String, String> fbBefore = snapshot(main, FB);
        assertThatThrownBy(() -> erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, preview.previewId())))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("跨租户"));
        assertThat(snapshot(main, FB)).isEqualTo(fbBefore);
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".order_links")).isEqualTo(1);
    }

    @Test
    void unregisteredSchemaHoldingTheFactorysRowsBlocksErasure() throws Exception {
        exec(main, "CREATE SCHEMA " + otherSchema() + "; CREATE TABLE " + otherSchema()
                + ".notes (id int, factory_id varchar(64)); INSERT INTO " + otherSchema() + ".notes VALUES (1, '" + FA + "')");
        FactoryErasureService erasure = erasure();
        long requestId = requestAndWait(erasure, true);
        var preview = data().preview(OPERATOR, FA);
        assertThat(preview.blockers()).anyMatch(b -> b.contains("不像副本的 schema"));
        assertThatThrownBy(() -> erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, preview.previewId())))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("不像副本的 schema"));
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".users WHERE factory_id = '" + FA + "'")).isEqualTo(1);
    }

    /**
     * prod 2026-10-27: postgres 建的删前备份 schema (backup_f006_20261024) 没给应用账号 USAGE, 预演去数它, 当场
     * 409「permission denied for schema」, 任何工厂都预演不了。SmartBI 这边测试用的就是非超级用户的应用账号。
     */
    @Test
    void datedCopyTheAppAccountCannotReadIsListedAndExpiresWithRetention() throws Exception {
        String unreadable = "backup_" + schema + "_20261024";
        exec(smartbiAdmin, "CREATE SCHEMA " + unreadable + "; CREATE TABLE " + unreadable
                + ".agg_copy (id int, factory_id varchar(64)); INSERT INTO " + unreadable + ".agg_copy VALUES (1, '" + FA + "')");
        FactoryErasureService erasure = erasure();
        long requestId = requestAndWait(erasure, true);
        var preview = data().preview(OPERATOR, FA);
        assertThat(preview.databases().get(1).unreadableCopies()).containsExactly(unreadable + ".agg_copy");
        assertThat(preview.blockers()).isEmpty();
        FactoryErasureService.Receipt receipt = erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, preview.previewId()));
        assertThat(receipt.smartbi().copies()).anyMatch(c -> c.startsWith(unreadable + ".agg_copy") && c.contains("账号读不了")
                && c.contains("20261024"));
        // 账号碰不到这份副本: 行还在, 由 30 天清理 (backup-copy-retention.sh, postgres 身份) 带走
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + unreadable + ".agg_copy")).isEqualTo(1);
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".factories WHERE id = '" + FA + "'")).isZero();
    }

    @Test
    void undatedCopyOrOtherSchemaTheAppAccountCannotReadBlocksErasure() throws Exception {
        FactoryErasureService erasure = erasure();
        long requestId = requestAndWait(erasure, true);
        String[][] cases = {
                {"backup_" + schema, "名字里又没有日期"},          // 像副本、没日期: 30 天清理认不出
                {"oth_" + schema, "查不清有没有该厂的行"},          // 不像副本: 读不了就不知道里面是什么
        };
        for (String[] c : cases) {
            exec(smartbiAdmin, "CREATE SCHEMA " + c[0] + "; CREATE TABLE " + c[0] + ".t (id int, factory_id varchar(64))");
            var preview = data().preview(OPERATOR, FA);
            assertThat(preview.blockers()).as(c[0]).anyMatch(b -> b.contains(c[1]) && b.contains(c[0] + ".t"));
            assertThatThrownBy(() -> erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, preview.previewId())))
                    .as(c[0])
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains(c[1]));
            assertThat(count(main, "SELECT count(*) FROM " + schema + ".users WHERE factory_id = '" + FA + "'")).isEqualTo(1);
            exec(smartbiAdmin, "DROP SCHEMA " + c[0] + " CASCADE");
        }
        // 归因对照: 两个 schema 都移走后, 同一个申请就能删
        long previewId = data().preview(OPERATOR, FA).previewId();
        erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, previewId));
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".users WHERE factory_id = '" + FA + "'")).isZero();
    }

    @Test
    void concurrentOperationOnTheSameFactoryIsRefused() throws Exception {
        FactoryErasureService erasure = erasure();
        long requestId = requestAndWait(erasure, true);
        long previewId = data().preview(OPERATOR, FA).previewId();
        try (Connection c = main.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(true);
            st.execute("SELECT pg_advisory_lock(hashtextextended('factory-data:" + FA + "', 0))");
            assertThatThrownBy(() -> erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, previewId)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("正有另一个"));
            assertThatThrownBy(() -> erasure.cancel(OPERATOR, FA, requestId)).isInstanceOf(BusinessException.class);
            st.execute("SELECT pg_advisory_unlock(hashtextextended('factory-data:" + FA + "', 0))");
        }
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".users WHERE factory_id = '" + FA + "'")).isEqualTo(1);
        erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, previewId));      // 锁放开后照常
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".users WHERE factory_id = '" + FA + "'")).isZero();
    }

    @Test
    void factoryReEnabledDuringCoolingOffCannotBeErased() throws Exception {
        FactoryErasureService erasure = erasure();
        long requestId = requestAndWait(erasure, true);
        exec(main, "UPDATE " + schema + ".factories SET is_active = true WHERE id = '" + FA + "'");
        long previewId = data().preview(OPERATOR, FA).previewId();
        assertThatThrownBy(() -> erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, previewId)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("重新启用"));
        assertThat(count(main, "SELECT count(*) FROM " + schema + ".users WHERE factory_id = '" + FA + "'")).isEqualTo(1);
    }

    @Test
    void exportRequestMustBeRunningForThisFactoryAndASkippedExportDoesNotCount() throws Exception {
        FactoryDataService d = data();
        long id = d.startExport(OPERATOR, FA);
        assertThatThrownBy(() -> d.writeExport(id, OPERATOR, FB, AttachmentMode.INDEX_ONLY,
                Set.of(FactoryDataService.Format.CSV), new ByteArrayOutputStream()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        assertThatThrownBy(() -> d.writeExport(id + 999, OPERATOR, FA, AttachmentMode.INDEX_ONLY,
                Set.of(FactoryDataService.Format.CSV), new ByteArrayOutputStream()))
                .isInstanceOf(BusinessException.class);
        d.writeExport(id, OPERATOR, FA, AttachmentMode.SKIP, Set.of(FactoryDataService.Format.CSV), new ByteArrayOutputStream());
        assertThatThrownBy(() -> erasure().request(OPERATOR, FA, body(id, true)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("SKIP"));
        assertThatThrownBy(() -> d.startExport(OPERATOR, FA, "backup scheduler")).isInstanceOf(BusinessException.class);
        long scheduled = d.startExport(OPERATOR, FA, "BACKUP_SCHEDULER");
        assertThat(d.ledger().find(scheduled).orElseThrow().triggerSource()).isEqualTo("BACKUP_SCHEDULER");
        d.writeExport(scheduled, OPERATOR, FA, AttachmentMode.INDEX_ONLY, Set.of(FactoryDataService.Format.CSV),
                new ByteArrayOutputStream());
        // 定时备份不等于「已交付给工厂」: 不能拿来申请删除
        assertThatThrownBy(() -> erasure().request(OPERATOR, FA, body(scheduled, true)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getMessage()).contains("exportRequestId"));
        assertThat(d.ledger().find(id).orElseThrow().triggerSource()).isEqualTo("MANUAL");
    }

    @Test
    void llmEgressEraseFunctionRefusesWhenTheTenantVariableIsNotThatFactory() throws Exception {
        try (Connection c = smartbiApp.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("SELECT set_config('app.factory_id', '" + FB + "', true)");
            assertThatThrownBy(() -> st.execute("SELECT " + schema + ".factory_erase_llm_egress_audit('" + FA + "')"))
                    .hasMessageContaining("app.factory_id");
            c.rollback();
            // 行级安全变量对了、却没有整厂删除的标记 ⇒ 仍然拒绝 (普通请求代码顺手调用不行)
            st.execute("SELECT set_config('app.factory_id', '" + FA + "', true)");
            assertThatThrownBy(() -> st.execute("SELECT " + schema + ".factory_erase_llm_egress_audit('" + FA + "')"))
                    .hasMessageContaining("app.factory_erasure");
            c.rollback();
            // 有删除标记、行级安全变量却是别的工厂 ⇒ 也拒绝 (两个条件各自生效)
            st.execute("SELECT set_config('app.factory_erasure', '" + FA + "', true), set_config('app.factory_id', '" + FB + "', true)");
            assertThatThrownBy(() -> st.execute("SELECT " + schema + ".factory_erase_llm_egress_audit('" + FA + "')"))
                    .hasMessageContaining("app.factory_id");
            c.rollback();
            // 阳性对照: 两个都对 ⇒ 删得掉
            st.execute("SELECT set_config('app.factory_erasure', '" + FA + "', true), set_config('app.factory_id', '" + FA + "', true)");
            try (ResultSet rs = st.executeQuery("SELECT " + schema + ".factory_erase_llm_egress_audit('" + FA + "')")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(1);
            }
            c.rollback();
        }
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + schema + ".smart_bi_llm_egress_audit WHERE factory_id = '" + FA + "'")).isEqualTo(1);
    }

    @Test
    void cascadeIntoAnotherTenantsRowOfAStrictRlsTableIsCaughtAndRolledBack() throws Exception {
        // 严格行级安全的父子表: A 厂的父行被一个只在 SmartBI 出现的租户的子行引用 (级联外键)。
        // 两次 resolve 都看不见那行子行; 只有逐个租户的阴性对照 (租户清单来自定义者函数) 能发现级联删了它。
        exec(smartbiAdmin, "SET search_path = " + schema + ";"
                + "CREATE TABLE sb_parent (id bigint PRIMARY KEY, factory_id varchar(64));"
                + "CREATE TABLE sb_child (id bigint PRIMARY KEY, factory_id varchar(64),"
                + "  parent_id bigint REFERENCES sb_parent(id) ON DELETE CASCADE);"
                + "ALTER TABLE sb_parent ENABLE ROW LEVEL SECURITY; ALTER TABLE sb_parent FORCE ROW LEVEL SECURITY;"
                + "ALTER TABLE sb_child ENABLE ROW LEVEL SECURITY; ALTER TABLE sb_child FORCE ROW LEVEL SECURITY;"
                + "CREATE POLICY p ON sb_parent FOR ALL USING (factory_id = current_setting('app.factory_id', true));"
                + "CREATE POLICY p ON sb_child FOR ALL USING (factory_id = current_setting('app.factory_id', true));"
                + "GRANT SELECT, INSERT, UPDATE, DELETE ON sb_parent, sb_child TO " + appRole + ";"
                + "INSERT INTO sb_parent VALUES (1, '" + FA + "');"
                + "INSERT INTO sb_child VALUES (1, 'SBONLY_TENANT', 1);");
        FactoryErasureService erasure = erasure();
        long requestId = requestAndWait(erasure, true);
        long previewId = data().preview(OPERATOR, FA).previewId();
        assertThatThrownBy(() -> erasure.erase(OPERATOR, FA, new EraseBody(FA, requestId, previewId)))
                .hasMessageContaining("阴性对照失败");
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + schema + ".sb_child")).isEqualTo(1);
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + schema + ".sb_parent")).isEqualTo(1);
        assertThat(count(smartbiAdmin, "SELECT count(*) FROM " + schema + ".smart_bi_uploads WHERE factory_id = '" + FA + "'")).isEqualTo(1);
        assertThat(store.list(FA + "/")).hasSize(2);
    }

    // ---------------------------------------------------------------- fixture

    private String copySchema() { return "bak_" + schema + "_20260101"; }

    private String otherSchema() { return "analytics_" + schema; }

    private long requestAndWait(FactoryErasureService erasure, boolean drill) throws Exception {
        return erasure.request(OPERATOR, FA, body(export(), drill)).requestId();
    }

    private ErasureRequestBody body(long exportId, boolean drill) {
        return new ErasureRequestBody(FA, exportId, "A厂总监", "wechat-20261026.png", "平台值班", drill);
    }

    private long export() throws Exception {
        return export(FA);
    }

    private long export(String factoryId) throws Exception {
        FactoryDataService d = data();
        long id = d.startExport(OPERATOR, factoryId);
        d.writeExport(id, OPERATOR, factoryId, AttachmentMode.INDEX_ONLY, Set.of(FactoryDataService.Format.CSV),
                new ByteArrayOutputStream());
        return id;
    }

    private FactoryDataService data() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("smartbiPostgresDataSource", smartbiApp);
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.systemDefault(); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.now().plus(clockOffset.get()); }
        };
        return new FactoryDataService(main, beans.getBeanProvider(DataSource.class), store,
                new FactoryDataRequestLedger(main, clock), Set.of(1L), clock, schema);
    }

    private FactoryErasureService erasure() {
        FactoryDataService d = data();
        return new FactoryErasureService(d, d.ledger());
    }

    private void seed() throws Exception {
        // 共用 FactoryDataScopePostgresContractTest 的两厂数据, 再加审计 / 训练 / LLM 外发审计
        FactoryDataScopePostgresContractTest base = new FactoryDataScopePostgresContractTest();
        base.schema = schema;
        base.fa = FA;
        base.fb = FB;
        base.main = main;
        base.smartbi = smartbiAdmin;
        base.seedCleanTenants();
        exec(main, "SET search_path = " + schema + ";"
                + "INSERT INTO operation_logs (id, factory_id, user_id, username, module, action, entity_type, entity_id, old_value, ip_address, success, created_at)"
                + " VALUES (1, '" + FA + "', 1, 'a_admin', 'sales', 'create', 'sales_order', '10', '{\"v\":\"SECRET-OLD-VALUE\"}', '10.0.0.1', true, now()),"
                + "        (2, '" + FB + "', 2, 'b_admin', 'sales', 'create', 'sales_order', '30', null, null, true, now());");
        exec(smartbiAdmin, "SET search_path = " + schema + ";"
                + "INSERT INTO smart_bi_distillation_samples (id, factory_id, input_text, metadata) VALUES"
                + " (1, '" + FA + "', 'A-DESENSITIZED', '{\"training_rule\":\"2026-10-24\",\"desensitized\":true}'),"
                + " (2, '" + FA + "', 'A-RAW', '{\"training_rule\":\"2026-10-24\"}'),"
                + " (3, '" + FB + "', 'B-RAW', '{}');"
                + "INSERT INTO smart_bi_llm_egress_audit (id, created_at, call_site, provider, model, factory_id, status_code) VALUES"
                + " (1, now(), 'chart_insight', 'dashscope', 'qwen', '" + FA + "', 200),"
                + " (2, now(), 'chart_insight', 'dashscope', 'qwen', '" + FB + "', 200);"
                + "INSERT INTO agg_supplier_price (id, factory_id, price) VALUES (1, '" + FA + "', 1.0), (2, '" + FA + "', 2.0), (3, '" + FB + "', 3.0);");
        exec(main, "CREATE SCHEMA " + copySchema() + "; CREATE TABLE " + copySchema()
                + ".sales_orders AS SELECT id, factory_id, note FROM " + schema + ".sales_orders WHERE factory_id IN ('" + FA + "', '" + FB + "')");
    }

    static String auditTablesMain(String s) {
        return "CREATE TABLE " + s + ".operation_logs (id bigint PRIMARY KEY, factory_id varchar(64), user_id bigint,"
                + " username varchar(64), module varchar(64), action varchar(64), entity_type varchar(64), entity_id varchar(64),"
                + " old_value jsonb, ip_address varchar(64), success boolean, created_at timestamp);"
                + "ALTER TABLE " + s + ".platform_data_access_log ADD COLUMN created_at timestamp DEFAULT now(),"
                + " ADD COLUMN actor_type varchar(32) DEFAULT 'PLATFORM_ADMIN', ADD COLUMN actor_user_id bigint DEFAULT 1,"
                + " ADD COLUMN actor_username varchar(64), ADD COLUMN http_method varchar(8) DEFAULT 'GET',"
                + " ADD COLUMN request_path varchar(255);"
                // 不带 factory_id 的关联表: 一行同时挂两个工厂的订单时, 不能算成 A 厂的
                + "CREATE TABLE " + s + ".order_links (id bigint PRIMARY KEY,"
                + " order_id bigint REFERENCES " + s + ".sales_orders(id) ON DELETE CASCADE,"
                + " other_order_id bigint REFERENCES " + s + ".sales_orders(id));"
                + "CREATE TABLE " + s + ".factory_links (id bigint PRIMARY KEY,"
                + " from_factory varchar(64) REFERENCES " + s + ".factories(id) ON DELETE CASCADE,"
                + " to_factory varchar(64) REFERENCES " + s + ".factories(id));";
    }

    static String smartbiExtras(String s) {
        return "SET search_path = " + s + ";"
                + "CREATE TABLE smart_bi_distillation_samples (id bigint PRIMARY KEY, factory_id varchar(64), input_text text, metadata jsonb);"
                + "CREATE TABLE smart_bi_llm_egress_audit (id bigint PRIMARY KEY, created_at timestamp NOT NULL, call_site text NOT NULL,"
                + " provider text NOT NULL, model text NOT NULL, factory_id text, status_code integer);"
                // prod 同款行级安全: 读在 GUC 为空或等于本厂时可见, 删只在 GUC 等于本厂时允许
                + "ALTER TABLE smart_bi_uploads ENABLE ROW LEVEL SECURITY; ALTER TABLE smart_bi_uploads FORCE ROW LEVEL SECURITY;"
                + "CREATE POLICY sel ON smart_bi_uploads FOR SELECT USING (factory_id = current_setting('app.factory_id', true)"
                + " OR current_setting('app.factory_id', true) = '' OR current_setting('app.factory_id', true) IS NULL);"
                + "CREATE POLICY del ON smart_bi_uploads FOR DELETE USING (factory_id = current_setting('app.factory_id', true));"
                + "CREATE POLICY ins ON smart_bi_uploads FOR INSERT WITH CHECK (true);"
                + "CREATE POLICY upd ON smart_bi_uploads FOR UPDATE USING (true);"
                // prod 有 95 张表是严格策略 (例 agg_supplier_price): 只认 factory_id = app.factory_id, 空变量下一行都看不见
                + "CREATE TABLE agg_supplier_price (id bigint PRIMARY KEY, factory_id varchar(64), price numeric);"
                + "ALTER TABLE agg_supplier_price ENABLE ROW LEVEL SECURITY; ALTER TABLE agg_supplier_price FORCE ROW LEVEL SECURITY;"
                + "CREATE POLICY strict_all ON agg_supplier_price FOR ALL USING (factory_id = current_setting('app.factory_id', true))"
                + " WITH CHECK (factory_id = current_setting('app.factory_id', true));";
    }

    /**
     * 某工厂在一个库里每张表的行 (按行排序) 的数量与内容哈希。带 factory_id 的表按 factory_id 取;
     * 不带的表逐张写明怎么认出这个工厂的行 —— 新加的夹具表没登记就直接失败, ⛔ 不退回整表哈希
     * (整表会把被删工厂那一侧的正常变化误报成阴性对照失败)。
     */
    private Map<String, String> snapshot(DataSource ds, String factory) throws Exception {
        String f = "'" + factory + "'";
        String s = schema;
        Map<String, String> byTable = Map.of(
                "factories", "x.id = " + f,
                "internal_transfers", "x.source_factory_id = " + f + " OR x.target_factory_id = " + f,
                "platform_data_access_log", "x.target_factory_id = " + f,
                "sales_order_items", "x.order_id IN (SELECT id FROM " + s + ".sales_orders WHERE factory_id = " + f + ")",
                "sales_item_lots", "x.item_id IN (SELECT i.id FROM " + s + ".sales_order_items i JOIN " + s
                        + ".sales_orders o ON o.id = i.order_id WHERE o.factory_id = " + f + ")",
                "notes", "x.author_id IN (SELECT id FROM " + s + ".users WHERE factory_id = " + f + ")",
                "smart_bi_field_defs", "x.upload_id IN (SELECT id FROM " + s + ".smart_bi_uploads WHERE factory_id = " + f + ")",
                "factory_links", "x.from_factory = " + f + " OR x.to_factory = " + f,
                "order_links", "x.order_id IN (SELECT id FROM " + s + ".sales_orders WHERE factory_id = " + f + ")"
                        + " OR x.other_order_id IN (SELECT id FROM " + s + ".sales_orders WHERE factory_id = " + f + ")");
        Map<String, String> out = new TreeMap<>();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            Map<String, Boolean> tables = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT c.relname, EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid"
                    + " AND a.attname = 'factory_id' AND NOT a.attisdropped) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                    + " WHERE n.nspname = '" + schema + "' AND c.relkind = 'r' ORDER BY 1")) {
                while (rs.next()) tables.put(rs.getString(1), rs.getBoolean(2));
            }
            for (Map.Entry<String, Boolean> t : tables.entrySet()) {
                String cond = t.getValue() ? "x.factory_id::text = " + f : byTable.get(t.getKey());
                if (cond == null) throw new IllegalStateException("snapshot 没登记怎么认出这张表的工厂行: " + t.getKey());
                String where = " WHERE " + cond;
                try (ResultSet rs = st.executeQuery("SELECT count(*), md5(coalesce(string_agg(x::text, '|' ORDER BY x::text), ''))"
                        + " FROM " + schema + "." + t.getKey() + " x" + where)) {
                    rs.next();
                    out.put(t.getKey(), rs.getLong(1) + ":" + rs.getString(2));
                }
            }
        }
        return out;
    }

    private boolean isActive(String factory) throws Exception {
        try (Connection c = main.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT is_active FROM " + schema + ".factories WHERE id = '" + factory + "'")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static long count(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String url() { return System.getenv("CRETAS_WORKFLOW_PG_URL"); }
    private static String user() { return System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"); }
    private static String password() { return System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""); }

    private static PGSimpleDataSource ds(String jdbcUrl, String user, String password) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static void exec(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(true);
            st.execute(sql);
        }
    }
}
