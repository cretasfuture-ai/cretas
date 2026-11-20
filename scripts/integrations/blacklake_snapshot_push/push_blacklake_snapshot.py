"""黑湖只读快照推送到 47（统一原型迁移 P10，DECISIONS 十八 M6）。

采集仍在本机（计划任务 Cretas-Blacklake-Daily-0800 写 monitor.sqlite3），本脚本只做三件事：
  1. 只读打开 monitor.sqlite3，取最近一次 success 的采集轮；
  2. 按白名单投影报工、工单成本和生产领料，只保留工单汇总人工成本；
  3. 用平台签发的专用推送凭证 POST 到 47 的 /api/public/external-snapshots/push。

凭据不出本机：推送凭证只存在本机 DPAPI 文件（当前 Windows 用户才能解开），⛔ 不进命令行参数、不进日志、不进仓库。
黑湖账号凭据本脚本完全不碰（不登录黑湖、不调黑湖接口、不回写）。

用法（Windows，B:\\anaconda3\\python.exe 或任意 Python 3.10+，无第三方依赖）：
  python -I push_blacklake_snapshot.py set-token            # 从标准输入读凭证，DPAPI 加密落盘
  python -I push_blacklake_snapshot.py push --config D:\\...\\config.json --endpoint https://admin.cretaceousfuture.com
  python -I push_blacklake_snapshot.py push --config ... --dry-run --out preview.json   # 只生成，不发

退出码三态（measurement-and-wiring 硬约束 4）：0 已推送 / 重复推送已确认；1 服务端拒收或网络失败；
2 没量到（库不存在、没有成功采集轮、凭证缺失、有报工的良品 / 不良数读不出来）。
⛔ 读不出来的数量不当 0：整批不推（rc=2），输出里报出几行、哪个字段。
"""
from __future__ import annotations

import argparse
import ctypes
import ctypes.wintypes
import json
import os
import sqlite3
import sys
import urllib.error
import urllib.request
from collections import OrderedDict
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path

SYSTEM = "blacklake"
DATASET = "report_output"
TOKEN_HEADER = "X-Cretas-Snapshot-Token"
PUSH_PATH = "/api/public/external-snapshots/push"
# 与 Java ExternalDataset.REPORT_OUTPUT.allowedFields 一致（服务端多一个键就整批拒收）
ALLOWED_FIELDS = ("reportDate", "productCode", "productName", "processName", "unit", "goodQty", "defectQty", "reportCount")
MAX_ROWS = 5000
# 与 Java ExternalIntegrationService.MAX_PUSH_BODY_BYTES 一致（低于 nginx 默认 1m），先在本机自查
MAX_BODY_BYTES = 1_000_000


# ------------------------------------------------------------------ DPAPI（当前用户范围）

class _Blob(ctypes.Structure):
    _fields_ = [("cbData", ctypes.wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_char))]


def _blob(data: bytes) -> _Blob:
    buf = ctypes.create_string_buffer(data, len(data))
    return _Blob(len(data), ctypes.cast(buf, ctypes.POINTER(ctypes.c_char)))


def _dpapi(data: bytes, protect: bool) -> bytes:
    if os.name != "nt":
        raise SystemExit("DPAPI 只在 Windows 上可用")
    crypt32 = ctypes.windll.crypt32
    kernel32 = ctypes.windll.kernel32
    src, out = _blob(data), _Blob()
    fn = crypt32.CryptProtectData if protect else crypt32.CryptUnprotectData
    ok = fn(ctypes.byref(src), None, None, None, None, 0x1, ctypes.byref(out))  # CRYPTPROTECT_UI_FORBIDDEN
    if not ok:
        raise OSError("DPAPI 调用失败: " + str(ctypes.GetLastError()))
    try:
        return ctypes.string_at(out.pbData, out.cbData)
    finally:
        kernel32.LocalFree(out.pbData)


def default_token_path() -> Path:
    base = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
    return Path(base) / "Cretas" / "SnapshotPush" / "blacklake-push-token.dpapi"


def save_token(token: str, path: Path) -> None:
    token = token.strip()
    if not token.startswith("cxs_"):
        raise SystemExit("凭证格式不对（应以 cxs_ 开头，由平台「工厂管理 › 外部软件」签发）")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(_dpapi(token.encode("utf-8"), protect=True))


def load_token(path: Path) -> str | None:
    if not path.exists():
        return None
    return _dpapi(path.read_bytes(), protect=False).decode("utf-8").strip()


# ------------------------------------------------------------------ 读采集库（只读）

