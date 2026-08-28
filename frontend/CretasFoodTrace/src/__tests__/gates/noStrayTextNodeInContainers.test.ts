/**
 * 🔒 不许把裸文本直接放进 <View> 这类容器 —— **真机上 RN 会抛**
 * `Text strings must be rendered within a <Text> component`，而 web 上只是一条 console error。
 *
 * <h3>这道闸怎么来的（2026-08-28）</h3>
 *
 * 前两轮走查在 web 上看到过这条 console error，台账把它记成
 * 「KPIReport / PurchaseOrderCreate / WHSettings 三屏里有裸文本节点」，
 * 并且**三次尝试定位都没找到**。
 *
 * 这一轮换成 AST 扫描（⛔ 不用正则数字符串，见 measurement-and-wiring 形态 C⁸），
 * 先用阳性对照证明尺子能读出 5 种形态，然后扫全仓 617 个 tsx：
 *
 *   命中 0 —— 那三屏自己**一个都没有**，放宽到「裸标识符」也没有。
 *
 * ⇒ 台账里那句「这三屏里有裸文本」是错的。真正的来源只能是它们渲染的**子组件**
 *   或某个三方组件，而那条 console 消息不带组件栈，无法据此定位。**这一点仍然挂账。**
 *
 * <h3>那这道闸守什么</h3>
 *
 * 守「这一类不许**新**进来」。今天源码里是 0，所以它是一道真正的零容忍闸，
 * ⛔ 不是棘轮 —— 棘轮只在有存量时才需要。
 */
import * as fs from 'fs';
import * as path from 'path';
import * as ts from 'typescript';

const SRC = path.join(__dirname, '../..');

/** 会把 children 直接渲染、且不接受裸字符串的容器。 */
const CONTAINERS = new Set([
  'View', 'ScrollView', 'SafeAreaView', 'TouchableOpacity', 'Pressable',
  'KeyboardAvoidingView', 'Modal', 'Surface',
]);

type Hit = { file: string; line: number; container: string; sample: string };

function tagName(open: ts.JsxOpeningElement | ts.JsxSelfClosingElement): string {
  return open.tagName.getText();
}

function scanSource(file: string, text: string): Hit[] {
  const src = ts.createSourceFile(file, text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const hits: Hit[] = [];
  const visit = (node: ts.Node): void => {
    if (ts.isJsxElement(node) && CONTAINERS.has(tagName(node.openingElement))) {
      const container = tagName(node.openingElement);
      for (const c of node.children) {
        let sample: string | null = null;
        if (ts.isJsxText(c) && c.getText().trim().length > 0) {
          sample = c.getText().trim().slice(0, 60);
        } else if (ts.isJsxExpression(c) && c.expression) {
          const e = c.expression;
          const isStr =
            ts.isStringLiteral(e) ||
            ts.isTemplateExpression(e) ||
            ts.isNoSubstitutionTemplateLiteral(e) ||
            (ts.isConditionalExpression(e) &&
              (ts.isStringLiteral(e.whenTrue) || ts.isStringLiteral(e.whenFalse))) ||
            (ts.isBinaryExpression(e) &&
              e.operatorToken.kind === ts.SyntaxKind.AmpersandAmpersandToken &&
              (ts.isStringLiteral(e.right) ||
                ts.isTemplateExpression(e.right) ||
                ts.isNoSubstitutionTemplateLiteral(e.right)));
          if (isStr) sample = c.getText().replace(/\s+/g, ' ').slice(0, 80);
        }
        if (sample !== null) {
          hits.push({
            file, container, sample,
            line: src.getLineAndCharacterOfPosition(c.getStart()).line + 1,
          });
        }
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(src);
  return hits;
}

function walk(dir: string, out: string[] = []): string[] {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name === '__tests__') continue;
      walk(p, out);
    } else if (e.name.endsWith('.tsx')) {
      out.push(p);
    }
  }
  return out;
}

/** 阳性对照：五种真机会抛的写法，尺子必须**全部**读得出来。 */
const CONTROL = `
import React from 'react';
import { View, Text } from 'react-native';
export function Ctl({ n }: { n: number }) {
  return (
    <View>
      裸文本
      <Text>正常</Text>
      {' '}
      {\`模板串 \${n}\`}
      {n > 0 && '短路出来的字符串'}
      <View>{'字符串字面量'}</View>
    </View>
  );
}`;

describe('容器里不许有裸文本节点', () => {
  it('阳性对照：五种写法一个都不能漏 —— 漏了的话下面那个 0 就不可判定', () => {
    const hits = scanSource('control.tsx', CONTROL);
    expect(hits.length).toBe(5);
  });

  it('阴性对照：规规矩矩包在 <Text> 里的不许被报出来', () => {
    const clean = `
import React from 'react';
import { View, Text } from 'react-native';
export const A = () => (<View><Text>{'包好了'}</Text><Text>也包好了</Text></View>);`;
    expect(scanSource('clean.tsx', clean)).toEqual([]);
  });

  it('全仓零容忍：源码里一处都不许有', () => {
    const files = walk(SRC);
    expect(files.length).toBeGreaterThan(400); // 自检：真的扫到了东西
    const hits = files.flatMap((f) => scanSource(f, fs.readFileSync(f, 'utf8')));
    const report = hits
      .map((h) => `  ${path.relative(SRC, h.file)}:${h.line} <${h.container}> ← ${h.sample}`)
      .join('\n');
    // jest 的 expect 只收一个参数 —— 报告用抛错带出来
    if (hits.length > 0) {
      throw new Error(`真机上这些会抛 Text strings must be rendered:
${report}`);
    }
    expect(hits.length).toBe(0);
  });
});
