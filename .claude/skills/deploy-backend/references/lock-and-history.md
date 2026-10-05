# 部署锁处置与发布流程的来历

SKILL.md 只留做法；这里是按需读的细节：锁被拒时怎么处置、为什么流程是现在这样、CI 制品车道的旧资料。

## 部署锁

三把本机锁：`cretas-release` / `cretas-backend-deploy` / `cretas-web-admin-deploy`。

走哪种模式只看有没有 `flock` 命令：

- Linux（含 47、CI ubuntu）有 flock ⇒ flock 模式。
- macOS 默认没有（Mac mini 实测 `command -v flock` 为空，也没有 `/proc`），Windows Git Bash 也没有 ⇒ PID 文件模式，stale PID 自动回收。

被拒时按拒绝信息分两种：

1. 「另一个 deploy 进程持有锁, PID x 活着」：真并发，等它跑完。
2. 「登记持有者已死 / 已正常释放, 但锁仍被按着」：它的孤儿子进程（rsync / ssh / mvn）继承了 fd 200 还没退。拒绝信息会逐行列出 `pid:cmdline`，并给出可复制的 `kill <pids>` 和一行复核。

第 2 种的处置顺序：

1. 看每行 cmdline 里 rsync / ssh 的目标，确认这些孤儿不会再写生产制品。
2. 先跑拒绝信息给的 `ps -o pid,args -p <pids>`，确认还是那几个孤儿。pid 会被系统回收再分配，拒绝信息里的 cmdline 只是拒绝那一刻的快照，隔几分钟同一个数字可能已经是别的进程。
3. `kill`，跑复核行（打印 `checked` 且没有 `still alive`），再重发。

不要 `rm` 锁文件：flock 模式下 rm 只会造出第二把互不可见的锁，两个部署就能同时进来（5/30 覆盖事故的形状）。正常释放由 `release_deploy_lock` 只关 fd、不 unlink。

## 为什么先 build 再合并

main 上相邻合并经常不到 4 分钟。过去在合并后才做约 200s 的本地构建，构建期间 origin/main 一被推进，exact-main 闸就让整次发布作废（07-30、07-31 各一次）。把构建挪到合并前，合并后的窗口只剩约 1 分钟。

## 为什么不跑 prewarm、不用 CI 制品车道

`prewarm-main-artifact.sh` 和 `--prefer-ci-artifact` 都是等 CI 构建好的制品。2026-09-15 实测这条车道三处同时不通：

1. 自托管 Java job 在 4G runner 上 OOM（#814 已修，但修 runner 不解决下面两条）。
2. GitHub 报 `Artifact storage quota has been hit`，制品传不上去（组织级配额）。
3. 私有仓没有 attestation，ECS 按设计拒收未签名制品。

当晚两次发布各 8 min，全花在等待后回落的本地 Maven 上（javac 4277 个文件 2:41，纯 CPU）；改成先 build 再合并后各约 1 min。所以修 runner 不会让部署变快，加速只靠 build 阶段的 manifest 复用。

## CI 制品运输链（目前不在默认路径上）

链路是 `GitHub → 东京 Lightsail → 上海 OSS → ECS 内网`（`scripts/deploy/Publish-GitHubArtifactViaLightsailOss.ps1`，字节不经过本机），细节见 `scripts/deploy/RELEASE-ARTIFACT-TRANSPORT.md`。`transport_verified` 不等于 `deployable_trust_verified`。默认发布带 `--no-prefer-ci-artifact`，不走这条链；只有将来车道修通、重新启用时才需要下面这些。

东京中转不可达的症状：`CI_ARTIFACT_UNAVAILABLE` / `WEB_CI_ARTIFACT_UNAVAILABLE reason=tokyo_stage_failed`，底下是 `Connection closed by 10.66.66.1 port 22`。这不是基础设施故障：`10.66.66.1` 是东京主机在隧道内的私网地址（AmneziaVPN 已停用），公网 SSH `52.196.123.155` 一直通；缓存服务绑在 `10.66.66.1:18081`，不监听回环，用端口转发接上：

```bash
KEY="$USERPROFILE/.ssh/ai-egress-tokyo-windows_ed25519"
ssh -f -N -L 18081:10.66.66.1:18081 -i "$KEY" -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes -o ExitOnForwardFailure=yes ubuntu@52.196.123.155
export CRETAS_TOKYO_SSH_HOST='ubuntu@52.196.123.155' CRETAS_TOKYO_CACHE_ENDPOINT='http://127.0.0.1:18081/artifacts'
```

东京两台同时在跑 VPN，所以不启停 AmneziaVPN / Clash·Mihomo，不改 sshd、安全组、密钥。主机指纹对不上就停下，不用 `StrictHostKeyChecking=no` 绕过。`WEB_CI_ARTIFACT=already-cached` 不能证明运输链通（可能被本地 by-tree 缓存短路），要看到 `used` 才算。

R2 / OSS 上传通道默认关闭（`ENABLE_R2=1` 是紧急 opt-in）。47 出网到 Cloudflare R2 极慢（10MB 200 秒没传完，阿里云 OSS 内网 1 秒），所以不要把传输统一到 R2。
