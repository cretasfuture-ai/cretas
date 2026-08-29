import fs from 'fs';
import path from 'path';

/**
 * 「我的 → 编辑」这类**自助改资料**的屏，不许调人事管理的 `updateUser`。
 *
 * <h3>实测（2026-08-29 逐屏走查）</h3>
 *
 * 仓储主管点「我的 → 编辑 → 保存」：
 *
 * ```
 * PUT /api/mobile/F006/users/1554  ->  403
 * 您的角色 [仓储主管] 在 [人事管理] 模块无 [读写] 权限
 * 请联系工厂管理员…开通 [人事管理] 的 [读写] 权限
 * ```
 *
 * ▎按那句提示去做是**错的** —— 为了让人改自己的名字而开通整个人事模块的写权限，
 * ▎等于把员工名册的增删改一起给出去。改自己的名字是自助行为，不是人事管理。
 *
 * ⇒ 新增 `PUT /users/me/profile`（userId 从 token 解析，请求体只有 fullName/email），
 * 三个自助编辑屏改走它。
 *
 * <h3>这道闸守什么</h3>
 *
 * `profile` 目录下的屏不许出现 `userApiClient.updateUser(` ——
 * 那是人事管理接口。⛔ 管理类屏（`management/`、`platform/`）不在管辖范围，
 * 它们**应该**用那个接口。
 *
 * ⚠️ 文本判据：换个别名或间接调用就看不见。兜底，不是免检。
 */
const SCREENS = path.join(__dirname, '..', '..', 'screens');

function walk(dir: string, out: string[] = []): string[] {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    if (fs.statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.tsx')) out.push(p);
  }
  return out;
}

/** ⛔ 先剥注释 —— 否则闸会把说明文字里引用的旧写法当成被测对象（本仓同形五次）。 */
function stripComments(src: string): string {
  const noBlock = src.replace(/\/\*[\s\S]*?\*\//g, ' ');
  return noBlock
    .split('\n')
    .map((line) => {
      const i = line.indexOf('//');
      return i >= 0 ? line.slice(0, i) : line;
    })
    .join('\n');
}

const isSelfProfileScreen = (rel: string) =>
  rel.includes('/profile/') && !rel.startsWith('management/') && !rel.startsWith('platform/');

describe('自助改资料不许走人事管理接口', () => {
  const files = walk(SCREENS);
  const rels = files.map((f) => path.relative(SCREENS, f).replace(/\\/g, '/'));

  it('阳性对照：确实存在自助资料屏（扫到 0 个说明判据写错了）', () => {
    expect(rels.filter(isSelfProfileScreen).length).toBeGreaterThan(2);
  });

  it('阴性对照：管理类屏**允许**用 updateUser（否则这道闸管得太宽）', () => {
    expect(isSelfProfileScreen('management/UserManagementScreen.tsx')).toBe(false);
    expect(isSelfProfileScreen('warehouse/profile/WHProfileEditScreen.tsx')).toBe(true);
  });

  it('自助资料屏里没有 userApiClient.updateUser(', () => {
    const offenders: string[] = [];
    files.forEach((f, i) => {
      const rel = rels[i] as string;
      if (!isSelfProfileScreen(rel)) return;
      const src = stripComments(fs.readFileSync(f, 'utf8'));
      if (src.includes('userApiClient.updateUser(')) offenders.push(rel);
    });
    expect(offenders).toEqual([]);
  });
});
