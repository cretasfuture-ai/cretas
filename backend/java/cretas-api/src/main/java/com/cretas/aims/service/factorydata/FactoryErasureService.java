package com.cretas.aims.service.factorydata;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.factorydata.FactoryDataCatalog.Category;
import com.cretas.aims.service.factorydata.FactoryDataCatalog.Database;
import com.cretas.aims.service.factorydata.FactoryDataRequestLedger.Row;
import com.cretas.aims.service.factorydata.FactoryDataRequestLedger.Status;
import com.cretas.aims.service.factorydata.FactoryDataRequestLedger.Type;
import com.cretas.aims.service.factorydata.FactoryDataScope.TableScope;
import com.cretas.aims.service.factorydata.FactoryObjectStore.StoredObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 整厂数据彻底删除 (手册 docs/security/factory-data-export-and-erasure-runbook.md §6; 口径 Q1–Q6)。
 *
 * <p>三步, 每步一个接口:
 * <ol>
 *   <li>{@link #request}: 登记删除申请 (必须先有一次成功的导出), 当场停用工厂 (登录与在线会话都被
 *       「当前服务已关闭」闸拦住); 冷静期 7 天, 期间可 {@link #cancel} 撤销并恢复。</li>
 *   <li>预演 ({@link FactoryDataService#preview}): 冷静期内或期满后都能跑, 只计数; 计数给 Steve 看过才执行。</li>
 *   <li>{@link #erase}: 冷静期满、带着申请编号和预演编号、再输一遍工厂编号。当场重算范围, 与预演不一致即拒绝。
 *       顺序 SmartBI 库 → 附件 → 主库 (工厂主记录最后删), 每个库一个事务, 事务内做阴性对照
 *       (其他工厂的行数一行不少), 不通过整体回滚。</li>
 * </ol>
 *
 * <p>演练: 编号以 {@link #DRILL_PREFIX} 开头、且 3 天内新建的一次性测试工厂, 申请时可带 {@code drill=true}
 * 免冷静期 (回执写明)。其余工厂一律 7 天。
 */
@Slf4j
@Service
public class FactoryErasureService {

    public static final int COOLING_OFF_DAYS = 7;
    public static final String DRILL_PREFIX = "ERASETEST_";
    static final int DRILL_FACTORY_MAX_AGE_DAYS = 3;
    static final int PREVIEW_MAX_AGE_HOURS = 24;
    /** 删除申请要带的导出, 最多是这么多天之前的。 */
    static final int EXPORT_MAX_AGE_DAYS = 30;
    static final int MAX_DELETE_PASSES = 30;
    static final String FK_VIOLATION = "23503";
    /** SmartBI 的 LLM 外发审计表是只追加的 (smartbi_user 没有 DELETE), 经专用函数删本厂行。 */
    static final String LLM_EGRESS_AUDIT = "smart_bi_llm_egress_audit";
    static final String LLM_EGRESS_ERASE_FUNCTION = "factory_erase_llm_egress_audit";
    /** SmartBI 定义者函数: 库里出现过的全部工厂编号 (迁移 V20261101_19)。 */
    static final String TENANT_IDS_FUNCTION = "factory_erasure_tenant_ids";

    public record ErasureRequestBody(String confirmFactoryId, Long exportRequestId, String applicant,
                                     String applicationEvidence, String verifiedBy, Boolean drill) {}

    public record EraseBody(String confirmFactoryId, Long erasureRequestId, Long previewId) {}

    public record RequestView(long requestId, String factoryId, LocalDateTime notBefore, boolean drill,
                              boolean factoryDisabled) {}

    public record TableReceipt(String table, Category category, long before, long after) {}

    public record DatabaseReceipt(Database database, List<TableReceipt> tables, long rowsDeleted,
                                  long auditRowsRetained, long trainingSamplesRetained, List<String> copies,
                                  int otherFactoriesChecked) {}

    public record Receipt(long erasureId, String factoryId, String factoryName, DatabaseReceipt smartbi,
                          long attachmentsDeleted, DatabaseReceipt cretas, LocalDateTime completedAt,
                          LocalDateTime localBackupsExpireBy, LocalDateTime offsiteBackupsExpireBy) {}

    private final FactoryDataService data;
    private final FactoryDataRequestLedger ledger;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public FactoryErasureService(FactoryDataService data, FactoryDataRequestLedger ledger) {
        this.data = data;
        this.ledger = ledger;
    }

    // ------------------------------------------------------------------ request

    public RequestView request(AuthenticatedActor actor, String factoryId, ErasureRequestBody body) {
        data.requireOperator(actor);
        return data.withFactoryLock(factoryId, () -> requestLocked(actor, factoryId, body));
    }

    private RequestView requestLocked(AuthenticatedActor actor, String factoryId, ErasureRequestBody body) {
        requireConfirmed(factoryId, body == null ? null : body.confirmFactoryId());
        requireNotProtected(factoryId);
        FactoryDataService.FactoryRow factory = data.requireFactory(factoryId);
        if (body.applicant() == null || body.applicant().isBlank()
                || body.applicationEvidence() == null || body.applicationEvidence().isBlank()
                || body.verifiedBy() == null || body.verifiedBy().isBlank()) {
            throw new BusinessException(400, "必须填写申请人、申请留底 (微信/邮件截图文件名) 与核对人");
        }
        Row export = body.exportRequestId() == null ? null : ledger.find(body.exportRequestId()).orElse(null);
        if (export == null || export.type() != Type.EXPORT || export.status() != Status.SUCCEEDED
                || !factoryId.equals(export.factoryId())
                || !FactoryDataService.MANUAL_TRIGGER.equals(export.triggerSource())) {
            throw new BusinessException(409, "删除前必须先完成一次该厂的导出并交付工厂 (exportRequestId 无效)")
                .withHint("先导出 (attachments 用 INCLUDE 或 INDEX_ONLY) 并交付工厂, 再带这次导出的回执编号申请");
        }
        if (export.createdAt().isBefore(data.now().minusDays(EXPORT_MAX_AGE_DAYS))) {
            throw new BusinessException(409, "导出已超过 " + EXPORT_MAX_AGE_DAYS + " 天, 请重新导出交付后再申请")
                .withHint("重新导出并交付工厂后, 用新的回执编号申请");
        }
        if (readString(export.summaryJson(), "/attachments/mode", "").equals(FactoryDataService.AttachmentMode.SKIP.name())) {
            throw new BusinessException(409, "这次导出跳过了附件 (attachments=SKIP), 不能作为删除前的交付，请用 INCLUDE 或 INDEX_ONLY 重新导出")
                .withHint("用 attachments=INCLUDE 或 INDEX_ONLY 重新导出");
        }
        if (ledger.findActiveErasureRequest(factoryId).isPresent()) {
            throw new BusinessException(409, "该厂已有未完成的删除申请")
                .withHint("在 GET /requests 查看旧申请, 撤销后再申请或直接沿用它");
        }
        // 取整到秒: 库里的 timestamp 按微秒四舍五入, 不取整的话「当场可执行」的演练申请可能被存成比现在晚一点点
        LocalDateTime now = data.now().withNano(0);
        boolean drill = Boolean.TRUE.equals(body.drill());
        if (drill && !(factoryId.startsWith(DRILL_PREFIX) && factory.createdAt() != null
                && factory.createdAt().isAfter(now.minusDays(DRILL_FACTORY_MAX_AGE_DAYS)))) {
            throw new BusinessException(400, "只有 " + DRILL_PREFIX + " 开头、" + DRILL_FACTORY_MAX_AGE_DAYS
                    + " 天内新建的演练工厂可以免冷静期");
        }
        LocalDateTime notBefore = drill ? now : now.plusDays(COOLING_OFF_DAYS);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("applicant", body.applicant());
        summary.put("applicationEvidence", body.applicationEvidence());
        summary.put("verifiedBy", body.verifiedBy());
        summary.put("exportRequestId", body.exportRequestId());
        summary.put("drill", drill);
        summary.put("previousIsActive", factory.isActive());
        long id = ledger.start(Type.ERASURE_REQUEST, factoryId, factory.name(), actor.type().name(), actor.id(),
                actor.role(), body.exportRequestId(), notBefore);
        try {
            data.setFactoryActive(factoryId, false);
            ledger.finish(id, Status.SUCCEEDED, toJson(summary), null, null, null);
        } catch (RuntimeException e) {
            // 申请没登记成功就不能让工厂停在停用状态 (否则没有有效申请, 也撤销不了)
            try {
                data.setFactoryActive(factoryId, factory.isActive());
                ledger.finish(id, Status.FAILED, toJson(summary), null, null, e.getMessage());
            } catch (RuntimeException restore) {
                e.addSuppressed(restore);
            }
            throw e;
        }
        log.warn("factory-data erasure requested factoryId={} requestId={} notBefore={} drill={}",
                factoryId, id, notBefore, drill);
        return new RequestView(id, factoryId, notBefore, drill, true);
    }

    public RequestView cancel(AuthenticatedActor actor, String factoryId, long requestId) {
        data.requireOperator(actor);
        return data.withFactoryLock(factoryId, () -> cancelLocked(actor, factoryId, requestId));
    }

    private RequestView cancelLocked(AuthenticatedActor actor, String factoryId, long requestId) {
        Row request = activeRequest(factoryId, requestId);
        FactoryDataService.FactoryRow factory = data.requireFactory(factoryId);
        boolean previous = readBoolean(request.summaryJson(), "previousIsActive", true);
        long id = ledger.start(Type.ERASURE_CANCEL, factoryId, factory.name(), actor.type().name(), actor.id(),
                actor.role(), requestId, null);
        data.setFactoryActive(factoryId, previous);
        ledger.finish(id, Status.SUCCEEDED, toJson(Map.of("restoredIsActive", previous)), null, null, null);
        return new RequestView(requestId, factoryId, request.notBefore(), false, !previous);
    }

    // -------------------------------------------------------------------- erase

    public Receipt erase(AuthenticatedActor actor, String factoryId, EraseBody body) {
        data.requireOperator(actor);
        return data.withFactoryLock(factoryId, () -> eraseLocked(actor, factoryId, body));
    }

    private Receipt eraseLocked(AuthenticatedActor actor, String factoryId, EraseBody body) {
        requireConfirmed(factoryId, body == null ? null : body.confirmFactoryId());
        requireNotProtected(factoryId);
        FactoryDataService.FactoryRow factory = data.requireFactory(factoryId);
        Row request = activeRequest(factoryId, body.erasureRequestId());
        LocalDateTime now = data.now();
        if (now.isBefore(request.notBefore())) {
            throw new BusinessException(409, "冷静期未满, 最早 " + request.notBefore() + " 才能执行删除")
                .withHint("冷静期结束后重新预演, 再执行删除");
        }
        if (!Boolean.FALSE.equals(factory.isActive())) {
            throw new BusinessException(409, "工厂在冷静期内被重新启用了, 不能删除，请撤销这次申请, 需要时重新申请")
                .withHint("先撤销这次申请, 需要删除时重新申请");
        }
        Row preview = body.previewId() == null ? null : ledger.find(body.previewId()).orElse(null);
        if (preview == null || preview.type() != Type.PREVIEW || preview.status() != Status.SUCCEEDED
                || !factoryId.equals(preview.factoryId()) || preview.createdAt().isBefore(request.createdAt())
                || preview.createdAt().isBefore(now.minusHours(PREVIEW_MAX_AGE_HOURS))) {
            throw new BusinessException(409, "必须带一次本申请之后、" + PREVIEW_MAX_AGE_HOURS + " 小时内的预演编号 (previewId)")
                .withHint("先调用 GET /preview 拿到新的 previewId");
        }
        Map<Database, Map<String, Long>> expected = expectedFromPreview(preview.summaryJson());

        long id = ledger.start(Type.ERASURE, factoryId, factory.name(), actor.type().name(), actor.id(),
                actor.role(), request.id(), null);
        String stage = "PREFLIGHT";
        try {
            // 先在所有库上只读核对一遍 (跨租户命中 / 与预演一致 / 删除权限), 全部通过才开始删 ——
            // 否则 SmartBI 和附件删完了, 才在主库发现跨租户命中, 留下删了一半的工厂。
            for (Database db : Database.values()) {
                Map<String, Long> exp = expected.get(db);
                data.snapshot(db, conn -> {
                    FactoryDataScope scope = FactoryDataScopeResolver.resolveVisible(conn, db, data.schema(), factoryId);
                    if (!scope.foreignHits().isEmpty()) {
                        throw new BusinessException(409, db + " 库有 " + scope.foreignHits().size() + " 行跨租户命中, 停止删除")
                .withHint("按预演里的 foreignHits 把这些行的归属查清, 处理后重新预演");
                    }
                    compareWithPreview(db, scope, exp);
                    List<FactoryDataService.CopyCount> unregistered =
                            FactoryDataService.copiesOutside(conn, data.schema(), factoryId, false);
                    if (!unregistered.isEmpty()) {
                        throw new BusinessException(409, db + " 库有不像副本的 schema 也存着该厂的行, 先查清: " + unregistered)
                .withHint("查清这些 schema 的用途: 是副本就按副本命名规则改名, 不是就先迁走该厂的行");
                    }
                    List<String> stuck = undatedCopiesWithoutPrivilege(conn, data.schema(), factoryId);
                    if (!stuck.isEmpty()) {
                        throw new BusinessException(409, db + " 库这些副本里有该厂的行, 账号删不了、名字里又没有日期 (30 天清理认不出): "
                                + stuck)
                .withHint("给这些副本 schema 补上 YYYYMMDD 日期或删除权限, 让 30 天清理认得出它们");
                    }
                    if (db == Database.SMARTBI) smartbiTenants(conn, data.schema());   // 函数缺失 ⇒ 这里就失败
                    requireDeletePrivileges(conn, data.schema(),
                            scope.nonEmpty().stream().filter(t -> t.category().erased()).toList());
                    return null;
                });
            }
            stage = "SMARTBI";
            DatabaseReceipt smartbi = eraseDatabase(Database.SMARTBI, factoryId, expected.get(Database.SMARTBI), id);
            stage = "ATTACHMENTS";
            long attachments = eraseAttachments(factoryId);
            stage = "CRETAS";
            DatabaseReceipt cretas = eraseDatabase(Database.CRETAS, factoryId, expected.get(Database.CRETAS), id);
            // 三步都提交之后, 在新的快照里把两个库再算一遍: 事务内的复核看不见期间别的会话写进来的行
            stage = "VERIFY";
            for (Database db : Database.values()) {
                List<String> left = data.snapshot(db, conn -> {
                    List<String> l = new ArrayList<>(FactoryDataScopeResolver
                            .resolveVisible(conn, db, data.schema(), factoryId).nonEmpty().stream()
                            .filter(t -> t.category().erased() && t.category() != Category.FACTORY_ROOT)
                            .map(t -> t.table() + "(" + t.rows() + ")").toList());
                    FactoryDataService.copiesOutside(conn, data.schema(), factoryId).stream()
                            .filter(c -> canDelete(conn, c.table())).forEach(c -> l.add(c.table() + "(" + c.rows() + ")"));
                    return l;
                });
                if (!left.isEmpty()) throw new IllegalStateException(db + " 库删除提交后复核仍有本厂行: " + left);
            }
            if (!data.objectStore().list(factoryId + "/").isEmpty()) {
                throw new IllegalStateException("删除提交后复核: OSS 前缀 " + factoryId + "/ 下又出现了对象");
            }
            // 复核通过才删工厂主记录 (最后一步)
            stage = "ROOT";
            data.deleteFactoryRoot(factoryId);
            LocalDateTime done = data.now();
            Receipt receipt = new Receipt(id, factoryId, factory.name(), smartbi, attachments, cretas, done,
                    done.plusDays(7), done.plusDays(30));
            ledger.finish(id, Status.SUCCEEDED, toJson(receipt), null, null, null);
            log.warn("factory-data erasure done factoryId={} erasureId={} smartbiRows={} cretasRows={} attachments={}",
                    factoryId, id, smartbi.rowsDeleted(), cretas.rowsDeleted(), attachments);
            return receipt;
        } catch (RuntimeException e) {
            log.error("factory-data erasure failed factoryId={} erasureId={} stage={}", factoryId, id, stage, e);
            ledger.finish(id, Status.FAILED, toJson(Map.of("stage", stage)), null, null, stage + ": " + e.getMessage());
            throw e;
        }
    }

    private DatabaseReceipt eraseDatabase(Database db, String factoryId, Map<String, Long> expected, long erasureId) {
        DataSource ds = data.dataSource(db);
        String schema = data.schema();
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                DatabaseReceipt receipt = eraseInTransaction(conn, db, schema, factoryId, expected, erasureId);
                conn.commit();
                return receipt;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(db + " 库删除失败 (已回滚): " + e.getMessage(), e);
        }
    }

    private DatabaseReceipt eraseInTransaction(Connection conn, Database db, String schema, String factoryId,
                                               Map<String, Long> expected, long erasureId) throws SQLException {
        // SmartBI 有行级安全: resolveVisible 本厂行在 app.factory_id=本厂 时算、跨租户命中在为空时补算,
        // 返回时连接停在「本厂」, 正好满足 DELETE 策略 (factory_id = app.factory_id)
        Set<String> tenants = db == Database.SMARTBI ? smartbiTenants(conn, schema) : null;
        FactoryDataScope scope = FactoryDataScopeResolver.resolveVisible(conn, db, schema, factoryId);
        if (!scope.foreignHits().isEmpty()) {
            throw new BusinessException(409, db + " 库有 " + scope.foreignHits().size() + " 行跨租户命中, 停止删除")
                .withHint("按预演里的 foreignHits 把这些行的归属查清, 处理后重新预演");
        }
        compareWithPreview(db, scope, expected);
        Map<String, Long> othersBefore = others(FactoryDataScopeResolver.rowsPerFactory(conn, schema, tenants), factoryId);
        tenant(conn, db, factoryId);
        Map<String, Long> before = new LinkedHashMap<>();
        scope.nonEmpty().forEach(t -> before.put(t.table(), t.rows()));

        // 1) 审计 / 留痕挪进留存区 (主库; SmartBI 的审计行另开主库连接先提交, 幂等)
        long audit = 0;
        for (TableScope ts : scope.nonEmpty()) {
            if (ts.category() != Category.AUDIT) continue;
            List<FactoryAuditRetention.Row> rows = FactoryAuditRetention.read(conn, db, schema, ts, factoryId);
            audit += db == Database.CRETAS
                    ? FactoryAuditRetention.write(conn, erasureId, factoryId, rows, data.now())
                    : data.writeRetentionCommitted(erasureId, factoryId, rows);
        }

        // 2) 训练样本: 已脱敏的保留, 清空工厂编号 (口径 Q4); 其余随下面一起删
        long retained = 0;
        for (TableScope ts : scope.nonEmpty()) {
            if (ts.category() != Category.TRAINING) continue;
            String keep = FactoryDataCatalog.trainingRetainPredicate(ts.table());
            FactoryDataScopeResolver.Where where = FactoryDataScopeResolver.ownedRows(conn, ts, factoryId, "");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE " + q(schema) + "." + q(ts.table())
                    + " SET " + q(FactoryDataCatalog.FACTORY_ID) + " = NULL WHERE " + where.sql() + " AND (" + keep + ")")) {
                FactoryDataScopeResolver.bind(ps, where.params());
                retained += ps.executeUpdate();
            }
        }
        // 保留的样本已经不带本厂编号, 重算一次要删的范围
        FactoryDataScope target = retained > 0 ? FactoryDataScopeResolver.resolveVisible(conn, db, schema, factoryId) : scope;
        if (!target.foreignHits().isEmpty()) throw new IllegalStateException("重算范围出现跨租户命中");

        // 3) 多轮删除: 撞外键的留到下一轮 (不手写拓扑顺序)
        tenant(conn, db, factoryId);
        List<TableScope> pending = new ArrayList<>();
        // 工厂主记录不在这一步删: 留到所有库复核通过之后 (删了它, 复核失败时就再也预演 / 重试不了)
        for (TableScope ts : target.nonEmpty()) {
            if (ts.category().erased() && ts.category() != Category.FACTORY_ROOT) pending.add(ts);
        }
        requireDeletePrivileges(conn, schema, pending);
        long deleted = 0;
        for (int pass = 1; !pending.isEmpty(); pass++) {
            if (pass > MAX_DELETE_PASSES) {
                throw new IllegalStateException(MAX_DELETE_PASSES + " 轮后仍删不动: "
                        + pending.stream().map(TableScope::table).toList());
            }
            List<TableScope> blocked = new ArrayList<>();
            for (TableScope ts : pending) {
                Savepoint sp = conn.setSavepoint();
                try {
                    deleted += deleteOwned(conn, schema, ts, factoryId);
                    conn.releaseSavepoint(sp);
                } catch (SQLException e) {
                    conn.rollback(sp);
                    if (!FK_VIOLATION.equals(e.getSQLState())) throw e;
                    blocked.add(ts);
                }
            }
            if (blocked.size() == pending.size()) {
                // 一整轮没有任何一张表删得动, 再试也一样
                throw new IllegalStateException("外键互相阻塞, 删不动: " + blocked.stream().map(TableScope::table).toList());
            }
            pending = blocked;
        }

        // 4) 库内副本 (public 以外的 schema) 里该厂的行 (口径 Q6)
        List<String> copies = eraseCopies(conn, schema, factoryId);

        // 5) 复核: 本厂要删的类别全部为 0; 其他工厂一行不少
        FactoryDataScope after = FactoryDataScopeResolver.resolveVisible(conn, db, schema, factoryId);
        List<String> left = after.nonEmpty().stream()
                .filter(t -> t.category().erased() && t.category() != Category.FACTORY_ROOT)
                .map(t -> t.table() + "(" + t.rows() + ")").toList();
        if (!left.isEmpty()) throw new IllegalStateException(db + " 库删后仍有本厂行: " + left);
        Map<String, Long> othersAfter = others(FactoryDataScopeResolver.rowsPerFactory(conn, schema, tenants), factoryId);
        if (!othersAfter.equals(othersBefore)) {
            throw new IllegalStateException(db + " 库阴性对照失败: 其他工厂行数变了 " + othersBefore + " -> " + othersAfter);
        }
        List<TableReceipt> tables = new ArrayList<>();
        before.forEach((t, n) -> tables.add(new TableReceipt(t, FactoryDataCatalog.categorize(t), n,
                after.tables().containsKey(t) ? after.tables().get(t).rows() : 0)));
        return new DatabaseReceipt(db, tables, deleted, audit, retained, copies, othersBefore.size());
    }

    private long deleteOwned(Connection conn, String schema, TableScope ts, String factoryId) throws SQLException {
        if (LLM_EGRESS_AUDIT.equals(ts.table())) {
            // 函数要求本事务的 app.factory_erasure 也等于这个工厂: 只有整厂删除会设它
            try (PreparedStatement ps = conn.prepareStatement("SELECT set_config('app.factory_erasure', ?, true)")) {
                ps.setString(1, factoryId);
                ps.execute();
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT " + q(schema) + "." + LLM_EGRESS_ERASE_FUNCTION + "(?)")) {
                ps.setString(1, factoryId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
        FactoryDataScopeResolver.Where where = FactoryDataScopeResolver.ownedRows(conn, ts, factoryId, "");
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + q(schema) + "." + q(ts.table())
                + " WHERE " + where.sql())) {
            FactoryDataScopeResolver.bind(ps, where.params());
            return ps.executeUpdate();
        }
    }

    private static void requireDeletePrivileges(Connection conn, String schema, List<TableScope> tables)
            throws SQLException {
        List<String> missing = missingPrivileges(conn, schema, tables);
        if (!missing.isEmpty()) throw new BusinessException(409, "数据库账号对这些表没有删除权限: " + missing)
                .withHint("用迁移给数据库账号补上删除权限后重新预演");
    }

    /**
     * 当前连接账号删不了的表 (预演与删除共用)。LLM 外发审计表看专用删除函数能不能执行;
     * 训练样本表还要 UPDATE (保留的脱敏样本要清空工厂编号)。
     */
    static List<String> missingPrivileges(Connection conn, String schema, List<TableScope> tables) throws SQLException {
        List<String> missing = new ArrayList<>();
        for (TableScope ts : tables) {
            boolean egress = LLM_EGRESS_AUDIT.equals(ts.table());
            String sql;
            List<String> params = new ArrayList<>();
            if (egress) {
                String fn = q(schema) + "." + LLM_EGRESS_ERASE_FUNCTION + "(text)";
                sql = "SELECT to_regprocedure(?) IS NOT NULL AND has_function_privilege(to_regprocedure(?), 'EXECUTE')";
                params.add(fn);
                params.add(fn);
            } else {
                // ⚠️ has_table_privilege(x, 'DELETE, UPDATE') 是「有其一即真」, 所以两个权限分开判再 AND
                String table = q(schema) + "." + q(ts.table());
                sql = ts.category() == Category.TRAINING
                        ? "SELECT has_table_privilege(?, 'DELETE') AND has_table_privilege(?, 'UPDATE')"
                        : "SELECT has_table_privilege(?, 'DELETE')";
                params.add(table);
                if (ts.category() == Category.TRAINING) params.add(table);
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    if (!rs.getBoolean(1)) missing.add(ts.table());
                }
            }
        }
        return missing;
    }

    private static final Pattern NAME_DATE = Pattern.compile("(\\d{8})");

    /** public 以外 schema 的副本: 有权限的删掉该厂的行; 没权限的记下来, 随 30 天保留期过期 (backup-copy-retention.sh)。 */
    private static List<String> eraseCopies(Connection conn, String schema, String factoryId) throws SQLException {
        List<String> out = new ArrayList<>();
        for (FactoryDataService.CopyCount c : FactoryDataService.copiesOutside(conn, schema, factoryId)) {
            String[] st = c.table().split("\\.", 2);
            boolean can;
            try (PreparedStatement ps = conn.prepareStatement("SELECT has_table_privilege(?, 'DELETE')")) {
                ps.setString(1, q(st[0]) + "." + q(st[1]));
                try (ResultSet rs = ps.executeQuery()) { rs.next(); can = rs.getBoolean(1); }
            }
            if (!can) {
                Matcher m = NAME_DATE.matcher(st[0]);
                out.add(c.table() + " " + c.rows() + " 行: 无删除权限, 随副本保留期过期"
                        + (m.find() ? " (副本日期 " + m.group(1) + ", 30 天后清理)" : " (副本无日期, 需人工处理)"));
                continue;
            }
            Savepoint sp = conn.setSavepoint();
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + q(st[0]) + "." + q(st[1])
                    + " WHERE factory_id::text = ?")) {
                ps.setString(1, factoryId);
                out.add(c.table() + " 删除 " + ps.executeUpdate() + " 行");
                conn.releaseSavepoint(sp);
            } catch (SQLException e) {
                conn.rollback(sp);
                out.add(c.table() + " " + c.rows() + " 行: 删除失败 (" + e.getSQLState() + "), 随副本保留期过期");
            }
        }
        return out;
    }

    private long eraseAttachments(String factoryId) {
        FactoryObjectStore store = data.objectStore();
        List<StoredObject> objects = store.list(factoryId + "/");
        store.delete(objects);
        List<StoredObject> left = store.list(factoryId + "/");
        if (!left.isEmpty()) throw new IllegalStateException("附件删后仍有 " + left.size() + " 个对象");
        return objects.size();
    }

    // ------------------------------------------------------------------ helpers

    private Row activeRequest(String factoryId, Long requestId) {
        Row active = ledger.findActiveErasureRequest(factoryId)
                .orElseThrow(() -> new BusinessException(409, "该厂没有有效的删除申请")
                .withHint("先登记删除申请 (POST /erasure-requests)"));
        if (requestId == null || active.id() != requestId) {
            throw new BusinessException(409, "删除申请编号不是该厂当前有效的那一个 (当前: " + active.id() + ")")
                .withHint("用 GET /requests 里最新的有效申请编号");
        }
        return active;
    }

    private static void requireConfirmed(String factoryId, String confirm) {
        if (confirm == null || !confirm.equals(factoryId)) {
            throw new BusinessException(400, "confirmFactoryId 必须与路径里的工厂编号逐字一致");
        }
    }

    private static void requireNotProtected(String factoryId) {
        if (FactoryDataService.PROTECTED_FACTORIES.contains(factoryId)) {
            throw new BusinessException(403, "受保护租户 (团队演示 / 真实客户), 不允许删除: " + factoryId);
        }
    }

    private void compareWithPreview(Database db, FactoryDataScope scope, Map<String, Long> expected) {
        Map<String, Long> now = FactoryDataService.strictCounts(scope);
        if (!now.equals(expected == null ? Map.of() : expected)) {
            throw new BusinessException(409, db + " 库当前范围与预演不一致 (期间有写入?), 请重新预演: 预演 "
                    + expected + " / 当前 " + now)
                .withHint("重新调用 GET /preview, 用新的 previewId 执行删除");
        }
    }

    private Map<Database, Map<String, Long>> expectedFromPreview(String summaryJson) {
        Map<Database, Map<String, Long>> out = new LinkedHashMap<>();
        try {
            JsonNode root = json.readTree(summaryJson);
            for (JsonNode d : root.path("databases")) {
                Map<String, Long> t = new LinkedHashMap<>();
                d.path("strictTables").fields().forEachRemaining(e -> t.put(e.getKey(), e.getValue().asLong()));
                out.put(Database.valueOf(d.path("database").asText()), t);
            }
        } catch (Exception e) {
            throw new IllegalStateException("预演回执读不出来: " + e.getMessage(), e);
        }
        if (out.size() != Database.values().length) throw new BusinessException(409, "预演回执缺库, 请重新预演")
                .withHint("重新调用 GET /preview");
        return out;
    }

    private static void tenant(Connection conn, Database db, String value) throws SQLException {
        if (db == Database.SMARTBI) FactoryDataScopeResolver.setTenant(conn, value);
    }

    /**
     * SmartBI 阴性对照要逐个工厂去数 (严格行级安全的表只在 app.factory_id = 该厂时可见):
     * 主库的全部工厂 + SmartBI 宽松表里出现过的租户值。
     */
    private Set<String> smartbiTenants(Connection conn, String schema) throws SQLException {
        Set<String> t = new java.util.TreeSet<>(data.knownFactoryIds());
        // 严格行级安全的表在任何单一身份下都看不全, 工厂编号清单由定义者函数给 (只有编号, 不含行内容)
        try (PreparedStatement ps = conn.prepareStatement("SELECT DISTINCT x FROM " + q(schema) + "." + TENANT_IDS_FUNCTION + "() x");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) t.add(rs.getString(1));
        } catch (SQLException e) {
            throw new BusinessException(503, "SmartBI 库缺少函数 " + TENANT_IDS_FUNCTION
                    + "() (迁移 V20261101_19), 无法做完整的阴性对照: " + e.getMessage());
        }
        return t;
    }

    /** 副本里有该厂的行、账号删不了、schema 名字里又没有 YYYYMMDD (30 天清理认不出日期) ⇒ 永远清不掉。 */
    private static List<String> undatedCopiesWithoutPrivilege(Connection conn, String schema, String factoryId)
            throws SQLException {
        List<String> out = new ArrayList<>();
        for (FactoryDataService.CopyCount c : FactoryDataService.copiesOutside(conn, schema, factoryId)) {
            String copySchema = c.table().split("\\.", 2)[0];
            if (!canDelete(conn, c.table()) && !NAME_DATE.matcher(copySchema).find()) out.add(c.table());
        }
        return out;
    }

    private static boolean canDelete(Connection conn, String schemaDotTable) {
        String[] st = schemaDotTable.split("\\.", 2);
        try (PreparedStatement ps = conn.prepareStatement("SELECT has_table_privilege(?, 'DELETE')")) {
            ps.setString(1, q(st[0]) + "." + q(st[1]));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查删除权限失败: " + e.getMessage(), e);
        }
    }

    private static Map<String, Long> others(Map<String, Long> perFactory, String factoryId) {
        Map<String, Long> m = new LinkedHashMap<>(perFactory);
        m.remove(factoryId);
        return m;
    }

    private String readString(String summaryJson, String pointer, String fallback) {
        try {
            JsonNode n = json.readTree(summaryJson == null ? "{}" : summaryJson).at(pointer);
            return n.isMissingNode() || n.isNull() ? fallback : n.asText();
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean readBoolean(String summaryJson, String field, boolean fallback) {
        try {
            JsonNode n = json.readTree(summaryJson).path(field);
            return n.isBoolean() ? n.asBoolean() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String q(String identifier) {
        return FactoryDataScopeResolver.q(identifier);
    }
}
