"""「本机 PostgreSQL 根本连不上」的判别式 —— 唯一实现。

## 为什么单独一个模块

这段判别式此前**在两个测试文件里各有一份**（`test_chat_session_service.py`
与 `test_chat_session_user_identity_postgres.py`），逐字相同。
2026-08-27 要修它时，只改一份就等于当场造一份漂移（形态 D / 硬约束 8）
⇒ 抽成一处，两边 `from .pg_absence import is_local_postgres_absent`。

## 它判什么、不判什么

判的是**「服务器不在那儿」**，⛔ 不是「连上了但出错」。
两者的处置完全相反：前者应当 `pytest.skip`（本机没起库，不是缺陷），
后者必须让它红（迁移写错、权限不对、schema 冲突……）。

⇒ 所以这里**宁可漏判**：判不出来就返回 False，让异常照常抛出去。
   一个过宽的「没有库」判据会把真实故障吞成 skip——那正是
   「收窄告警时别把『没能做决定』一起收掉」的反面。

## 🔴 2026-08-27：原版对「双栈 localhost」这种形态一条分支都不成立

原版只认三种形态：单个 `ConnectionRefusedError` /
`errno in {61, 111}` / `winerror == 10061`，并沿
`__cause__` / `__context__` 往下走。

而自建 CI runner（macOS）上 `localhost` **同时解析到 `::1` 和 `127.0.0.1`**，
两个地址都被拒，asyncio 把它们合成**一个**异常：

    OSError: Multiple exceptions: [Errno 61] Connect call failed ('::1', 5432, 0, 0),
                                  [Errno 61] Connect call failed ('127.0.0.1', 5432)

这个聚合异常 **`errno` 是 `None`，`__cause__` / `__context__` 也是空** ——
两个子异常只活在**消息字符串**里（见 CPython `asyncio/base_events.py`
的 `_connect_sock`：多个互不相同的子异常时构造一个纯消息 `OSError`）。

于是那 8 个用例在 macOS 上不是「优雅跳过」而是 ERROR。
⛔ 唯一可用的信号就是那段消息，所以这里**必须**匹配文本 —— 但匹配得窄：
   只认「多地址连接失败」这一种聚合形态，别的 `OSError` 一律不认。

🔴 **订正（2026-08-27，另一条线在真 Linux 上复核）**：
   我原本写的是「这与 Linux 上的表现不同」。**那是错的。**
   在 139（Linux / python3.11）上对 `localhost` 双栈跑同一个探针：

       str  = Multiple exceptions: [Errno 111] ('::1'), [Errno 111] ('127.0.0.1')
       errno = None    __cause__ = None    __context__ = None
       判别式认得它吗 = False          ← 与 macOS **结构逐字相同**

   ⇒ 这不是「macOS 兼容性」，是一个**平台无关的真缺陷**。
     它一直没暴露，是因为托管 runner 上那个连接**根本没失败**
     （有库，或只解析到单栈），**不是异常形态不同**。

   ⚠️ 同一条判别式对**字面 IPv4**（`127.0.0.1:5432`）是有效的（返回 True），
     只对 `localhost` 这种会解析出多地址的写法失效 —— 所以「换个 DSN 就好了」
     这种解释也不成立：失效面取决于 DSN 怎么写，而默认 DSN 写的就是 `localhost`。
"""
from __future__ import annotations

#: macOS `ECONNREFUSED` = 61, Linux = 111。Windows 走 `winerror`。
_CONNECTION_REFUSED_ERRNOS = frozenset({61, 111})
_WINDOWS_CONNECTION_REFUSED = 10061


def _looks_like_multi_address_refusal(exc: OSError) -> bool:
    """asyncio 的双栈聚合形态：`errno` 为 None，子异常只在消息里。

    ⛔ 两个条件都要满足才算 —— 只看 "Multiple exceptions:" 会把
    「多个地址各自出了别的错」也吞掉。
    """
    if exc.errno is not None:
        return False
    message = str(exc)
    return "Multiple exceptions:" in message and "Connect call failed" in message


def is_local_postgres_absent(exc: BaseException) -> bool:
    """遍历整条异常链（含 3.11+ 的 `ExceptionGroup`），判断是不是「库没起」。"""
    pending: list[BaseException | None] = [exc]
    seen: set[int] = set()

    while pending:
        current = pending.pop()
        if current is None or id(current) in seen:
            continue
        seen.add(id(current))

        if isinstance(current, ConnectionRefusedError):
            return True
        if isinstance(current, OSError):
            if getattr(current, "winerror", None) == _WINDOWS_CONNECTION_REFUSED:
                return True
            if current.errno in _CONNECTION_REFUSED_ERRNOS:
                return True
            if _looks_like_multi_address_refusal(current):
                return True

        # 🔴 2026-08-28: 第三种缺席形态 —— **服务器在，库不在**。
        #
        # 自建 runner 那台机器 2026-08-27 装了原生 PostgreSQL(为修 jpa-gate 的
        # 4 条红)。装之前这些用例走「连不上 -> skip」那条路; 装之后 TCP 握手
        # 成功、认证通过, 而 `smartbi_db` 不存在 ⇒ asyncpg 抛
        # `InvalidCatalogNameError`, 判别式认不出, 8 条用例由 skip 变 ERROR。
        # 与 #172 修的是同一个形状: **skip 守卫认不出一种新的缺席形态**,
        # ⛔ 不是「机器缺件」, 所以不进 ci-gate-excludes.self-hosted.txt。
        #
        # ⚠️ 这没有违反本模块「不判『连上了但出错』」那条原则:
        #    `InvalidCatalogNameError` 只在 **connect 阶段** 抛 —— 目标库不存在,
        #    连接压根没建成。迁移写错 / 权限不对 / schema 冲突这些「真实故障」
        #    全都发生在**连上之后**, 一条都不会被这里吞掉。
        #    判据: 它不可能在一个已建立的连接上出现。
        if type(current).__name__ == "InvalidCatalogNameError":
            return True

        # `ExceptionGroup`(3.11+) 把子异常挂在 `.exceptions` 上, 不在 cause/context 里。
        for sub in getattr(current, "exceptions", ()) or ():
            pending.append(sub)
        pending.append(current.__cause__)
        pending.append(current.__context__)

    return False
