#!/usr/bin/env python3
"""
菜单/端点权限漂移扫描 —— 找出「菜单里看得见、点进去必 403」的组合。

═══════════════════════════════════════════════════════════════════════════════
为什么需要它 (2026-08-21 实测)
═══════════════════════════════════════════════════════════════════════════════
owner 用 f006_sales_mgr 随手点了几页, 连撞三个 403:
  · 车辆字典        菜单说 sales, 后端 VehicleController 要 warehouse:read
  · 销售付款申请    菜单说 sales, 后端方法级要 finance|procurement
  · AI 工作台       菜单说 dashboard(人人都有), 页面要 AI 数据权限

▎菜单按「有没有 A 模块权限」判可见, 页面按「有没有 B 模块权限」判可用 ——
▎两个判据不是同一个, 中间的差就是「看得见点不开」。

全量扫下来: **20 个角色, 每个 2~27 处**。销售主管 7 处已经算少的 ——
owner 随手就撞到三个, 因为每个角色都有一堆。

═══════════════════════════════════════════════════════════════════════════════
⛔ 判据必须是三个条件的交集 —— 少一个就会误报一大片
═══════════════════════════════════════════════════════════════════════════════
  ① 路由/菜单声明 module = A          ⇒ 有 A 权限的角色看得见这一项
  ② 该页面实际调的端点要 module = B
  ③ 某个角色**有 A 但没有 B**          ⇒ 它点进去必 403

只做 ①② 会得到 114 处, 其中大部分无害(有权限的角色本来就过得去)。
必须叠加 ③(真实角色权限矩阵, 从 prod 的 platform_role_permissions 读)才是真会炸的组合。

═══════════════════════════════════════════════════════════════════════════════
⚠️ 这把尺子看不见什么 (⛔ 别把「没报」当成「没问题」)
═══════════════════════════════════════════════════════════════════════════════
  · 只认 `/${factoryId}/xxx` 这种模板串写法的调用。经 api client 封装(如
    yieldReportApi.submitReport)的调用**扫不到** —— 那类要另想办法。
  · 只认控制器**类级** @RequestMapping + 文件内出现过的 @RequirePermission 模块。
    方法级与类级的差异这里不区分(取并集), 所以可能**漏报**方法级更严格的那些。
    ⚠️ 实测踩过: PermissionInterceptor 是「方法级存在就完全取代类级」, 不是取交集。
  · 🔴 **只读 router/index.ts, 完全不读 menuConfig.ts**。
    「看得见」的真正来源是**侧边栏菜单**(menuConfig), 这里拿**路由 module** 当代理。
    两者通常同步, 但不同步时这把尺子只看得到路由那一份。
    ⚠️ 2026-08-22 实测: 验闸时把变异打在 menuConfig 上, 闸纹丝不动 ——
       我差点读成「零基线的闸是恒真式」。真相是**变异打在了尺子看不见的文件上**。
       改打 router/index.ts, 闸当场 rc=1 / 新增 9 处。
    ▎变异不红先分两种: 断言没用 / 变异没生效。⛔ 别默认是前者。
  · @RequireRole(角色白名单)完全没纳入 —— 那是另一道闸。
  · 餐饮(restaurant_*)与平台(platform_*)角色**刻意排除**: 餐饮是独立体系。

用法:
    python scripts/check-menu-permission-drift.py                # 扫 prod, 打印摘要
    python scripts/check-menu-permission-drift.py --report FILE  # 同时写出完整清单
    python scripts/check-menu-permission-drift.py --role sales_manager   # 只看一个角色
    python scripts/check-menu-permission-drift.py --baseline FILE --check # 闸模式(只禁增长)

退出码 (三态, ⛔ 不是两态):
  0  没有新增漂移 (闸模式) / 扫描完成 (报告模式)
  1  有新增漂移 (闸模式)
  2  **没量到** —— 拿不到角色权限 / 一条路由都没扫到 / 一个控制器都没扫到。
     ⛔ 不要把 2 读成 0。
"""
import argparse
import io
import json
import os
import re
import subprocess
import sys
import typing
from collections import defaultdict

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WEB = os.path.join(REPO, "web-admin", "src")
BE = os.path.join(REPO, "backend", "java", "cretas-api", "src", "main", "java",
                  "com", "cretas", "aims", "controller")
SSH_HOST = os.environ.get("CRETAS_SSH_HOST", "root@47.100.235.168")

# 餐饮是独立体系(owner 明确划的禁区); 平台角色本就该看全部。
EXCLUDED_ROLE_PREFIXES = ("restaurant_", "platform_")

# ─────────────────────────────────────────────────────────────────────────────
# 🔴 2026-08-22: 本仓有**两道**闸, 第一版只扫了一道。
#
# owner 登录 f006_sales_mgr 打开销售订单详情, 当场 403:
#   「账号 f006_sales_mgr(1556) 缺少模块 production_plan 的访问权限」
# 而那时这把尺子刚报完「漂移 0 处」。⇒ 报的 0 只覆盖了一道闸。
#
#   闸一 @RequirePermission  -> PermissionInterceptor  -> 需要**任一**模块
#   闸二 @RequireModule      -> ModuleEnabledInterceptor
#                            -> ProductionModuleRegistry.permissionModule(code)
#                            -> hasAnyPermission(<映射后的模块>:read|write|read_write)
#
# ⛔ 两者是**与**关系(两个独立拦截器都要过), 不是任一 ——
#    有 sales 也没用, 缺 production 照样 403。
# ⇒ 端点的要求必须建模成两个集合: anyOf(闸一) 且 allOf(闸二)。
#
# 规模: @RequireModule 共 387 处 / 96 个控制器 / 16 个模块码。
#
# ⚠️ 这张映射表是 ProductionModuleRegistry.java 的**镜像**(跨语言, 抽不成一份),
#    所以下面配了一道一致性闸把两份钉住 —— 见 check_module_registry_mirror()。
# ⚠️ 未登记的 moduleCode 在 Java 侧 resolve() 回落成它自己(identity), 这里同构。
# ─────────────────────────────────────────────────────────────────────────────
MODULE_TO_PERMISSION = {
    "production_plan": "production",
    "production_report": "production",
    "work_process": "production",
    "bom": "production",
    "scheduling": "scheduling",
    "warehouse": "warehouse",
    "quality_inspection": "quality",
    "purchase_order": "procurement",
    "sales_order": "sales",
    "equipment": "equipment",
    "finance_ap": "finance",
    "finance_ar": "finance",
    "hr_employee": "hr",
    "restaurant": "restaurant",
    "production": "production",
}

