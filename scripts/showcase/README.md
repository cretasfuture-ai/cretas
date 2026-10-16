# 对话建厂官网介绍与客户 PDF

本目录只处理静态官网内容和演示材料，不实现平台建厂或工厂日常业务功能。
沿用 `platform/css/v6.css` 和既有首页动画，仅新增专属 CSS、内容和入口。

## 本地预览

在仓库根目录运行，独立使用 31814，不占用业务系统端口：

```powershell
& 'B:\anaconda3\python.exe' -m http.server 31814 --bind 127.0.0.1 --directory platform
```

访问 `http://127.0.0.1:31814/factory-onboarding.html`。

## 素材和 PDF

真实本地 UI 截图使用模拟工厂数据。私有登录态、原始验收记录和渲染输出留在代码外；
不要将登录态或业务接口回包放进 `platform/`。公开素材只做矩形裁切、格式转换，
不重绘数据或拼造界面。现有完整业务截图可复用，不要求为了更新官网重跑业务。

```powershell
& 'B:\anaconda3\python.exe' scripts/showcase/prepare-onboarding-assets.py --captures '<私有截图目录>' --business-evidence '<原业务验收目录>'
& 'B:\anaconda3\python.exe' scripts/showcase/build-onboarding-pdf.py --captures '<私有截图目录>' --output '<代码外PDF路径>'
& 'B:\anaconda3\python.exe' scripts/showcase/render-onboarding-pdf.py --pdf '<代码外PDF路径>' --output '<代码外渲染目录>'
```

PDF 构建同时生成 `platform/downloads/cretas-factory-onboarding.pdf`，逐页渲染后必须人工检查。
图片裁切坐标与已审核的原截图尺寸绑定，替换截图时重新核对裁切范围。

需要重新截图时，使用 `capture-onboarding.cjs`，设置 `SHOWCASE_CAPTURE_DIR`、
`SHOWCASE_PLATFORM_STATE`、`SHOWCASE_FACTORY_STATE` 和可选 `SHOWCASE_APP_ORIGIN`。
仅允许本地 GET/HEAD/OPTIONS，拒绝业务写入；使用独立浏览器，不占用用户标签页。
`SHOWCASE_CAPTURE_ONLY=traceability,cost` 可仅补拍缺失项。

## 静态页面验收

```powershell
$env:SHOWCASE_PLAYWRIGHT_PATH='<已有playwright包绝对路径>'
$env:SHOWCASE_VERIFY_DIR='<代码外验收目录>'
node scripts/showcase/verify-onboarding.cjs
```

覆盖首页、食品工厂页、对话建厂详情页的 1440/1024/390/320 四种宽度，
检查资源、横向溢出、折叠说明、锚点和 PDF 下载哈希。已有共享浏览器用于其他任务，
本地预览采用独立 Chromium。脚本结果与 PDF 人工逐页验收分别记录。

当前仅本地体验，不自动推送、合并或部署。
