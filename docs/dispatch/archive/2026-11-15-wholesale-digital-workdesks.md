# WHOLESALE-DESKS-20261115 收尾

- Owner /root；初始 Base `3c1ed4be3f313c7b3692c7652c8f21d83ba7590c`，scope 已释放。
- 源码 PR #1734，候选 `c7700aefc99b08b8955c28158cf7c50fad34f28a`，13 success / 11 skipped 后 squash 合并为 `8016607cf396bfd30544a83b9f180cd95b23a870`。
- 最终发布父提交 `ad9ac3718ec2813522e8b3741029e56c37bd2ff8` 已含 #1733 新组件环境。整合版重新验证，未复用旧深色壳截图冒充新版。

## 结果与边界

显式 wholesale profile 下，销售、采购、库存分别内嵌原有岗位工作台；头像使用 11-14 终版组件库的 clerk/keeper 原文件。没有独立数字班组菜单。客户/供应商、商品及数据分析菜单的既有配置保留。默认租户原菜单归属与标签通过 UI 对照；本次未改租户配置、角色权限或业务数据。

销售复用客户跟进/订单查询及原 AI 开单抽屉；采购使用线上可用的低库存查询、原 AI 采购开单及供应商入口；库存复用待收查询、原入库任务/库存/出货页面。试用租户缺旧周采购计划配置，未将它冒充可用。自动分单与直送执行继续列未接入。未创建订单、请购、收货或签收数据，未验证真实交易闭环。

## 验证索引

- 候选：30 files / 208 tests、vue-tsc -b、统一 Web build 通过；独立源码审查通过。
- exact main `8016607c` + Nuxt UI 环境：31 files / 216 tests（含 csThemeLayer）、vue-tsc -b、统一 Web build 通过；新壳源码整合终审通过。
- 本体命令：`node node_modules/vitest/vitest.mjs run src/components/layout/__tests__ src/components/workdesk/__tests__/DigitalEmployeeFrame.spec.ts src/router/__tests__/digitalEmployeeProps.spec.ts src/composables/__tests__/useAiEntryLink.spec.ts src/views/workdesk/__tests__/PurchaserWorkdesk.queryMode.spec.ts src/__tests__/gates/csThemeLayer.spec.ts`；随后顺序执行 `node node_modules/vue-tsc/bin/vue-tsc.js -b`，exit 0。
- 新外壳浏览器：三岗位菜单/头像、销售采购原 AI 抽屉、库存原入库任务跳转、默认租户原菜单对照；390x844 文档宽384px，头像64px。
- 上线后界面抽查：批发商试用采购岗位标题/头像/当前企业/低库存结果/业务链接可见，无控制台 error；未运行完整生产业务闭环或宣称标准只读 harness 全量通过。
- 私有证据：`Documents/批发商数字员工-20261115/`（`*-新版UI-8016607c.*` 为新壳；`采购工作台-线上.*` 为上线抽查），测试/构建/发布日志在 `.codex/tmp/wholesale-desks-*`，不含凭据。

## 发布

标准入口 `scripts/deploy/release-cretas.sh`；新壳 build 报告 `cretas-1794715632-23380.json`；部署报告 `cretas-1794716274-26668.json`（本机 `.cache/cretas/release-reports/`）。

部署 exit 0，`RELEASE_FINAL_STATUS=deployed` 恰好一次；仅 Web，build reused / build_count 0，Java not-needed。Web tree `bf9fd24d65a2c607e51c3d622b4668a589b0219a`；本地/服务器/网关/公网四方 index SHA256 均为 `10954626dd9cd28682923de9e962162a2ae48f566bf5c223adb6d1ac7ca5dd1a`，入口 chunk HTTP 200。备份及回退由发布脚本保留。

首次候选构建因本任务 dev server 占用 esbuild.exe 失败，关闭后重试成功；未削弱门禁。收尾时本任务 dev server、类型检查、CI watcher 均已结束。