def latest_success_cycle(db: Path) -> tuple[dict, dict]:
    """返回 (cycle 行, 该轮 payload)。只读打开，不写采集库。"""
    conn = sqlite3.connect(f"file:{db.as_posix()}?mode=ro", uri=True)
    try:
        row = conn.execute(
            "SELECT id, finished, hash FROM cycle WHERE status = 'success' AND hash IS NOT NULL "
            "AND finished IS NOT NULL ORDER BY finished DESC LIMIT 1").fetchone()
        if not row:
            raise LookupError("采集库里没有成功的采集轮")
        payload_row = conn.execute("SELECT payload FROM content WHERE hash = ?", (row[2],)).fetchone()
        if not payload_row:
            raise LookupError("成功采集轮的内容缺失: " + row[2])
        return {"id": row[0], "finished": row[1]}, json.loads(payload_row[0], parse_float=Decimal)
    finally:
        conn.close()


class UnreadableQuantity(ValueError):
    """报工里有数量读不出来：不能当 0 推上去（会把「不知道」变成「有差异」）。"""


def _dec(value) -> Decimal | None:
    """读不出来返回 None（由调用方计数并拒推），⛔ 不当 0。空值 / 缺字段也算读不出来。"""
    if value is None or isinstance(value, bool):
        return None
    text = str(value).strip()
    if not text:
        return None
    try:
        d = Decimal(text)
    except InvalidOperation:
        return None
    return d if d.is_finite() else None


def _fmt(d: Decimal) -> str:
    q = d.quantize(Decimal("0.001"))
    return format(q, "f")


def project_report_output(payload: dict) -> list[dict]:
    """黑湖报工 → 白名单字段汇总行。只读 output / process 两个数据集里允许的键。"""
    data = payload.get("data") or {}
    process_names = {}
    for p in data.get("process") or []:
        if isinstance(p, dict) and p.get("id") is not None:
            process_names[p["id"]] = str(p.get("name") or "").strip()
    groups: "OrderedDict[tuple, dict]" = OrderedDict()
    unreadable: dict[str, int] = {}
    for r in data.get("output") or []:
        if not isinstance(r, dict):
            continue
        end = str(r.get("endtimeofReport") or r.get("starttimeofReport") or "")
        date = end[:10]
        if len(date) != 10 or date[4] != "-" or date[7] != "-":
            continue
        key = (date, str(r.get("productCode") or "").strip(), str(r.get("productName") or "").strip(),
               process_names.get(r.get("processId"), ""), str(r.get("stockUnit") or "").strip())
        good = _dec(r.get("numberofgoodProducts"))
        defect = _dec(r.get("numberofDefectives"))
        if good is None:
            unreadable["numberofgoodProducts"] = unreadable.get("numberofgoodProducts", 0) + 1
        if defect is None:
            unreadable["numberofDefectives"] = unreadable.get("numberofDefectives", 0) + 1
        if good is None or defect is None:
            continue
        g = groups.setdefault(key, {"good": Decimal(0), "defect": Decimal(0), "count": 0})
        g["good"] += good
        g["defect"] += defect
        g["count"] += 1
    if unreadable:
        detail = "，".join(f"{k} {v} 行" for k, v in sorted(unreadable.items()))
        raise UnreadableQuantity("报工数量读不出来（不当 0，整批不推）：" + detail)
    rows = []
    for (date, code, name, process, unit), g in sorted(groups.items()):
        rows.append({"reportDate": date, "productCode": code, "productName": name, "processName": process,
                     "unit": unit, "goodQty": _fmt(g["good"]), "defectQty": _fmt(g["defect"]),
                     "reportCount": g["count"]})
    for row in rows:
        extra = set(row) - set(ALLOWED_FIELDS)
        if extra:
            raise AssertionError("投影带出了白名单外的字段: " + ", ".join(sorted(extra)))
    return rows


_COST_AMOUNTS = {
    "materialCost": "actualMaterialCost",
    "laborCost": "actualLaborCost",
    "otherCost": "actualOtherCost",
    "totalCost": "totalActualCost",
    "unitCost": "actualUnitCost",
}
_ISSUE_AMOUNTS = {
    "quantity": "qty",
    "costUnitPrice": "costUnitPrice",
    "signedCostAmount": "changedCostAmountSigned",
    "movingAveragePrice": "movingWeightedAverageCost",
}


def _text(value, field: str, *, nullable: bool = False) -> str | None:
    if value is None and nullable:
        return None
    if not isinstance(value, (str, int)) or isinstance(value, bool):
        raise ValueError(f"{field} 不是文本")
    result = str(value).strip()
    if not result and nullable:
        return None
    if not result or len(result) > 200:
        raise ValueError(f"{field} 为空或过长")
    return result