REGISTRY_JAVA = os.path.join(
    os.path.dirname(BE), "permission", "ProductionModuleRegistry.java")

# 被跳过的调用点。⛔ 不许静默 —— 「扫了 N 处」和「扫了 N 处、另外放过了 M 处」
# 在最终读数里长得一模一样, 而后者的覆盖面小得多。main() 末尾必须把它打出来。
SKIPPED = defaultdict(set)

# 已被前端守卫挡住、本次没有计入的组合。⛔ 同样不许静默 —— 末尾要打出来,
# 否则「16 处」和「16 处 + 悄悄免了 54 处」在读数里长得一模一样。
EXEMPTED = set()


def die(msg) -> "typing.NoReturn":
    """rc=2 = 「没量到」。⛔ 调用方不要把它当成「没问题」。"""
    print(f"⛔ {msg}", file=sys.stderr)
    sys.exit(2)


def load_role_permissions():
    """从 prod 读真实的角色×模块权限。⛔ 不读代码里的 PERMISSION_MATRIX ——
    那只是镜像, 真正的权威是 DB 表(2026-08-21 实测踩过)。"""
    remote = (
        'PID=$(ss -lntp 2>/dev/null | grep -E ":(10010|10020)" '
        '| grep -oP "pid=\\K[0-9]+" | head -1); '
        '[ -n "$PID" ] || exit 2; '
        'eval "$(tr \'\\0\' \'\\n\' < /proc/$PID/environ | grep -E \'^DB_\' | sed \'s/^/export /\')"; '
        'export PGPASSWORD="$DB_PASSWORD"; '
        'psql -h localhost -U "${DB_USERNAME:-cretas_user}" -d "$DB_NAME" -tAc '
        '"SELECT role_code || chr(124) || module_code FROM public.platform_role_permissions '
        'WHERE deleted_at IS NULL AND permission_level <> chr(45)"'
    )
    try:
        out = subprocess.run(["ssh", SSH_HOST, remote], capture_output=True,
                             text=True, timeout=120)
    except Exception as e:  # noqa: BLE001 — 连不上就是 rc=2, 不区分原因
        die(f"连库失败: {e}")
    if out.returncode != 0:
        die(f"取角色权限失败 (rc={out.returncode}): {out.stderr.strip()[:200]}")
    perms = defaultdict(set)
    for line in out.stdout.splitlines():
        if line.count("|") == 1:
            role, mod = line.strip().split("|")
            perms[role].add(mod)
    # 阳性对照: 角色数个位数 = 查询本身出问题了
    if len(perms) < 10:
        die(f"阳性对照失败: 只读到 {len(perms)} 个角色 (预期 20+)。本次读数作废。")
    return perms


def check_module_registry_mirror():
    """钉住 MODULE_TO_PERMISSION 与 ProductionModuleRegistry.java 一致。

    ⛔ 形态 D′: 抽不成一份(跨语言)时, 闸要钉的是**两份一致**, 而不是「各自都对」。
    各自都对是两条独立断言, 它们同时绿也不排除两份内容不同。

    ⚠️ 读不到 Java 文件时 **die(rc=2)**, ⛔ 不是静默放行 ——
    「没量到」必须和「没问题」分开。
    """
    if not os.path.exists(REGISTRY_JAVA):
        die(f"读不到 ProductionModuleRegistry.java ({REGISTRY_JAVA}) —— "
            "无法确认模块映射表是否已漂, 本次读数作废")
    src = io.open(REGISTRY_JAVA, encoding="utf-8", errors="replace").read()
    java = dict(re.findall(
        r'new ModuleDefinition\(\s*"([a-z_]+)"\s*,\s*"[^"]*"\s*,\s*"[^"]*"\s*,\s*"([a-z_]+)"\s*\)',
        src))
    # 阳性对照: Java 侧解析出个位数 = 正则跟源码结构漂了, 而不是「表变小了」
    if len(java) < 10:
        die(f"阳性对照失败: 从 ProductionModuleRegistry.java 只解析出 {len(java)} 条映射 "
            "(预期 15 条)。是正则跟源码结构漂了, 本次读数作废。")
    if java != MODULE_TO_PERMISSION:
        only_java = {k: v for k, v in java.items() if MODULE_TO_PERMISSION.get(k) != v}
        only_py = {k: v for k, v in MODULE_TO_PERMISSION.items() if java.get(k) != v}
        die("模块映射表已漂 —— Java 是权威, 请同步本文件的 MODULE_TO_PERMISSION:\n"
            f"  Java 有而这里不同/没有: {only_java}\n"
            f"  这里有而 Java 不同/没有: {only_py}")


def strip_java_comments(src):
    """去掉 // 行注释与 /* */ 块注释。⛔ 保留字符串字面量里的内容不做处理 ——
    本仓的注解值都是简单字面量, 不含 // 或 /*, 所以这个近似是安全的。"""
    src = re.sub(r"/\*[\s\S]*?\*/", "", src)
    return re.sub(r"(^|[^:])//[^\n]*", r"\1", src)


