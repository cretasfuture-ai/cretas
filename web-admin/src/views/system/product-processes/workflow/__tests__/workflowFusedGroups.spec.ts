import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  FUSED_MATERIAL_OWNER_WIDTH,
  FUSED_PROCESS_OWNER_WIDTH,
  fusedBelowOffset,
  fusedGroupBounds,
  fusedGroupNodeIds,
  reconcileFocusIds,
  type FusedAttachmentDescriptor,
} from '../workflowFusedGroups';

const read = (name: string): string => readFileSync(resolve(__dirname, '..', name), 'utf8');

describe('融合几何：下方附属的偏移', () => {
  it('偏移 = owner 高度 + 排在自己前面那几条的高度', () => {
    const sizes = {
      proc: { width: 440, height: 300 },
      a: { width: 440, height: 40 },
      b: { width: 440, height: 60 },
    };
    expect(fusedBelowOffset('proc', [], sizes)).toBe(300);
    expect(fusedBelowOffset('proc', ['a'], sizes)).toBe(340);
    expect(fusedBelowOffset('proc', ['a', 'b'], sizes)).toBe(400);
  });

  it('量不到时返回 null —— ⛔ 不用 0 顶上（0 会把卡片画在 owner 身上）', () => {
    // 形态 A¹⁰：兜底的默认值会把「我不知道」翻译成「是 0」，而这两件事对下游完全不同。
    expect(fusedBelowOffset('proc', [], {})).toBeNull();
    expect(fusedBelowOffset('proc', ['a'], { proc: { width: 440, height: 300 } })).toBeNull();
    // 阴性对照：0 高度同样算「没量到」，不是一个合法读数。
    expect(fusedBelowOffset('proc', [], { proc: { width: 440, height: 0 } })).toBeNull();
  });
});

describe('融合几何：group 包围盒（自动布局用）', () => {
  const attachments: FusedAttachmentDescriptor[] = [
    { id: 'aux', ownerId: 'proc', side: 'above', stackIndex: 0 },
    { id: 'byp', ownerId: 'proc', side: 'below', stackIndex: 0 },
    { id: 'rew', ownerId: 'proc', side: 'below', stackIndex: 1 },
  ];

  it('上下分别累加，owner 自身高度不重复计入', () => {
    const bounds = fusedGroupBounds(attachments, {
      aux: { width: 440, height: 120 },
      byp: { width: 440, height: 40 },
      rew: { width: 440, height: 44 },
    }, 999);
    expect(bounds).toEqual({ above: 120, below: 84 });
  });

  it('量不到的那几条按保守下限计入 —— 宁可多留白也不能压上去', () => {
    const bounds = fusedGroupBounds(attachments, { aux: { width: 440, height: 120 } }, 100);
    expect(bounds).toEqual({ above: 120, below: 200 });
  });

  it('没有附属时上下都是 0（阴性对照：不会凭空多留白）', () => {
    expect(fusedGroupBounds([], {}, 100)).toEqual({ above: 0, below: 0 });
  });
});

describe('融合就是一体：聚焦以 group 为单位', () => {
  const attachments: FusedAttachmentDescriptor[] = [
    { id: 'aux:p1', ownerId: 'p1', side: 'above', stackIndex: 0 },
    { id: 'rew:p1', ownerId: 'p1', side: 'below', stackIndex: 0 },
    { id: 'aux:p2', ownerId: 'p2', side: 'above', stackIndex: 0 },
  ];

  it('聚焦一个 owner 会带上它的全部融合附属', () => {
    expect([...fusedGroupNodeIds(['p1'], attachments)].sort())
      .toEqual(['aux:p1', 'p1', 'rew:p1']);
  });

  it('⛔ 不会顺带带上别的 owner 的附属（阴性对照）', () => {
    expect(fusedGroupNodeIds(['p1'], attachments).has('aux:p2')).toBe(false);
  });
});

describe('孤儿 focus 不许把整张画布变白（§9）', () => {
  const live = new Set(['a', 'b', 'c']);

  it('对账后非空 → 允许全局 dim', () => {
    expect([...(reconcileFocusIds(['a', 'zzz'], live) ?? [])]).toEqual(['a']);
  });

  it('🔴 对账后为空 → 必须返回 null（= 全部正常显示），而不是一个空集合', () => {
    // 空集合会让「非 focus 的全部淡化」命中**每一个** Cell —— 用户看到一张白图，
    // 而且没有任何报错。这是本条判据存在的全部理由。
    expect(reconcileFocusIds(['已经不存在的节点'], live)).toBeNull();
    expect(reconcileFocusIds([], live)).toBeNull();
    expect(reconcileFocusIds(null, live)).toBeNull();
  });
});

describe('附属卡片与 owner 同宽 —— 两处常量必须逐字一致（形态 D）', () => {
  it('常量与组件 CSS 里的宽度对得上', () => {
    // 「同宽」是验收项。宽度写在两个地方（布局常量 + 组件 CSS），
    // 它们漂开不会报错，只会让融合看起来像两张卡片叠着。
    expect(read('WorkflowProcessNode.vue')).toContain(`width: ${FUSED_PROCESS_OWNER_WIDTH}px;`);
    expect(read('WorkflowMaterialNode.vue')).toContain(`width: ${FUSED_MATERIAL_OWNER_WIDTH}px;`);
  });

  it('⛔ 附属组件自己不再写死宽度（阴性对照：宽度只有一个出处）', () => {
    const aux = read('WorkflowAuxiliaryNode.vue');
    const pack = read('WorkflowPackagingNode.vue');
    expect(aux).toMatch(/:owner-width="FUSED_PROCESS_OWNER_WIDTH"/);
    expect(pack).toMatch(/:owner-width="FUSED_MATERIAL_OWNER_WIDTH"/);
    expect(aux).not.toMatch(/\.aux-node\s*\{[^}]*width:\s*\d+px/);
    expect(pack).not.toMatch(/\.packaging-node\s*\{[^}]*width:\s*\d+px/);
  });
});
