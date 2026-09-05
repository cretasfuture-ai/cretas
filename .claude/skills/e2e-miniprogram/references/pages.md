# MallCenter 小程序页面清单（43 页）

## 主包（22 页）

| 路径 | 说明 | 测试套件 |
|------|------|----------|
| `pages/home/index` | 首页 | core |
| `pages/base/search/index` | 搜索 | core |
| `pages/base/webview/index` | WebView | - |
| `pages/auth/login/index` | 登录 | core |
| `pages/auth/register/index` | 注册 | core |
| `pages/auth/bind-merchant/index` | 商家绑定 | merchant |
| `pages/goods/goods-category/index` | 商品分类 (Tab) | shopping |
| `pages/goods/goods-list/index` | 商品列表 | shopping |
| `pages/goods/goods-detail/index` | 商品详情 | shopping |
| `pages/goods/price-calculator/index` | 价格计算器 | shopping |
| `pages/shopping-cart/index` | 购物车 (Tab) | shopping |
| `pages/order/order-confirm/index` | 订单确认 | shopping |
| `pages/order/order-list/index` | 订单列表 | shopping |
| `pages/order/order-detail/index` | 订单详情 | shopping |
| `pages/order/order-logistics/index` | 物流跟踪 | shopping |
| `pages/appraises/form/index` | 评价表单 | shopping |
| `pages/user/user-center/index` | 个人中心 (Tab) | user |
| `pages/user/settings/index` | 设置 | user |
| `pages/user/user-address/list/index` | 地址列表 | user |
| `pages/user/user-address/form/index` | 地址编辑 | user |
| `pages/notification/index` | 通知 | user |
| `pages/orders/checkout/index` | 结算 | shopping |

## 分包 — 溯源（3 页）

| 路径 | 说明 | 测试套件 |
|------|------|----------|
| `pages/traceability/scan/index` | 扫码溯源 | trace |
| `pages/traceability/detail/index` | 溯源详情 | trace |
| `pages/traceability/quality-report/index` | 质量报告 | trace |

## 分包 — AI RAG（2 页）

| 路径 | 说明 | 测试套件 |
|------|------|----------|
| `pages/ai-rag/chat/index` | AI 客服对话 | ai |
| `pages/ai-rag/history/index` | 对话历史 | ai |

## 分包 — AI 分析（3 页）

| 路径 | 说明 | 测试套件 |
|------|------|----------|
| `pages/ai-analysis/product/index` | 产品分析 | ai |
| `pages/ai-analysis/factory/index` | 工厂分析 | ai |
| `pages/ai-analysis/industry/index` | 行业分析 | ai |

## 分包 — 推荐/分销（3 页）

| 路径 | 说明 | 测试套件 |
|------|------|----------|
| `pages/referral/share/index` | 分享 | user |
| `pages/referral/my-referrals/index` | 我的推荐 | user |
| `pages/referral/rewards/index` | 奖励 | user |

## 分包 — 商家中心（9 页）

| 路径 | 说明 | 测试套件 |
|------|------|----------|
| `pages/merchant-center/index/index` | 商家首页 | merchant |
| `pages/merchant-center/orders/index` | 订单管理 | merchant |
| `pages/merchant-center/product-list/index` | 商品管理 | merchant |
| `pages/merchant-center/product-edit/index` | 商品编辑 | merchant |
| `pages/merchant-center/staff/index` | 员工管理 | merchant |
| `pages/merchant-center/stats/index` | 数据统计 | merchant |
| `pages/merchant-center/settings/index` | 商家设置 | merchant |
| `pages/merchant-center/shop-design/index` | 店铺装修 | merchant |
| `pages/merchant-center/decoration-chat/index` | AI 装修 Chat | ai, decoration |

## TabBar

| Tab | 页面 |
|-----|------|
| 首页 | `pages/home/index` |
| 分类 | `pages/goods/goods-category/index` |
| 购物车 | `pages/shopping-cart/index` |
| 我的 | `pages/user/user-center/index` |
