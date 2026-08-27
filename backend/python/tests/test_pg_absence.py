"""`is_local_postgres_absent` 的判别式测试。

## 为什么这个函数值得单独一组测试

它决定「skip 还是红」，而**两个方向错了都很贵**：

- 判得太窄 → 本机没起库时报 8 个 ERROR（2026-08-27 在 macOS 上实测发生）
- 判得太宽 → 真实故障（迁移写错、权限不对）被吞成 skip，**一道安静的绿**

而且它的输入形态**跨平台不一样**（Linux 单个 `ConnectionRefusedError`，
macOS 双栈聚合成一个无 errno 的 `OSError`），
所以「在我的机器上跑通了」对这个函数格外不算数 ⇒ 把各平台的真实形态钉成用例。

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
