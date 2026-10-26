package com.cretas.aims.service.factorydata;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.factorydata.FactoryDataCatalog.Category;
import com.cretas.aims.service.factorydata.FactoryDataCatalog.Database;
import com.cretas.aims.service.factorydata.FactoryDataScope.ForeignHit;
import com.cretas.aims.service.factorydata.FactoryDataScope.NulledReference;
import com.cretas.aims.service.factorydata.FactoryDataScope.TableScope;
import com.cretas.aims.service.factorydata.FactoryObjectStore.StoredObject;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 整厂数据导出 / 删除预演 (手册 docs/security/factory-data-export-and-erasure-runbook.md §5 §6)。
 *
 * <p>导出、预演 (以及之后的删除) 共用 {@link FactoryDataScopeResolver} 算出的同一份「本厂拥有哪些行」,
 * 每个库各在一个 REPEATABLE READ 只读事务里算和读, 所以导出包里的行数与同一快照的计数逐表相等 (不等即报错)。
 *
 * <p>只有登记过的平台账号能调用 ({@code cretas.factory-data.operator-admin-ids}, 默认空 = 谁都不能)。
 * {@code @RequireRole} 会放行所有平台角色 (含 developer), 不够细, 所以这里再按账号判一次。
 */
@Slf4j
@Service
public class FactoryDataService {

    /** 删除接口对这两个租户直接拒绝: 团队演示租户 / 真实客户 (手册 §3)。 */
    public static final Set<String> PROTECTED_FACTORIES = Set.of("F006", "LIUSHANMEN");

    public enum AttachmentMode {
        /** 附件原文件打进包里。 */
        INCLUDE,
        /** 只给附件清单 (对象键 / 大小 / ETag), 原文件另行交付 —— 大厂附件可能有十几 GB。 */
        INDEX_ONLY,
        /** 操作人明确选择不处理附件; manifest 里写明。 */
        SKIP
    }

    public record TableRow(String table, Category category, int depth, long rows, boolean exported,
                           boolean erasedByDefault) {}

    public record DatabasePreview(Database database, String schema, int closurePasses, long rowsInScope,
                                  long rowsErasedByDefault, List<TableRow> tables, long foreignHitCount,
                                  List<ForeignHit> foreignHits, List<NulledReference> nulledReferences,
                                  List<String> suspectTenantColumns, List<CopyCount> copiesOutsideSchema) {}

    public record CopyCount(String table, long rows) {}

    public record Attachments(String status, long objects, long bytes, String error) {}

    public record Preview(long previewId, String factoryId, String factoryName, String factoryType,
                          LocalDateTime computedAt, List<DatabasePreview> databases, Attachments attachments,
                          boolean erasable, List<String> blockers) {}

    static final int MAX_LISTED_FOREIGN_HITS = 200;

    private final DataSource cretasDataSource;
    private final ObjectProvider<DataSource> smartbiDataSource;
    private final FactoryObjectStore objectStore;
    private final FactoryDataRequestLedger ledger;
    private final Set<Long> operatorAdminIds;
    private final Clock clock;
    /** 两个库都是 public; 测试用独立 schema。 */
    private final String schema;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public FactoryDataService(@Qualifier("primaryDataSource") DataSource cretasDataSource,
                              @Qualifier("smartbiPostgresDataSource") ObjectProvider<DataSource> smartbiDataSource,
                              FactoryObjectStore objectStore,
                              FactoryDataRequestLedger ledger,
                              @Value("${cretas.factory-data.operator-admin-ids:}") String operatorAdminIds) {
        this(cretasDataSource, smartbiDataSource, objectStore, ledger, parseIds(operatorAdminIds),
                Clock.systemDefaultZone(), "public");
    }

    FactoryDataService(DataSource cretasDataSource, ObjectProvider<DataSource> smartbiDataSource,
                       FactoryObjectStore objectStore, FactoryDataRequestLedger ledger, Set<Long> operatorAdminIds,
                       Clock clock, String schema) {
        this.cretasDataSource = cretasDataSource;
        this.smartbiDataSource = smartbiDataSource;
        this.objectStore = objectStore;
        this.ledger = ledger;
        this.operatorAdminIds = Set.copyOf(operatorAdminIds);
        this.clock = clock;
        this.schema = schema;
    }

    // ------------------------------------------------------------------ preview

