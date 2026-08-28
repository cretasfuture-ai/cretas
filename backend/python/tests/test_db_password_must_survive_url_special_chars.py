"""数据库密码含 URL 特殊字符时，连接配置**不许**被拼坏。

## 🔴 2026-08-28 生产实测抓到

`/smart-bi/data-completeness` 整屏 500：

```
POST /smartbi-api/api/client-requirement/completeness/compute  {"factory_id":"F006"}
-> 500 {"success":false,"message":"invalid literal for int() with base 10: 'XXXXXXX'"}
```

那个 `'XXXXXXX'` **是生产数据库密码的首段**。成因是一行裸拼：

```python
db_url = f"postgresql://{user}:{password}@{host}:{port}/{db}"
```

生产密码里含 `/`，而 `/` 会把 netloc 在 `@` **之前**就截断：

```
postgresql://cretas_user:AbC/xxxxxxxx@host:5432/db
             └── netloc 只到第一个 / ──┘   @host:5432 掉进了 path
```

asyncpg 于是解析出 `host=cretas_user` / `port="AbC"`，`int(port)` 当场炸。

⚠️ **两条独立的坏**，这个文件各守一条：

1. **这条路 100% 起不来** —— 不是偶发、不是数据相关。只要密码含 `/`，
   `_get_db_pool()` 每一次都失败，也就是说这块功能**一天都没工作过**。
2. **异常文本裹着凭证被回显给客户端** —— `detail=str(e)` 把密码首段
   返给了任何已认证调用方。

## ⚠️ 修法不是「补一个 quote()」

仓里这个 DSN 已经有 6 份拷贝，其中 3 份记得 `quote()`、3 份忘了 ——
再补一个 `quote()` 只是把同一个决定复制成第 7 份，下一份照样会忘。

- **能分参数传的（asyncpg）一律分参数传** —— 那样「哪个字符要转义」
  这个问题从物理上不存在。
- **必须是 URL 串的（SQLAlchemy）** 才允许 `quote(password, safe='')`，
  并由本文件最后那道源码闸钉住。
"""
from __future__ import annotations

import ast
import io
import re
from pathlib import Path
from urllib.parse import unquote, urlparse

import pytest

# 一个把所有已知坑都踩一遍的密码：`/` 截断 netloc、`@` 冒充分隔符、`:` 冒充端口。
NASTY_PASSWORD = "AbC/xy@z:1234/tail"

PY_ROOT = Path(__file__).resolve().parents[1]


# --------------------------------------------------------------------------
# 阳性对照：先证明「裸拼」这个写法真的会坏
# --------------------------------------------------------------------------

def test_positive_control_naive_interpolation_really_does_break():
    """⛔ 没有这一条，下面的「修好了」可能只是因为我的判据谁也测不出来。

    这条**复现**生产那个报错：裸拼之后，被解析出来的 port 不是 5432，
    而是密码的首段 —— 那正是 `int()` 拿到的东西。
    """
    naive = f"postgresql://cretas_user:{NASTY_PASSWORD}@db.internal:5432/cretas_db"
    parsed = urlparse(naive)

    # netloc 在第一个 `/` 处就断了 —— `@db.internal:5432` 根本不在 netloc 里。
    assert "@db.internal" not in parsed.netloc, (
        "这条阳性对照失效了：netloc 没有被 `/` 截断，说明本测试的前提变了")

    # 驱动会把 `:` 后面那段当端口。它是密码的一部分，不是数字。
    port_candidate = parsed.netloc.rsplit(":", 1)[-1]
    with pytest.raises(ValueError, match="invalid literal for int"):
        int(port_candidate)


# --------------------------------------------------------------------------
# 被修的那条路：分参数传，特殊字符一个都不该影响连接参数
# --------------------------------------------------------------------------

def test_completeness_pool_kwargs_survive_a_nasty_password(monkeypatch):
    from client_requirement import completeness_calculator as cc

    monkeypatch.setenv("FOOD_KB_POSTGRES_HOST", "db.internal")
    monkeypatch.setenv("FOOD_KB_POSTGRES_PORT", "5432")
    monkeypatch.setenv("FOOD_KB_POSTGRES_DB", "cretas_db")
    monkeypatch.setenv("FOOD_KB_POSTGRES_USER", "cretas_user")
    monkeypatch.setenv("FOOD_KB_POSTGRES_PASSWORD", NASTY_PASSWORD)

    kwargs = cc._pool_kwargs()

    assert kwargs["host"] == "db.internal", "host 被密码里的字符污染了"
    assert kwargs["port"] == 5432, "port 被密码首段顶替了 —— 生产那个 500 会复发"
    assert kwargs["database"] == "cretas_db"
    assert kwargs["user"] == "cretas_user"
    # 密码要原样送到驱动，⛔ 不许在这一层做任何转义（转义是 URL 才需要的）。
    assert kwargs["password"] == NASTY_PASSWORD, "分参数传时密码不该被改写"


