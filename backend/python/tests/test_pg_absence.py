"""`is_local_postgres_absent` 的判别式测试。

## 为什么这个函数值得单独一组测试

它决定「skip 还是红」，而**两个方向错了都很贵**：

- 判得太窄 → 本机没起库时报 8 个 ERROR（2026-08-27 在 macOS 上实测发生）
- 判得太宽 → 真实故障（迁移写错、权限不对）被吞成 skip，**一道安静的绿**

而且它的输入形态**取决于 DSN 里的主机名怎么解析**，不取决于操作系统：
`127.0.0.1` 这种字面 IPv4 给出单个 `ConnectionRefusedError`（判别式认得），
而 `localhost` 在双栈机器上解析出 `::1` + `127.0.0.1` 两个地址，两个都被拒时
asyncio 合成一个**无 errno、无 cause/context** 的聚合 `OSError`（判别式认不出）。

⚠️ 2026-08-27 在 **Linux 上复核过**：聚合形态与 macOS **结构逐字相同**
   （只有 errno 数字不同，111 vs 61，而聚合异常自己的 errno 两边都是 None）。
   ⇒ 这不是「macOS 兼容性」，是平台无关的缺陷；它一直没暴露只是因为托管上
     那个连接根本没失败。所以「在我的机器上跑通了」对这个函数格外不算数。

⚠️ 阳性形态全部照抄**真实异常**的构造方式，不是我想象的形状：
   聚合那条的消息是从 macOS 上实测的报错原文取的。
"""
from __future__ import annotations

import pytest

from .pg_absence import is_local_postgres_absent


# ══════════════════════════════════════════════════════════
# 阳性：这些都该判成「库没起」→ skip
# ══════════════════════════════════════════════════════════

def test_plain_connection_refused_is_absence():
    assert is_local_postgres_absent(ConnectionRefusedError(61, "Connection refused"))


@pytest.mark.parametrize("errno_value", [61, 111])
def test_oserror_with_refused_errno_is_absence(errno_value):
    """macOS ECONNREFUSED=61 / Linux=111 —— 两个平台各钉一条。"""
    exc = OSError()
    exc.errno = errno_value
    assert is_local_postgres_absent(exc)


def test_macos_dual_stack_aggregate_is_absence():
    """🔴 这一条就是 2026-08-27 漏掉的形态。

    `localhost` 同时解析到 `::1` 和 `127.0.0.1`，两个都被拒，
    asyncio 合成一个**没有 errno、没有 cause/context** 的 OSError，
    子异常只活在消息里。原版判别式在这条上返回 False。
    """
    exc = OSError(
        "Multiple exceptions: [Errno 61] Connect call failed ('::1', 5432, 0, 0), "
        "[Errno 61] Connect call failed ('127.0.0.1', 5432)"
    )
    assert exc.errno is None, "前提: 聚合异常确实没有 errno(否则本用例测的不是那个形态)"
    assert exc.__cause__ is None and exc.__context__ is None, "前提: 链上也没有子异常"
    assert is_local_postgres_absent(exc)


def test_absence_found_through_cause_chain():
    outer = RuntimeError("pool bootstrap failed")
    outer.__cause__ = ConnectionRefusedError(111, "Connection refused")
    assert is_local_postgres_absent(outer)


def test_absence_found_inside_exception_group():
    """3.11+ 的 `ExceptionGroup` 把子异常挂在 `.exceptions` 上，不在 cause 链里。"""
    group = ExceptionGroup(                                    # noqa: F821 (3.11+)
        "connect attempts", [ConnectionRefusedError(61, "Connection refused")],
    )
    assert is_local_postgres_absent(group)


# ══════════════════════════════════════════════════════════
# 阴性：这些**必须**让它红，⛔ 不许吞成 skip
# ══════════════════════════════════════════════════════════

