#!/bin/bash
# PostgreSQL Daily Backup Script
# Cron: 0 3 * * * /www/wwwroot/cretas/scripts/backup-db.sh >> /www/wwwroot/cretas/logs/backup.log 2>&1
#
# 部署位置: 47 上 /www/wwwroot/cretas/scripts/backup-db.sh (本文件是它的源; 2026-10-26 把服务器上的
# 现行版本收回仓库 —— 此前仓库停在初始基线, 没有异地副本一段, 保留期也还是 2 天)。
#
# 2026-08-19 新增异地副本 (阿里云 OSS)
#   在此之前: 备份只存在于本机 /www/wwwroot/cretas/backups 且保留 2 天。
#   47 一旦丢失(实例故障/磁盘损坏), 数据库与它的备份【一起没有】。
#
#   为什么是 OSS 而不是 Cloudflare R2(pomelo-x 那套):
#     同机实测 10 MB 上传 —— R2 跑满 200 秒都没传完; OSS 内网 1 秒(10 MB/s), 快 200 倍以上,
#     且同区内网流量免费。R2 那条路对 47 的出网链路不可用, 不是配置问题。
#     (101 传 R2 是 131 KB/s 可用, 所以 pomelo-x 那边保持不变。)
#
#   ⛔ 只上传两个【生产】库。test 库不做异地容灾。
#   ⚠️ 异地那一段不能让整个脚本失败 —— 本地备份成功才是主线(脚本开了 set -e)。
#
# 2026-10-26 异地副本改为【只传加密件】(Steve 拍板: 服务器只放公钥, 私钥他离线保管)
#   - 用 age (/usr/local/bin/age, 官方 v1.2.1) 按 /etc/cretas-backup/age-recipient.txt 里的公钥加密成
#     *.sql.gz.age 再上传; 服务器上没有私钥, 拿到异地副本的人解不开。
#   - age 或公钥缺失 → 本次不上传 (⛔ 绝不退回去传明文), 日志写明原因。
#   - 本机副本仍是明文: 库本身就在这台机器上, 本机副本加密不增加保护, 反而让本机快速恢复也要私钥。
#   - 异地保留 30 → 7 天 (Steve 2026-10-26: 每天传、只留 7 天)。
#   - 解密 / 恢复步骤见 docs/security/backup-encryption-and-restore-runbook.md。

set -euo pipefail

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_DIR="${BACKUP_DIR:-/www/wwwroot/cretas/backups}"
RETENTION_DAYS="${RETENTION_DAYS:-7}"           # 2026-08-19: 2 -> 7 天
OSS_BUCKET="${OSS_BUCKET:-oss://cretas-db-backups}"
OSS_PREFIX="${OSS_PREFIX:-cretas-prod}"
OSS_RETENTION_DAYS="${OSS_RETENTION_DAYS:-7}"   # 2026-10-26: 30 -> 7 天
AGE_BIN="${AGE_BIN:-/usr/local/bin/age}"
AGE_RECIPIENT_FILE="${AGE_RECIPIENT_FILE:-/etc/cretas-backup/age-recipient.txt}"
LOG_PREFIX="[backup $(date '+%Y-%m-%d %H:%M:%S')]"

mkdir -p "$BACKUP_DIR"

echo "$LOG_PREFIX Starting database backup..."

# Main database (cretas_prod_db)
echo "$LOG_PREFIX Backing up cretas_prod_db..."
sudo -u postgres pg_dump cretas_prod_db 2>/dev/null | gzip > "$BACKUP_DIR/cretas_prod_db_$TIMESTAMP.sql.gz"
MAIN_SIZE=$(du -sh "$BACKUP_DIR/cretas_prod_db_$TIMESTAMP.sql.gz" | cut -f1)
echo "$LOG_PREFIX cretas_prod_db backup complete: $MAIN_SIZE"

# SmartBI database (smartbi_prod_db)
echo "$LOG_PREFIX Backing up smartbi_prod_db..."
sudo -u postgres pg_dump smartbi_prod_db 2>/dev/null | gzip > "$BACKUP_DIR/smartbi_prod_db_$TIMESTAMP.sql.gz"
SMARTBI_SIZE=$(du -sh "$BACKUP_DIR/smartbi_prod_db_$TIMESTAMP.sql.gz" | cut -f1)
echo "$LOG_PREFIX smartbi_prod_db backup complete: $SMARTBI_SIZE"

# Test databases (optional, smaller) —— 本地保留即可, 不上异地
if sudo -u postgres pg_dump cretas_db 2>/dev/null | gzip > "$BACKUP_DIR/cretas_db_test_$TIMESTAMP.sql.gz"; then
    echo "$LOG_PREFIX cretas_db (test) backup complete"
else
    echo "$LOG_PREFIX cretas_db (test) backup skipped (not available)"
    rm -f "$BACKUP_DIR/cretas_db_test_$TIMESTAMP.sql.gz"
fi

