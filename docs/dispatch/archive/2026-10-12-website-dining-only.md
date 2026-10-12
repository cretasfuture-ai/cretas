# WEB-DINING-ONLY-20261012

- Owner `/root`; Base `cec0839cab2a05ffc9228e5ad619a2cb6bc7803c`; branch `codex/website-dining-only-20261012`.
- 用户要求撤回上一版内容和动效优化，仅移除餐饮部分。
- 八个主页面以原版 `3ee3db1dad527404cd251c61947e6844628cd9cf` 重建，仅移除餐饮导航/页脚、方案卡、案例、演示、驾驶舱截图及相关餐饮文案；业务卡4改3、截图3改2为必要布局调整。
- 原主标题、CTA、首屏/视觉带图片、品牌开场、文案与动效恢复。五份CSS/JS与修改前线上保存的原始文件逐字节一致；不将Git里已与原线上不同的动画版本冒充原版。
- 已移除的餐饮旧入口跳转与sitemap移除继续保留，未再次修改。
- 发布工具只改白名单13个本次变化文件，并使用上一版已验证线上commit作基线；REMOTE、生产确认、expected-head、备份、guarded rollback、测试内容均未变，复用18个Linux测试证据。
- 新验证：13文件线上只读baseline通过；8页×1440/390无餐饮入口、无相关文案、无横向溢出；正常动效原开场与滚轮进入通过，原主标题/hero路径/三业务卡断言通过；四份JS语法与diff检查通过。
- 本地证据 `C:/Users/Steve/cretas-website-dining-only-evidence-20261012/`，含原始资产SHA证明及桌面/手机截图。PR、合并、发布及线上验收分别记录最终回执。
