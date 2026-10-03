# F006 老板文本问答的 Mini 试点

本文件是可审查的准备方案，不是部署或验收回执。

只处理可信 Java JWT 身份中的 F006 / factory_super_admin / 正整数 userId 的普通文本咨询。库存、订单等仍走原来的只读工具和权限门。Python exact route `/api/llm/f006-boss-question-stream` 必须通过既有内部 secret，再检查三项身份；请求 body 不接受工厂、角色、模型、tools、images、URL。Java `cretas.f006.boss-mini.enabled` 默认为 false，Python `F006_BOSS_MINI_ENABLED` 默认为未启用。启用后该通道的忙、超时、空流或未完成回答明确失败，不自动转云。

模型固定 `qwen3.8:27b`；Python 固定访问 `127.0.0.1:11435/api/chat`，禁用代理环境和重定向。使用原生 Ollama stream 的终帧和真实 prompt_eval_count/eval_count；未知 usage 不记成零 token。本试点新增的 provider/client 操作日志记录身份范围、provider/model、关联 ID、状态、延迟和 usage 是否可用，不记录明文问题、答案、secret、headers 或请求体；手机流式请求移除正文 console，Java stream 入口仅记 inputLength。

这不代表既有全应用日志已完成隐私治理。GENERAL_QUESTION→Mini 早路必经的 IntentKnowledgeBase 通用问题检测、短语命中/跳过、AnalysisRouterServiceImpl 分析检测四条日志也改为 inputLength。启用前必须运行真实 DEBUG Logback 捕获门禁，证明问题标记不出现，并由发布回执证明线上是该源码制品。旧版本的有效 logger 读取目前为 404，不能声称 DEBUG 已关闭，旧制品不得启用此试点。其他分类/分析执行、非 stream AI 入口不在本批保证范围；排查时不把已有日志正文复制到报告。

## 连接准备

生产 47 主机目前无法直连 Mini 的 Tailscale 地址。计划由 `canada-mini` 向 47 发起专用反向 SSH，只将 47 的 **127.0.0.1:11435** 转到 Mini 的 **127.0.0.1:11434**；不启用 GatewayPorts，不安装 Tailscale，不开放公网模型端口，不改原有 Ollama/ERP/CI 服务。

部署前先确认：两侧主机身份/现有 SSH host key；47 的 11435 未占用；专用用户、key、sshd drop-in、Mini LaunchDaemon 文件均不存在或已有同任务明确所有权。任一同名资源被其他任务占用则停止，不覆盖。检查现有 sshd Include/Match 规则，不能假设模板会被加载。

47 专用用户拟为 `cretas-mini-f006`，禁止交互登录，仅允许 remote forwarding。专用公钥不进仓库；私钥仅在 Mini 以 0600 保存，不回传本机或生产。authorized_keys 必须限制 `restrict,port-forwarding,permitlisten="127.0.0.1:11435"`，禁止 PTY/agent/X11；再以独立 Match User 约束：

```text
Match User cretas-mini-f006
    AuthenticationMethods publickey
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AllowTcpForwarding remote
    AllowStreamLocalForwarding no
    GatewayPorts no
    PermitListen 127.0.0.1:11435
    PermitOpen none
    MaxSessions 0
    X11Forwarding no
    AllowAgentForwarding no
    PermitTTY no
Match all
```

修改前保存该范围 baseline 和旧文件，先执行 `sshd -t`、`sshd -T -C user=cretas-mini-f006,host=localhost,addr=<Mini出口地址>` 证明 Match 生效。仅在验证成功后 reload sshd，不重启共享业务服务；保留已建立的管理连接。账户可用性与密钥验证以真实握手为准，不修改全局密码/SSH 登录策略。

Mini 改为独立的 root-owned LaunchDaemon 与专用非交互 UID `_cretas_f006_tunnel`，不使用 rex 的 login agent、SSH agent、CI key 或 home。先核对账号与 UID 未占用，不能覆盖同名资源；home 为 `/var/empty`，shell 为 `/usr/bin/false`。专用目录 `/var/db/cretas-f006-mini` 归 root，SSH 私钥只允许专用 UID 读取（0600），known_hosts 与启动配置只由 root 修改。所有父目录禁止普通用户写入。私钥在 Mini 生成且留在 Mini，公钥只走本任务 gitignored 配置。

服务器 authorized_keys 还要增加 `from="<本次实际握手验证的Mini出口IPv4>"`。只填写单个已验证地址，不用宽 CIDR；出口变化时先拒绝，重新核验再改该专用 key。`permitlisten`/`PermitListen` 限制的是生产监听地址；`PermitOpen none`、host-key pin 和 from 均不能当作 Mini 反向目标地址限制。

