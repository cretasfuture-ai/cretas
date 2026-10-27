package com.cretas.aims.service.tenantbackup;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.factorydata.FactoryDataService;
import com.cretas.aims.service.factorydata.FactoryDataServiceFixture;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.Frequency;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.KeyHolder;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.Run;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository.RunStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 真实 PostgreSQL: 按工厂备份 = 真实的整厂导出 + 迁移 V20261103_90 建的三张表。
 *
 * <p>加密器与对象存储用测试替身 (真实 age 的加解密见 {@link AgeBackupEncryptorTest});
 * 替身加密器写 age 文件头并把内容逐字节异或, 所以「上传的是明文」会被断言抓到。
 * 阴性对照: 另一个已开通 (但关闭) 的工厂、一个旧工厂、一个已删除工厂, 各自的数据与备份件不能被动到。
 */
@EnabledIfEnvironmentVariable(named = "CRETAS_WORKFLOW_PG_VERIFY", matches = "true")
class TenantBackupPostgresContractTest {

    static final String FA = "TBK_A";
    static final String FB = "TBK_B";
    static final String FOLD = "TBK_OLD";
    static final String FGONE = "TBK_GONE";
    static final long SCHEDULER_ADMIN = 71L;
    static final long OTHER_ADMIN = 72L;
    static final String PLATFORM_KEY = "age1" + "q".repeat(58);

    String schema;
    String smartbiDb;
    PGSimpleDataSource main;
    PGSimpleDataSource smartbi;
    FakeStore store;
    XorEncryptor encryptor;
    TenantBackupRepository repo;
    TenantBackupService service;
    @TempDir Path tmp;