def test_efficiency_repository_url_survives_a_nasty_password(monkeypatch):
    """SQLAlchemy 必须是 URL 串 —— 那就必须 percent-encode。"""
    from efficiency_recognition.database.repository import EfficiencyRepository

    monkeypatch.setenv("POSTGRES_ENABLED", "true")
    monkeypatch.setenv("POSTGRES_HOST", "db.internal")
    monkeypatch.setenv("POSTGRES_PORT", "5432")
    monkeypatch.setenv("POSTGRES_DB", "smartbi_db")
    monkeypatch.setenv("POSTGRES_USER", "smartbi_user")
    monkeypatch.setenv("POSTGRES_PASSWORD", NASTY_PASSWORD)

    url = EfficiencyRepository.__dict__["_get_database_url"](object.__new__(EfficiencyRepository))
    parsed = urlparse(url)

    assert parsed.hostname == "db.internal", f"host 被拼坏了: {parsed.hostname!r}"
    assert parsed.port == 5432, "port 被密码首段顶替了"
    assert parsed.path == "/smartbi_db"
    assert parsed.username == "smartbi_user"
    # ⚠️ urlparse **不做** percent-decode, 取回原值必须 unquote —— 少这一步会
    #    把「正确编码」误判成「密码被改坏了」。
    assert unquote(parsed.password or "") == NASTY_PASSWORD, "percent-encode 之后解不回原密码"


# --------------------------------------------------------------------------
# 同族闸：别的地方别再裸拼
# --------------------------------------------------------------------------

# ⚠️ 这道闸**必须走 AST**，⛔ 不能用字符串计数。第一版是正则，当场栽了两次：
#   ① 它匹配到**本文件和被测文件里解释这个坑的 docstring**（本仓记过四次的
#      「闸把自己的注释当成被测对象」）；
#   ② 它把 `smartbi/config.py` 三处**本来就对的**判成违例 —— 那里的 `passwd`
#      在上一行已经 `quote()` 过了，而正则只看得见 `{passwd}` 这个字面。
# AST 一次解决两个：docstring 是 `Constant` 不是 `JoinedStr`，天然扫不到；
# 而「这个值有没有过 quote」是可以顺着赋值解出来的。

# 建闸当天的存量豁免。⚠️ 是**清单**不是计数 —— 计数会掩盖「修好一个、弄坏一个」。
KNOWN_RAW_DSN: list[str] = []

_DSN_PREFIX = re.compile(r"postgresql(?:\+\w+)?://$")


def _is_quote_call(node: ast.AST) -> bool:
    """`quote(...)` 或 `urllib.parse.quote(...)` —— 两种写法仓里都有。

    ⚠️ 只认裸 `quote` 会把 `main_food_kb.py` 误报成违例（它用的是
    `urllib.parse.quote`，AST 上是 `Attribute` 不是 `Name`）。
    """
    if not isinstance(node, ast.Call):
        return False
    func = node.func
    if isinstance(func, ast.Name):
        return func.id == "quote"
    if isinstance(func, ast.Attribute):
        return func.attr == "quote"
    return False


def _names_bound_to_quote(tree: ast.AST) -> set[str]:
    """收集 `x = quote(...)` 这种名字 —— 它们是已经编码过的。"""
    bound: set[str] = set()
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assign):
            continue
        if _is_quote_call(node.value):
            for target in node.targets:
                if isinstance(target, ast.Name):
                    bound.add(target.id)
    return bound


def _password_slot_is_encoded(fstring: ast.JoinedStr, quoted_names: set[str]) -> bool | None:
    """在 f-string 里找 ``postgresql://{user}:{password}@`` 的密码槽。

    返回 True/False；不是这种形状就返回 None（不关本闸的事）。
    """
    parts = fstring.values
    for i, part in enumerate(parts):
        # 找 `...postgresql://` 紧跟一个插值(user)、再跟字面 `:`、再跟插值(password)
        if not (isinstance(part, ast.Constant) and isinstance(part.value, str)):
            continue
        if not _DSN_PREFIX.search(part.value):
            continue
        rest = parts[i + 1:]
        if len(rest) < 3:
            continue
        sep = rest[1]
        if not (isinstance(sep, ast.Constant) and isinstance(sep.value, str) and sep.value.startswith(":")):
            continue
        slot = rest[2]
        if not isinstance(slot, ast.FormattedValue):
            continue
        expr = slot.value
        if _is_quote_call(expr):
            return True
        if isinstance(expr, ast.Name) and expr.id in quoted_names:
            return True
        return False
    return None