可审查的 LaunchDaemon 配置如下，实际用户名、目录、server host key 与 ownership 均须在安装前读回核实。文件 `/Library/LaunchDaemons/com.cretas.f006-mini-tunnel.plist` 为 root:wheel/0644；部署前 `plutil -lint`，随后用 launchctl 读回加载的参数与 PID。服务只加载这个绝对路径，不经过 shell。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.cretas.f006-mini-tunnel</string>
  <key>UserName</key><string>_cretas_f006_tunnel</string>
  <key>ProgramArguments</key><array>
    <string>/usr/bin/ssh</string><string>-F</string><string>/dev/null</string>
    <string>-N</string><string>-T</string>
    <string>-o</string><string>BatchMode=yes</string>
    <string>-o</string><string>IdentitiesOnly=yes</string>
    <string>-o</string><string>IdentityAgent=none</string>
    <string>-o</string><string>ControlMaster=no</string>
    <string>-o</string><string>StrictHostKeyChecking=yes</string>
    <string>-o</string><string>UserKnownHostsFile=/var/db/cretas-f006-mini/known_hosts</string>
    <string>-o</string><string>GlobalKnownHostsFile=/dev/null</string>
    <string>-o</string><string>KnownHostsCommand=none</string>
    <string>-o</string><string>VerifyHostKeyDNS=no</string>
    <string>-o</string><string>UpdateHostKeys=no</string>
    <string>-o</string><string>ExitOnForwardFailure=yes</string>
    <string>-o</string><string>ServerAliveInterval=15</string>
    <string>-o</string><string>ServerAliveCountMax=3</string>
    <string>-i</string><string>/var/db/cretas-f006-mini/id_ed25519</string>
    <string>-R</string><string>127.0.0.1:11435:127.0.0.1:11434</string>
    <string>cretas-mini-f006@47.100.235.168</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>30</integer>
</dict></plist>
```

known_hosts 由已有可信服务器管理连接读出的 host public key 与已保存指纹核验后写入，不仅信任一次 ssh-keyscan。root 管理固定参数，专用 UID 与受管 SSH 进程属于此短期试点的可信边界；它保护其他普通账号/runner不能取得该 key、改目标或替换启动文件，不能声称抵抗该 UID/SSH 进程本身失陷，也不声称固定 IP 的 OS egress 隔离。

2026-10-04 实机准备验证：macOS26.4 的 sandbox-exec 存在但已弃用。具体IP SBPL 探针被编译器拒绝（host must be * or localhost）；随后放宽到localhost/*端口的探针也未取得预期11434正向成功。两次均未安装profile、创建UID/key/daemon或激活tunnel，没有模型请求或业务写。首次探针的 networkPolicyCompiled 字段原以错误消息字符串推断，已明确纠正为 false 并保留原始证据；不能把错误目标连接0当成policy成功。当前不会安装未验证的Seatbelt profile。

若后续验收必须抵抗持钥进程并在错误反向目标前阻止明文prompt，需要另立两UID+mTLS loopback gateway范围：生产客户端固定私有CA/SAN且先完成TLS再发body，tunnel UID不能读取gateway TLS key，gateway UID不能读取SSH key，gateway固定转发Ollama11434。该加强方案尚未实施，也不属于当前默认关闭代码的已验收事实。

连接启用前的实际门禁：专用UID不能交互登录；其他普通UID不能读取key或改plist/known_hosts；生产key不能shell、PTY、本地/Unix forwarding或监听其他端口；sshd有效配置与实际socket仅为127.0.0.1:11435；错误host key令受管连接失败；固定参数正确且tags/有完整stop终帧的文本流成功。任一不成立不启用feature，按具体PID/Label回退，不改Ollama、runner或其他账号。

当前安装条件：rex 的 `sudo -n true` 返回需要密码。管理员安装尚未执行；不得把手机USB权限当成这一步的原因，也不在聊天索取Mac密码。待Mac本机管理员完成专用账户/目录/key/LaunchDaemon步骤并读回，协调者再安装生产专用公钥、验证连接，最后启用F006 feature并验手机。代码保持默认关闭，可独立合入与发布。

生产当前是单主机、两个 Python worker。试点除进程内无队列锁外，使用本机私有临时目录中的空 SQLite 协调文件持有 OS 事务锁，其他 worker 立即返回忙；进程退出自动释放。该文件不保存问题、答案或 ERP 数据。启用前再读回实例数、服务用户及临时文件系统；若改为多个主机或容器各有独立临时目录，必须停止启用并调整协调方案。

运行时须确认两个 worker 的临时目录和 lease inode 相同、目录 0700 / 文件 0600 由服务用户拥有，且没有其他 mount namespace 或第二实例绕过该文件。服务活动期间禁止清理、unlink 或替换 lease 文件/目录；默认长期保留空协调文件。回退先关闭 feature 并排空请求，再处理专用资源。

## 发布与验收

R1、R2 的合入和发布分别记录；R2 从 clean exact origin/main 经仓库统一发布入口，Python 新端点先部署（仍禁用），Java 已验证内认证 client 后再启用试点。内部 secret 继续使用现有 gitignored 配置真值，不添加公开默认 secret。

真机以 F006 老板登录，并保存请求前 identity / 新问题点击 / Java 路由事件 / Python provider 关联 ID / Mini 终帧 usage / 手机答案的同一次链路证据。其他角色、其他工厂的负向请求不得进入模型。断线、忙、超时、未知 usage 和无终帧须分别证明 UI 明确失败或未知状态，不能以 Mini 本机 canary 代替手机验收。也不能以功能测试断言已经完成了全角色、全业务流程。

## 回退

先关闭 Java F006 feature 和 Python feature，读回非 Mini 通道行为；按本次记录的具体 Label 退出 Mini 专用 LaunchDaemon，确认 47 的 11435 监听消失。仅撤销本次专用 authorized key / sshd Match 文件（保留 baseline），验证 `sshd -t` 后 reload。仅处理本任务新建资源，不删除或重启原有 CI、其他 SSH key、Ollama、ERP、其他工厂配置。代码回滚继续走统一入口及其制品/蓝绿回退门禁。