    @BeforeEach
    void setUp() throws Exception {
        schema = "tbk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        PGSimpleDataSource admin = ds(url());
        exec(admin, "CREATE SCHEMA " + schema);
        // 回执台账在 public (与生产相同, 导出服务的 ledger 不带 schema)
        exec(admin, Files.readString(Path.of("src/main/resources/db/flyway/V20261103_87__factory_data_requests.sql")));
        exec(admin, FactoryDataServiceFixture.mainSchema(schema));
        exec(admin, "SET search_path = " + schema + ";"
                + Files.readString(Path.of("src/main/resources/db/flyway/V20261103_90__tenant_backup_plans.sql"))
                + "CREATE TABLE platform_admins (id bigint PRIMARY KEY, platform_role varchar(50), status varchar(20));");
        main = ds(url());
        main.setCurrentSchema(schema + ",public");
        smartbiDb = schema + "_sb";
        exec(admin, "CREATE DATABASE " + smartbiDb);
        smartbi = ds(url().replaceAll("/[^/?]+(\\?|$)", "/" + smartbiDb + "$1"));
        exec(smartbi, "CREATE SCHEMA " + schema);
        exec(smartbi, FactoryDataServiceFixture.smartbiSchema(schema));
        seed();

        store = new FakeStore();
        encryptor = new XorEncryptor();
        Path keyFile = tmp.resolve("age-recipient.txt");
        Files.writeString(keyFile, "# public key\n" + PLATFORM_KEY + "\n");
        FactoryDataService data = FactoryDataServiceFixture.create(main, smartbi, Set.of(SCHEDULER_ADMIN), schema);
        repo = new TenantBackupRepository(main, Clock.systemDefaultZone());
        service = new TenantBackupService(repo, data, store, encryptor, Clock.systemDefaultZone(),
                String.valueOf(SCHEDULER_ADMIN), keyFile, tmp.resolve("work"), LocalDateTime.now().minusDays(1));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (main == null) return;
        PGSimpleDataSource admin = ds(url());
        exec(admin, "DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        exec(admin, "DELETE FROM factory_data_requests WHERE factory_id IN ('" + FA + "','" + FB + "','" + FOLD + "','" + FGONE + "')");
        exec(admin, "DROP DATABASE IF EXISTS " + smartbiDb + " WITH (FORCE)");
    }

    private void seed() throws Exception {
        exec(main, "INSERT INTO factories (id, name, created_at) VALUES "
                + "('" + FA + "', '新厂A', now()), ('" + FB + "', '新厂B', now()), "
                + "('" + FOLD + "', '老厂', '2026-01-01');"
                + "INSERT INTO users (id, factory_id, username, password_hash) VALUES "
                + "(1, '" + FA + "', 'a', 'HASH-A'), (2, '" + FB + "', 'b', 'HASH-B'), (3, '" + FOLD + "', 'o', 'HASH-O');"
                + "INSERT INTO sales_orders (id, factory_id, created_by, note) VALUES "
                + "(10, '" + FA + "', 1, 'A-ORDER-NOTE'), (20, '" + FB + "', 2, 'B-ORDER-NOTE'), (30, '" + FOLD + "', 3, 'OLD-NOTE');"
                + "INSERT INTO platform_admins VALUES (" + SCHEDULER_ADMIN + ", 'super_admin', 'active'), "
                + "(" + OTHER_ADMIN + ", 'super_admin', 'active');");
    }

    // ------------------------------------------------------------------ cases

    @Test
    void tickEnablesOnlyNewFactoriesAndBacksUpEachEnabledOneIntoItsOwnFolderEncrypted() throws Exception {
        // FB 由平台先关掉: 自动开通不能覆盖平台的决定
        service.updatePlan(platform(OTHER_ADMIN), FB, new TenantBackupService.PlanRequest(false, Frequency.DAILY, 7,
                false, KeyHolder.PLATFORM, null, "合同未签"));

        TenantBackupService.TickResult r = service.tick();

        assertThat(r.defaultPlansAdded()).isEqualTo(1);   // 只有 FA; FB 已有套餐, FOLD 是旧厂
        assertThat(repo.findPlan(FA)).get().satisfies(p -> {
            assertThat(p.enabled()).isTrue();
            assertThat(p.frequency()).isEqualTo(Frequency.DAILY);
            assertThat(p.retentionDays()).isEqualTo(7);
            assertThat(p.source()).isEqualTo(TenantBackupRepository.Source.AUTO_NEW_FACTORY);
        });
        assertThat(repo.findPlan(FB)).get().satisfies(p -> assertThat(p.enabled()).isFalse());
        assertThat(repo.findPlan(FOLD)).isEmpty();
        assertThat(r.ran()).isEqualTo(1);
        assertThat(r.succeeded()).isEqualTo(1);

        Run run = repo.listRuns(FA, 10).get(0);
        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.triggerSource()).isEqualTo(TenantBackupService.SCHEDULER_TRIGGER);
        assertThat(run.actorId()).isEqualTo(SCHEDULER_ADMIN);
        assertThat(run.attachmentsMode()).isEqualTo("INDEX_ONLY");
        assertThat(run.objectKey()).startsWith("tenants/" + FA + "/" + FA + "_").endsWith(".zip.age");
        assertThat(store.objects.keySet()).containsExactly(run.objectKey());
        byte[] stored = store.objects.get(run.objectKey());
        assertThat(run.encryptedBytes()).isEqualTo(stored.length);
        assertThat(run.encryptedSha256()).isEqualTo(sha256(stored));
        // 上传的必须是加密件: 文件头是 age 的, 正文里搜不到明文
        assertThat(new String(stored, 0, BackupEncryptor.AGE_HEADER.length(), StandardCharsets.US_ASCII))
                .isEqualTo(BackupEncryptor.AGE_HEADER);
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("manifest.json");
        assertThat(encryptor.recipients).containsExactly(PLATFORM_KEY);

        Map<String, String> zip = unzip(XorEncryptor.decrypt(stored));
        assertThat(run.packageBytes()).isEqualTo(XorEncryptor.decrypt(stored).length);
        assertThat(zip).containsKey("manifest.json");
        assertThat(zip.get("manifest.json").replaceAll("\\s", "")).contains("\"factoryId\":\"" + FA + "\"");
        String all = String.join("\n", zip.values());
        assertThat(all).contains("A-ORDER-NOTE").doesNotContain("B-ORDER-NOTE").doesNotContain("OLD-NOTE")
                .doesNotContain("HASH-A");   // 凭证列由导出抹掉

        // 导出回执能区分是定时任务触发的 (口径 Q7 ③)
        assertThat(query("SELECT trigger_source || '|' || actor_id || '|' || status FROM public.factory_data_requests "
                + "WHERE id = " + run.exportRequestId()))
                .containsExactly(TenantBackupService.SCHEDULER_TRIGGER + "|" + SCHEDULER_ADMIN + "|SUCCEEDED");
        // 阴性对照: 关掉的 FB、没有套餐的 FOLD 没有被导出, 也没有执行记录
        assertThat(query("SELECT factory_id FROM public.factory_data_requests WHERE factory_id IN ('" + FB + "','" + FOLD + "')"))
                .isEmpty();
        assertThat(repo.listRuns(FB, 10)).isEmpty();
        assertThat(repo.listRuns(FOLD, 10)).isEmpty();

        // 当天再跑一轮: 不到期, 不再备份
        TenantBackupService.TickResult again = service.tick();
        assertThat(again.ran()).isZero();
        assertThat(store.objects).hasSize(1);
        assertThat(service.overview()).filteredOn(f -> f.factoryId().equals(FA)).singleElement()
                .satisfies(f -> assertThat(f.health()).isEqualTo(TenantBackupService.Health.OK));
    }

