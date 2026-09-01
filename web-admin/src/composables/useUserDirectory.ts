/**
 * 员工名录 —— 把 `users.id` 翻译成人能读的姓名。
 *
 * 背景 (缺陷 5, 2026-09-01, LIUSHANMEN 生产走查): 请购单的列表 / 待审批 / 详情
 * 三个页面都把请购人渲染成 `#1638` —— 一个数据库主键。采购主管看不出是谁提的单。
 *
 * ⛔ **不要为了显示姓名把 `hr` 权限开给采购角色。** 用的是 2026-08-22 专为此类场景
 * 开的只读最小字段集端点 `GET /{factoryId}/users/directory`
 * (id / fullName / username / department / roleCode / isActive, **不含手机/邮箱**),
 * 它刻意不带 `@RequirePermission`, 仍受 JWT + factoryId 租户隔离约束。
 * 要完整用户信息 (含手机邮箱) 仍然只能走 `GET /users`, 那个照旧要 hr。
 * 详见 `UserController#getUserDirectory` 头部说明。
 *
 * ⚠️ 该端点**只返回在职用户**。离职/停用的请购人查不到 ⇒ 回退显示 `#id`。
 *
 * ▎回退口径 (三处调用方共用, 这也是把它抽成一份的理由):
 * ▎**查不到名字回退显示 `#id`, ⛔ 绝不回退成空白。**
 * ▎「查不到这个人」和「这一格没有值」是两件事; 回退成空白会把前者伪装成后者,
 * ▎正是本仓形态 A¹⁰ 说的「兜底的默认值会把『我不知道』翻译成一个假读数」。
 *
 * ⚠️ 形态 D 备注: 抽成这一份之前, 同样的 `#${id}` 拼接散落在
 * `procurement/requisitions/{list,pending-approval,detail}.vue` 三处。
 * 新增第四个需要显示人名的页面时 ⛔ 不要再抄一遍, 复用本 composable。
 */
import { ref, type Ref } from 'vue';
import { get } from '@/api/request';

export interface DirectoryUser {
  id: number | string;
  fullName?: string | null;
  username?: string | null;
  department?: string | null;
  roleCode?: string | null;
  isActive?: boolean | null;
}

export interface UserDirectoryHandle {
  /** 名录原始数据, 一般不需要调用方直接读 */
  users: Ref<DirectoryUser[]>;
  /** 拉取名录; factoryId 为空时静默跳过 */
  load: (factoryId: string | null | undefined) => Promise<void>;
  /**
   * 人名显示。
   * - 无 id → `'-'`
   * - 查得到 → 姓名 (fullName 优先, 退 username)
   * - 查不到 → `#${id}` (⛔ 不是空白, 见文件头「回退口径」)
   */
  personLabel: (userId: number | string | null | undefined) => string;
  /**
   * 某人**当前**所属部门的显示名（后端已翻成中文）；查不到返回空串。
   * ⚠️ 是「现在」不是「单据创建时」—— 有快照字段的调用方应快照优先、本函数兜底。
   */
  personDepartment: (userId: number | string | null | undefined) => string;
}

export function useUserDirectory(): UserDirectoryHandle {
  const users = ref<DirectoryUser[]>([]);

  async function load(factoryId: string | null | undefined): Promise<void> {
    if (!factoryId) return;
    try {
      const res = await get<DirectoryUser[]>(`/${factoryId}/users/directory`);
      users.value = Array.isArray(res?.data) ? res.data : [];
    } catch {
      // request 拦截器已提示; personLabel 按回退口径显示 #id
    }
  }

  function personLabel(userId: number | string | null | undefined): string {
    if (userId === null || userId === undefined || userId === '') return '-';
    const hit = users.value.find((u) => String(u.id) === String(userId));
    const name = hit?.fullName || hit?.username;
    return name ? String(name) : `#${userId}`;
  }

  /**
   * 某个人**当前**所属部门的显示名（缺陷 15）。
   *
   * ⚠️ 名录端点返回的 `department` 已经由后端 `DepartmentDisplayNames` 翻成中文
   * （`procurement` → `采购部`），⛔ 前端不再自己翻一遍 —— 那会变成第二份字典。
   *
   * ⚠️ 这是**当前**部门，不是单据创建时的快照。调用方若手上有快照字段
   * （如 `requisition.requesterDeptId`），应当**快照优先、本函数兜底**，
   * 理由与采购明细的原料名同形：快照记录的是当时，名录给的是现在，人调岗后两者会不同。
   */
  function personDepartment(userId: number | string | null | undefined): string {
    if (userId === null || userId === undefined || userId === '') return '';
    const hit = users.value.find((u) => String(u.id) === String(userId));
    return hit?.department ? String(hit.department) : '';
  }

  return { users, load, personLabel, personDepartment };
}

export default useUserDirectory;