    /** 删除预演: 只计数、不删。结果写进回执台账, 返回的 previewId 是删除时要带的那个编号。 */
    public Preview preview(AuthenticatedActor actor, String factoryId) {
        requireOperator(actor);
        FactoryRow factory = requireFactory(factoryId);
        long id = ledger.start(FactoryDataRequestLedger.Type.PREVIEW, factoryId, factory.name(),
                actor.type().name(), actor.id(), actor.role());
        try {
            List<DatabasePreview> dbs = new ArrayList<>();
            for (Database db : Database.values()) {
                dbs.add(withSnapshot(db, conn -> previewOf(conn, db, factoryId)));
            }
            Attachments attachments = attachmentSummary(factoryId);
            List<String> blockers = blockers(factoryId, dbs, attachments);
            Preview preview = new Preview(id, factoryId, factory.name(), factory.type(), LocalDateTime.now(clock),
                    dbs, attachments, blockers.isEmpty(), blockers);
            ledger.finish(id, FactoryDataRequestLedger.Status.SUCCEEDED, toJson(summaryOf(preview)), null, null, null);
            return preview;
        } catch (RuntimeException e) {
            ledger.finish(id, FactoryDataRequestLedger.Status.FAILED, null, null, null, e.getMessage());
            throw e;
        }
    }

    private DatabasePreview previewOf(Connection conn, Database db, String factoryId) throws SQLException {
        FactoryDataScope scope = FactoryDataScopeResolver.resolve(conn, db, schema, factoryId);
        List<TableRow> rows = scope.nonEmpty().stream()
                .map(t -> new TableRow(t.table(), t.category(), t.depth(), t.rows(), t.category().exported(),
                        t.category().erasedByDefault()))
                .toList();
        long erased = rows.stream().filter(TableRow::erasedByDefault).mapToLong(TableRow::rows).sum();
        List<ForeignHit> hits = scope.foreignHits();
        return new DatabasePreview(db, schema, scope.closurePasses(), scope.totalRows(), erased, rows, hits.size(),
                hits.subList(0, Math.min(hits.size(), MAX_LISTED_FOREIGN_HITS)), scope.nulledReferences(),
                scope.suspectTenantColumns(), copiesOutside(conn, schema, factoryId));
    }