    @Test
    void factoryWithoutAnEnabledPlanIsSkippedAndNothingIsExported() {
        Run r = service.backup(FOLD, service.schedulerActor(), TenantBackupService.SCHEDULER_TRIGGER);

        assertThat(r.status()).isEqualTo(RunStatus.SKIPPED);
        assertThat(r.errorMessage()).contains("未开通");
        assertThat(query("SELECT id FROM public.factory_data_requests WHERE factory_id = '" + FOLD + "'")).isEmpty();
        assertThat(store.objects).isEmpty();
    }

    @Test
    void factoryWhosePlanIsSwitchedOffIsSkippedAndNothingIsExported() {
        service.updatePlan(platform(OTHER_ADMIN), FB, new TenantBackupService.PlanRequest(false, Frequency.DAILY, 30,
                false, KeyHolder.PLATFORM, null, "合同到期"));

        Run r = service.backup(FB, service.schedulerActor(), TenantBackupService.SCHEDULER_TRIGGER);

        assertThat(r.status()).isEqualTo(RunStatus.SKIPPED);
        assertThat(query("SELECT id FROM public.factory_data_requests WHERE factory_id = '" + FB + "'")).isEmpty();
        assertThat(store.objects).isEmpty();
    }

    @Test
    void uploadSizeMismatchIsRecordedAsFailureAndShownAsLastFailed() throws Exception {
        enable(FA);
        store.reportWrongSize = true;

        Run r = service.backup(FA, service.schedulerActor(), TenantBackupService.SCHEDULER_TRIGGER);

        assertThat(r.status()).isEqualTo(RunStatus.FAILED);
        assertThat(r.errorMessage()).contains("大小不一致");
        assertThat(r.objectKey()).isNull();
        assertThat(service.overview()).filteredOn(f -> f.factoryId().equals(FA)).singleElement()
                .satisfies(f -> assertThat(f.health()).isEqualTo(TenantBackupService.Health.LAST_FAILED));
        assertThat(Files.list(tmp.resolve("work")).toList()).isEmpty();   // 临时加密件已删
    }