def load_controllers():
    """后端 **端点级** URL -> (类名, 需要的模块集合)。

    🔴 2026-08-22 修: 第一版按**文件**取「所有 @RequirePermission 的并集」, 结果把
    **别的端点**的权限算到了这个端点头上。实测误报: AIIntentConfigController 的
    /execute **早在 Sprint 11 就移除了 @RequirePermission**(注释白纸黑字写着), 而同文件
    另一个端点有 system:read_write ⇒ 扫描器报「15 个角色打不开工作台」, **全是假的**。
    ▎这条局限我在文件头部自己写过, 读结果时却忘了 —— 所以改成从源头就不产生它。

    现在的做法: 逐个 @(Get|Post|Put|Delete)Mapping 解析, 每个端点用
    **方法级注解; 方法级没有才回落类级**(与 PermissionInterceptor:60 的
    `methodAnnotation != null ? methodAnnotation : classAnnotation` 一致)。
    ⛔ 两者不是「与」, 是就近覆盖 —— 今天在 PaymentRequestController 上诊断反过一次。
    方法级/类级都没有 ⇒ **不拦**, 这个端点不进结果集。
    """
    ctrl = {}
    for dp, _, fs in os.walk(BE):
        for fn in fs:
            if not fn.endswith(".java"):
                continue
            s = io.open(os.path.join(dp, fn), encoding="utf-8", errors="replace").read()
            # 🔴 必须先剥注释再扫。实测: AIIntentConfigController 里有一行
            #     // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) ...
            # 不剥的话正则把**这句说明**当成真注解, 于是 /execute/multi 和 /execute/stream
            # 被标成要 system —— **闸把「说它被移除了」读成了「它还在」**。
            # ▎本仓的老形态: 源码闸把注释当被测对象。⛔ 不能为了让闸变绿去删注释, 该改的是尺子。
            s = strip_java_comments(s)
            m = re.search(r'@RequestMapping\("(/api/mobile/\{factoryId\})?(/[^"]*)"\)', s)
            if not m:
                continue
            base = m.group(2).rstrip("/")

            # 类级 @RequirePermission: 出现在 class 声明之前、且不在任何方法里。
            cls_pos = s.find("public class ")
            cls_mods = set()
            cls_req_module = set()
            if cls_pos > 0:
                head = s[:cls_pos]
                for grp in re.findall(r"@RequirePermission\(\{?([^)]*)\)", head):
                    cls_mods |= set(re.findall(r'"([a-z_]+):', grp))
                # 闸二的类级声明(ModuleEnabledInterceptor 同样是「方法级优先, 无则回落类级」)
                for code in re.findall(r'@RequireModule\(\s*"([a-z_]+)"', head):
                    cls_req_module.add(MODULE_TO_PERMISSION.get(code, code))

            # 逐端点: 取它上方注解块里的方法级声明
            # ⚠️ 括号是**可选**的: `@GetMapping` 裸写(无路径无参数)是本仓常见形态,
            #    实测 CustomerController 的列表端点就是裸的。第一版正则强制 `\(`,
            #    于是所有裸 Mapping 端点整批不可见 —— 它们本可以继承**类级**权限。
            for em in re.finditer(
                    r'@(Get|Post|Put|Delete|Patch)Mapping'
                    r'(?:\(\s*(?:value\s*=\s*)?"?([^")\s]*)"?)?', s):
                verb = em.group(1).upper()
                sub = em.group(2) or ""
                # 往上收这个方法的注解块(到上一条语句/上一个方法结束为止)
                # 🔴 注解块的边界: 从**上一个 @XxxMapping 之后**开始, 而不是固定往回 900 字符。
                # 实测踩过: 固定窗口会串到上一个方法的 @RequirePermission, 于是
                # /ai-intents/execute/multi(源码注释明写移除了权限)被标成要 system。
                prev_map = max(
                    (mm.end() for mm in re.finditer(
                        r"@(?:Get|Post|Put|Delete|Patch)Mapping", s[:em.start()])),
                    default=0)
                blk = s[prev_map:em.start()]
                # 再从块内最后一个「方法体结束/语句结束」之后取, 去掉上个方法的尾巴
                cut = max(blk.rfind("    }"), blk.rfind(";"))
                if cut >= 0:
                    blk = blk[cut + 1:]
                # 🔴 2026-08-22(尺子第四个缺陷): 注解块在 @XxxMapping **两侧**都有。
                # Java 不在乎注解顺序, 本仓大量写成
                #     @GetMapping("/by-sales-order/{salesOrderId}")
                #     @RequirePermission({... "sales:read" ...})
                # 只往前收 ⇒ 读不到, 回落类级 ⇒ 报出「4 个角色打不开销售订单的发票页」,
                # 而那个端点的注释白纸黑字写着 "used by sales order detail page tab"
                # —— **当初就是特意给销售开的**。
                # ⇒ 往后再收到**方法签名**为止(不跨过下一个 Mapping)。
                nxt = re.search(r"@(?:Get|Post|Put|Delete|Patch)Mapping", s[em.end():])
                sig = re.search(r"\n\s*(?:public|private|protected)\s", s[em.end():])
                stop = min(x.start() for x in (nxt, sig) if x) if (nxt or sig) else 0
                blk += "\n" + s[em.end():em.end() + stop]
                mth = set()
                for grp in re.findall(r"@RequirePermission\(\{?([^)]*)\)", blk):
                    mth |= set(re.findall(r'"([a-z_]+):', grp))
                mth_req_module = set()
                for code in re.findall(r'@RequireModule\(\s*"([a-z_]+)"', blk):
                    mth_req_module.add(MODULE_TO_PERMISSION.get(code, code))
                # 方法级存在就完全取代类级, 与 PermissionInterceptor 一致
                any_of = mth if mth else cls_mods
                # 闸二同样就近覆盖; 它是**必须满足**(allOf), 与闸一是**与**关系
                all_of = mth_req_module if mth_req_module else cls_req_module
                if not any_of and not all_of:
                    continue   # ⛔ 两道闸都不拦的端点不进结果集
                full = (base + ("/" + sub.lstrip("/") if sub else "")).rstrip("/") or base
                # 🔴 2026-08-22(尺子第五个缺陷): 路径参数段换成 `*`, ⛔ 不是删掉。
                # 删掉会让 /sales/orders/{orderId} 与 /sales/orders **同键**, 两者取并集
                # ⇒ **详情端点更严的要求被列表的宽要求冲淡**(漏报)。
                # 实测: GET /sales/orders 含 finance, 而 GET /sales/orders/{id} **只认 sales**
                #   —— 合并后尺子报「财务够得着」, 我据此差点建出一个必然 403 的抽屉。
                # ▎「同一前缀的两个端点」不是同一个端点。
                full = re.sub(r"\{[^}]*\}", "*", full).replace("//", "/").rstrip("/")
                # 🔴 2026-08-22 修(本尺子第三个自造缺陷): 键必须是 **(方法, 路径)**。
                #
                # 第一版按路径建键, 同一 URL 的多个方法**取并集**, 注释还写着
                # 「偏向漏报而不是误报」—— **那句推理是反的**。取并集是把要求**变严**:
                # /customers 的 POST 要 sales|finance, GET 根本不拦
                # (CustomerController 头部白纸黑字: "@RequirePermission added only on
                #  write methods — reads stay open"), 并集之后 GET 被标成要 sales
                # ⇒ 报出 **19 个角色打不开客户列表**, 全是假的。
                # ▎形态 A: 我想知道「页面调的那个方法要什么」,
                #   实际量的是「这个路径上**任意**方法要什么」。
                #
                # 有了方法维度, 碰撞只剩「不同控制器同方法同路径」, 那种才该取并集。
                k = (verb, full)
                prev = ctrl.get(k)
                ctrl[k] = (fn[:-5],
                           (prev[1] | any_of) if prev else any_of,
                           (prev[2] | all_of) if prev else all_of)
    return ctrl