def test_authentication_failure_is_not_absence():
    """连上了但密码不对 —— 库是在的，这是真缺陷。"""
    assert not is_local_postgres_absent(
        Exception('password authentication failed for user "smartbi_user"')
    )


def test_missing_relation_is_not_absence():
    """迁移没跑 —— 库在、连上了，⛔ 这必须红。"""
    assert not is_local_postgres_absent(
        Exception('relation "smart_bi_chat_session" does not exist')
    )


def test_timeout_is_not_absence():
    """超时不等于「没有库」—— 可能是库卡住了，那是要看的东西。"""
    assert not is_local_postgres_absent(TimeoutError("connect timed out"))


def test_unrelated_oserror_is_not_absence():
    exc = OSError()
    exc.errno = 2                                   # ENOENT
    assert not is_local_postgres_absent(exc)


def test_multiple_exceptions_without_connect_failure_is_not_absence():
    """⛔ 只匹配 "Multiple exceptions:" 会太宽 —— 多地址各自出别的错时不该吞。

    这一条守的正是「收窄判据时别把真故障一起收掉」。
    """
    assert not is_local_postgres_absent(
        OSError("Multiple exceptions: [Errno 13] Permission denied, [Errno 13] Permission denied")
    )


def test_cycle_in_exception_chain_terminates():
    """自引用的异常链不能让它转不出来 —— 判别式跑在 except 块里，挂死等于测试卡住。"""
    a = RuntimeError("a")
    b = RuntimeError("b")
    a.__cause__ = b
    b.__cause__ = a
    assert not is_local_postgres_absent(a)


# ── 第三种缺席形态: 服务器在, 库不在 (2026-08-28) ──────────────────


class _FakeInvalidCatalogName(Exception):
    """伪造 asyncpg 的 InvalidCatalogNameError —— 判别式按类名认, 不 import asyncpg。

    ⚠️ 按类名认是**故意**的: 这个模块被 conftest 之外的地方 import,
    不该为了一条分支强制拖进 asyncpg 依赖。代价是类名撞车的风险,
    而 `InvalidCatalogNameError` 这个名字在 Python 生态里只有 asyncpg 用。
    """

    pass


_FakeInvalidCatalogName.__name__ = "InvalidCatalogNameError"


def test_database_does_not_exist_is_absence():
    """PG 在跑但目标库不存在 —— 对这些用例而言同样是「没有本地测试环境」。

    2026-08-27 那台自建 runner 装了原生 PostgreSQL 之后出现的新形态:
    握手成功、认证通过, 而 smartbi_db 不存在 ⇒ 8 条用例由 skip 变 ERROR。
    """
    assert is_local_postgres_absent(
        _FakeInvalidCatalogName('database "smartbi_db" does not exist')
    )


def test_it_is_reachable_through_a_cause_chain():
    outer = RuntimeError("wrapped")
    outer.__cause__ = _FakeInvalidCatalogName('database "smartbi_db" does not exist')
    assert is_local_postgres_absent(outer)


def test_post_connect_errors_are_NOT_absence():
    """🔴 阴性对照 —— 这条才是这次改动的边界。

    连上之后才可能出现的错误一律不许被吞成 skip: 表不存在 / 权限不对 /
    迁移写错, 那些都是**必须让它红**的真实故障。本次只放行 connect 阶段
    那一种(目标库不存在), 因为它不可能在一个已建立的连接上出现。
    """

    class _FakeUndefinedTable(Exception):
        pass

    _FakeUndefinedTable.__name__ = "UndefinedTableError"

    class _FakeInsufficientPrivilege(Exception):
        pass

    _FakeInsufficientPrivilege.__name__ = "InsufficientPrivilegeError"

    assert not is_local_postgres_absent(_FakeUndefinedTable('relation "x" does not exist'))
    assert not is_local_postgres_absent(_FakeInsufficientPrivilege("permission denied"))
    # 「does not exist」这句话本身不构成理由 —— 上面两个也含它。