    @Test
    void missingPlatformKeyRefusesToUploadPlaintext() throws Exception {
        enable(FA);
        Files.writeString(tmp.resolve("age-recipient.txt"), "# no key here\n");

        Run r = service.backup(FA, service.schedulerActor(), TenantBackupService.SCHEDULER_TRIGGER);

        assertThat(r.status()).isEqualTo(RunStatus.FAILED);
        assertThat(r.errorMessage()).contains("拒绝上传明文");
        assertThat(store.objects).isEmpty();
    }

    @Test
    void customerKeyIsUsedWhenTheFactoryHoldsItsOwnKey() throws Exception {
        String customerKey = "age1" + "p".repeat(58);
        service.updatePlan(platform(OTHER_ADMIN), FA, new TenantBackupService.PlanRequest(true, Frequency.WEEKLY, 30,
                false, KeyHolder.CUSTOMER, customerKey, "合同 C-001"));

        Run r = service.backup(FA, service.schedulerActor(), TenantBackupService.SCHEDULER_TRIGGER);

        assertThat(r.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(r.keyHolder()).isEqualTo("CUSTOMER");
        assertThat(encryptor.recipients).containsExactly(customerKey);
        assertThat(service.downloadable(FA, r.id()).objectKey()).isEqualTo(r.objectKey());
        // 别的工厂拿这个编号下载: 404
        assertThatThrownBy(() -> service.downloadable(FB, r.id())).isInstanceOf(BusinessException.class);
    }

    @Test
    void platformKeyBackupsCannotBeDownloadedByTheFactory() throws Exception {
        enable(FA);
        Run r = service.backup(FA, service.schedulerActor(), TenantBackupService.SCHEDULER_TRIGGER);
        assertThat(r.status()).isEqualTo(RunStatus.SUCCEEDED);

        assertThatThrownBy(() -> service.downloadable(FA, r.id()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("平台钥匙");
    }

    @Test
    void purgeDeletesOnlyExpiredCopiesInEachFactorysOwnFolderAndAlwaysKeepsTheNewest() throws Exception {
        enable(FA);   // 每天 × 7 天
        service.updatePlan(platform(OTHER_ADMIN), FB, new TenantBackupService.PlanRequest(false, Frequency.DAILY, 7,
                false, KeyHolder.PLATFORM, null, "暂停"));
        LocalDateTime now = LocalDateTime.now();
        String a10 = put(FA, now.minusDays(10)), a8 = put(FA, now.minusDays(8)), a1 = put(FA, now.minusDays(1));
        String b30 = put(FB, now.minusDays(30)), b20 = put(FB, now.minusDays(20));   // 全过期: 留最新一份
        String old = put(FOLD, now.minusDays(400));                                   // 没有套餐: 不动
        String gone1 = put(FGONE, now.minusDays(1)), gone2 = put(FGONE, now.minusDays(2)); // 工厂已删除: 全删
        String odd = "tenants/" + FA + "/notes.txt";                                  // 解析不出时间: 不动
        store.objects.put(odd, new byte[]{1});

        TenantBackupService.PurgeResult r = service.purgeExpired();

        assertThat(store.objects.keySet()).containsExactlyInAnyOrder(a1, b20, old, odd);
        assertThat(r.deleted()).isEqualTo(3);                       // a10, a8, b30
        assertThat(r.deletedOfRemovedFactories()).isEqualTo(2);     // gone1, gone2
        assertThat(r.errors()).isEmpty();
        assertThat(List.of(a10, a8, b30, gone1, gone2)).noneMatch(store.objects::containsKey);
    }

    @Test
    void planChangesAreValidatedAndEveryChangeIsRecordedWithBeforeAndAfter() {
        AuthenticatedActor admin = platform(OTHER_ADMIN);
        assertThatThrownBy(() -> service.updatePlan(admin, FA, new TenantBackupService.PlanRequest(true,
                Frequency.DAILY, 8, false, KeyHolder.PLATFORM, null, "x"))).hasMessageContaining("保留期");
        assertThatThrownBy(() -> service.updatePlan(admin, FA, new TenantBackupService.PlanRequest(true,
                Frequency.DAILY, 7, false, KeyHolder.CUSTOMER, "not-a-key", "x"))).hasMessageContaining("age 公钥");
        assertThatThrownBy(() -> service.updatePlan(admin, FA, new TenantBackupService.PlanRequest(true,
                Frequency.DAILY, 7, false, KeyHolder.PLATFORM, null, " "))).hasMessageContaining("原因");
        AuthenticatedActor factoryUser = new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 1L, FA,
                "factory_super_admin");
        assertThatThrownBy(() -> service.updatePlan(factoryUser, FA, new TenantBackupService.PlanRequest(true,
                Frequency.DAILY, 7, false, KeyHolder.PLATFORM, null, "x"))).hasMessageContaining("只有平台");
        assertThat(repo.listChanges(FA, 10)).isEmpty();

        service.updatePlan(admin, FA, new TenantBackupService.PlanRequest(true, Frequency.DAILY, 7, false,
                KeyHolder.PLATFORM, null, "合同 A-1"));
        service.updatePlan(admin, FA, new TenantBackupService.PlanRequest(true, Frequency.MONTHLY, 365, true,
                KeyHolder.PLATFORM, null, "合同 A-2 升级"));

        List<TenantBackupRepository.PlanChange> changes = repo.listChanges(FA, 10);
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).reason()).isEqualTo("合同 A-2 升级");
        assertThat(changes.get(0).actorId()).isEqualTo(OTHER_ADMIN);
        assertThat(changes.get(0).beforePlan()).contains("\"retentionDays\": 7");
        assertThat(changes.get(0).afterPlan()).contains("\"retentionDays\": 365").contains("\"includeAttachments\": true");
        assertThat(changes.get(1).beforePlan()).isNull();
    }

