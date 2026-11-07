import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  executeAgentAction,
  observeAgentPage,
  validateAgentAction,
  type AgentAction,
} from '../pageAgentDom';

function mountPage(): HTMLElement {
  document.body.innerHTML = `
    <aside class="app-sidebar">
      <button class="el-menu-item" aria-label="销售订单">销售订单</button>
      <button class="el-menu-item" aria-label="删除订单">删除订单</button>
    </aside>
    <main class="app-content">
      <form class="el-form-item">
        <label for="customer">客户</label>
        <input id="customer" name="customer" required />
      </form>
      <div role="combobox" aria-label="仓库" tabindex="0">
        <input aria-label="仓库内部输入" readonly value="上海仓" />
      </div>
      <button aria-label="提交">提交</button>
      <button aria-label="分析">分析</button>
      <button aria-label="AI录入">AI录入</button>
      <button aria-label="移除按钮">移除按钮</button>
      <div class="ai-entry-drawer">
        <button aria-label="AI填写">AI填写</button>
        <textarea aria-label="AI录入">页面自己的 AI 输入</textarea>
        <button aria-label="填入表单">填入表单</button>
      </div>
      <input type="file" aria-label="上传附件" />
      <button aria-label="密码">不应暴露</button>
      <input type="password" aria-label="密码" value="secret" />
      <input type="hidden" name="token" value="hidden-token" />
      <button aria-label="隐藏" style="display:none">隐藏</button>
      <button data-ai-page-agent aria-label="代理工具">代理工具</button>
      <button data-native-demo-toolbar aria-label="演示工具">演示工具</button>
      <div class="el-popper" role="listbox">
        <div role="option" aria-label="上海仓">上海仓</div>
        <div role="option" aria-label="供应商">供应商</div>
        <button aria-label="关闭">关闭</button>
        <button aria-label="退出登录">退出登录</button>
        <button aria-label="切换工厂">切换工厂</button>
        <button aria-label="Sign out">Sign out</button>
      </div>
    </main>
    <aside class="reference-pane"><button aria-label="参考操作">参考操作</button><iframe></iframe></aside>
  `;
  return document.querySelector('.app-content')!;
}

function snapshot() {
  mountPage();
  return observeAgentPage({
    document,
    route: '/sales/orders',
    title: '销售订单',
    routes: [
      { path: '/sales/orders', title: '销售订单' },
      { path: '/warehouse/stock', title: '库存' },
      { path: 'https://evil.example/', title: '外部' },
    ],
  });
}

function observeCurrentPage() {
  return observeAgentPage({
    document,
    route: '/sales/orders',
    title: '销售订单',
    routes: [{ path: '/sales/orders', title: '销售订单' }],
  });
}