def _scan_source(source: str) -> list[int]:
    """返回裸拼密码的行号。"""
    tree = ast.parse(source)
    quoted_names = _names_bound_to_quote(tree)
    bad: list[int] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.JoinedStr):
            verdict = _password_slot_is_encoded(node, quoted_names)
            if verdict is False:
                bad.append(node.lineno)
    return sorted(set(bad))


def _sweep() -> tuple[list[str], int]:
    """返回 (违例清单, 实际扫过的文件数)。

    ⚠️ 文件数要**一起返回**：`PY_ROOT` 写错时这个函数会安安静静返回 `[]`，
    而「0 处违例」和「一个文件都没扫」长得一模一样。本仓记过这个形态
    （「验证 X 没发生时，必须同时有一个 Y 一定会发生的读数」）。
    """
    offenders: list[str] = []
    scanned = 0
    for path in sorted(PY_ROOT.rglob("*.py")):
        rel = path.relative_to(PY_ROOT).as_posix()
        if rel.startswith(("tests/", "venv", ".venv")):
            continue
        try:
            source = io.open(path, encoding="utf-8", newline="").read()
        except (UnicodeDecodeError, OSError):
            continue
        try:
            found = _scan_source(source)
        except SyntaxError:
            continue
        scanned += 1
        offenders.extend(f"{rel}:{lineno}" for lineno in found)
    return sorted(set(offenders)), scanned


def _dsn_offenders() -> list[str]:
    return _sweep()[0]


RAW_SAMPLE = 'x = f"postgresql://{user}:{password}@{host}:{port}/{db}"'
ENCODED_INLINE = 'x = f"postgresql://{quote(u)}:{quote(p)}@{h}:{po}/{d}"'
ENCODED_VIA_ATTR = (
    "def f():\n"
    "    enc = urllib.parse.quote(db_pass, safe='')\n"
    '    return f"postgresql://{db_user}:{enc}@{h}:{po}/{d}"\n'
)
ENCODED_VIA_VAR = (
    "def f():\n"
    "    u = quote(user, safe='')\n"
    "    pw = quote(password, safe='')\n"
    '    return f"postgresql://{u}:{pw}@{host}:{port}/{db}"\n'
)
DOCSTRING_MENTION = '"""说明: 以前写的是 f\\"postgresql://{user}:{password}@{host}\\" 这种。"""\n'


def test_positive_control_the_dsn_scanner_can_see_a_raw_dsn():
    """⛔ 没有这条，「0 处违例」可能只是因为这把尺子谁也匹配不上。"""
    assert _scan_source(RAW_SAMPLE), "认不出裸拼 —— 本次读数作废"


def test_the_scanner_does_not_flag_already_encoded_forms():
    """⚠️ 收紧判据时最容易犯的错是把真违例一起放掉；这两条守另一头。"""
    assert _scan_source(ENCODED_INLINE) == [], "把 inline quote() 误报成违例"
    assert _scan_source(ENCODED_VIA_VAR) == [], (
        "把「上一行 quote()、这一行用变量」误报成违例 —— smartbi/config.py 就是这个写法")
    assert _scan_source(ENCODED_VIA_ATTR) == [], (
        "把 `urllib.parse.quote` 误报成违例 —— main_food_kb.py 就是这个写法")


def test_the_scanner_does_not_flag_its_own_documentation():
    """🔴 第一版正则就是栽在这里：它匹配到了解释这个坑的注释本身。"""
    assert _scan_source(DOCSTRING_MENTION) == [], "闸把自己的说明文字当成了被测对象"


def test_the_sweep_actually_visited_the_source_tree():
    """⛔ 「0 处违例」必须先证明它扫过东西 —— 否则那个 0 是「没量到」，不是「没问题」。"""
    _, scanned = _sweep()
    assert scanned > 100, (
        f"只扫到 {scanned} 个 .py —— PY_ROOT 或过滤条件坏了，下面那条读数作废")


def test_no_new_raw_password_interpolation_into_a_dsn():
    offenders = _dsn_offenders()
    added = [o for o in offenders if o.split(":")[0] not in KNOWN_RAW_DSN]
    assert added == [], (
        "新增了「把密码裸拼进 postgres URL」的地方。\n"
        "⛔ 不要加进 KNOWN —— asyncpg 就分参数传，SQLAlchemy 就 quote(pw, safe='')。\n"
        f"违例: {added}")