# --------------------------------------------------------- 异地副本 (阿里云 OSS, 只传加密件)
oss_sync() {
    if ! command -v ossutil64 >/dev/null 2>&1; then
        echo "$LOG_PREFIX OSS: SKIPPED (ossutil64 not found)"; return 0
    fi
    if [ ! -f /root/.ossutilconfig ]; then
        echo "$LOG_PREFIX OSS: SKIPPED (/root/.ossutilconfig not found)"; return 0
    fi
    # ⛔ 加密条件不满足就整段不传 —— 不能因为 age 没装好就把明文发到异地
    if [ ! -x "$AGE_BIN" ]; then
        echo "$LOG_PREFIX OSS: SKIPPED (age not found at $AGE_BIN, refusing to upload plaintext)"; return 0
    fi
    local recipient
    recipient=$(grep -oE '^age1[0-9a-z]+' "$AGE_RECIPIENT_FILE" 2>/dev/null | head -1 || true)
    if [ -z "$recipient" ]; then
        echo "$LOG_PREFIX OSS: SKIPPED (no age public key in $AGE_RECIPIENT_FILE, refusing to upload plaintext)"; return 0
    fi

    local uploaded=0 failed=0
    for db in cretas_prod_db smartbi_prod_db; do
        local f="$BACKUP_DIR/${db}_$TIMESTAMP.sql.gz"
        local enc="$f.age"
        local obj="$OSS_BUCKET/$OSS_PREFIX/$(basename "$enc")"
        if [ ! -s "$f" ]; then
            echo "$LOG_PREFIX OSS: skip $db (local file missing/empty)"; failed=$((failed+1)); continue
        fi
        if ! "$AGE_BIN" -r "$recipient" -o "$enc" "$f" 2>/dev/null || [ ! -s "$enc" ] \
                || [ "$(head -c 21 "$enc")" != "age-encryption.org/v1" ]; then
            echo "$LOG_PREFIX OSS: encrypt FAILED for $db, not uploaded"
            rm -f "$enc"; failed=$((failed+1)); continue
        fi
        if ossutil64 cp "$enc" "$obj" -f >/dev/null 2>&1; then
            # ⛔ 「命令返回 0」不是判据 —— 去对象存储侧确认它真的在, 且大小对得上
            local remote_size local_size
            remote_size=$(ossutil64 stat "$obj" 2>/dev/null | awk -F': *' '/^Content-Length/{print $2}' | tr -d ' \r')
            local_size=$(stat -c %s "$enc")
            if [ "${remote_size:-0}" = "$local_size" ]; then
                echo "$LOG_PREFIX OSS upload OK: $(basename "$enc") ($(du -h "$enc" | cut -f1), encrypted, size verified)"
                uploaded=$((uploaded+1))
            else
                echo "$LOG_PREFIX OSS upload SIZE MISMATCH: $(basename "$enc") local=$local_size remote=${remote_size:-none}"
                failed=$((failed+1))
            fi
        else
            echo "$LOG_PREFIX OSS upload FAILED: $(basename "$enc")"
            failed=$((failed+1))
        fi
        rm -f "$enc"    # 本机只留明文副本; 加密件只为上传而生
    done

    # 结论由实际值算出来, ⛔ 不硬编码
    echo "$LOG_PREFIX OSS summary: uploaded=$uploaded failed=$failed"
    if [ "$uploaded" -eq 0 ]; then
        echo "$LOG_PREFIX OSS WARNING: 本次没有任何异地副本 —— 只有本机备份"
    fi

    # 清理 OSS 上超过保留期的对象(按文件名里的 YYYYMMDD 判断, 不依赖列表的时间列;
    # 明文旧件 *.sql.gz 与加密件 *.sql.gz.age 一视同仁)
    local cutoff purged
    cutoff=$(date -d "$OSS_RETENTION_DAYS days ago" +%Y%m%d)
    purged=0
    while read -r obj; do
        [ -n "$obj" ] || continue
        local stamp
        stamp=$(basename "$obj" | grep -oE '[0-9]{8}_[0-9]{6}' | cut -d_ -f1)
        [ -n "$stamp" ] || continue
        if [ "$stamp" -lt "$cutoff" ]; then
            ossutil64 rm "$obj" -f >/dev/null 2>&1 && purged=$((purged+1))
        fi
    done < <(ossutil64 ls "$OSS_BUCKET/$OSS_PREFIX/" 2>/dev/null | awk '/oss:\/\//{print $NF}')
    echo "$LOG_PREFIX OSS purged: $purged object(s) older than $OSS_RETENTION_DAYS days (cutoff $cutoff)"
}
oss_sync || echo "$LOG_PREFIX OSS: section errored (local backup unaffected)"

# Cleanup old backups
DELETED=$(find "$BACKUP_DIR" -name "*.sql.gz" -mtime +$RETENTION_DAYS -delete -print | wc -l)
echo "$LOG_PREFIX Cleaned up $DELETED old backup files (retention: ${RETENTION_DAYS} days)"

# Verify backup integrity (check file size > 0)
for f in "$BACKUP_DIR"/*_$TIMESTAMP.sql.gz; do
    if [ -f "$f" ] && [ ! -s "$f" ]; then
        echo "$LOG_PREFIX WARNING: Empty backup file: $f"
    fi
done
