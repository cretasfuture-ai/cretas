package com.cretas.aims.service.factorydata;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.factorydata.FactoryDataCatalog.Category;
import com.cretas.aims.service.factorydata.FactoryDataCatalog.Database;
import com.cretas.aims.service.factorydata.FactoryDataScope.ForeignHit;
import com.cretas.aims.service.factorydata.FactoryDataScope.ForeignKind;
import com.cretas.aims.service.factorydata.FactoryDataService.AttachmentMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 PostgreSQL: 「本厂拥有哪些行」(种子 + 外键闭包 + 跨租户命中) 以及导出包。
 *
 * <p>每个用例在两个独立的库 (主库 / SmartBI 库) 里各建一个随机名 schema, 造两个工厂 FA / FB:
 * FA 是被导出 / 预演的那一个, FB 的每一行都是阴性对照 —— 它不许出现在 FA 的范围或导出包里。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class FactoryDataScopePostgresContractTest {

    static final String FA = "FA_TEST";
    static final String FB = "FB_TEST";
    static final AuthenticatedActor OPERATOR =
            new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "super_admin");

    String schema;
    /** 被导出 / 删除的工厂与阴性对照工厂; 删除测试复用这套数据时换成演练编号。 */
    String fa = FA;
    String fb = FB;
    String smartbiDb;
    PGSimpleDataSource main;
    PGSimpleDataSource smartbi;
    FakeStore store;

    @BeforeEach
    void setUp() throws Exception {
        schema = "fe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        main = dataSource(url());
        exec(main, "CREATE SCHEMA " + schema);
        exec(main, Files.readString(Path.of(
                "src/main/resources/db/flyway/V20261103_87__factory_data_requests.sql")));
        exec(main, mainSchema(schema));
        smartbiDb = schema + "_sb";
        exec(main, "CREATE DATABASE " + smartbiDb);
        smartbi = dataSource(url().replaceAll("/[^/?]+(\\?|$)", "/" + smartbiDb + "$1"));
        exec(smartbi, "CREATE SCHEMA " + schema);
        exec(smartbi, smartbiSchema(schema));
        store = new FakeStore();
        store.put("cretas-media", FA + "/attachments/a.jpg", "A-PHOTO");
        store.put("cretas-media", FB + "/attachments/b.jpg", "B-PHOTO-SECRET");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (main == null) return;
        exec(main, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        exec(main, "DELETE FROM factory_data_requests WHERE factory_id IN ('" + FA + "','" + FB + "')");
        exec(main, "DROP DATABASE IF EXISTS " + smartbiDb + " WITH (FORCE)");
    }

    // ---------------------------------------------------------------- scope

    @Test
    void scopeFollowsForeignKeysIntoChildTablesWithoutFactoryIdAndStopsAtOtherFactories() throws Exception {
        seedCleanTenants();
        FactoryDataScope scope = resolve(main, Database.CRETAS, FA);

        assertThat(rows(scope)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "factories", 1L,
                "users", 1L,
                "sales_orders", 2L,
                "sales_order_items", 2L,       // 不带 factory_id, 第 1 层
                "sales_item_lots", 1L,         // 不带 factory_id, 第 2 层 (CASCADE 外键也算)
                "internal_transfers", 1L,      // 别名租户列
                "activity_events", 2L,         // 无主键: 按租户列条件
                "platform_data_access_log", 1L,
                "backup_sales_orders_20260801", 1L));
        assertThat(scope.tables().get("sales_order_items").depth()).isEqualTo(1);
        assertThat(scope.tables().get("sales_item_lots").depth()).isEqualTo(2);
        assertThat(scope.tables().get("platform_data_access_log").category()).isEqualTo(Category.AUDIT);
        assertThat(scope.tables().get("backup_sales_orders_20260801").category()).isEqualTo(Category.COPY);
        assertThat(scope.foreignHits()).isEmpty();
        // 平台共享表 notes 引用了 FA 的用户 (SET NULL): 不纳入, 只统计会被置空
        assertThat(scope.nulledReferences()).singleElement()
                .satisfies(n -> {
                    assertThat(n.table()).isEqualTo("notes");
                    assertThat(n.rows()).isEqualTo(1);
                });

        // 阴性对照: FB 的范围里没有任何 FA 的行, 反之亦然
        FactoryDataScope b = resolve(main, Database.CRETAS, FB);
        assertThat(rows(b)).containsEntry("sales_orders", 1L).containsEntry("sales_order_items", 1L)
                .doesNotContainKey("sales_item_lots");
        assertThat(intersection(scope, b, "sales_order_items")).isEmpty();
        assertThat(intersection(scope, b, "users")).isEmpty();
    }

    @Test
    void rowsOfOtherFactoriesThatReferenceThisFactoryAreReportedNotClaimed() throws Exception {
        seedCleanTenants();
        exec(main, "SET search_path = " + schema + ";"
                // FB 的订单引用了 FA 的用户 -> OTHER_TENANT
                + "INSERT INTO sales_orders (id, factory_id, created_by, note) VALUES (40, '" + FB + "', 1, 'B-USES-A');"
                // FA -> FB 的调拨单 -> SHARED_ROW
                + "INSERT INTO internal_transfers (id, source_factory_id, target_factory_id) VALUES (60, '" + FA + "', '" + FB + "');"
                // 租户列为空的行引用 FA 的用户 -> UNSCOPED
                + "INSERT INTO tool_calls (id, factory_id, user_id) VALUES (70, NULL, 1);"
                // FB 的评审行经 SET NULL 外键引用 FA 的用户 -> OTHER_TENANT (外键置空)
                + "INSERT INTO reviews (id, factory_id, reviewer_id) VALUES (80, '" + FB + "', 1);");

        FactoryDataScope scope = resolve(main, Database.CRETAS, FA);

        assertThat(scope.foreignHits()).extracting(ForeignHit::table, ForeignHit::rowKey, ForeignHit::kind)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("sales_orders", "40", ForeignKind.OTHER_TENANT),
                        org.assertj.core.groups.Tuple.tuple("internal_transfers", "60", ForeignKind.SHARED_ROW),
                        org.assertj.core.groups.Tuple.tuple("tool_calls", "70", ForeignKind.UNSCOPED),
                        org.assertj.core.groups.Tuple.tuple("reviews", "80", ForeignKind.OTHER_TENANT));
        // 命中的行 ⛔ 不纳入 FA 的范围
        assertThat(scope.tables().get("sales_orders").keys()).doesNotContain("40");
        assertThat(scope.tables().get("internal_transfers").keys()).doesNotContain("60");
        assertThat(rows(scope)).containsEntry("sales_orders", 2L).containsEntry("internal_transfers", 1L);
    }

    @Test
    void rowsPerFactoryCountsEveryTableWithFactoryId() throws Exception {
        seedCleanTenants();
        try (Connection c = main.getConnection()) {
            Map<String, Long> totals = FactoryDataScopeResolver.rowsPerFactory(c, schema);
            // 每个租户列各算一次: users 1 + sales_orders 2 + activity_events 2 + backup 1
            // + internal_transfers (source / target 都是 FA) 2 + platform_data_access_log.target 1 = 9
            assertThat(totals).containsEntry(FA, 9L).containsEntry(FB, 3L);
        }
    }

    // --------------------------------------------------------------- export

    @Test
    void exportContainsExactlyThisFactorysRowsWithCountsMatchingThePreview() throws Exception {
        seedCleanTenants();
        FactoryDataService service = service(Set.of(1L));

        FactoryDataService.Preview preview = service.preview(OPERATOR, FA);
        Map<String, byte[]> zip = export(service, FA, AttachmentMode.INCLUDE,
                Set.of(FactoryDataService.Format.CSV, FactoryDataService.Format.JSON, FactoryDataService.Format.XLSX));

        JsonNode manifest = new ObjectMapper().readTree(zip.get("manifest.json"));
        Map<String, Long> exported = new LinkedHashMap<>();
        for (JsonNode db : manifest.get("databases")) {
            for (JsonNode t : db.get("tables")) {
                exported.put(db.get("database").asText() + "." + t.get("table").asText(), t.get("rows").asLong());
            }
        }
        Map<String, Long> previewed = new LinkedHashMap<>();
        for (FactoryDataService.DatabasePreview db : preview.databases()) {
            for (FactoryDataService.TableRow t : db.tables()) {
                if (t.exported()) previewed.put(db.database() + "." + t.table(), t.rows());
            }
        }
        assertThat(exported).isEqualTo(previewed);
        assertThat(exported).containsEntry("CRETAS.sales_item_lots", 1L).containsEntry("SMARTBI.smart_bi_field_defs", 2L);
        // 库内副本不导出
        assertThat(exported).doesNotContainKey("CRETAS.backup_sales_orders_20260801");

        // 阴性对照: FB 的业务数据、附件一个字都不在包里 (Excel 解出单元格文本再查)
        StringBuilder all = new StringBuilder();
        for (Map.Entry<String, byte[]> e : zip.entrySet()) {
            all.append(e.getKey().endsWith(".xlsx") ? xlsxText(e.getValue()) : text(e.getValue())).append('\n');
        }
        assertThat(all.toString()).doesNotContain("B-SECRET").doesNotContain("B-PHOTO-SECRET").doesNotContain("B-UPLOAD")
                .doesNotContain("hash-of-a");
        assertThat(all.toString()).contains("A-ORDER-1").contains("A-UPLOAD");
        assertThat(text(zip.get("attachments/cretas-media/" + FA + "/attachments/a.jpg"))).isEqualTo("A-PHOTO");

        // 凭证类列的值已移除, 列头还在 (三种格式都一样)
        String users = text(zip.get("cretas/csv/users.csv"));
        assertThat(users).startsWith("﻿").contains("password_hash").contains(FactoryDataCatalog.REDACTED);
        JsonNode usersJson = new ObjectMapper().readTree(zip.get("cretas/json/users.json"));
        assertThat(usersJson).hasSize(1);
        assertThat(usersJson.get(0).get("password_hash").asText()).isEqualTo(FactoryDataCatalog.REDACTED);
        assertThat(new ObjectMapper().readTree(zip.get("cretas/json/sales_order_items.json"))).hasSize(2);
        // CSV 注入: 以 = 开头的文字值前加单引号; JSON 原样
        assertThat(text(zip.get("cretas/csv/sales_orders.csv"))).contains("\"'=SUM(A1)\"");
        assertThat(text(zip.get("cretas/json/sales_orders.json"))).contains("\"=SUM(A1)\"");
        // Excel: 目录 + 每张导出表一个 sheet, 行数对得上
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(zip.get("cretas/cretas.xlsx")))) {
            assertThat(wb.getSheetName(0)).isEqualTo(FactoryDataXlsxBook.INDEX_SHEET);
            assertThat(wb.getSheet("sales_order_items").getLastRowNum()).isEqualTo(2);   // 表头 + 2 行
            assertThat(wb.getSheet("users").getRow(1).getCell(3).getStringCellValue()).isEqualTo(FactoryDataCatalog.REDACTED);
        }

        // 每个文件的 SHA256 与 manifest 一致
        for (JsonNode f : manifest.get("files")) {
            byte[] body = zip.get(f.get("path").asText());
            assertThat(sha(body)).as(f.get("path").asText()).isEqualTo(f.get("sha256").asText());
        }
        // 回执: 预演 + 导出各一行, 导出有包哈希
        try (Connection c = main.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT request_type, status, package_sha256 IS NOT NULL FROM "
                     + "factory_data_requests WHERE factory_id = '" + FA + "' ORDER BY id")) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) rows.add(rs.getString(1) + "/" + rs.getString(2) + "/" + rs.getBoolean(3));
            assertThat(rows).containsExactly("PREVIEW/SUCCEEDED/false", "EXPORT/SUCCEEDED/true");
        }
    }

    @Test
    void attachmentKeysCannotEscapeTheArchiveDirectory() throws Exception {
        seedCleanTenants();
        store.put("cretas-media", FA + "/../../etc/evil.txt", "X");
        Map<String, byte[]> zip = export(service(Set.of(1L)), FA, AttachmentMode.INCLUDE, Set.of(FactoryDataService.Format.CSV));
        assertThat(zip.keySet()).noneMatch(k -> k.contains("..") || k.startsWith("/"));
        assertThat(zip).containsKey("attachments/cretas-media/" + FA + "/%2E%2E/%2E%2E/etc/evil.txt");
        // 规范化后撞名的两个对象键都要导出, ⛔ 不能因重名让整个导出失败
        store.put("cretas-media", FA + "/a//b.txt", "ONE");
        store.put("cretas-media", FA + "/a/b.txt", "TWO");
        Map<String, byte[]> zip2 = export(service(Set.of(1L)), FA, AttachmentMode.INCLUDE, Set.of(FactoryDataService.Format.CSV));
        assertThat(zip2).containsKey("attachments/cretas-media/" + FA + "/a/b.txt")
                .containsKey("attachments/cretas-media/" + FA + "/a/b.txt~2");
    }

    @Test
    void excelSplitsOversizedTablesShortensLongSheetNamesAndTruncatesHugeCells() throws Exception {
        try (Connection c = main.getConnection(); Statement st = c.createStatement();
             FactoryDataXlsxBook book = new FactoryDataXlsxBook()) {
            book.setMaxDataRowsForTest(2);
            try (ResultSet rs = st.executeQuery("SELECT g AS id, CASE WHEN g = 5 THEN repeat('x', 40000) ELSE 'v' END AS body"
                    + " FROM generate_series(1, 5) g ORDER BY g")) {
                assertThat(book.addTable("a_table_name_that_is_longer_than_thirty_one", rs)).isEqualTo(5);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            book.write(out);
            try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                         new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
                assertThat(wb.getNumberOfSheets()).isEqualTo(4);                  // 目录 + 3 部分
                for (int i = 1; i < 4; i++) assertThat(wb.getSheetName(i).length()).isLessThanOrEqualTo(31);
                assertThat(wb.getSheetName(2)).endsWith("~2");
                assertThat(wb.getSheetAt(3).getLastRowNum()).isEqualTo(1);       // 第 5 行单独一个 sheet
                String big = wb.getSheetAt(3).getRow(1).getCell(1).getStringCellValue();
                assertThat(big).hasSize(FactoryDataXlsxBook.MAX_CELL_CHARS).endsWith("[已截断]");
                // 目录写全名
                assertThat(wb.getSheetAt(0).getRow(1).getCell(1).getStringCellValue())
                        .isEqualTo("a_table_name_that_is_longer_than_thirty_one");
            }
            assertThat(book.truncatedCells()).isEqualTo(1);
            assertThat(book.sheets()).extracting(m -> m.get("rows")).containsExactly(2, 2, 1);
            assertThat(book.sheets().get(1).get("note").toString()).contains("拆分第 2 部分");
        }
    }

    @Test
    void previewCountsEachDatabaseAndBlocksErasureWhileDecisionsArePending() throws Exception {
        seedCleanTenants();
        FactoryDataService.Preview p = service(Set.of(1L)).preview(OPERATOR, FA);
        assertThat(p.factoryName()).isEqualTo("A厂");
        assertThat(p.databases()).extracting(FactoryDataService.DatabasePreview::database)
                .containsExactly(Database.CRETAS, Database.SMARTBI);
        FactoryDataService.DatabasePreview sb = p.databases().get(1);
        assertThat(sb.tables()).extracting(FactoryDataService.TableRow::table, FactoryDataService.TableRow::rows)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("smart_bi_uploads", 1L),
                        org.assertj.core.groups.Tuple.tuple("smart_bi_field_defs", 2L),
                        org.assertj.core.groups.Tuple.tuple("training_consent", 1L));
        // 将删除: 业务 + 主记录 + 审计 (先挪留存区) + 副本; 回执 / 台账不算
        assertThat(p.databases().get(0).rowsToErase()).isEqualTo(1 + 1 + 2 + 2 + 1 + 1 + 2 + 1 + 1);
        assertThat(p.databases().get(0).auditRowsToRetain()).isEqualTo(1);
        assertThat(p.attachments().objects()).isEqualTo(1);
        // 没有删除申请 ⇒ 不可删
        assertThat(p.erasureRequest()).isNull();
        assertThat(p.erasable()).isFalse();
        assertThat(p.blockers()).anyMatch(b -> b.contains("没有有效的删除申请"));
    }

    @Test
    void onlyRegisteredPlatformSuperAdminsMayExportOrPreview() throws Exception {
        seedCleanTenants();
        FactoryDataService service = service(Set.of(1L));
        List<AuthenticatedActor> refused = List.of(
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 2L, "PLATFORM", "super_admin"),
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 1L, "PLATFORM", "developer"),
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 1L, FA, "super_admin"),
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 1L, FA, "factory_super_admin"));
        for (AuthenticatedActor a : refused) {
            assertThatThrownBy(() -> service.preview(a, FA)).as(a.toString())
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
            assertThatThrownBy(() -> service.startExport(a, FA)).as(a.toString())
                    .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
        }
        // 没登记任何账号 = 谁都不能
        assertThatThrownBy(() -> service(Set.of()).preview(OPERATOR, FA)).isInstanceOf(BusinessException.class);
        // 阳性对照
        assertThat(service.preview(OPERATOR, FA).factoryId()).isEqualTo(FA);
        assertThatThrownBy(() -> service.preview(OPERATOR, "NOPE_FACTORY"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(404));
    }

    // --------------------------------------------------------------- fixture

    void seedCleanTenants() throws Exception {
        exec(main, "SET search_path = " + schema + ";"
                + "INSERT INTO factories (id, name, type) VALUES ('" + fa + "', 'A厂', 'FACTORY'), ('" + fb + "', 'B厂', 'FACTORY');"
                + "INSERT INTO users (id, factory_id, username, password_hash) VALUES (1, '" + fa + "', 'a_admin', 'hash-of-a'), (2, '" + fb + "', 'b_admin', 'hash-of-b');"
                + "INSERT INTO sales_orders (id, factory_id, created_by, note) VALUES (10, '" + fa + "', 1, 'A-ORDER-1'), (11, '" + fa + "', 1, '=SUM(A1)'), (30, '" + fb + "', 2, 'B-SECRET');"
                + "INSERT INTO sales_order_items (id, order_id, qty) VALUES (100, 10, 1), (101, 10, 2), (300, 30, 9);"
                + "INSERT INTO sales_item_lots (id, item_id, lot) VALUES (1000, 100, 'LOT-A');"
                + "INSERT INTO internal_transfers (id, source_factory_id, target_factory_id) VALUES (50, '" + fa + "', '" + fa + "');"
                + "INSERT INTO activity_events (factory_id, event) VALUES ('" + fa + "', 'login'), ('" + fa + "', 'view'), ('" + fb + "', 'B-SECRET');"
                + "INSERT INTO notes (id, author_id, body) VALUES (1, 1, 'platform note');"
                + "INSERT INTO platform_data_access_log (id, target_factory_id, path) VALUES (1, '" + fa + "', '/x');"
                + "INSERT INTO backup_sales_orders_20260801 (factory_id, note) VALUES ('" + fa + "', 'old');");
        exec(smartbi, "SET search_path = " + schema + ";"
                + "INSERT INTO smart_bi_uploads (id, factory_id, file_name) VALUES (1, '" + fa + "', 'A-UPLOAD'), (2, '" + fb + "', 'B-UPLOAD');"
                + "INSERT INTO smart_bi_field_defs (id, upload_id, name) VALUES (1, 1, 'qty'), (2, 1, 'amount'), (3, 2, 'B-UPLOAD-FIELD');"
                + "INSERT INTO training_consent (factory_id, consent_scope) VALUES ('" + fa + "', 'private');");
    }

    static String mainSchema(String s) {
        return "SET search_path = " + s + ";"
                + "CREATE TABLE factories (id varchar(64) PRIMARY KEY, name varchar(255), type varchar(32),"
                + "  parent_id varchar(64) REFERENCES factories(id) ON DELETE SET NULL,"
                + "  is_active boolean DEFAULT true, created_at timestamp DEFAULT now());"
                + "CREATE TABLE users (id bigint PRIMARY KEY, factory_id varchar(64) REFERENCES factories(id),"
                + "  username varchar(64), password_hash varchar(128));"
                + "CREATE TABLE sales_orders (id bigint PRIMARY KEY, factory_id varchar(64) REFERENCES factories(id),"
                + "  created_by bigint REFERENCES users(id), note text);"
                + "CREATE TABLE sales_order_items (id bigint PRIMARY KEY, order_id bigint NOT NULL REFERENCES sales_orders(id), qty int);"
                + "CREATE TABLE sales_item_lots (id bigint PRIMARY KEY,"
                + "  item_id bigint REFERENCES sales_order_items(id) ON DELETE CASCADE, lot text);"
                + "CREATE TABLE internal_transfers (id bigint PRIMARY KEY,"
                + "  source_factory_id varchar(64) REFERENCES factories(id), target_factory_id varchar(64) REFERENCES factories(id));"
                + "CREATE TABLE activity_events (factory_id varchar(64), event text);"
                + "CREATE TABLE notes (id bigint PRIMARY KEY, author_id bigint REFERENCES users(id) ON DELETE SET NULL, body text);"
                + "CREATE TABLE tool_calls (id bigint PRIMARY KEY, factory_id varchar(64), user_id bigint REFERENCES users(id));"
                + "CREATE TABLE reviews (id bigint PRIMARY KEY, factory_id varchar(64),"
                + "  reviewer_id bigint REFERENCES users(id) ON DELETE SET NULL);"
                + "CREATE TABLE platform_data_access_log (id bigint PRIMARY KEY, target_factory_id varchar(64), path text);"
                + "CREATE TABLE backup_sales_orders_20260801 (factory_id varchar(64), note text);";
    }

    static String smartbiSchema(String s) {
        return "SET search_path = " + s + ";"
                + "CREATE TABLE smart_bi_uploads (id bigint PRIMARY KEY, factory_id varchar(64), file_name text);"
                + "CREATE TABLE smart_bi_field_defs (id bigint PRIMARY KEY,"
                + "  upload_id bigint REFERENCES smart_bi_uploads(id) ON DELETE CASCADE, name text);"
                + "CREATE TABLE training_consent (factory_id varchar(64) PRIMARY KEY, consent_scope text);";
    }

    private FactoryDataService service(Set<Long> operators) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("smartbiPostgresDataSource", smartbi);
        ObjectProvider<DataSource> provider = beans.getBeanProvider(DataSource.class);
        return new FactoryDataService(main, provider, store, new FactoryDataRequestLedger(main, Clock.systemDefaultZone()),
                operators, Clock.systemDefaultZone(), schema);
    }

    private Map<String, byte[]> export(FactoryDataService service, String factory, AttachmentMode mode,
                                       Set<FactoryDataService.Format> formats) throws IOException {
        long id = service.startExport(OPERATOR, factory);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeExport(id, OPERATOR, factory, mode, formats, out);
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) files.put(e.getName(), zin.readAllBytes());
        }
        return files;
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Excel 里所有单元格的文本, 用于阴性对照 (别的工厂的值不许出现)。 */
    private static String xlsxText(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (org.apache.poi.ss.usermodel.Sheet s : wb) {
                for (org.apache.poi.ss.usermodel.Row r : s) {
                    for (org.apache.poi.ss.usermodel.Cell c : r) sb.append(c.toString()).append('|');
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private FactoryDataScope resolve(DataSource ds, Database db, String factory) throws Exception {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                return FactoryDataScopeResolver.resolve(c, db, schema, factory);
            } finally {
                c.rollback();
            }
        }
    }

    private static Map<String, Long> rows(FactoryDataScope scope) {
        Map<String, Long> out = new LinkedHashMap<>();
        scope.nonEmpty().forEach(t -> out.put(t.table(), t.rows()));
        return out;
    }

    private static Set<String> intersection(FactoryDataScope a, FactoryDataScope b, String table) {
        Set<String> keys = new java.util.HashSet<>(a.tables().get(table).keys());
        keys.retainAll(b.tables().get(table).keys());
        return keys;
    }

    private static String sha(byte[] body) {
        return java.util.HexFormat.of().formatHex(FactoryDataPackageWriter.sha256().digest(body));
    }

    private static String url() {
        return System.getenv("CRETAS_WORKFLOW_PG_URL");
    }

    private static PGSimpleDataSource dataSource(String jdbcUrl) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl);
        ds.setUser(System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
        ds.setPassword(System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        return ds;
    }

    private static void exec(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(true);
            st.execute(sql);
        }
    }

    /** 内存对象存储: 按前缀列举。 */
    static final class FakeStore implements FactoryObjectStore {
        final Map<String, byte[]> objects = new LinkedHashMap<>();

        void put(String bucket, String key, String body) {
            objects.put(bucket + "|" + key, body.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public List<StoredObject> list(String prefix) {
            List<StoredObject> out = new ArrayList<>();
            objects.forEach((k, v) -> {
                String[] p = k.split("\\|", 2);
                if (p[1].startsWith(prefix)) out.add(new StoredObject(p[0], p[1], v.length, null));
            });
            return out;
        }

        @Override
        public void copyTo(StoredObject object, OutputStream out) throws IOException {
            out.write(objects.get(object.bucket() + "|" + object.key()));
        }

        @Override
        public void delete(List<StoredObject> list) {
            for (StoredObject o : list) {
                if (objects.remove(o.bucket() + "|" + o.key()) == null) throw new IllegalStateException("no such object " + o);
            }
        }
    }
}
