#!/usr/bin/env node
/**
 * 把 prod `platform_role_permissions` (L1, 权威) 的只读导出 转成 RN 的离线兜底快照
 * `src/utils/permissionMatrix.l1Snapshot.json`。
 *
 * 为什么有这个文件 (BUG-RN-PERMISSION-MATRIX-DRIFTS-FROM-L1-20260906):
 *   RN 以前自带一份手写 PERMISSION_MATRIX, 与 prod L1 量出 29 格漂移 (permission_admin.sales
 *   RN rw / L1 -, 等等)。现在 RN `hasModulePermission` 读**登录响应**的 `modulePermissions`
 *   (后端按 L2→L1→硬编码 逐格解析); 这份快照只给「登录响应还没带 modulePermissions 的
 *   旧会话」(OTA 之前登录、尚未重新登录) 兜底。⛔ 不要手改 JSON —— 改了就又是一份会漂的矩阵。
 *
 * 用法 (只读导出, 全程不写 prod):
 *   cat > /tmp/l1_export.sql <<'SQL'
 *   SELECT role_code || '|' || module_code || '|' || permission_level
 *     FROM platform_role_permissions WHERE deleted_at IS NULL ORDER BY role_code, module_code;
 *   SQL
 *   scp /tmp/l1_export.sql root@47.100.235.168:/tmp/l1_export.sql
 *   ssh root@47.100.235.168 "sudo -u postgres psql -d cretas_prod_db -At -f /tmp/l1_export.sql" > /tmp/l1_prod.txt
 *   node scripts/gen-permission-l1-snapshot.js /tmp/l1_prod.txt 2026-09-06
 *
 * 输出格式:
 *   { "source": "...", "exportedAt": "YYYY-MM-DD", "rows": N, "levels": { role: { module: "rw|r|w|-" } } }
 */
const fs = require('fs');
const path = require('path');

const VALID = new Set(['rw', 'r', 'w', '-']);

function main(argv) {
  const [input, exportedAt] = argv;
  if (!input || !exportedAt || !/^\d{4}-\d{2}-\d{2}$/.test(exportedAt)) {
    console.error('usage: gen-permission-l1-snapshot.js <role|module|level export> <YYYY-MM-DD>');
    process.exit(2);
  }
  const text = fs.readFileSync(input, 'utf8');
  const levels = {};
  let rows = 0;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    const parts = line.split('|');
    if (parts.length !== 3) throw new Error(`bad line (expect role|module|level): ${line}`);
    const [role, module, level] = parts;
    if (!VALID.has(level)) throw new Error(`bad level '${level}' at ${role}.${module}`);
    if (!levels[role]) levels[role] = {};
    if (levels[role][module] !== undefined) throw new Error(`duplicate cell ${role}.${module}`);
    levels[role][module] = level;
    rows += 1;
  }
  if (rows === 0) throw new Error('export is empty — refusing to write an empty snapshot');
  // 稳定排序, 让 diff 只反映真实变化
  const sorted = {};
  for (const role of Object.keys(levels).sort()) {
    sorted[role] = {};
    for (const module of Object.keys(levels[role]).sort()) sorted[role][module] = levels[role][module];
  }
  const out = {
    source: 'cretas_prod_db.platform_role_permissions (L1, deleted_at IS NULL) — read-only export',
    exportedAt,
    rows,
    levels: sorted,
  };
  const target = path.resolve(__dirname, '..', 'src', 'utils', 'permissionMatrix.l1Snapshot.json');
  fs.writeFileSync(target, JSON.stringify(out, null, 2) + '\n', 'utf8');
  console.log(`wrote ${target}: roles=${Object.keys(sorted).length} rows=${rows} exportedAt=${exportedAt}`);
}

main(process.argv.slice(2));
