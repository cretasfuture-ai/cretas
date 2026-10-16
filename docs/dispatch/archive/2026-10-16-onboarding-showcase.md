# 对话建厂官网恢复与本地验收

任务 `ONBOARDING-SHOWCASE-20261016`，Base `394a3183c0d2761c2668ecd5d52b89ad0e23eeae`，
独立工作区 `C:/Users/Steve/cretas-onboarding-showcase-20261016`，
分支 `codex/factory-onboarding-showcase-20261016`。用户要求恢复重启中断的官网 side chat 并继续。

## 恢复依据与范围

原 side chat 的完整消息未恢复；已核对同日独立工作区、ACTIVE 登记、HTML/CSS、
既有截图和 PDF 脚本，按保存的任务范围继续。没有恢复或执行历史中的其他任务。

这条线负责官网对外说明：从代表性产品、七个业务领域、方案复核到首批业务验收和持续扩展。
平台管理员组织建厂配置，工厂人员进入日常业务模块；官网不是第二套建厂或业务实现。
保留既有品牌、共享 V6 CSS/JS 和动画文件。仅新增首页入口、食品工厂页入口、
独立详情、专属 CSS、四组公开配图、8 页介绍 PDF 及复用/验收脚本。

## 发现与处理

- 原成本明细定位把 `el-card__header` 当整卡，只截标题；改为 class token 精确匹配。
- 原追溯截图被内部滚动容器截断，缺实际出货；截图 helper 扩展独立 viewport，
  增加只补拍指定页面入口，拒绝任何非只读请求。
- 本轮原业务服务 31764 未启动，补拍尝试明确 connection refused，未算通过，
  未重启主线程服务、写业务库或重新演练业务。
- 复用主线程已保存的 `p15-final-boxed-trace.png`、`boxed-batch-cost.png` 的真实局部，
  补齐原料、生产、实际出货和完整成本；原图不改，公开文件只裁切、编码。
- 经营指标原截图已有四个完工批次，改掉“两个完工批次”的图片说明，保留同期间表述。
- 修正原图尺寸/纵横比、手机导航遮挡和实施流程区贴边。
- 所有数据标注本地模拟快照；明确已出货不等于质检放行，不承诺客户上线成效。

## 验收

- `verify-onboarding.cjs` 最终 12/12 PASS：三页面乘四宽度，0 错误资源、
  0 页面异常、0 横向溢出；详情折叠、锚点、PDF 下载通过，下载 SHA 与公开 PDF 一致。
- `render-onboarding-pdf.py` 最终 8/8 页渲染、文本边界和实际使用字体嵌入通过；
  已查看全部页联系表，并单独放大核对经营指标、追溯、成本页，排版及截图完整。
  第一轮校验把 ReportLab 未使用的 Helvetica 资源误算成未嵌入字体，保留失败事实；
  修正为只核对实际文字使用字体后通过，未降低使用字体嵌入要求。
- `git diff --check` 和 `python scripts/ci/check_dispatch_active.py` 通过。
- 共享 V6 CSS/JS 相对 Base 无改动；本任务业务写入 0，原业务测试证据不重跑。

代码外证据：`C:/Users/Steve/Documents/cretas-onboarding-showcase-20261016/`，
`captures/asset-provenance.json`、`website-qa/responsive-receipt.json`、
`pdf-review/render-receipt.json`、8 页 PNG 及网站截图。

## 体验与未完成边界

独立静态预览 `http://127.0.0.1:31814/factory-onboarding.html`，首页 `/` 可看到新增入口。
PDF `/downloads/cretas-factory-onboarding.pdf`。本地预览进程与原主线程端口隔离。
未 push、PR、合并、部署或生产写入；未声称重新补拍 helper 已在重启后的真实业务运行时通过。
既有真实截图的复用与静态页面/PDF 本地验收已完成，等待用户体验。
