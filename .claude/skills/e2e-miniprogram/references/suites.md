# 测试套件定义与脚本目录

## 套件

### core — 核心流程
```
1.1 首页加载      reLaunch → pages/home/index → data() 验证
1.2 登录页        navigateTo → pages/auth/login → path 验证
1.3 跳过登录      tap .skip-login → 验证回到首页
1.4-1.6 Tab 导航  switchTab × 3 → path 验证
```

### shopping — 购物流程
```
2.1 商品分类      switchTab → goods-category → data() 验证
2.2 商品列表      navigateTo → goods-list → path 验证
2.3 购物车        switchTab → shopping-cart → data() 验证
```

### user — 用户中心
```
3.1 个人中心      switchTab → user-center → data() 验证
3.2 设置页        navigateTo → settings → screenshot
```

### merchant — 商家中心
```
4.1 商家首页      navigateTo → merchant-center/index → data() 验证
4.2 商品管理      navigateTo → product-list → data() 验证
4.3 店铺装修      navigateTo → shop-design → FAB 按钮验证
```

### ai — AI 功能
```
5.1 AI RAG 对话   navigateTo → ai-rag/chat → data() 验证初始化
5.2 产品分析      navigateTo → ai-analysis/product → screenshot
5.3 装修 Chat     navigateTo → decoration-chat → data() 验证:
    - quickQuestions 4 条 / themes 15 套 / sessionId 存在
    - 发送消息 → 轮询 AI 回复 → 验证 themeCard/action
```

### trace — 溯源功能
```
6.1 扫码页        navigateTo → traceability/scan → screenshot
6.2 溯源详情      navigateTo → traceability/detail?id=TEST001 → data() 验证
```

## 连接验证（setup 用）

```bash
cd tests/e2e-miniprogram && node -e "
const a = require('miniprogram-automator');
a.connect({wsEndpoint:'ws://localhost:9420'}).then(async mp => {
  const p = await mp.currentPage(); console.log('Connected! Page:', p.path); await mp.disconnect();
}).catch(e => console.error('Failed:', e.message));
"
```

## 脚本目录 `tests/e2e-miniprogram/`

| 文件 | 用途 |
|---|---|
| `run-e2e.js` | 主测试脚本（6 suites, 16 tests） |
| `final-e2e.mjs` | 装修 Chat 专项验证 |
| `quick-test.js` | 快速连接验证 |
| `verify-fixes.js` / `verify-extended.js` / `verify-functional.js` / `verify-shop-design.js` | 修复 / 扩展 / 功能 / 店铺装修验证 |
| `screenshots/`、`results.json`、`E2E_TEST_REPORT.md` | 产出 |
