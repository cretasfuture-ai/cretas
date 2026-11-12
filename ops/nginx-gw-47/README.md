# 47 上的网关 nginx 配置（2026-11-12 起）

139（原网关，阿里云账号 B）在 2026-11-12 0 点包年到期被欠费锁定，Steve 决定不续费，网关并入 47。
本目录是 47 上**现行配置的仓库副本**，用于审查和恢复。

| 文件 | 在 47 上的位置 | 作用 |
|---|---|---|
| `gw-00-upstream.conf` | `/etc/nginx/conf.d/` | `cretas_python`；`cretas_backend` 由 include 的 `/www/server/panel/vhost/nginx/_upstream_cretas.conf` 定义（`deploy-backend.sh` 每次蓝绿切换改写，内容形如 `server 127.0.0.1:10020;`） |
| `gw-01/02-*-map.conf` | 同上 | 原 139 的路径歧义 / 匿名词闸 map（来自 `ops/nginx-vhosts-139/`） |
| `gw-10-admin.conf` | 同上 | admin.cretaceousfuture.com，管理后台 `/www/wwwroot/web-admin` + API 转发 |
| `gw-11-www.conf` | 同上 | www 与根域名：官网 `/www/wwwroot/showcase/cretaceousfuture` + 与 admin 相同的 API 转发（App 的 API 入口是 www） |
| `gw-12-api.conf` / `gw-13-ota.conf` | 同上 | api 全量转 Java；ota 只转 `/api/ota/` 到 Python 8083 |
| `gw-14-centerapi.conf` | 同上 | 商城旧入口，返回 410 |
| `gw-15-download.conf` | 同上 | App 下载页 `/www/wwwroot/download`（`deploy-apk.sh` 发布；APK 本身在 dl CDN） |
| `gw-16-aiassist.conf` | 同上 | 工厂操作助手：`aiassist.html` + `/api/food-kb/` 转 Python |
| `gw-20-local-8086-8097.conf` | 同上 | 只监听 127.0.0.1 的 8086（prod web-admin）/ 8097（test web-admin），供发布脚本经 ssh 核对 |
| `snippets/*.conf` | `/www/server/nginx/conf/snippets/` | 原 139 的共享片段，后端地址改为 127.0.0.1 |

证书：Let's Encrypt，`/etc/letsencrypt/live/cretas-gw/`，覆盖上面 8 个域名，certbot（EPEL）定时续期，
续期后 `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh` 自动 reload。

`gen.py` 是生成这些文件的脚本：输入是 `ops/nginx-vhosts-139/` 里的 admin / api / snippets / map 副本和
`nginx/ota.cretaceousfuture.com.conf`（放到 `gen.py` 同目录后运行 `python gen.py https`，输出到 `out/`）。
改 47 上的配置时先改这里，再上传、`nginx -t`、`nginx -s reload`。

未迁移：`test.cretaceousfuture.com`（来源不明，DNS 仍指 139）；aiassist 下原有的 `/lsmsop/`、`/cysop/`
SOP 页面只存在于 139，仓库里没有源文件，暂缺。
