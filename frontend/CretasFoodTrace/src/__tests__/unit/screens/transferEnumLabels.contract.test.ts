/**
 * 调拨的状态/类型必须**每个后端取值都有中文** —— 对着 Java 枚举源文件断言。
 *
 * 🔴 2026-08-20 起因：Steve 截图里调拨卡上「类型」显示成 `WAREHOUSE_TO_WAREHOUSE`。
 * 查下来两处都漏：
 *   · `TransferType`   后端 4 个 / 前端映了 3 个（漏 `WAREHOUSE_TO_WAREHOUSE`
 *     —— 而 F006 的厂内仓间调拨**全是**这个类型，所以每一条都在显示英文码）
 *   · `TransferStatus` 后端 9 个 / 前端映了 8 个（漏 `REVERSED`）
 *
 * ▎漏映射不会报错、不会变红，只是**把内部代码摆到用户面前**。
 * ▎所以必须由闸来发现，⛔ 不能靠下次谁眼尖看见。
 *
 * ⚠️ 本仓 web-admin 侧已有同形的 `enumDisplayCoverage.spec.ts`（也是对着 Java 源文件断言），
 * 这里是 App 侧的对应物。
 */
import fs from 'fs';
import path from 'path';

const sourceRoot = path.resolve(__dirname, '../../../');
const enumDir = path.resolve(
  sourceRoot, '../../../backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums');
const screen = fs.readFileSync(
  path.resolve(sourceRoot, 'screens/factory-admin/inventory/TransferListScreen.tsx'), 'utf8');

/** 取 Java 枚举的常量名：行首缩进 + 全大写 + 紧跟左括号或逗号/分号。 */
function javaEnumValues(file: string): string[] {
  const src = fs.readFileSync(path.resolve(enumDir, file), 'utf8');
  return [...src.matchAll(/^\s{4}([A-Z][A-Z_]*)\s*[(,;]/gm)]
    .map((m) => m[1])
    .filter((v): v is string => Boolean(v));
}

/** 从屏幕源码里取某个 Record 字面量的键。 */
function mapKeys(mapName: string): string[] {
  const body = screen.match(new RegExp(`${mapName}[^=]*=\\s*\\{([\\s\\S]*?)\\n\\};`))?.[1] ?? '';
  return [...body.matchAll(/^\s{2}([A-Z][A-Z_]*)\s*:/gm)]
    .map((m) => m[1])
    .filter((v): v is string => Boolean(v));
}

describe('调拨枚举中文覆盖（对着 Java 源文件断言）', () => {
  it('前提：枚举文件读得到，且解析出合理数量（否则下面全是恒真）', () => {
    // 🔴 仪器自检。路径写错时 javaEnumValues 返回 []，「每个都有中文」会因为
    //    「一个都没有」而恒真 —— 那是最经典的假绿。
    expect(javaEnumValues('TransferStatus.java').length).toBeGreaterThanOrEqual(8);
    expect(javaEnumValues('TransferType.java').length).toBeGreaterThanOrEqual(4);
    expect(mapKeys('STATUS_MAP').length).toBeGreaterThanOrEqual(8);
    expect(mapKeys('TYPE_MAP').length).toBeGreaterThanOrEqual(4);
  });

  it('TransferStatus 的每个取值都有中文', () => {
    const missing = javaEnumValues('TransferStatus.java').filter((v) => !mapKeys('STATUS_MAP').includes(v));
    expect(missing).toEqual([]);
  });

  it('TransferType 的每个取值都有中文', () => {
    const missing = javaEnumValues('TransferType.java').filter((v) => !mapKeys('TYPE_MAP').includes(v));
    expect(missing).toEqual([]);
  });

  it('⛔ 反向：前端不许映射后端没有的取值（防止照记忆编）', () => {
    const backendStatus = javaEnumValues('TransferStatus.java');
    const backendType = javaEnumValues('TransferType.java');
    expect(mapKeys('STATUS_MAP').filter((k) => !backendStatus.includes(k))).toEqual([]);
    expect(mapKeys('TYPE_MAP').filter((k) => !backendType.includes(k))).toEqual([]);
  });

  it('界面上不许直接渲染英文码（有映射就要用）', () => {
    expect(screen).toContain('TYPE_MAP[item.transferType]');
    expect(screen).toContain('STATUS_MAP[item.status]');
  });
});
