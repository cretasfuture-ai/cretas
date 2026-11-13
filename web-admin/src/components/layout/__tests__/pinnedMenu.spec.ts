import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  PINNED_MENU_LIMIT,
  pinnedMenuStorageKey,
  readPinnedPaths,
  usePinnedMenu,
  writePinnedPaths,
} from '../pinnedMenu';

/** P2「常用」：浏览器本地、按账号+工厂分键、读写失败当空不报错（organizer 11-13 决定）。 */
describe('pinnedMenu（常用，本机浏览器存储）', () => {
  beforeEach(() => window.localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it('按「账号 id + 工厂 id」分键；没有账号不存', () => {
    expect(pinnedMenuStorageKey(42, 'F006')).toBe('cretas:pinned-menu:v1:42:F006');
    expect(pinnedMenuStorageKey('42', '')).toBe('cretas:pinned-menu:v1:42:-');
    expect(pinnedMenuStorageKey(null, 'F006')).toBeNull();
    expect(pinnedMenuStorageKey(42, 'F006')).not.toBe(pinnedMenuStorageKey(42, 'LIUSHANMEN'));
    expect(pinnedMenuStorageKey(42, 'F006')).not.toBe(pinnedMenuStorageKey(43, 'F006'));
  });

  it('写入后能读回；不同账号/工厂互不串', () => {
    const a = pinnedMenuStorageKey(1, 'F006');
    const b = pinnedMenuStorageKey(2, 'F006');
    expect(writePinnedPaths(a, ['/production/plans', '/sales/orders'])).toBe(true);
    expect(readPinnedPaths(a)).toEqual(['/production/plans', '/sales/orders']);
    expect(readPinnedPaths(b)).toEqual([]);
  });

  it('内容损坏 / 不是数组 / 混入非路径 ⇒ 当空或过滤，不抛', () => {
    const k = pinnedMenuStorageKey(1, 'F006')!;
    window.localStorage.setItem(k, '{not json');
    expect(readPinnedPaths(k)).toEqual([]);
    window.localStorage.setItem(k, '{"a":1}');
    expect(readPinnedPaths(k)).toEqual([]);
    window.localStorage.setItem(k, JSON.stringify(['/a', 3, 'javascript:x', '/a', '/b']));
    expect(readPinnedPaths(k)).toEqual(['/a', '/b']);
  });

  it('存储不可用（getItem/setItem 抛错）⇒ 读为空、写返回 false，不抛', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('denied'); });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    const k = pinnedMenuStorageKey(1, 'F006');
    expect(readPinnedPaths(k)).toEqual([]);
    expect(writePinnedPaths(k, ['/a'])).toBe(false);
    const pm = usePinnedMenu();
    pm.bind(pinnedMenuStorageKey(9, 'X'));
    expect(() => pm.toggle('/a')).not.toThrow();
  });

  it('toggle 加入/移出并持久化；上限内保留最新', () => {
    const pm = usePinnedMenu();
    const k = pinnedMenuStorageKey(7, 'F006');
    pm.bind(k);
    pm.toggle('/x');
    expect(pm.isPinned('/x')).toBe(true);
    expect(readPinnedPaths(k)).toEqual(['/x']);
    pm.toggle('/x');
    expect(pm.isPinned('/x')).toBe(false);
    expect(readPinnedPaths(k)).toEqual([]);
    for (let i = 0; i < PINNED_MENU_LIMIT + 3; i++) pm.toggle(`/p${i}`);
    expect(pm.pinned.value).toHaveLength(PINNED_MENU_LIMIT);
    expect(pm.pinned.value.at(-1)).toBe(`/p${PINNED_MENU_LIMIT + 2}`);
  });

  it('换账号重新读取对应的键', () => {
    writePinnedPaths(pinnedMenuStorageKey(100, 'F006'), ['/only-100']);
    const pm = usePinnedMenu();
    pm.bind(pinnedMenuStorageKey(100, 'F006'));
    expect(pm.pinned.value).toEqual(['/only-100']);
    pm.bind(pinnedMenuStorageKey(101, 'F006'));
    expect(pm.pinned.value).toEqual([]);
  });
});