def load_routes():
    """路由: 视图文件 -> (声明的 module, modulesAll 里额外要求的模块集合)。

    🔴 2026-08-22: 加 modulesAll。有些页面**天然跨两个模块**(财务的 SKU 毛利率
    要读生产批次), 而 meta.module 只能写一个。守卫已支持 modulesAll(全部满足),
    这里必须同步识别 —— 否则尺子会把「已经用 modulesAll 挡住的入口」继续报成漂移,
    形态 E: 一道天天误报的闸最终会被关掉。
    """
    # 🔴 2026-08-22(尺子第六个缺陷): 路由**不止 index.ts**。
    # router/modules/ 下还有 smartbi.ts / production-analytics.ts / ops.ts / crm.ts,
    # 共 30 条带 module 的路由 —— 此前一条都没扫到。
    # 实测代价: 「经营驾驶舱 / 经营分析 / AI问答 / 智能数据分析」全在 smartbi.ts,
    # 我砍掉 finance 的 analytics 之后它们全 403, 而尺子照报「0 处漂移」。
    # ▎报「0」之前先问: **我读的文件是全部吗。**
    paths = [os.path.join(WEB, "router", "index.ts")]
    mod_dir = os.path.join(WEB, "router", "modules")
    if os.path.isdir(mod_dir):
        paths += [os.path.join(mod_dir, f) for f in sorted(os.listdir(mod_dir))
                  if f.endswith(".ts") and not f.endswith(".spec.ts")]
    if not os.path.exists(paths[0]):
        die(f"找不到路由表: {paths[0]}")
    s = chr(10).join(io.open(x, encoding="utf-8", errors="replace").read() for x in paths)
    # 阳性对照: 只读到 index.ts 那点量 = modules/ 没被拼进来
    if len(paths) < 2:
        SKIPPED["router_modules_missing"].add(mod_dir)
    out = []
    # ⚠️ 尾部窗口必须用**前瞻**(?=...), ⛔ 不能用捕获组:
    #    捕获组会**消耗**那 200 个字符, 把紧接着的下一条路由 import 一起吞掉
    #    ⇒ 实测路由数从 203 掉到 130(少了 73 条), 而漂移随之假性归零。
    # ▎读数变好时先分清: 是修好了, 还是**仪器看见的东西变少了**。
    #   这次是后者 —— 靠对比「路由 203 条 -> 130 条」才认出来。
    # ⚠️ 窗口从 400 放到 1200: 路由条目之间可以隔着很长的注释。
    #    实测 2026-08-22 我给三条路由加了说明性注释, **路由数当场 204 -> 201**
    #    —— 那三条被挤出窗口, 扫描器再也看不见它们(而它们正是我刚修的那三条)。
    # ▎**注释也会让仪器变瞎。** 今天同一个坑第三次:
    #    ①尾部捕获组吞掉下一条 ②菜单 module 窗口太窄 ③这次是我自己写的注释。
    # ⇒ 判据固定成: 每次改完对比**路由条数**, 掉了就是窗口不够, ⛔ 不是「路由变少了」。
    for m in re.finditer(
            r"import\('@/views/([^']+)'\)([\s\S]{0,1200}?)module:\s*'([a-z_]+)'(?=([\s\S]{0,300}))", s):
        view, declared = m.group(1), m.group(3)
        # modulesAll 可能写在 module 前或后, 两侧窗口都找
        extra = set()
        for chunk in (m.group(2), m.group(4)):
            am = re.search(r"modulesAll:\s*\[([^\]]*)\]", chunk)
            if am:
                extra |= set(re.findall(r"'([a-z_]+)'", am.group(1)))
        out.append((view, declared, extra))
    return out


def load_menu_modules():
    """菜单项 path -> 声明的 module。

    🔴 2026-08-22: 补上这一维。此前尺子**只读 router/index.ts**, 拿路由 module
    当「看得见」的代理, 于是**菜单与路由声明不同 module** 这一整类漂移它一条都报不出。

    实测开火: menuConfig 里「SKU 管理 / 工序管理 / 产品-工序配置」写的是
    `module: 'production'`(生产经理有 ⇒ 看得见), 而 router 里写的是
    `module: 'system'`(生产经理没有 ⇒ 路由守卫直接送 /403)。
    ▎看得见靠菜单, 进得去靠路由 —— 两者不是同一个判据。
    """
    path = os.path.join(WEB, "components", "layout", "menuConfig.ts")
    if not os.path.exists(path):
        die(f"找不到菜单配置: {path}")
    s = io.open(path, encoding="utf-8", errors="replace").read()
    menu = {}
    # ⚠️ 同时抓 `roles` **允许式白名单** —— AppSidebar.canSeeMenuItem 里它是**一票否决**:
    #    写了 roles 的菜单项, 不在名单里的角色**根本看不见**, 与模块权限无关。
    #    ⛔ 不建模它就会误报: 实测 /restaurant 的白名单里没有 production_manager,
    #    我却把它报成了「生产经理看得见但进不去」—— 假阳性。
    # ▎权限有**两个承载点**(模块矩阵 + roles 白名单), 只看一个就会读错。
    for m in re.finditer(
            r"path:\s*'([^']+)'([\s\S]{0,600}?)module:\s*'([a-z_]+)'(?=([\s\S]{0,600}))", s):
        path = m.group(1).rstrip("/")
        allow, hide_factory = set(), False
        for chunk in (m.group(2), m.group(4)):
            am = re.search(r"roles:\s*\[([^\]]*)\]", chunk)
            if am:
                allow |= set(re.findall(r"'([a-z_]+)'", am.group(1)))
            # ⚠️ 第三个承载点: hideForFactoryTypes。写了 'FACTORY' 的菜单项
            #    在工厂租户**根本不渲染** —— 与模块权限、roles 白名单都无关。
            #    ⛔ 不建模它又会误报: 实测 /restaurant 带 hideForFactoryTypes:['FACTORY'],
            #    我却把它报成「采购/仓储主管看得见但进不去」。
            # ▎权限有**三个**承载点(模块矩阵 / roles 白名单 / 业态黑名单),
            #   今天已经因为漏看其中一个误报了两次。
            if re.search(r"hideForFactoryTypes:\s*\[[^\]]*'FACTORY'", chunk):
                hide_factory = True
        menu[path] = (m.group(3), allow, hide_factory)
    # 阳性对照: 菜单项个位数 = 正则跟结构漂了
    if len(menu) < 30:
        die(f"阳性对照失败: 只解析出 {len(menu)} 个菜单项 (预期数十个)。本次读数作废。")
    return menu


