---
name: e2e-miniprogram
description: 微信小程序 E2E 自动化测试。通过 miniprogram-automator 连接微信开发者工具，执行页面导航、元素交互、数据验证。覆盖 MallCenter 小程序 43 个页面。用户说小程序 E2E、miniprogram-automator、微信开发者工具自动化时触发。
allowed-tools:
  - Bash
  - Read
  - Write
  - Edit
  - Glob
  - Grep
---

# 微信小程序 E2E 自动化测试

Node 脚本经 WebSocket 9420 连微信开发者工具（DevTools）驱动 `MallCenter/mall_miniprogram`。本 skill 用 `miniprogram-automator` 库直接写测试脚本；`weapp-dev` MCP 可作辅助交互，但脚本更可靠。

## 子命令（`$ARGUMENTS`）

| 输入 | 动作 |
|------|------|
| _(空)_ / `run` | 运行全部套件（`node run-e2e.js`） |
| `run core|shopping|user|merchant|ai|trace|decoration` | 单套件（定义见 `references/suites.md`） |
| `setup` | 启动 DevTools 自动化并验证连接 |
| `check` | 环境连通性（node ≥ 18、9420 端口、后端 `http://139.196.165.140:8080/actuator/health`） |
| `verify <feature>` | 验证指定功能的数据正确性 |

## 前置条件

- DevTools（Windows）`C:\Program Files (x86)\Tencent\微信web开发者工具\cli.bat`；设置 → 安全设置里开启**服务端口**与**自动化测试**。
- `cd tests/e2e-miniprogram && npm install`（miniprogram-automator）。

## ⚠️ 用 `connect()`，不用 `launch()`

`automator.launch()` 在 Windows 上因 `child_process.spawn()` 处理不了含中文和空格的路径而失败。先在新终端启动自动化模式，再 `connect`：

```bash
"C:\Program Files (x86)\Tencent\微信web开发者工具\cli.bat" auto --project "C:\Users\Steve\my-prototype-logistics\MallCenter\mall_miniprogram" --auto-port 9420
```

```javascript
const mp = await require('miniprogram-automator').connect({ wsEndpoint: 'ws://localhost:9420' });
```

9420 被旧进程占住无响应：关 DevTools → `netstat -ano | findstr 9420` → `taskkill /PID <pid> /F` → 重新 `cli.bat auto`。

## 经实测可靠的编程模式

- **`navigateTo` / `reLaunch` 的 Promise 经常不 resolve（导航其实成功了）**：用 `Promise.race` 包 15s 超时，catch 掉，`sleep(2000)` 后用 `mp.currentPage()` 确认（`safeNav(mp, method, url)`）。
- **`page.data()` 是最可靠的验证手段**，比 DOM 选择器稳；`page.setData` 模拟输入，`page.callMethod` 同样要超时包装，异步结果轮询 `data()`。
- **`screenshot()` 在 `mp` 上不在 `page` 上**（`mp.screenshot({ path })`），某些版本会超时，包 try/catch。
- 自定义组件内部 `page.$` / `waitFor` 穿不透 → `page.data()` + 轮询。
- 自动化模式下小程序发不了真实 HTTPS：验证已有 / 缓存数据、`setData` 注入 mock、后端 API 用 `curl` 独立验。
- 分包页面先 `reLaunch('/pages/home/index')` 再导航；"page not found" 先查 `app.json` 注册；`data()` 返回 `{}` 是页面还没加载完，多等 2–3s。

API 速览：`mp.navigateTo/reLaunch/switchTab/navigateBack/currentPage/screenshot/disconnect`；`page.path/data()/setData()/callMethod()/$()/$$()/waitFor()`；`el.tap()/input()/value()/wxml()/attribute()`。

## 输出

按 `# | 测试 | 套件 | 状态 | 耗时 | 备注` 列表，总计 X/Y，截图目录 `tests/e2e-miniprogram/screenshots/`，结果 `results.json`。

## References

- `references/pages.md`：43 页清单（主包 / 分包）与 TabBar。
- `references/suites.md`：6 个套件的步骤定义与测试脚本目录结构。
