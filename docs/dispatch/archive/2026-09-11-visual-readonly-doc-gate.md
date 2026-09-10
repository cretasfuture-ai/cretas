# FIX-VISUAL-READONLY-DOC-GATE-20260911

Owner: Drive协调者。Base SHA: c9a531147fd5c4de324636d39381bd840c79dc1d。

范围：AGENTS.md、.claude/skills/e2e-web-admin/SKILL.md及本归档；未修改运行时代码、测试或只读白名单。合并后释放范围，无服务部署。

视觉验收前发现原28条门禁中2条失败：兼容指针的Markdown反引号不符合既有CANONICAL标记，frontmatter英文CRUD触发禁止独立流程断言；AGENTS精简后未显式保留零写入和零拦截判据。

将指针标记恢复为既有机器可读格式，描述改为中文业务操作，补回与canonical技能一致的实际业务写入和blocked mutation零值要求。未跳过或修改任何断言。

验证：node --test scripts/e2e/production-readonly/tests/unit.test.js：28/28通过，0跳过；git diff --check通过。生产视觉验收另由唯一浏览器Owner执行；本修正自身不证明业务通过。