def load_role_path_whitelist():
    """guards.ts 的 ROLE_PATH_WHITELIST —— 权限的**第四个**承载点。

    🔴 2026-08-22 实测: 它**只给 finance_manager 一个角色**(16 条路径), 且与模块闸
    **串联(AND)**: 路径在白名单里 **且** 模块权限通过, 才放行。

    guards.ts 里那句注释早就警告过:
      「删掉这两行会让 finance_manager 直接吃 403, 即使模块权限是通的。
        要改访问范围, **两处都得改**。」
    而我只改了模块那一处 ⇒ 砍掉 analytics 后, 财务的
    /smart-bi/analysis-hub(代码里标注为「finance_manager 主入口」)当场 403。

    ▎权限承载点数到四个了: 模块矩阵 / roles 白名单 / 业态黑名单 / 本表。
      ⛔ 每漏看一个就误判一次 —— 今天已经三次。
    """
    path = os.path.join(WEB, "router", "guards.ts")
    if not os.path.exists(path):
        die(f"找不到 guards.ts ({path}) —— 无法确认路径白名单, 本次读数作废")
    src = io.open(path, encoding="utf-8", errors="replace").read()
    # ⚠️ 用 chr(10) 拼换行, ⛔ 不写字面转义 —— 这段正则经 heredoc 落盘时
    #    「\n」被吃成过真换行, 当场 SyntaxError(硬约束 6, 今天第二次)。
    m = re.search(r"ROLE_PATH_WHITELIST[^=]*=\s*\{([\s\S]*?)" + chr(10) + r"\};", src)
    if not m:
        die("guards.ts 里找不到 ROLE_PATH_WHITELIST —— 结构变了, 本次读数作废")
    wl = {}
    for rm in re.finditer(r"^\s*([a-z_]+):\s*\[([\s\S]*?)\],", m.group(1), re.M):
        wl[rm.group(1)] = [x for x in re.findall(r"'([^']+)'", rm.group(2))]
    if not wl:
        die("ROLE_PATH_WHITELIST 解析出 0 个角色 —— 正则跟源码漂了, 本次读数作废")
    return wl


def check_role_path_whitelist_consistency(perms):
    """路径白名单与模块权限**必须同时通** —— 两者是串联(AND)。

    guards.ts 原注释:「要改访问范围, **两处都得改**」。
    2026-08-22 我只改了模块那一处 ⇒ 财务的 /smart-bi/analysis-hub
    (代码里标注为「finance_manager 主入口」)当场 403。

    双向检查, ⛔ 两个方向都要:
      A. 白名单放行、但该角色**没有那条路由的 module** -> 点进去 403(本次踩的)
      B. 该角色**有 module**、但路径不在白名单里     -> 同样 403, 只是原因反过来
    ▎只查一个方向, 另一个方向的漂移下次照样撞。
    """
    wl = load_role_path_whitelist()
    routes = load_routes()
    menu = load_menu_modules()
    findings = []
    for role, paths in wl.items():
        if role.startswith(EXCLUDED_ROLE_PREFIXES):
            continue
        has = perms.get(role, set())
        # A: 白名单有, 模块没有
        for path in paths:
            # ⚠️ 只比对**白名单路径本身**, ⛔ 不含它前缀覆盖的子项。
            #    白名单是前缀匹配, 一条 /workdesk 会覆盖各角色的工作台子项,
            #    把子项算进来会报出 5 条噪音(财务当然没有 warehouse/quality 的工作台)。
            #    ▎A 类要抓的是**配置自相矛盾**: 白名单专门为这条路径开了口子,
            #      而模块矩阵说这个角色没有它需要的模块 ⇒ 那条白名单是**死的**。
            #      2026-08-22 实测: 白名单里 /smart-bi/analysis-hub 注释写着
            #      「finance_manager 主入口」, 而我砍掉了 analytics —— 正是这一类。
            entry = menu.get(path)
            if entry and entry[0] not in has:
                findings.append(("A", role, path, entry[0]))
        # B: **菜单里对这个角色可见**、有 module、却不在白名单 -> 看得见点不开
        #
        # 🔴 第一版判据是错的: 只查「有 module 且不在白名单」, 报出 27 条。
        #    但路径白名单的**用意就是收窄** —— 挡住有 module 的路径正是它在起作用,
        #    那是**设计意图, 不是漂移**。一张 27 条的误报表只会让人把闸关掉(形态 E)。
        # ⇒ 必须叠加「菜单里看得见」这一条 —— 那才是「看得见但点不开」的定义。
        # ⚠️ 菜单是**树**, 而这里按扁平项解析 ⇒ 父组的 hideForFactoryTypes
        #    不会自动落到子项上。用**路径前缀**近似继承:
        #    /smart-bi 这一组隐藏了, /smart-bi/upload 等子项自然也不渲染。
        #    ⛔ 不继承的话会报出一批「父组已隐藏、子项照报」的假阳性(实测 10 条)。
        # 🔴 **但前缀只是近似, 它会漏**: children 的 path 不一定以父 path 开头。
        #    实测 /smart-bi 组的 children 里就有 /analytics/alert-dashboard 和
        #    /analytics/supply-chain —— 父组已隐藏, 而前缀对不上 ⇒ 仍被报成假阳性。
        #    要根治得**真解析菜单树**(括号配对), 正则做不到。
        # ⇒ 这条局限**登记在此**, ⛔ 不假装已解决。读结果时对 analytics 类的 B 条目
        #   要人工确认一下它是不是躺在某个已隐藏组里。
        hidden_groups = [gp for gp, (_m, _a, h) in menu.items() if h]
        def _hidden(path_):
            return any(path_ == g or path_.startswith(g + "/") for g in hidden_groups)
        for mp, (mod, allow, hide_factory) in menu.items():
            if hide_factory or _hidden(mp):        # 工厂租户不渲染(含父组隐藏)
                continue
            if allow and role not in allow:        # roles 白名单一票否决
                continue
            if mod not in has:                     # 没模块 -> 菜单本来就不显示
                continue
            if not any(mp == x or mp.startswith(x + "/") for x in paths):
                findings.append(("B", role, mp, mod))
    return findings