    @Test
    void schedulerIdentityMustBeAnActiveAdminOnTheExportAllowList() throws Exception {
        FactoryDataService data = FactoryDataServiceFixture.create(main, smartbi, Set.of(SCHEDULER_ADMIN), schema);
        TenantBackupService notListed = new TenantBackupService(repo, data, store, encryptor, Clock.systemDefaultZone(),
                String.valueOf(OTHER_ADMIN), tmp.resolve("age-recipient.txt"), tmp, LocalDateTime.now());
        assertThatThrownBy(notListed::tick).isInstanceOf(BusinessException.class);
        TenantBackupService unset = new TenantBackupService(repo, data, store, encryptor, Clock.systemDefaultZone(),
                "", tmp.resolve("age-recipient.txt"), tmp, LocalDateTime.now());
        assertThatThrownBy(unset::tick).hasMessageContaining("scheduler-admin-id");
        exec(main, "UPDATE platform_admins SET status = 'disabled' WHERE id = " + SCHEDULER_ADMIN);
        assertThatThrownBy(service::tick).hasMessageContaining("不存在或未启用");
        // 三种都在动手之前失败: 没有任何执行记录、没有补默认套餐
        assertThat(repo.listPlans()).isEmpty();
        assertThat(repo.listRuns(FA, 10)).isEmpty();
    }

    @Test
    void migrationRejectsRetentionOutsideTheOfferedTiersAndCustomerKeyWithoutAKey() {
        assertThatThrownBy(() -> exec(main, "INSERT INTO tenant_backup_plans (factory_id, enabled, frequency, "
                + "retention_days, key_holder, source, created_at, updated_at) VALUES ('X', true, 'DAILY', 8, 'PLATFORM', "
                + "'PLATFORM', now(), now())")).isInstanceOf(SQLException.class).hasMessageContaining("retention");
        assertThatThrownBy(() -> exec(main, "INSERT INTO tenant_backup_plans (factory_id, enabled, frequency, "
                + "retention_days, key_holder, source, created_at, updated_at) VALUES ('X', true, 'DAILY', 7, 'CUSTOMER', "
                + "'PLATFORM', now(), now())")).isInstanceOf(SQLException.class).hasMessageContaining("customer_key");
    }