def _business_date(value, field: str) -> str:
    try:
        return date.fromisoformat(_text(value, field)[:10]).isoformat()
    except ValueError:
        raise ValueError(f"{field} 不是有效业务日期") from None


def _amounts(row: dict, fields: dict[str, str], *, required: tuple[str, ...] = ()) -> dict:
    result = {}
    for field, source in fields.items():
        value = row.get(source)
        if value is None or value == "":
            if field in required:
                raise ValueError(f"{source} 缺失")
            result[field] = None
            continue
        amount = _dec(value)
        if amount is None or not -100 <= amount.adjusted() <= 100 or amount.as_tuple().exponent < -100:
            raise ValueError(f"{source} 不是有效数值")
        number = format(amount, "f")
        if len(number) > 200:
            raise ValueError(f"{source} 超过数值长度上限")
        result[field] = number
    return result


def _source_rows(data: dict, key: str) -> list[dict]:
    rows = data[key]
    if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows):
        raise ValueError(f"{key} 不是有效的明细数组")
    return rows


def _project_index(data: dict) -> dict[str, dict]:
    projects = {}
    ids = set()
    for project in _source_rows(data, "project") if "project" in data else []:
        code = _text(project.get("projectCode"), "projectCode")
        identity = _text(project.get("id"), "project.id")
        if code in projects or identity in ids:
            raise ValueError("project 工单编码或 ID 重复")
        projects[code] = project
        ids.add(identity)
    return projects


def _matched_project(projects: dict[str, dict], code: str | None, identity) -> dict:
    project = projects.get(code)
    return project if project and str(project["id"]) == str(identity) else {}


def project_work_order_cost(data: dict, projects: dict[str, dict]) -> list[dict]:
    rows = []
    for cost in _source_rows(data, "project_cost"):
        if cost.get("deleted") is True:
            continue
        code = _text(cost.get("projectCode"), "projectCode")
        project = _matched_project(projects, code, cost.get("projectId"))
        business_date = (project.get("actualEndTime") or project.get("actualStartTime")
                         or project.get("createTime") or cost.get("createdAt"))
        project_unit = _text(project.get("projectUnit"), "projectUnit", nullable=True)
        cost_unit = _text(cost.get("unitName"), "unitName", nullable=True)
        amounts = _amounts(cost, _COST_AMOUNTS)
        if project_unit and cost_unit and project_unit.casefold() != cost_unit.casefold():
            # The reported unit cost has a different denominator unit.
            amounts["unitCost"] = None
        rows.append({
            "workOrderCode": code,
            "costDate": _business_date(business_date, "costDate"),
            "productCode": _text(cost.get("productCode"), "productCode"),
            "productName": _text(cost.get("productName"), "productName", nullable=True),
            "outputUnit": project_unit,
            # project_cost.realAmount is a costing quantity and can differ
            # from the actual final good output used for yield.
            **_amounts(project, {"outputQty": "numberofGoodProducts"}),
            **amounts,
        })
    return sorted(rows, key=lambda row: row["workOrderCode"])


def project_production_issue(data: dict, projects: dict[str, dict]) -> list[dict]:
    rows = []
    for order in _source_rows(data, "stock_order"):
        if str(order.get("bizType")) != "32" or str(order.get("orderStatus")) != "1":
            continue
        issue_date = _business_date(order.get("bizAt"), "bizAt")
        order_id = _text(order.get("id"), "stockOrder.id")
        for detail in _source_rows(order, "orderDetails"):
            detail_id = _text(detail.get("id"), "stockOrderDetail.id")
            line_id = f"{order_id}:{detail_id}"
            if ":" in order_id or ":" in detail_id or len(line_id) > 200:
                raise ValueError("领料单据与明细复合键格式错误")
            code = _text(detail.get("associatedBizOrderCode"), "workOrderCode", nullable=True)
            linked = (str(detail.get("associatedBizOrderType")) == "10"
                      and _matched_project(projects, code, detail.get("associatedBizOrderId")))
            rows.append({
                "lineId": line_id,
                "issueDate": issue_date,
                "workOrderCode": code if linked else None,
                "materialCode": _text(detail.get("productCode"), "materialCode"),
                "materialName": _text(detail.get("productName"), "materialName", nullable=True),
                "unit": _text(detail.get("unitName"), "unit", nullable=True),
                **_amounts(detail, _ISSUE_AMOUNTS, required=("quantity",)),
            })
    return sorted(rows, key=lambda row: row["lineId"])