def check_menu_route_module_mismatch(perms):
    """菜单声明的 module 与路由声明的 module 不一致 -> 「看得见但路由守卫直接 403」。

    🔴 2026-08-22 实测开火: menuConfig 里
        SKU 管理 / 工序管理 / 产品-工序配置  -> module: 'production'
    而 router/index.ts 里同样三条 -> module: 'system'
    ⇒ 生产经理(有 production 无 system)看得见入口, 点进去被**路由守卫**送到 /403。

    ▎**看得见靠菜单, 进得去靠路由 —— 两者不是同一个判据。**
      本脚本此前只读路由、拿它当「看得见」的代理, 于是这一整类一条都报不出来。

    ⚠️ 按 path 末段比对。⛔ 匹配不上或匹配到多条时**不猜**, 计入 unmatched 并打印 ——
       「没量到」必须和「没问题」分开。
    """
    menu = load_menu_modules()
    rpath = os.path.join(WEB, "router", "index.ts")
    src = io.open(rpath, encoding="utf-8", errors="replace").read()
    # 路由: path 末段 -> 它后面最近的一个 module 声明
    route_mod = defaultdict(set)
    # ⚠️ 窗口必须取到**下一个 path:** 为止, ⛔ 不能用固定字符数:
    #    实测 400 字符窗口把 /system/products 那条的 meta 挤出去了
    #    (component 的 import 路径 + 注释很长) ⇒ 它一个 module 都抓不到,
    #    于是这道检查对**我明知存在的那个缺陷**是瞎的 —— 变异时才发现。
    # ▎变异不红先分两种: 断言没用 / 变异没生效。这次两者都不是, 是**检查没覆盖到**。
    starts = [m.start() for m in re.finditer(r"path:\s*'", src)] + [len(src)]
    for i, m in enumerate(re.finditer(r"path:\s*'([^']*)'", src)):
        seg = m.group(1).strip("/").split("/")[-1]
        nxt = next((x for x in starts if x > m.start()), len(src))
        mm = re.search(r"module:\s*'([a-z_]+)'", src[m.end():nxt])
        if seg and mm:
            route_mod[seg].add(mm.group(1))

    findings, unmatched = [], []
    for path, (menu_m, allow, hide_factory) in sorted(menu.items()):
        # ⛔ 工厂租户不渲染的项直接跳过 —— 本仓的被测对象是工厂端
        if hide_factory:
            continue
        seg = path.strip("/").split("/")[-1]
        mods = route_mod.get(seg)
        if not mods or len(mods) != 1:
            unmatched.append(path)
            continue
        route_m = next(iter(mods))
        if route_m == menu_m:
            continue
        # 只有当**存在角色有菜单那个、却没有路由那个**时才是真漂移
        hit = sorted(r for r, has in perms.items()
                     if not r.startswith(EXCLUDED_ROLE_PREFIXES)
                     # ⛔ roles 白名单一票否决 —— 不在名单里的角色看不见这一项
                     and (not allow or r in allow)
                     and menu_m in has and route_m not in has)
        if hit:
            findings.append((path, menu_m, route_m, hit))
    return findings, unmatched


def longest_match(call, ctrl, verb):
    """把前端调的路径对到后端端点。

    🔴 2026-08-22 修: 第一版用**前缀匹配**(call.startswith(base + "/")), 结果
    /ai-intents/execute(它自己**没有**权限声明, 因此不在表里)被落到了 /ai-intents 上,
    于是报「15 个角色打不开工作台」—— 全是假的。
    ▎`/a/b` 不是 `/a` 的「更具体版本」, 它是**另一个端点**。
      前缀匹配对 REST 路径是错的判据。

    现在: 先精确匹配; 匹配不上再**只**回落到「去掉末尾一段路径参数」的形式
    (前端常写 `/xxx/by-sales-order/` + 拼 id, 后端是 `/xxx/by-sales-order/{id}` ——
     load_controllers 已把花括号剥掉, 所以这类精确就能对上)。
    ⛔ 对不上就返回 None(不猜) —— 宁可**漏报**也不误报: 误报会让人去改不该改的地方。
    """
    # ⚠️ 前端模板串 `/${factoryId}/sales/orders/${id}` 被截成 "/sales/orders/"
    #    —— **末尾那个斜杠就是「后面还有一个 id」的信号**。
    #    ⇒ 这种调用要优先对到参数化端点 /sales/orders/*, ⛔ 不能对到列表端点。
    if call.endswith("/"):
        param = call.rstrip("/") + "/*"
        if (verb, param) in ctrl:
            return (param,) + ctrl[(verb, param)]
    c = call.rstrip("/")
    if (verb, c) in ctrl:
        return (c,) + ctrl[(verb, c)]
    return None


_VERBS = {"get": "GET", "post": "POST", "put": "PUT",
          "patch": "PATCH", "del": "DELETE", "delete": "DELETE"}


def verb_before(s, pos):
    """从模板串起点往回解析这次调用的 HTTP 动词。⛔ 认不出就 None(不猜)。

    本仓主流写法是**裸函数**而不是 `client.get(...)`:
        const r = await get<Array<{ id: string; name: string }>>(`/${factoryId}/...`)
    实测 217 个调用点里只有 5 个是 `.get(` 紧邻形态 ⇒ 只匹配 `.verb(` 会漏掉 98%。

    ⚠️ 泛型里有嵌套 `<>`, 正则数不清 ⇒ 用**平衡扫描**倒着跨过它。
    ⚠️ 还有一类根本没有动词(`{ label: '提交 OA 审批', url: `/${factoryId}/...` }` 这种
       配置对象), 这类返回 None 被跳过 —— 宁可漏报也不误报, 但**数量要报出来**。
    """
    head = s[max(0, pos - 400):pos].rstrip()
    if not head.endswith("("):
        return None
    head = head[:-1].rstrip()
    if head.endswith(">"):                     # 倒着跨过泛型参数
        depth, i = 0, len(head) - 1
        while i >= 0:
            if head[i] == ">":
                depth += 1
            elif head[i] == "<":
                depth -= 1
                if depth == 0:
                    break
            i -= 1
        if depth != 0:
            return None
        head = head[:i].rstrip()
    m = re.search(r"([A-Za-z_$][\w$]*)$", head)
    return _VERBS.get(m.group(1).lower()) if m else None


