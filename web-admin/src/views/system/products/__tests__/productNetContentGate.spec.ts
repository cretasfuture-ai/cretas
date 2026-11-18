import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { netContentGateMessage } from '../productNetContentGate';

const pageSource = readFileSync(resolve(__dirname, '..', 'index.vue'), 'utf8');

describe('SKU 净含量必须大于 0 (客户端先拦)', () => {
  it.each([[0], [-1], [null], [undefined], [''], [NaN], ['abc']])(
    '非半成品, 净含量 %s 被拦并给出提示',
    (value) => {
      expect(netContentGateMessage(value, false)).toBe('请填写大于 0 的净含量');
    },
  );

  it.each([[0.001], [1], [250], ['500']])('非半成品, 净含量 %s 放行', (value) => {
    expect(netContentGateMessage(value, false)).toBeNull();
  });

  it('半成品不要求净含量 (阴性对照: 同样的 0 不被拦)', () => {
    expect(netContentGateMessage(0, true)).toBeNull();
    expect(netContentGateMessage(null, true)).toBeNull();
  });

  it('提交入口 handleSubmit 真的调用了这道闸, 且被拦时 return 不发请求', () => {
    const submit = pageSource.slice(pageSource.indexOf('async function handleSubmit()'));
    const gate = submit.indexOf('netContentGateMessage(netContentAmount.value, isSemiFinishedSku.value)');
    expect(gate).toBeGreaterThan(-1);
    const afterGate = submit.slice(gate, gate + 300);
    expect(afterGate).toMatch(/ElMessage\.warning\(netContentMessage\);\s*return;/);
    // 闸必须在发请求 (post/put) 之前
    const firstRequest = submit.search(/await (post|put)\(/);
    expect(firstRequest).toBeGreaterThan(gate);
  });
});