def build_request(config: dict, cycle: dict, payload: dict) -> dict:
    finished = datetime.fromisoformat(cycle["finished"])
    if finished.tzinfo is None:
        finished = finished.replace(tzinfo=timezone.utc)
    source = payload.get("data") or {}
    datasets = {DATASET: project_report_output(payload)}
    projects = _project_index(source) if "project_cost" in source or "stock_order" in source else {}
    if "project_cost" in source:
        datasets["project_cost"] = project_work_order_cost(source, projects)
    if "stock_order" in source:
        datasets["production_issue"] = project_production_issue(source, projects)
    for name, rows in datasets.items():
        if len(rows) > MAX_ROWS:
            raise SystemExit(f"{name} 汇总后 {len(rows)} 行，超过服务端上限 {MAX_ROWS}")
    body = {
        "system": SYSTEM,
        "sourceOrgId": str(config["source_factory_id"]),
        "collectedAt": finished.astimezone(timezone.utc).isoformat(timespec="seconds"),
        "collectorRunId": str(cycle["id"])[:80],
        "datasets": datasets,
    }
    size = len(encode_body(body))
    if size > MAX_BODY_BYTES:
        raise SystemExit(f"请求体 {size} 字节，超过服务端上限 {MAX_BODY_BYTES}")
    return body


def encode_body(body: dict) -> bytes:
    return json.dumps(body, ensure_ascii=False).encode("utf-8")


def post(endpoint: str, token: str, body: dict, timeout: float = 60) -> tuple[int, dict]:
    url = endpoint.rstrip("/") + PUSH_PATH
    if not url.startswith("https://") and not url.startswith("http://localhost") and not url.startswith("http://127.0.0.1"):
        raise SystemExit("只允许推到 https，或本机 http://localhost / 127.0.0.1（本地测试）")
    req = urllib.request.Request(url, data=encode_body(body), method="POST",
                                 headers={"Content-Type": "application/json; charset=utf-8", TOKEN_HEADER: token})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")
        except ValueError:
            return e.code, {}


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="黑湖只读快照推送到 47（P10）")
    sub = ap.add_subparsers(dest="cmd", required=True)
    st = sub.add_parser("set-token", help="从标准输入读推送凭证并用 DPAPI 加密保存")
    st.add_argument("--token-file", type=Path, default=default_token_path())
    pu = sub.add_parser("push", help="取最近一次成功采集，投影后推送")
    pu.add_argument("--config", type=Path, required=True, help="采集配置 config.json（读 db 与 source_factory_id）")
    pu.add_argument("--endpoint", default="https://admin.cretaceousfuture.com")
    pu.add_argument("--token-file", type=Path, default=default_token_path())
    pu.add_argument("--dry-run", action="store_true", help="只生成请求体，不发送")
    pu.add_argument("--out", type=Path, help="把请求体写到这个文件（便于核对，不含凭证）")
    args = ap.parse_args(argv)

    if args.cmd == "set-token":
        token = sys.stdin.readline()
        save_token(token, args.token_file)
        print(json.dumps({"saved": str(args.token_file)}, ensure_ascii=False))
        return 0

    try:
        config = json.loads(args.config.read_text(encoding="utf-8"))
        db = Path(config["db"])
        if not db.exists():
            print(json.dumps({"status": "unmeasured", "reason": "采集库不存在: " + str(db)}, ensure_ascii=False))
            return 2
        cycle, payload = latest_success_cycle(db)
        body = build_request(config, cycle, payload)
    except (LookupError, KeyError, ValueError) as e:
        print(json.dumps({"status": "unmeasured", "reason": str(e)}, ensure_ascii=False))
        return 2
    rows = len(body["datasets"][DATASET])
    counts = {name: len(lines) for name, lines in body["datasets"].items()}
    if args.out:
        args.out.write_text(json.dumps(body, ensure_ascii=False, indent=1), encoding="utf-8")
    if args.dry_run:
        print(json.dumps({"status": "dry-run", "collectedAt": body["collectedAt"], "rows": rows,
                          "datasetRows": counts}, ensure_ascii=False))
        return 0
    token = load_token(args.token_file)
    if not token:
        print(json.dumps({"status": "unmeasured", "reason": "没有推送凭证（先 set-token）"}, ensure_ascii=False))
        return 2
    try:
        code, resp = post(args.endpoint, token, body)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(json.dumps({"status": "failed", "reason": "网络失败: " + type(e).__name__}, ensure_ascii=False))
        return 1
    ok = code == 200 and resp.get("success") is True
    print(json.dumps({"status": "pushed" if ok else "rejected", "http": code, "collectedAt": body["collectedAt"],
                      "rows": rows, "datasetRows": counts, "message": resp.get("message"),
                      "data": resp.get("data") if ok else None},
                     ensure_ascii=False))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