    /** public 以外的 schema 里带 factory_id 的表 (删前备份、临时副本): 只计数, 手册 §8 / 口径 Q6。 */
    static List<CopyCount> copiesOutside(Connection conn, String schema, String factoryId) throws SQLException {
        List<String[]> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT n.nspname, c.relname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE a.attname = 'factory_id' AND NOT a.attisdropped AND c.relkind IN ('r','p') "
                        + "AND NOT c.relispartition AND n.nspname <> ? "
                        + "AND n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_%' "
                        + "ORDER BY 1, 2")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) tables.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        }
        List<CopyCount> out = new ArrayList<>();
        for (String[] t : tables) {
            String sql = "SELECT count(*) FROM " + FactoryDataScopeResolver.q(t[0]) + "." + FactoryDataScopeResolver.q(t[1])
                    + " WHERE factory_id::text = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, factoryId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    long n = rs.getLong(1);
                    if (n > 0) out.add(new CopyCount(t[0] + "." + t[1], n));
                }
            }
        }
        return out;
    }

    private Attachments attachmentSummary(String factoryId) {
        try {
            List<StoredObject> objects = objectStore.list(factoryId + "/");
            return new Attachments("AVAILABLE", objects.size(), objects.stream().mapToLong(StoredObject::size).sum(), null);
        } catch (RuntimeException e) {
            log.warn("factory-data preview: 附件列举失败 factoryId={} err={}", factoryId, e.toString());
            return new Attachments("UNAVAILABLE", 0, 0, e.getMessage());
        }
    }

    private static List<String> blockers(String factoryId, List<DatabasePreview> dbs, Attachments attachments) {
        List<String> out = new ArrayList<>();
        out.add("真正删除尚未上线: 口径 Q1–Q6 待拍板 (手册 §0)");
        if (PROTECTED_FACTORIES.contains(factoryId)) out.add("受保护租户 (团队演示 / 真实客户), 不允许删除");
        for (DatabasePreview db : dbs) {
            if (db.foreignHitCount() > 0) {
                out.add(db.database() + " 库有 " + db.foreignHitCount() + " 行跨租户命中, 删除前必须查清");
            }
        }
        if (!"AVAILABLE".equals(attachments.status())) out.add("附件列举失败: " + attachments.error());
        return out;
    }

    // ------------------------------------------------------------------- export

    /** 开始导出: 先写回执, 返回编号 (控制器把它放进响应头, 再调用 {@link #writeExport})。 */
    public long startExport(AuthenticatedActor actor, String factoryId) {
        requireOperator(actor);
        FactoryRow factory = requireFactory(factoryId);
        return ledger.start(FactoryDataRequestLedger.Type.EXPORT, factoryId, factory.name(),
                actor.type().name(), actor.id(), actor.role());
    }

    /**
     * 把整厂数据写成 ZIP 流。出错时回执标 FAILED 并抛出 —— 流已经开始写的话, 包里没有 manifest.json,
     * 收件人据此判断它不完整。
     */
    public void writeExport(long requestId, AuthenticatedActor actor, String factoryId, AttachmentMode mode,
                            OutputStream rawOut) throws IOException {
        FactoryRow factory = requireFactory(factoryId);
        MessageDigest whole = FactoryDataPackageWriter.sha256();
        CountingOut counted = new CountingOut(rawOut);
        DigestOutputStream out = new DigestOutputStream(counted, whole);
        try {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("format", "cretas-factory-export/1");
            manifest.put("factoryId", factoryId);
            manifest.put("factoryName", factory.name());
            manifest.put("generatedAt", LocalDateTime.now(clock).toString());
            manifest.put("requestId", requestId);
            manifest.put("operator", Map.of("type", actor.type().name(), "id", actor.id()));
            FactoryDataPackageWriter writer = new FactoryDataPackageWriter(out);
            List<Map<String, Object>> dbManifests = new ArrayList<>();
            for (Database db : Database.values()) {
                dbManifests.add(withSnapshot(db, conn -> exportDatabase(conn, db, factoryId, writer)));
            }
            manifest.put("databases", dbManifests);
            manifest.put("attachments", exportAttachments(factoryId, mode, writer));
            writer.writeText("README.txt", readme(factoryId, factory.name(), mode));
            manifest.put("files", writer.entries());
            writer.writeManifest(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            writer.close();
            out.flush();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("databases", dbManifests.stream().map(m -> Map.of("database", m.get("database"),
                    "tables", ((List<?>) m.get("tables")).size(), "rows", m.get("rows"))).toList());
            summary.put("attachments", manifest.get("attachments") instanceof Map<?, ?> a
                    ? Map.of("mode", a.get("mode"), "objects", a.get("objects")) : null);
            ledger.finish(requestId, FactoryDataRequestLedger.Status.SUCCEEDED, toJson(summary),
                    HexFormat.of().formatHex(whole.digest()), counted.count, null);
        } catch (IOException | RuntimeException e) {
            log.error("factory-data export failed requestId={} factoryId={}", requestId, factoryId, e);
            ledger.finish(requestId, FactoryDataRequestLedger.Status.FAILED, null, null, counted.count, e.toString());
            throw e;
        }
    }

    private Map<String, Object> exportDatabase(Connection conn, Database db, String factoryId,
                                               FactoryDataPackageWriter writer) throws SQLException {
        FactoryDataScope scope = FactoryDataScopeResolver.resolve(conn, db, schema, factoryId);
        String dir = db.name().toLowerCase();
        List<Map<String, Object>> tables = new ArrayList<>();
        List<Map<String, Object>> notExported = new ArrayList<>();
        long rows = 0;
        for (TableScope ts : scope.nonEmpty()) {
            if (!ts.category().exported()) {
                notExported.add(Map.of("table", ts.table(), "category", ts.category().name(), "rows", ts.rows()));
                continue;
            }
            FactoryDataPackageWriter.Entry entry;
            try {
                entry = writer.writeTable(conn, dir, schema, ts, factoryId);
            } catch (IOException e) {
                throw new IllegalStateException("写出 " + ts.table() + " 失败: " + e.getMessage(), e);
            }
            if (entry.rows() != ts.rows()) {
                throw new IllegalStateException("行数不一致 " + db + "." + ts.table() + ": 计数 " + ts.rows()
                        + " / 写出 " + entry.rows());
            }
            rows += entry.rows();
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("table", ts.table());
            t.put("category", ts.category().name());
            t.put("rows", entry.rows());
            t.put("file", entry.path());
            t.put("sha256", entry.sha256());
            tables.add(t);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("database", db.name());
        m.put("rows", rows);
        m.put("tables", tables);
        m.put("notExported", notExported);
        m.put("foreignHitCount", scope.foreignHits().size());
        return m;
    }

    private Map<String, Object> exportAttachments(String factoryId, AttachmentMode mode, FactoryDataPackageWriter writer)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode.name());
        if (mode == AttachmentMode.SKIP) {
            m.put("objects", 0);
            m.put("note", "操作人选择不处理附件; 附件未导出, 也未列出");
            return m;
        }
        List<StoredObject> objects = objectStore.list(factoryId + "/");
        StringBuilder index = new StringBuilder("bucket,key,size,etag\r\n");
        long bytes = 0;
        for (StoredObject o : objects) {
            index.append(FactoryDataPackageWriter.csv(o.bucket())).append(',')
                    .append(FactoryDataPackageWriter.csv(o.key())).append(',')
                    .append(o.size()).append(',')
                    .append(o.etag() == null ? "" : FactoryDataPackageWriter.csv(o.etag())).append("\r\n");
            bytes += o.size();
            if (mode == AttachmentMode.INCLUDE) {
                writer.writeEntryFromStore("attachments/" + o.bucket() + "/" + o.key(), objectStore, o);
            }
        }
        writer.writeText("attachments/index.csv", "﻿" + index);
        m.put("objects", objects.size());
        m.put("bytes", bytes);
        return m;
    }

    private static String readme(String factoryId, String name, AttachmentMode mode) {
        return String.join("\r\n",
                "整厂数据导出包  " + name + " (" + factoryId + ")",
                "",
                "manifest.json   清单: 每张表的行数与 SHA256, 附件数量。没有这个文件说明包不完整, 请重新导出。",
                "cretas/         业务主库, 每张表一个 CSV (UTF-8 带 BOM, Excel 可直接打开)。",
                "smartbi/        经营分析库, 同上。",
                "attachments/    附件。index.csv 是附件清单; " + (mode == AttachmentMode.INCLUDE
                        ? "原文件按对象键存放。" : "本包未含原文件 (" + mode + ")。"),
                "",
                "说明:",
                "1. 密码、令牌、密钥类字段的值已替换为 " + FactoryDataCatalog.REDACTED + "。",
                "2. 以 = + - @ 开头的文字值前加了一个单引号, 防止 Excel 把它当公式执行。",
                "3. 根据《食品安全法》, 进货查验与出厂检验记录须保存至产品保质期满后六个月 (没有明确保质期的不少于二年),",
                "   请工厂自行妥善保存本包。",
                "");
    }

    // ------------------------------------------------------------------ helpers

    private void requireOperator(AuthenticatedActor actor) {
        if (actor == null || !actor.isPlatformAdmin() || !"super_admin".equalsIgnoreCase(actor.role())
                || !operatorAdminIds.contains(actor.id())) {
            throw new BusinessException(403, "整厂数据导出/删除只允许登记过的平台超级管理员操作");
        }
    }

    record FactoryRow(String id, String name, String type) {}

    private FactoryRow requireFactory(String factoryId) {
        if (!FactoryDataCatalog.validFactoryId(factoryId)) throw new BusinessException(400, "工厂编号格式不合法");
        try (Connection conn = cretasDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, name, type FROM " + FactoryDataScopeResolver.q(schema) + ".factories WHERE id = ?")) {
            ps.setString(1, factoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new BusinessException(404, "工厂不存在: " + factoryId);
                return new FactoryRow(rs.getString(1), rs.getString(2), rs.getString(3));
            } finally {
                if (!conn.getAutoCommit()) conn.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取工厂失败: " + e.getMessage(), e);
        }
    }

    private interface SnapshotWork<T> { T run(Connection conn) throws SQLException; }

    /** 在一个 REPEATABLE READ 只读事务里做完, 最后回滚 (什么都不写)。 */
    private <T> T withSnapshot(Database db, SnapshotWork<T> work) {
        DataSource ds = db == Database.CRETAS ? cretasDataSource : smartbiDataSource.getIfAvailable();
        if (ds == null) throw new BusinessException(503, "SmartBI 数据库未启用, 无法导出/预演该库");
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setReadOnly(true);
            try {
                return work.run(conn);
            } finally {
                conn.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(db + " 库读取失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> summaryOf(Preview p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("factoryName", p.factoryName());
        m.put("databases", p.databases().stream().map(d -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("database", d.database().name());
            x.put("rowsInScope", d.rowsInScope());
            x.put("rowsErasedByDefault", d.rowsErasedByDefault());
            x.put("foreignHitCount", d.foreignHitCount());
            x.put("tables", d.tables().stream().collect(Collectors.toMap(TableRow::table, TableRow::rows,
                    (a, b) -> a, LinkedHashMap::new)));
            return x;
        }).toList());
        m.put("attachments", p.attachments());
        m.put("blockers", p.blockers());
        return m;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static Set<Long> parseIds(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Long::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static final class CountingOut extends FilterOutputStream {
        long count;
        CountingOut(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(b); count++; }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); count += len; }
    }
}