# ─────────────────────────────────────────────────────────────────────────────
# 已登记的例外: 「后端确实拦, 但前端已经把入口挡住了」
#
# 🔴 这不是白名单。本扫描器读的是「路由声明的 module」和「端点要的权限」,
#    **看不见前端的 v-if 守卫** —— 于是已经修好的东西会继续天天报。
#    形态 E: 一道天天误报的闸, 最终会被人关掉, 那时它的覆盖率归零。
#
# ⛔ 所以每条例外必须给出 `guard` —— 那道守卫在源码里的**字面量**。
#    扫描器会去那个文件里**验它还在**:
#      · 守卫还在  -> 这条不报(它真的被挡住了)
#      · 守卫没了  -> 例外**自动失效**, 立刻恢复报警
#    ▎登记的是「谁在挡它」, 不是「别报了」。守卫被删掉时闸必须自己醒过来。
#
# ⚠️ 例外只能免掉**这一个** (端点前缀, 页面) 组合, ⛔ 不是整个页面或整个端点。
# ─────────────────────────────────────────────────────────────────────────────
GUARDED_EXEMPTIONS = [
    # (端点前缀, 视图, 那道守卫的字面量, 理由)
    ("/material-segments", "warehouse/material-segments/index.vue",
     "permissionStore.canWrite('system')",
     "分类字典增删改要 system; 新增按钮/操作列/恢复列/已删除开关都在 canWrite 之下"),
    ("/material-segments", "warehouse/material-types/list.vue",
     "canManageClassification",
     "内联新建分类已由 canManageClassification(=canWrite('system')) 挡住, 且无权限时给出提示文案"),
    ("/raw-material-types", "warehouse/material-types/list.vue",
     "permissionStore.canWrite('production')",
     "原料类型增删改要 production; 新增/编辑/删除按钮都在 canWrite 之下"),
    ("/material-packaging", "warehouse/material-types/list.vue",
     "permissionStore.canWrite('production')",
     "包装规格随原料类型一起维护, 同一道守卫"),
    ("/finance/receivable", "sales/orders/list.vue",
     "FINANCE_WRITE_ACTIONS",
     "快速开票/登记收款直写财务流水, 已按 canWrite('finance') 从行内操作里过滤掉"),
    ("/alerts/detect", "analytics/AlertDashboard.vue",
     "canTriggerDetection",
     "手动触发检测是运维动作, 按钮已挂 canWrite('equipment'); 看板本身照常可看"),
    # ── 2026-08-22 第二批: 闸二(@RequireModule)带出来的 ────────────────────
    ("/finance/invoices/request-from-order", "sales/orders/detail.vue",
     "canWrite && canWriteFinance",
     "开票申请入口已挂 canWrite('finance')。⚠️ 闸一特意放行了 sales:read_write, "
     "但闸二 @RequireModule(\"finance_ar\")->finance 把它否决了 —— 两处都没提到对方"),
    ("/finance/payments/record", "sales/orders/detail.vue",
     "canWrite && canWriteFinance",
     "登记收款/一键收款两个入口都已挂 canWrite('finance')"),
    ("/production-plans/by-sales-order", "sales/orders/detail.vue",
     "productionPlansForbidden",
     "可选区块: 已 _silent 不弹全局红框, 且 403 单独成一态并给出诚实文案"
     "(⛔ 不再说「请刷新后重试」—— 权限不足时刷新一万次也没用)"),
]


def exemption_hits(view, endpoint):
    """这条发现是否已被前端守卫挡住(且那道守卫**当前确实还在**)。"""
    for prefix, exempt_view, guard, _reason in GUARDED_EXEMPTIONS:
        if view != exempt_view or not endpoint.startswith(prefix):
            continue
        p = os.path.join(WEB, "views", view)
        if not os.path.exists(p):
            return False          # ⛔ 文件都没了, 不给豁免
        if guard in io.open(p, encoding="utf-8", errors="replace").read():
            return True
        SKIPPED["guard_gone"].add(f"{view}:{endpoint} 守卫 `{guard}` 不见了")
        return False
    return False


def scan(perms, ctrl, routes, only_role=None):
    findings = []
    for view, declared, route_extra in routes:
        # ⚠️ modulesAll 已经在守卫里挡住了没有这些模块的角色 ⇒ 它们进不来这个页面,
        #    所以判「看得见」时要把这些模块一起要求。⛔ 不算进来就会误报已修好的东西。
        p = os.path.join(WEB, "views", view)
        if not os.path.exists(p):
            continue
        s = io.open(p, encoding="utf-8", errors="replace").read()
        seen = set()
        for cm in re.finditer(r"[`'\"]/\$\{factoryId[^}]*\}(/[a-z][a-z0-9/-]*)", s):
            call = cm.group(1)
            verb = verb_before(s, cm.start())
            if verb is None:
                SKIPPED["no_verb"].add(f"{view}:{call}")
                continue
            if (call, verb) in seen:
                continue
            seen.add((call, verb))
            hit = longest_match(call, ctrl, verb)
            if not hit:
                continue
            if exemption_hits(view, call):
                EXEMPTED.add(f"{view}:{verb} {call}")
                continue
            _, cls, any_of, all_of = hit
            for role, has in perms.items():
                if role.startswith(EXCLUDED_ROLE_PREFIXES):
                    continue
                if only_role and role != only_role:
                    continue
                # ③ 看得见(有 A) 但过不去
                # ⛔ 两道闸是**与**关系:
                #    闸一 @RequirePermission —— 需要 any_of 里**任一**
                #    闸二 @RequireModule     —— 需要 all_of 里**每一个**
                #    任一道过不去就是 403。有 sales 也没用, 缺 production 照样 403。
                blocked_any = bool(any_of) and not (any_of & has)
                blocked_all = bool(all_of - has)
                # 🔴 2026-08-22: 菜单被隐藏的页面, 用户根本到不了 -> ⛔ 不算漂移。
                # 此前只用**路由 module** 当「看得见」的代理, 于是我按 owner 要求
                # 隐藏「数据分析」之后, 那 4 个 analytics 页(菜单已隐藏)照样被报成
                # 「财务看得见但点不开」—— 假阳性。
                # ▎「路由能进」和「菜单里看得见」是两件事, 判可见性要用后者。
                # ⚠️ 2026-08-22: 这里**试过**再叠加「菜单是否隐藏」, 已撤销。
                #    那个过滤靠文件名猜菜单项(seg in view 子串匹配), 太松,
                #    **把整道闸滤瞎了** —— 同一个变异(调拨单 inventory->warehouse)
                #    在加过滤前报 9 处, 加过滤后报 0。
                #    ▎读数变好先分清: 修好了 / 仪器瞎了。这次是后者。
                # ⇒ 宁可留几条**已知原因**的误报(见文件头「看不见什么」), 也不要一个
                #   会让真缺陷溜过去的过滤器。view(文件路径) 与菜单键(URL) 之间
                #   没有可靠映射, 要做得先有那个映射, ⛔ 不是靠子串猜。
                visible = declared in has and route_extra.issubset(has)
                if visible and (blocked_any or blocked_all):
                    findings.append({
                        "role": role, "view": view, "menu_module": declared,
                        "endpoint": call, "verb": verb, "controller": cls,
                        "requires": sorted(any_of),
                        # 闸二: 必须全部满足。⛔ 与 requires 分开报 ——
                        # 合并成一个列表会让读的人以为「任一即可」。
                        "requiresAll": sorted(all_of),
                        "gate": ("module" if blocked_all else "permission"),
                    })
    return findings