    // ---------------------------------------------------------------- helpers

    private void enable(String factoryId) {
        service.updatePlan(platform(OTHER_ADMIN), factoryId, new TenantBackupService.PlanRequest(true, Frequency.DAILY,
                7, false, KeyHolder.PLATFORM, null, "测试开通"));
    }

    private String put(String factoryId, LocalDateTime at) {
        String key = "tenants/" + factoryId + "/" + factoryId + "_" + TenantBackupService.STAMP.format(at) + ".zip.age";
        store.objects.put(key, new byte[]{1, 2, 3});
        return key;
    }

    private static AuthenticatedActor platform(long id) {
        return new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, id, null, "super_admin");
    }

    private List<String> query(String sql) {
        try (Connection c = main.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                out.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static String sha256(byte[] b) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
    }

    private static String url() { return System.getenv("CRETAS_WORKFLOW_PG_URL"); }

    private static PGSimpleDataSource ds(String url) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_USER", "cretas_ci"));
        ds.setPassword(System.getenv().getOrDefault("CRETAS_WORKFLOW_PG_PASSWORD", ""));
        return ds;
    }

    private static void exec(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            c.setAutoCommit(true);
            st.execute(sql);
        }
    }

    /** 内存对象存储; {@code reportWrongSize} 模拟「上传后回读大小对不上」。 */
    static final class FakeStore implements TenantBackupStore {
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        boolean reportWrongSize;

        @Override public long put(Path file, String key) throws IOException {
            byte[] b = Files.readAllBytes(file);
            objects.put(key, b);
            return reportWrongSize ? b.length - 1 : b.length;
        }

        @Override public List<StoredBackup> list(String prefix) {
            return objects.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                    .map(e -> new StoredBackup(e.getKey(), e.getValue().length)).toList();
        }

        @Override public List<String> listChildren(String prefix) {
            return objects.keySet().stream().filter(k -> k.startsWith(prefix))
                    .map(k -> k.substring(prefix.length())).filter(k -> k.contains("/"))
                    .map(k -> k.substring(0, k.indexOf('/'))).distinct().toList();
        }

        @Override public void delete(List<String> keys) {
            for (String k : keys) if (objects.remove(k) == null) throw new IllegalStateException("no such object " + k);
        }

        @Override public void copyTo(String key, OutputStream out) throws IOException {
            out.write(objects.get(key));
        }
    }

    /** 写 age 文件头 + 逐字节异或的正文; 记下用过的公钥。 */
    static final class XorEncryptor implements BackupEncryptor {
        final List<String> recipients = new ArrayList<>();

        @Override public void encrypt(String recipient, Writer writer, Path target) throws IOException {
            if (!BackupEncryptor.validRecipient(recipient)) throw new IOException("bad recipient");
            recipients.add(recipient);
            ByteArrayOutputStream plain = new ByteArrayOutputStream();
            writer.writeTo(plain);
            byte[] p = plain.toByteArray();
            byte[] header = (AGE_HEADER + "\n").getBytes(StandardCharsets.US_ASCII);
            byte[] out = new byte[header.length + p.length];
            System.arraycopy(header, 0, out, 0, header.length);
            for (int i = 0; i < p.length; i++) out[header.length + i] = (byte) (p[i] ^ 0x5A);
            Files.write(target, out);
        }

        static byte[] decrypt(byte[] enc) {
            int skip = AGE_HEADER.length() + 1;
            byte[] p = new byte[enc.length - skip];
            for (int i = 0; i < p.length; i++) p[i] = (byte) (enc[skip + i] ^ 0x5A);
            return p;
        }
    }
}
