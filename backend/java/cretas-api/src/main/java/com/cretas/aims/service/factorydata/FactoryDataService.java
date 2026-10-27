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
import org.springframework.beans.factory.annotation.Autowired;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 整厂数据导出 / 删除预演 (手册 docs/security/factory-data-export-and-erasure-runbook.md §5 §6)。
 *
 * <p>导出、预演、删除 ({@link FactoryErasureService}) 共用 {@link FactoryDataScopeResolver} 算出的同一份
 * 「本厂拥有哪些行」。导出 / 预演每个库各在一个 REPEATABLE READ 只读事务里算和读, 所以导出包里的行数
 * 与同一快照的计数逐表相等 (不等即报错)。
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

    /** 导出格式, 可组合 (口径 Q5)。 */
    public enum Format { CSV, XLSX, JSON }

    public record TableRow(String table, Category category, int depth, long rows, boolean exported, boolean erased,
                           long retainedOnErase) {}

    public record DatabasePreview(Database database, String schema, int closurePasses, long rowsInScope,
                                  long rowsToErase, long auditRowsToRetain, long trainingSamplesToRetain,
                                  List<TableRow> tables, long foreignHitCount, List<ForeignHit> foreignHits,
                                  List<NulledReference> nulledReferences, List<String> suspectTenantColumns,
                                  List<String> missingDeletePrivileges, List<CopyCount> copiesOutsideSchema,
                                  List<CopyCount> unregisteredSchemas, List<String> unreadableCopies,
                                  List<String> unreadableUnregistered) {}

    public record CopyCount(String table, long rows) {}

    public record Attachments(String status, long objects, long bytes, String error) {}

    public record ErasureRequestState(long requestId, LocalDateTime notBefore, boolean coolingOffPassed) {}

    public record Preview(long previewId, String factoryId, String factoryName, String factoryType,
                          LocalDateTime computedAt, List<DatabasePreview> databases, Attachments attachments,
                          ErasureRequestState erasureRequest, boolean erasable, List<String> blockers) {}

    record FactoryRow(String id, String name, String type, Boolean isActive, LocalDateTime createdAt) {}

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

    @Autowired   // 另有一个包内构造器给测试用; 不标的话 Spring 找不到该用哪个 (2026-10-26 Fresh DB 启动即失败)
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
            ErasureRequestState request = ledger.findActiveErasureRequest(factoryId)
                    .map(r -> new ErasureRequestState(r.id(), r.notBefore(), !now().isBefore(r.notBefore())))
                    .orElse(null);
            List<String> blockers = blockers(factoryId, dbs, attachments, request);
            Preview preview = new Preview(id, factoryId, factory.name(), factory.type(), now(),
                    dbs, attachments, request, blockers.isEmpty(), blockers);
            ledger.finish(id, FactoryDataRequestLedger.Status.SUCCEEDED, toJson(summaryOf(preview)), null, null, null);
            return preview;
        } catch (RuntimeException e) {
            ledger.finish(id, FactoryDataRequestLedger.Status.FAILED, null, null, null, e.getMessage());
            throw e;
        }
    }

    private DatabasePreview previewOf(Connection conn, Database db, String factoryId) throws SQLException {
        FactoryDataScope scope = FactoryDataScopeResolver.resolveVisible(conn, db, schema, factoryId);
        List<TableRow> rows = new ArrayList<>();
        long training = 0;
        for (TableScope t : scope.nonEmpty()) {
            long retained = t.category() == Category.TRAINING ? countRetained(conn, t, factoryId) : 0;
            training += retained;
            rows.add(new TableRow(t.table(), t.category(), t.depth(), t.rows(), t.category().exported(),
                    t.category().erased(), retained));
        }
        long erased = rows.stream().filter(TableRow::erased).mapToLong(r -> r.rows() - r.retainedOnErase()).sum();
        long audit = rows.stream().filter(r -> r.category() == Category.AUDIT).mapToLong(TableRow::rows).sum();
        List<ForeignHit> hits = scope.foreignHits();
        List<TableScope> toErase = scope.nonEmpty().stream().filter(t -> t.category().erased()).toList();
        return new DatabasePreview(db, schema, scope.closurePasses(), scope.totalRows(), erased, audit, training,
                rows, hits.size(), hits.subList(0, Math.min(hits.size(), MAX_LISTED_FOREIGN_HITS)),
                scope.nulledReferences(), scope.suspectTenantColumns(),
                FactoryErasureService.missingPrivileges(conn, schema, toErase), copiesOutside(conn, schema, factoryId),
                copiesOutside(conn, schema, factoryId, false), unreadableOutside(conn, schema, true),
                unreadableOutside(conn, schema, false));
    }

    private long countRetained(Connection conn, TableScope t, String factoryId) throws SQLException {
        FactoryDataScopeResolver.Where where = FactoryDataScopeResolver.ownedRows(conn, t, factoryId);
        try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM " + FactoryDataScopeResolver.q(schema)
                + "." + FactoryDataScopeResolver.q(t.table()) + " WHERE " + where.sql()
                + " AND (" + FactoryDataCatalog.trainingRetainPredicate(t.table()) + ")")) {
            FactoryDataScopeResolver.bind(ps, where.params());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * {@code schema} 以外的 schema 里带 factory_id 的表 (删前备份、临时副本): 计数, 删除时一并清 (口径 Q6)。
     * 本功能自己的回执 / 留存表 (RECEIPT) 与迁移台账不算副本。
     */
    static List<CopyCount> copiesOutside(Connection conn, String schema, String factoryId) throws SQLException {
        return copiesOutside(conn, schema, factoryId, true);
    }

    /**
     * @param copiesOnly true = 只数名字像副本的 schema ({@link FactoryDataCatalog#isCopySchema}); false = 只数其余的
     *                   (不像副本、却有该厂行的 schema: 预演报出来, 删除拒绝, 由人判断)
     */
    static List<CopyCount> copiesOutside(Connection conn, String schema, String factoryId, boolean copiesOnly)
            throws SQLException {
        List<CopyCount> out = new ArrayList<>();
        for (String[] t : tablesOutside(conn, schema, copiesOnly, true)) {
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

    /**
     * {@code schema} 以外、带 factory_id、应用账号却读不了的表 (prod 上 postgres 建的删前备份 schema 没给 USAGE)。
     * 数不了该厂有没有行, 只列名字: 名字带日期的副本随 30 天保留期过期; 没日期的 / 不像副本的由删除拒绝。
     *
     * <p>2026-10-27: 之前直接去 count, prod 上 F006 预演当场 409「permission denied for schema backup_f006_20261024」。
     */
    static List<String> unreadableOutside(Connection conn, String schema, boolean copiesOnly) throws SQLException {
        return tablesOutside(conn, schema, copiesOnly, false).stream().map(t -> t[0] + "." + t[1]).toList();
    }

    private static List<String[]> tablesOutside(Connection conn, String schema, boolean copiesOnly, boolean readable)
            throws SQLException {
        List<String[]> tables = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT n.nspname, c.relname FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid "
                        + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE a.attname = 'factory_id' AND NOT a.attisdropped AND c.relkind IN ('r','p') "
                        + "AND NOT c.relispartition AND n.nspname <> ? "
                        + "AND n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg\\_%' "
                        // 表的 SELECT 权限不看 schema 的 USAGE, 两个都有才读得了
                        + "AND (has_schema_privilege(n.oid, 'USAGE') AND has_table_privilege(c.oid, 'SELECT')) = ? "
                        + "ORDER BY 1, 2")) {
            ps.setString(1, schema);
            ps.setBoolean(2, readable);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Category c = FactoryDataCatalog.categorize(rs.getString(2));
                    if (c == Category.RECEIPT || c == Category.LEDGER) continue;
                    if (FactoryDataCatalog.isCopySchema(rs.getString(1)) != copiesOnly) continue;
                    tables.add(new String[]{rs.getString(1), rs.getString(2)});
                }
            }
        }
        return tables;
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

    private static List<String> blockers(String factoryId, List<DatabasePreview> dbs, Attachments attachments,
                                         ErasureRequestState request) {
        List<String> out = new ArrayList<>();
        if (PROTECTED_FACTORIES.contains(factoryId)) out.add("受保护租户 (团队演示 / 真实客户), 不允许删除");
        if (request == null) out.add("没有有效的删除申请 (先导出交付, 再登记申请; 冷静期 7 天)");
        else if (!request.coolingOffPassed()) out.add("冷静期未满, 最早 " + request.notBefore() + " 才能执行删除");
        for (DatabasePreview db : dbs) {
            if (db.foreignHitCount() > 0) {
                out.add(db.database() + " 库有 " + db.foreignHitCount() + " 行跨租户命中, 删除前必须查清");
            }
            if (!db.missingDeletePrivileges().isEmpty()) {
                out.add(db.database() + " 库账号对这些表没有删除权限: " + db.missingDeletePrivileges());
            }
            if (!db.unregisteredSchemas().isEmpty()) {
                out.add(db.database() + " 库有不像副本的 schema 也存着该厂的行, 先查清是什么: " + db.unregisteredSchemas());
            }
            if (!db.unreadableUnregistered().isEmpty()) {
                out.add(db.database() + " 库有不像副本的 schema 账号读不了, 查不清有没有该厂的行: " + db.unreadableUnregistered());
            }
            List<String> undated = FactoryErasureService.undated(db.unreadableCopies());
            if (!undated.isEmpty()) {
                out.add(db.database() + " 库这些副本账号读不了、名字里又没有日期 (30 天清理认不出): " + undated);
            }
        }
        if (!"AVAILABLE".equals(attachments.status())) out.add("附件列举失败: " + attachments.error());
        return out;
    }

    /** 删除时与预演逐表比对的计数: 只算 strict 类别 (审计 / 回执 / 台账会因操作本身而变)。 */
    static Map<String, Long> strictCounts(FactoryDataScope scope) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (TableScope t : scope.nonEmpty()) if (t.category().strict()) m.put(t.table(), t.rows());
        return m;
    }

    // ------------------------------------------------------------------- export

    /** 开始导出: 先写回执, 返回编号 (控制器把它放进响应头, 再调用 {@link #writeExport})。 */
    public long startExport(AuthenticatedActor actor, String factoryId) {
        return startExport(actor, factoryId, MANUAL_TRIGGER);
    }

    /**
     * @param triggerSource 谁触发的 (写进回执): 接口调用是 {@value #MANUAL_TRIGGER}; 定时任务传自己的名字,
     *                      如 {@code BACKUP_SCHEDULER}。只允许大写字母、数字、下划线, 最长 32。
     */
    public long startExport(AuthenticatedActor actor, String factoryId, String triggerSource) {
        requireOperator(actor);
        if (triggerSource == null || !TRIGGER_FORMAT.matcher(triggerSource).matches()) {
            throw new BusinessException(400, "triggerSource 只能是大写字母、数字、下划线, 最长 32");
        }
        FactoryRow factory = requireFactory(factoryId);
        return ledger.start(FactoryDataRequestLedger.Type.EXPORT, factoryId, factory.name(),
                actor.type().name(), actor.id(), actor.role(), null, null, triggerSource);
    }

    public static final String MANUAL_TRIGGER = "MANUAL";
    private static final java.util.regex.Pattern TRIGGER_FORMAT = java.util.regex.Pattern.compile("^[A-Z0-9_]{1,32}$");

    /**
     * 把整厂数据写成 ZIP 流。出错时回执标 FAILED 并抛出 —— 流已经开始写的话, 包里没有 manifest.json,
     * 收件人据此判断它不完整。
     */
    public void writeExport(long requestId, AuthenticatedActor actor, String factoryId, AttachmentMode mode,
                            Set<Format> formats, OutputStream rawOut) throws IOException {
        requireOperator(actor);
        if (formats == null || formats.isEmpty()) throw new BusinessException(400, "至少选择一种导出格式 (CSV / XLSX / JSON)");
        FactoryDataRequestLedger.Row started = ledger.find(requestId).orElse(null);
        if (started == null || started.type() != FactoryDataRequestLedger.Type.EXPORT
                || started.status() != FactoryDataRequestLedger.Status.RUNNING || !factoryId.equals(started.factoryId())) {
            throw new BusinessException(409, "requestId 不是该厂一次进行中的导出 (先调用 startExport)")
                .withHint("先调用 startExport 拿到回执编号, 再用同一个编号写出导出包");
        }
        FactoryRow factory = requireFactory(factoryId);
        Set<Format> fmt = EnumSet.copyOf(formats);
        MessageDigest whole = FactoryDataPackageWriter.sha256();
        CountingOut counted = new CountingOut(rawOut);
        DigestOutputStream out = new DigestOutputStream(counted, whole);
        try {
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("format", "cretas-factory-export/1");
            manifest.put("factoryId", factoryId);
            manifest.put("factoryName", factory.name());
            manifest.put("generatedAt", now().toString());
            manifest.put("requestId", requestId);
            manifest.put("operator", Map.of("type", actor.type().name(), "id", actor.id()));
            manifest.put("fileFormats", fmt.stream().map(Enum::name).toList());
            FactoryDataPackageWriter writer = new FactoryDataPackageWriter(out);
            List<Map<String, Object>> dbManifests = new ArrayList<>();
            for (Database db : Database.values()) {
                dbManifests.add(withSnapshot(db, conn -> exportDatabase(conn, db, factoryId, fmt, writer)));
            }
            manifest.put("databases", dbManifests);
            manifest.put("attachments", exportAttachments(factoryId, mode, writer));
            writer.writeText("README.txt", readme(factoryId, factory.name(), mode, fmt));
            manifest.put("files", writer.entries());
            writer.writeManifest(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            writer.close();
            out.flush();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("fileFormats", manifest.get("fileFormats"));
            summary.put("databases", dbManifests.stream().map(m -> {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("database", m.get("database"));
                d.put("rows", m.get("rows"));
                Map<String, Object> tables = new LinkedHashMap<>();
                for (Object t : (List<?>) m.get("tables")) {
                    Map<?, ?> tm = (Map<?, ?>) t;
                    tables.put(String.valueOf(tm.get("table")), tm.get("rows"));
                }
                d.put("tables", tables);
                return d;
            }).toList());
            summary.put("attachments", manifest.get("attachments"));
            ledger.finish(requestId, FactoryDataRequestLedger.Status.SUCCEEDED, toJson(summary),
                    HexFormat.of().formatHex(whole.digest()), counted.count, null);
        } catch (IOException | RuntimeException e) {
            log.error("factory-data export failed requestId={} factoryId={}", requestId, factoryId, e);
            ledger.finish(requestId, FactoryDataRequestLedger.Status.FAILED, null, null, counted.count, e.toString());
            throw e;
        }
    }

    private Map<String, Object> exportDatabase(Connection conn, Database db, String factoryId, Set<Format> formats,
                                               FactoryDataPackageWriter writer) throws SQLException {
        FactoryDataScope scope = FactoryDataScopeResolver.resolveVisible(conn, db, schema, factoryId);
        String dir = db.name().toLowerCase();
        List<Map<String, Object>> tables = new ArrayList<>();
        List<Map<String, Object>> notExported = new ArrayList<>();
        long rows = 0;
        Map<String, Object> m = new LinkedHashMap<>();
        try (FactoryDataXlsxBook book = formats.contains(Format.XLSX) ? new FactoryDataXlsxBook() : null) {
            for (TableScope ts : scope.nonEmpty()) {
                if (!ts.category().exported()) {
                    notExported.add(Map.of("table", ts.table(), "category", ts.category().name(), "rows", ts.rows()));
                    continue;
                }
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("table", ts.table());
                t.put("category", ts.category().name());
                t.put("rows", ts.rows());
                List<Map<String, Object>> files = new ArrayList<>();
                if (formats.contains(Format.CSV)) {
                    FactoryDataPackageWriter.Entry e = writer.writeTable(conn, dir + "/csv", schema, ts, factoryId);
                    requireRows(db, ts, e.rows());
                    files.add(Map.of("path", e.path(), "sha256", e.sha256()));
                }
                if (formats.contains(Format.JSON)) {
                    FactoryDataPackageWriter.Entry e = writer.writeTableJson(conn, dir + "/json", schema, ts, factoryId);
                    requireRows(db, ts, e.rows());
                    files.add(Map.of("path", e.path(), "sha256", e.sha256()));
                }
                if (book != null) requireRows(db, ts, writer.addToBook(conn, book, schema, ts, factoryId));
                t.put("files", files);
                tables.add(t);
                rows += ts.rows();
            }
            if (book != null) {
                FactoryDataPackageWriter.Entry e = writer.writeBook(dir + "/" + dir + ".xlsx", book);
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("path", e.path());
                x.put("sha256", e.sha256());
                x.put("sheets", book.sheets());
                x.put("truncatedCells", book.truncatedCells());
                x.put("limits", Map.of("rowsPerSheet", FactoryDataXlsxBook.MAX_DATA_ROWS,
                        "sheetNameChars", FactoryDataXlsxBook.MAX_SHEET_NAME, "cellChars", FactoryDataXlsxBook.MAX_CELL_CHARS));
                m.put("xlsx", x);
            }
        } catch (IOException e) {
            throw new IllegalStateException(db + " 库写出失败: " + e.getMessage(), e);
        }
        m.put("database", db.name());
        m.put("rows", rows);
        m.put("tables", tables);
        m.put("notExported", notExported);
        m.put("foreignHitCount", scope.foreignHits().size());
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String k : List.of("database", "rows", "tables", "xlsx", "notExported", "foreignHitCount")) {
            if (m.containsKey(k)) ordered.put(k, m.get(k));
        }
        return ordered;
    }

    private static void requireRows(Database db, TableScope ts, long written) {
        if (written != ts.rows()) {
            throw new IllegalStateException("行数不一致 " + db + "." + ts.table() + ": 计数 " + ts.rows() + " / 写出 " + written);
        }
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

    private static String readme(String factoryId, String name, AttachmentMode mode, Set<Format> formats) {
        List<String> lines = new ArrayList<>(List.of(
                "整厂数据导出包  " + name + " (" + factoryId + ")",
                "",
                "manifest.json   清单: 每张表的行数与每个文件的 SHA256, 附件数量。没有这个文件说明包不完整, 请重新导出。",
                "cretas/         业务主库; smartbi/  经营分析库。"));
        if (formats.contains(Format.CSV)) lines.add("  csv/           每张表一个 CSV (UTF-8 带 BOM, Excel 可直接打开)。");
        if (formats.contains(Format.JSON)) lines.add("  json/          每张表一个 JSON 数组, 值都是文本或 null。");
        if (formats.contains(Format.XLSX)) {
            lines.add("  <库名>.xlsx    每个库一个 Excel, 每张表一个工作表; 第一个工作表「目录」列出工作表与表名的对应。");
            lines.add("                 超过 104 万行的表拆成多个工作表, 超过 31 字的表名缩短, 超长单元格截断 —— 见 manifest。");
        }
        lines.add("attachments/    附件。index.csv 是附件清单; " + (mode == AttachmentMode.INCLUDE
                ? "原文件按对象键存放。" : "本包未含原文件 (" + mode + ")。"));
        lines.addAll(List.of(
                "",
                "说明:",
                "1. 密码、令牌、密钥类字段的值已替换为 " + FactoryDataCatalog.REDACTED + "。",
                "2. CSV 里以 = + - @ 开头的文字值前加了一个单引号, 防止 Excel 把它当公式执行。",
                "3. 根据《食品安全法》, 进货查验与出厂检验记录须保存至产品保质期满后六个月 (没有明确保质期的不少于二年),",
                "   请工厂自行妥善保存本包。",
                ""));
        return String.join("\r\n", lines);
    }

    // ------------------------------------------------------- shared with erasure

    public void requireOperator(AuthenticatedActor actor) {
        if (actor == null || !actor.isPlatformAdmin() || !"super_admin".equalsIgnoreCase(actor.role())
                || !operatorAdminIds.contains(actor.id())) {
            throw new BusinessException(403, "整厂数据导出/删除只允许登记过的平台超级管理员操作");
        }
    }

    FactoryRow requireFactory(String factoryId) {
        if (!FactoryDataCatalog.validFactoryId(factoryId)) throw new BusinessException(400, "工厂编号格式不合法");
        try (Connection conn = cretasDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id, name, type, is_active, created_at FROM "
                     + FactoryDataScopeResolver.q(schema) + ".factories WHERE id = ?")) {
            ps.setString(1, factoryId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new BusinessException(404, "工厂不存在: " + factoryId);
                boolean active = rs.getBoolean(4);
                Boolean isActive = rs.wasNull() ? null : active;
                Timestamp created = rs.getTimestamp(5);
                return new FactoryRow(rs.getString(1), rs.getString(2), rs.getString(3), isActive,
                        created == null ? null : created.toLocalDateTime());
            } finally {
                if (!conn.getAutoCommit()) conn.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取工厂失败: " + e.getMessage(), e);
        }
    }

    /** 停用 / 恢复工厂 (登录与在线会话由 is_active 闸拦截)。 */
    void setFactoryActive(String factoryId, Boolean active) {
        try (Connection conn = cretasDataSource.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("UPDATE " + FactoryDataScopeResolver.q(schema)
                    + ".factories SET is_active = ? WHERE id = ?")) {
                if (active == null) ps.setNull(1, java.sql.Types.BOOLEAN); else ps.setBoolean(1, active);
                ps.setString(2, factoryId);
                if (ps.executeUpdate() != 1) throw new IllegalStateException("工厂不存在: " + factoryId);
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("更新工厂状态失败: " + e.getMessage(), e);
        }
    }

    /** SmartBI 库的审计行写进主库留存区, 单独提交 (先于 SmartBI 的删除; 幂等)。 */
    long writeRetentionCommitted(long erasureId, String factoryId, List<FactoryAuditRetention.Row> rows) {
        try (Connection conn = cretasDataSource.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int n = FactoryAuditRetention.write(conn, erasureId, factoryId, rows, now());
                conn.commit();
                return n;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("审计留存写入失败: " + e.getMessage(), e);
        }
    }

    DataSource dataSource(Database db) {
        DataSource ds = db == Database.CRETAS ? cretasDataSource : smartbiDataSource.getIfAvailable();
        if (ds == null) throw new BusinessException(503, "SmartBI 数据库未启用, 无法导出/预演/删除该库");
        return ds;
    }

    FactoryObjectStore objectStore() { return objectStore; }

    /**
     * 同一个工厂的删除申请 / 撤销 / 删除串行执行 (PostgreSQL 会话级咨询锁, 拿不到立即 409, ⛔ 不排队等)。
     * 锁连接用自动提交: 主库连接池有 idle_in_transaction_session_timeout=30s, 事务里干等会被服务器断开、锁跟着丢。
     */
    <T> T withFactoryLock(String factoryId, java.util.function.Supplier<T> work) {
        String key = "factory-data:" + factoryId;
        try (Connection conn = cretasDataSource.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(true);
            try {
                boolean locked;
                try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        locked = rs.getBoolean(1);
                    }
                }
                if (!locked) throw new BusinessException(409, "该厂正有另一个删除申请 / 撤销 / 删除在进行, 请稍后再试")
                .withHint("等正在进行的那个操作结束后再试, 进行中的回执可在 GET /requests 查看");
                try {
                    return work.get();
                } finally {
                    unlock(conn, key);
                }
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("工厂操作锁失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解锁失败时 ⛔ 不让这条连接带着会话锁回到连接池 (那样该厂之后的操作都会 409): 直接 abort, 连接池会丢弃它。
     * 也 ⛔ 不让解锁的错误盖掉业务本身的错误: 只记日志。
     */
    private static void unlock(Connection conn, String key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getBoolean(1)) return;
            }
            log.error("factory-data lock {} was not held at unlock time", key);
        } catch (SQLException e) {
            log.error("factory-data unlock failed for {}, aborting the connection", key, e);
        }
        try {
            conn.abort(Runnable::run);
        } catch (SQLException e) {
            log.error("factory-data: abort after failed unlock also failed", e);
        }
    }

    /** 删除工厂主记录 (所有库复核通过之后的最后一步)。必须恰好删掉 1 行。 */
    void deleteFactoryRoot(String factoryId) {
        try (Connection conn = cretasDataSource.getConnection()) {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + FactoryDataScopeResolver.q(schema)
                    + ".factories WHERE id = ?")) {
                ps.setString(1, factoryId);
                int n = ps.executeUpdate();
                if (n != 1) throw new IllegalStateException("工厂主记录删除 " + n + " 行 (应为 1)");
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("工厂主记录删除失败: " + e.getMessage(), e);
        }
    }

    /** 主库里全部工厂编号: SmartBI 阴性对照逐个工厂设置行级安全变量去数。 */
    Set<String> knownFactoryIds() {
        try (Connection conn = cretasDataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM " + FactoryDataScopeResolver.q(schema) + ".factories")) {
            Set<String> out = new java.util.TreeSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            } finally {
                if (!conn.getAutoCommit()) conn.rollback();
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("读取工厂列表失败: " + e.getMessage(), e);
        }
    }

    FactoryDataRequestLedger ledger() { return ledger; }

    String schema() { return schema; }

    LocalDateTime now() { return LocalDateTime.now(clock); }

    // ------------------------------------------------------------------ helpers

    interface SnapshotWork<T> { T run(Connection conn) throws SQLException; }

    /** 删除前的只读预检也用同一个快照写法。 */
    <T> T snapshot(Database db, SnapshotWork<T> work) {
        return withSnapshot(db, work);
    }

    /** 在一个 REPEATABLE READ 只读事务里做完, 最后回滚 (什么都不写)。 */
    private <T> T withSnapshot(Database db, SnapshotWork<T> work) {
        try (Connection conn = dataSource(db).getConnection()) {
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            conn.setReadOnly(true);
            try {
                // 主库连接池设了 idle_in_transaction_session_timeout=30s: 导出边读边往 HTTP 客户端写,
                // 客户端慢时这个只读事务会「空闲」超过 30 秒被服务器杀掉。本事务内放宽到 15 分钟。
                try (PreparedStatement ps = conn.prepareStatement("SET LOCAL idle_in_transaction_session_timeout = '15min'")) {
                    ps.execute();
                }
                // SmartBI 行级安全的租户变量由 FactoryDataScopeResolver.resolveVisible 在本事务内设置
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
        m.put("erasureRequestId", p.erasureRequest() == null ? null : p.erasureRequest().requestId());
        m.put("databases", p.databases().stream().map(d -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("database", d.database().name());
            x.put("rowsInScope", d.rowsInScope());
            x.put("rowsToErase", d.rowsToErase());
            x.put("foreignHitCount", d.foreignHitCount());
            x.put("unreadableCopies", d.unreadableCopies());
            x.put("strictTables", d.tables().stream().filter(t -> t.category().strict())
                    .collect(Collectors.toMap(TableRow::table, TableRow::rows, (a, b) -> a, LinkedHashMap::new)));
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