def key(f):
    # ⚠️ 键里必须含 verb: 同一路径的 GET/POST 是**两个端点**, 权限可以不同
    # (本仓普遍是「读放开、写收紧」)。不含 verb 会把两者折叠成一条。
    return f"{f['role']}|{f['view']}|{f.get('verb', '?')}|{f['endpoint']}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", help="写出完整清单(JSON)")
    ap.add_argument("--role", help="只看一个角色")
    ap.add_argument("--baseline", help="基线文件(闸模式)")
    ap.add_argument("--check", action="store_true", help="闸模式: 只禁增长")
    args = ap.parse_args()

    check_module_registry_mirror()   # ⛔ 先钉住镜像表, 漂了就 rc=2, 不出错读数
    ctrl = load_controllers()
    routes = load_routes()
    if not ctrl:
        die("一个控制器都没扫到 —— 仪器坏了, 本次读数作废")
    if not routes:
        die("一条路由都没扫到 —— 仪器坏了, 本次读数作废")

    perms = load_role_permissions()
    findings = scan(perms, ctrl, routes, args.role)
    uniq = {key(f): f for f in findings}

    by_role = defaultdict(set)
    for f in uniq.values():
        by_role[f["role"]].add((f["view"], f["endpoint"]))

    print(f"路由(带 module) {len(routes)} 条 / 有权限声明的控制器 {len(ctrl)} 个 / "
          f"角色 {len(perms)} 个")
    print(f"🔴 「看得见但点进去必 403」的组合: {len(uniq)} 处, 覆盖 {len(by_role)} 个角色\n")
    for role in sorted(by_role, key=lambda r: -len(by_role[r])):
        print(f"  {role:24s} {len(by_role[role]):3d} 处")

    # ⛔ 三个数都要报, 缺一个读数就会被误读:
    if EXEMPTED:
        print("")
        print(f"✅ 已被前端守卫挡住(登记在案, 且那道守卫**当前仍在**): {len(EXEMPTED)} 处")
    if SKIPPED["guard_gone"]:
        print("")
        print(f"🔴 例外失效 —— 登记的守卫不见了, 这些又暴露了: {len(SKIPPED['guard_gone'])} 处")
        for x in sorted(SKIPPED["guard_gone"]):
            print(f"     {x}")
    # ── 角色路径白名单一致性 (guards.ts 的 ROLE_PATH_WHITELIST) ──────────────
    wl_findings = check_role_path_whitelist_consistency(perms)
    wl_a = [x for x in wl_findings if x[0] == "A"]
    wl_b = [x for x in wl_findings if x[0] == "B"]
    if wl_a:
        print("")
        print(f"🔴 路径白名单里的**死条目**(白名单放行、但该角色没有对应模块): {len(wl_a)} 处")
        for _t, role, path, mod in wl_a:
            print(f"     {role}  {path}  需要 module={mod}")
    if wl_b:
        print("")
        print(f"⚠️ 仅供参考 —— 菜单可见但不在路径白名单: {len(wl_b)} 处")
        print("   ⛔ **这个数会误报**: 菜单是树, 而本脚本按扁平项解析,")
        print("      父组的 hideForFactoryTypes 只能靠路径前缀近似继承, 而 children")
        print("      的 path 不一定以父 path 开头(实测 /smart-bi 组里有 /analytics/*)。")
        print("      ⇒ 逐条人工确认它是不是躺在某个已隐藏的组里。")
        for _t, role, path, mod in wl_b:
            print(f"     {role}  {path}  module={mod}")
    if SKIPPED["no_verb"]:
        print("")
        print(f"⚠️ 覆盖面缺口 —— 认不出 HTTP 动词而跳过: {len(SKIPPED['no_verb'])} 处"
              f" (配置对象里的 url:, 不是函数调用)")

    if args.report:
        io.open(args.report, "w", encoding="utf-8", newline="\n").write(
            json.dumps(sorted(uniq.values(), key=key), ensure_ascii=False, indent=2))
        print(f"\n完整清单已写入 {args.report}")

    if args.check:
        if not args.baseline or not os.path.exists(args.baseline):
            die("闸模式需要 --baseline 指向一个存在的基线文件")
        base = {key(f) for f in json.load(io.open(args.baseline, encoding="utf-8"))}
        new = sorted(set(uniq) - base)
        gone = sorted(base - set(uniq))
        print(f"\n基线 {len(base)} 处 / 当前 {len(uniq)} 处 / 新增 {len(new)} / 修掉 {len(gone)}")
        if gone:
            print("✓ 修掉了:")
            for k in gone[:10]:
                print(f"    {k}")
            print("  ⇒ 记得同步收紧基线(重跑 --report 覆盖它), 否则棘轮松了。")
        if new:
            print("🔴 新增(不允许):")
            for k in new:
                print(f"    {k}")
            return 1
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