describe('pageAgentDom', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('observes only visible safe DOM and exposes serializable page data plus refs', () => {
    const page = snapshot();
    expect(page.id).toMatch(/^agent-page-/);
    expect(page.route).toBe('/sales/orders');
    expect(page.page.title).toBe('销售订单');
    expect(page.page.text).toContain('客户');
    expect(page.page.text).not.toContain('hidden-token');
    expect(page.page.text).not.toContain('AI录入');
    expect(page.page.controls.map((control) => control.label)).toEqual(expect.arrayContaining(['客户', '仓库', '提交', '销售订单', '上海仓']));
    expect(page.page.controls.map((control) => control.label)).not.toEqual(expect.arrayContaining(['密码', '删除订单', '隐藏', '代理工具', '演示工具', '参考操作', 'AI录入', 'AI填写', '填入表单', '上传附件', '退出登录', '切换工厂', 'Sign out']));
    expect(page.page.controls.map((control) => control.label)).toEqual(expect.arrayContaining(['供应商', '关闭']));
    expect(page.page.controls.map((control) => control.label)).toContain('分析');
    expect(page.page.routes.map((route) => route.path)).toEqual(['/sales/orders', '/warehouse/stock']);
    expect(() => JSON.stringify(page.page)).not.toThrow();
    expect(page.targets.size).toBe(page.page.controls.length);
  });

  it('validates fill/click/navigate and rejects unknown, unsafe, or extra commands', () => {
    const page = snapshot();
    const customer = page.page.controls.find((control) => control.label === '客户')!;
    const submit = page.page.controls.find((control) => control.label === '提交')!;
    const route = page.page.routes[1]!;
    expect(validateAgentAction({ kind: 'fill', target: customer.ref, value: '甲公司', message: '填写客户' }, page)).toMatchObject({ kind: 'fill', target: customer.ref });
    expect(validateAgentAction({ kind: 'click', target: submit.ref, message: '提交' }, page)).toMatchObject({ kind: 'click' });
    expect(validateAgentAction({ kind: 'navigate', target: route.ref, message: '打开库存' }, page)).toMatchObject({ kind: 'navigate' });
    expect(() => validateAgentAction({ kind: 'navigate', target: 'https://evil.example/', message: '外部' }, page)).toThrow();
    expect(() => validateAgentAction({ kind: 'click', target: 'c999', message: '伪造' }, page)).toThrow();
    expect(() => validateAgentAction({ kind: 'click', target: submit.ref, value: 'javascript:alert(1)', message: '额外命令' }, page)).toThrow();
    expect(() => validateAgentAction({ kind: 'run', target: submit.ref, message: '执行脚本' }, page)).toThrow();
  });

  it('fills through the native setter/events and clicks only after the async hook', async () => {
    const page = snapshot();
    const customer = page.page.controls.find((control) => control.label === '客户')!;
    const input = page.targets.get(customer.ref)! as HTMLInputElement;
    const events: string[] = [];
    input.addEventListener('input', () => events.push('input'));
    input.addEventListener('change', () => events.push('change'));
    const click = vi.fn();
    page.targets.get(page.page.controls.find((control) => control.label === '提交')!.ref)!.click = click;
    const order: string[] = [];
    const result = await executeAgentAction(page, { kind: 'fill', target: customer.ref, value: '甲公司', message: '填写' }, {
      assertCurrent: () => order.push('assert'),
      beforeAction: async () => { order.push('before'); await Promise.resolve(); },
    });
    expect(input.value).toBe('甲公司');
    expect(events).toEqual(['input', 'change']);
    expect(result).toMatchObject({ label: '客户', before: '', after: '甲公司' });
    expect(order).toEqual(['assert', 'before', 'assert', 'assert']);
  });

  it('rejects stale or changed controls before invoking a click', async () => {
    const page = snapshot();
    const submit = page.page.controls.find((control) => control.label === '提交')!;
    const element = page.targets.get(submit.ref)!;
    const click = vi.spyOn(element, 'click');
    element.textContent = '已变更';
    await expect(executeAgentAction(page, { kind: 'click', target: submit.ref, message: '点击' }, {
      assertCurrent: () => undefined,
    })).rejects.toThrow(/stale|changed/);
    expect(click).not.toHaveBeenCalled();
  });

  it('allows an option click to hide its dropdown after the click', async () => {
    const page = snapshot();
    const option = page.page.controls.find((control) => control.label === '上海仓')!;
    const element = page.targets.get(option.ref)!;
    const dropdown = element.closest('.el-popper')!;
    const clicked = vi.fn(() => dropdown.setAttribute('hidden', ''));
    element.addEventListener('click', clicked);

    await expect(executeAgentAction(page, { kind: 'click', target: option.ref, message: '选择上海仓' }, {
      assertCurrent: () => undefined,
    })).resolves.toMatchObject({ label: '上海仓', after: '已点击' });
    expect(clicked).toHaveBeenCalledTimes(1);
    expect(dropdown.hasAttribute('hidden')).toBe(true);
  });

  it('allows a modal button click to close and remove its dialog', async () => {
    mountPage();
    const dialog = document.createElement('div');
    dialog.className = 'el-dialog';
    const closeElement = document.createElement('button');
    closeElement.setAttribute('aria-label', '关闭弹窗');
    closeElement.textContent = '关闭弹窗';
    dialog.append(closeElement);
    document.body.append(dialog);
    const page = observeCurrentPage();
    const close = page.page.controls.find((control) => control.label === '关闭弹窗')!;
    const element = page.targets.get(close.ref)!;
    const clicked = vi.fn(() => dialog.remove());
    element.addEventListener('click', clicked);

    await expect(executeAgentAction(page, { kind: 'click', target: close.ref, message: '关闭弹窗' }, {
      assertCurrent: () => undefined,
    })).resolves.toMatchObject({ label: '关闭弹窗', after: '已点击' });
    expect(clicked).toHaveBeenCalledTimes(1);
    expect(dialog.isConnected).toBe(false);
  });

  it('keeps the topmost modal actionable after more than 140 background controls', () => {
    document.body.innerHTML = '<main class="app-content"></main>';
    const content = document.querySelector('.app-content')!;
    for (let index = 0; index < 160; index += 1) {
      const button = document.createElement('button');
      button.setAttribute('aria-label', `背景按钮${index}`);
      button.textContent = `背景按钮${index}`;
      content.append(button);
    }
    const modal = document.createElement('div');
    modal.className = 'el-dialog';
    const confirm = document.createElement('button');
    confirm.setAttribute('aria-label', '确认弹窗');
    confirm.textContent = '确认弹窗';
    modal.append(confirm);
    document.body.append(modal);

    const page = observeCurrentPage();
    expect(page.page.controls.map((control) => control.label)).toEqual(['确认弹窗']);
    expect(page.page.routes).toEqual([]);
  });

  it('exposes a visible message-box confirmation as the foreground control', () => {
    document.body.innerHTML = `
      <main class="app-content"><button aria-label="背景提交">背景提交</button></main>
      <div class="el-message-box"><button aria-label="确认弹窗操作">确认弹窗操作</button></div>
    `;
    const page = observeCurrentPage();
    expect(page.page.controls.map((control) => control.label)).toEqual(['确认弹窗操作']);
    expect(page.page.routes).toEqual([]);
  });

  it('does not click a visible control covered by another element', async () => {
    const page = snapshot();
    const submit = page.page.controls.find((control) => control.label === '提交')!;
    const element = page.targets.get(submit.ref)!;
    const customer = page.page.controls.find((control) => control.label === '客户')!;
    const input = page.targets.get(customer.ref)! as HTMLInputElement;
    const overlay = document.createElement('div');
    document.body.append(overlay);
    vi.spyOn(element, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 100, 30));
    vi.spyOn(input, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 100, 30));
    const previous = Object.getOwnPropertyDescriptor(document, 'elementFromPoint');
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: () => overlay,
    });
    const clicked = vi.spyOn(element, 'click');
    try {
      await expect(executeAgentAction(page, { kind: 'click', target: submit.ref, message: '提交' }, {
        assertCurrent: () => undefined,
      })).rejects.toThrow(/stale|covered|changed/);
      expect(clicked).not.toHaveBeenCalled();
      await expect(executeAgentAction(page, {
        kind: 'fill', target: customer.ref, value: '不应填写', message: '填写客户',
      }, { assertCurrent: () => undefined })).rejects.toThrow(/stale|covered|changed/);
      expect(input.value).toBe('');
    } finally {
      if (previous) Object.defineProperty(document, 'elementFromPoint', previous);
      else Reflect.deleteProperty(document, 'elementFromPoint');
    }
  });

  it('prioritizes a popover option before a long background control list', () => {
    document.body.innerHTML = '<main class="app-content"></main>';
    const content = document.querySelector('.app-content')!;
    for (let index = 0; index < 160; index += 1) {
      const button = document.createElement('button');
      button.setAttribute('aria-label', `列表按钮${index}`);
      button.textContent = `列表按钮${index}`;
      content.append(button);
    }
    const popper = document.createElement('div');
    popper.className = 'el-popper';
    const option = document.createElement('div');
    option.setAttribute('role', 'option');
    option.setAttribute('aria-label', '前景选项');
    option.textContent = '前景选项';
    popper.append(option);
    document.body.append(popper);

    const page = observeCurrentPage();
    expect(page.page.controls[0]).toMatchObject({ label: '前景选项', role: 'option' });
    expect(page.page.controls).toHaveLength(140);
  });

  it('allows a click handler to remove the clicked control itself', async () => {
    const page = snapshot();
    const remove = page.page.controls.find((control) => control.label === '移除按钮')!;
    const element = page.targets.get(remove.ref)!;
    const clicked = vi.fn(() => element.remove());
    element.addEventListener('click', clicked);

    await expect(executeAgentAction(page, { kind: 'click', target: remove.ref, message: '移除按钮' }, {
      assertCurrent: () => undefined,
    })).resolves.toMatchObject({ label: '移除按钮', after: '已点击' });
    expect(clicked).toHaveBeenCalledTimes(1);
    expect(element.isConnected).toBe(false);
  });

  it('fails when the post-click current assertion rejects, after exactly one click', async () => {
    const page = snapshot();
    const submit = page.page.controls.find((control) => control.label === '提交')!;
    const element = page.targets.get(submit.ref)!;
    const clicked = vi.spyOn(element, 'click');
    let assertions = 0;

    await expect(executeAgentAction(page, { kind: 'click', target: submit.ref, message: '提交' }, {
      assertCurrent: () => {
        assertions += 1;
        if (assertions === 3) throw new Error('post-click stale page');
      },
    })).rejects.toThrow('post-click stale page');
    expect(clicked).toHaveBeenCalledTimes(1);
  });

  it('does not act when the pre-action hook rejects or current assertion fails', async () => {
    const page = snapshot();
    const submit = page.page.controls.find((control) => control.label === '提交')!;
    const element = page.targets.get(submit.ref)!;
    const click = vi.spyOn(element, 'click');
    await expect(executeAgentAction(page, { kind: 'click', target: submit.ref, message: '点击' }, {
      assertCurrent: () => undefined,
      beforeAction: async () => { throw new Error('pause'); },
    })).rejects.toThrow('pause');
    expect(click).not.toHaveBeenCalled();
    await expect(executeAgentAction(page, { kind: 'click', target: submit.ref, message: '点击' }, {
      assertCurrent: () => { throw new Error('stale page'); },
    })).rejects.toThrow('stale page');
    expect(click).not.toHaveBeenCalled();
  });

  it('returns an authorized route without navigating or calling an API', async () => {
    const page = snapshot();
    const route = page.page.routes[1]!;
    const result = await executeAgentAction(page, { kind: 'navigate', target: route.ref, message: '库存' }, {
      assertCurrent: () => undefined,
    });
    expect(result.path).toBe('/warehouse/stock');
    expect(window.location.pathname).not.toBe('/warehouse/stock');
  });

  it('supports wait/ask/done as non-DOM control messages only', async () => {
    const page = snapshot();
    const assertCurrent = vi.fn();
    const actions: AgentAction[] = [
      { kind: 'wait', message: '等待页面稳定' },
      { kind: 'ask', message: '请补充客户名称' },
      { kind: 'done', message: '已完成' },
    ];
    for (const action of actions) {
      await expect(executeAgentAction(page, action, { assertCurrent })).resolves.toMatchObject({ label: action.message });
    }
    expect(assertCurrent).not.toHaveBeenCalled();
  });

  it('keeps the full original value for stale checks while displaying at most 240 characters', async () => {
    const page = snapshot();
    const customer = page.page.controls.find((control) => control.label === '客户')!;
    const input = page.targets.get(customer.ref)! as HTMLInputElement;
    const original = `${'x'.repeat(240)}A`;
    input.value = original;
    const observed = observeAgentPage({
      document,
      route: '/sales/orders',
      title: '销售订单',
      routes: [{ path: '/sales/orders', title: '销售订单' }],
    });
    const observedCustomer = observed.page.controls.find((control) => control.label === '客户')!;
    expect(observedCustomer.value).toHaveLength(240);
    input.value = `${'x'.repeat(240)}B`;
    await expect(executeAgentAction(observed, {
      kind: 'fill', target: observedCustomer.ref, value: '新客户', message: '填写客户',
    }, { assertCurrent: () => undefined })).rejects.toThrow(/value changed/);
  });

  it('disambiguates table columns and rows using headers plus safe row identity', async () => {
    document.body.innerHTML = `
      <main class="app-content">
        <table class="el-table">
          <thead><tr>
            <th>采购单号</th><th>物料</th><th>数量</th><th>单价</th><th>操作</th>
          </tr></thead>
          <tbody>
            <tr>
              <td>PO-001</td><td>面粉</td>
              <td><div class="el-form-item"><label>采购物料</label><input value="2" /></div></td>
              <td><input value="12.50" /></td>
              <td><button aria-label="查看">查看</button></td>
            </tr>
            <tr>
              <td>PO-002</td><td>白糖</td>
              <td><div class="el-form-item"><label>采购物料</label><input value="3" /></div></td>
              <td><input value="8.00" /></td>
              <td><button aria-label="查看">查看</button></td>
            </tr>
          </tbody>
        </table>
      </main>
    `;
    const page = observeCurrentPage();
    const quantities = page.page.controls.filter((control) => control.label.startsWith('数量'));
    const views = page.page.controls.filter((control) => control.label.startsWith('查看'));
    expect(quantities).toHaveLength(2);
    expect(quantities[0]?.label).toContain('数量 · 采购单号=PO-001；物料=面粉');
    expect(quantities[1]?.label).toContain('数量 · 采购单号=PO-002；物料=白糖');
    expect(views.map((control) => control.label)).toEqual([
      '查看 · 采购单号=PO-001；物料=面粉',
      '查看 · 采购单号=PO-002；物料=白糖',
    ]);

    const firstQuantity = page.targets.get(quantities[0]!.ref)! as HTMLInputElement;
    firstQuantity.value = '9';
    await expect(executeAgentAction(page, {
      kind: 'fill', target: quantities[0]!.ref, value: '4', message: '修改数量',
    }, { assertCurrent: () => undefined })).rejects.toThrow(/value changed/);
  });

  it('maps split Element Plus header/body tables without counting fixed clones as duplicate rows', () => {
    document.body.innerHTML = `
      <main class="app-content"><div class="el-table">
        <div class="el-table__header-wrapper"><table><thead><tr><th>采购单号</th><th>操作</th></tr></thead></table></div>
        <div class="el-table__body-wrapper"><table class="el-table__body"><tbody>
          <tr><td>PO-SPLIT-001</td><td><button aria-label="查看">查看</button></td></tr>
        </tbody></table></div>
      </div></main>
    `;
    const page = observeCurrentPage();
    expect(page.page.controls.map((control) => control.label)).toContain('查看 · 采购单号=PO-SPLIT-001');
  });

  it('uses a unique product SKU or name/spec identity for sales detail inputs', () => {
    document.body.innerHTML = `
      <main class="app-content"><div class="el-table">
        <div class="el-table__header-wrapper"><table><thead><tr><th>产品</th><th>数量</th><th>含税单价</th></tr></thead></table></div>
        <div class="el-table__body-wrapper"><table class="el-table__body"><tbody>
          <tr><td><strong>苹果汁</strong><div class="muted">SKU-001 · 500ml</div></td><td><input value="1" /></td><td><input value="12.50" /></td></tr>
        </tbody></table></div>
      </div></main>
    `;
    const page = observeCurrentPage();
    expect(page.page.controls.filter((control) => control.label.startsWith('数量'))).toHaveLength(1);
    expect(page.page.controls.filter((control) => control.label.startsWith('含税单价'))).toHaveLength(1);
  });

  it('rejects duplicate product identities instead of using row position', () => {
    document.body.innerHTML = `
      <main class="app-content"><div class="el-table">
        <div class="el-table__header-wrapper"><table><thead><tr><th>产品</th><th>数量</th></tr></thead></table></div>
        <div class="el-table__body-wrapper"><table class="el-table__body"><tbody>
          <tr><td><strong>苹果汁</strong><div class="muted">SKU-DUP · 500ml</div></td><td><input value="1" /></td></tr>
          <tr><td><strong>苹果汁</strong><div class="muted">SKU-DUP · 500ml</div></td><td><input value="2" /></td></tr>
        </tbody></table></div>
      </div></main>
    `;
    const page = observeCurrentPage();
    expect(page.page.controls.filter((control) => control.label.startsWith('数量'))).toEqual([]);
  });

  it('allows an offscreen control after the beforeAction scroll hook reaches it', async () => {
    const page = snapshot();
    const submit = page.page.controls.find((control) => control.label === '提交')!;
    const element = page.targets.get(submit.ref)!;
    vi.spyOn(element, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 100, 30));
    const previous = Object.getOwnPropertyDescriptor(document, 'elementFromPoint');
    let hit: Element | null = null;
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: () => hit,
    });
    const clicked = vi.spyOn(element, 'click');
    try {
      await expect(executeAgentAction(page, { kind: 'click', target: submit.ref, message: '提交' }, {
        assertCurrent: () => undefined,
        beforeAction: async () => { hit = element; },
      })).resolves.toMatchObject({ label: '提交', after: '已点击' });
      expect(clicked).toHaveBeenCalledTimes(1);
    } finally {
      if (previous) Object.defineProperty(document, 'elementFromPoint', previous);
      else Reflect.deleteProperty(document, 'elementFromPoint');
    }
  });

  it('rejects a reused row button when its full key changes during beforeAction', async () => {
    document.body.innerHTML = `
      <main class="app-content"><table class="el-table"><thead><tr><th>采购单号</th><th>操作</th></tr></thead>
      <tbody><tr><td>PO-001</td><td><button aria-label="审批">审批</button></td></tr></tbody></table></main>
    `;
    const page = observeCurrentPage();
    const approval = page.page.controls.find((control) => control.label.startsWith('审批'))!;
    const element = page.targets.get(approval.ref)!;
    const click = vi.spyOn(element, 'click');
    await expect(executeAgentAction(page, { kind: 'click', target: approval.ref, message: '审批' }, {
      assertCurrent: () => undefined,
      beforeAction: async () => {
        document.querySelector('.el-table tbody td')!.textContent = 'PO-002';
      },
    })).rejects.toThrow(/stale|changed/);
    expect(click).not.toHaveBeenCalled();
  });

  it('does not expose row actions when the key is missing or duplicated', () => {
    document.body.innerHTML = `
      <main class="app-content"><table class="el-table"><thead><tr><th>采购单号</th><th>操作</th></tr></thead>
      <tbody>
        <tr><td>PO-DUP</td><td><button aria-label="编辑">编辑</button></td></tr>
        <tr><td>PO-DUP</td><td><button aria-label="编辑">编辑</button></td></tr>
        <tr><td></td><td><button aria-label="编辑">编辑</button></td></tr>
      </tbody></table></main>
    `;
    const page = observeCurrentPage();
    expect(page.page.controls.filter((control) => control.label.startsWith('编辑'))).toEqual([]);
  });

  it('uses the full long key for stale checks even when display summaries share a prefix', async () => {
    const keyA = `PO-${'X'.repeat(120)}A`;
    const keyB = `PO-${'X'.repeat(120)}B`;
    document.body.innerHTML = `
      <main class="app-content"><table class="el-table"><thead><tr><th>采购单号</th><th>操作</th></tr></thead>
      <tbody>
        <tr><td>${keyA}</td><td><button aria-label="查看">查看</button></td></tr>
        <tr><td>${keyB}</td><td><button aria-label="查看">查看</button></td></tr>
      </tbody></table></main>
    `;
    const page = observeCurrentPage();
    const views = page.page.controls.filter((control) => control.label.startsWith('查看'));
    expect(views).toHaveLength(2);
    expect(views[0]?.label).toBe(views[1]?.label);
    const firstRow = document.querySelectorAll('.el-table tbody tr')[0]!;
    firstRow.querySelector('td')!.textContent = `${keyA.slice(0, -1)}C`;
    const element = page.targets.get(views[0]!.ref)!;
    const click = vi.spyOn(element, 'click');
    await expect(executeAgentAction(page, { kind: 'click', target: views[0]!.ref, message: '查看' }, {
      assertCurrent: () => undefined,
    })).rejects.toThrow(/stale|changed/);
    expect(click).not.toHaveBeenCalled();
  });
});
