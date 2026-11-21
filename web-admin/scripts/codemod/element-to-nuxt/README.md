# Element Plus 到 Nuxt UI 4 迁移试点

在 `web-admin` 中运行。默认只读模式扫描 `src` 下的 Vue SFC。写入需要明确给出 `src/views` 下的文件名单。

```text
node scripts/codemod/element-to-nuxt/index.mjs --dry-run --report <out>/dry-run.json
node scripts/codemod/element-to-nuxt/index.mjs --dry-run --element-only --source-root <baseline>/web-admin --report <out>/dry-run.json
node scripts/codemod/element-to-nuxt/index.mjs --list scripts/codemod/element-to-nuxt/pilot.json --report <out>/pilot.json
node --test scripts/codemod/element-to-nuxt/transform.test.mjs
npx vitest run src/components/compat/compat.spec.ts
```

模板使用 Vue SFC AST，脚本使用 compiler-sfc 导出的 Babel parser，按位置编辑。转换会先检查二次运行是否会改变源码，再写入指定页面和中文词表。单文件报告列出组件出现位置和无法安全转换的原因。表格、表单等父子组件遇到未覆盖用法时整组保留，仍可转换组内独立按钮及文案。

- 标签覆盖率以模板 AST 中的 Element 开始标签实例数为分母，每个组件实例计一次。
- 文件覆盖率分别报告至少迁移一个标签及全部标签均可迁移的文件数，分母为含 Element 标签的 SFC。
- 文案数分别统计去重词表 key、已提取文案位置，以及自动判定不了语义的中文候选。候选包括业务值，例如状态代码和比较条件。

中文试点提供 `const { t } = useT()`。基础 PR 合并后，把导入换为 `import { useI18n as useT } from 'vue-i18n'`，中文词表交由基础设施加载。

## 本地画面对比

先分别启动基线和本分支的 Vite，依赖各自安装。传入本地两个不同的端口、外部证据目录和本机 `.env.test` 路径。脚本使用一个 headed Chromium、两个本地 origin 和普通网页登录。页面路由在发送前拦截所有非 GET，登录 POST 限定 LSMDEMO 账号和登录入口。截图与报告不包含登录页面、认证数据或请求体。

```text
node scripts/codemod/element-to-nuxt/visual.mjs http://127.0.0.1:5187 http://127.0.0.1:5198 <out>/shots <private-env-file>
```

画面统一为 1440×1000。每像素任一 RGB 通道差值超过 24 计为变化，超过 12% 的页面列入人工复核。业务数据缺失、路由重定向、接口错误及拦截事件均保留在报告。操作证据限定清单里的打开弹窗或空表单校验。
