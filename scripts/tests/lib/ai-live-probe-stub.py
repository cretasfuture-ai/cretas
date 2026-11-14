#!/usr/bin/env python3
"""scripts/tests/test-ai-live-probe.sh 用的离线桩服务器 (⛔ 不连生产)。

模拟 ai_live_probe.py 用到的线上接口: 登录 / 健康 / 两个 AI 入口 / 预览 / 确认 /
客户列表 / 微信记录列表与删除。STUB_MODE 选一种行为, 用来证明套件的三态退出码与
写入真跑的阴性对照真的会红:

  pass               全部按预期
  stream_drift       流式入口把「查库存」识别成别的意图 (两入口漂移) ⇒ rc=1
  wrong_tenant       登录回的是 LIUSHANMEN ⇒ rc=2, 且之后一次 AI 调用都不许发生
  bad_login          登录 401 ⇒ rc=2
  write_no_persist   /confirm 说成功但没落库 ⇒ rc=1 (回查计数不 +1)
  forged_accepted    伪造令牌也能确认并落库 ⇒ rc=1 (阴性对照③, 计数那半边)
  forged_says_ok     伪造令牌回「成功」但没落库 ⇒ rc=1 (阴性对照③, 「必须被拒」那半边)
  write_on_say       说话那一轮就落库了 (没等确认) ⇒ rc=1, 且套件不得再去确认
  leftovers          预置两条 QA-PROBE- 残留: 一条 2 小时前 (该删), 一条刚写 (别人在跑, 不许删)
  confirm_drop       正规确认写入了但连接被掐断 (无答复) ⇒ rc=1, 且不许再重放
  slow               每个 AI 请求慢 2 秒 (配合 --deadline-seconds 证明整体 deadline)

端口写到 argv[1] 指定的文件; 每个请求一行写进 argv[2] (方法 + 路径) 供测试数调用次数。
"""
import json
import os
import re
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer

MODE = os.environ.get("STUB_MODE", "pass")
PORT_FILE, LOG_FILE = sys.argv[1], sys.argv[2]
CUSTOMER = {"id": "cust-qa-1", "factoryId": "F006", "name": "QA-STUB-客户"}
ROWS = []          # 微信记录
TOKENS = {}        # token -> {"used": bool, "params": {...}}
NEXT_ID = [100]


def respond_result(intent, status="SUCCESS", message="ok", ai_mode="READ", **extra):
    data = {"intentCode": intent, "status": status, "message": message, "aiMode": ai_mode}
    data.update(extra)
    return data


def recognize(text, entry):
    if text == "查库存":
        if entry == "stream" and MODE == "stream_drift":
            return respond_result("REPORT_DASHBOARD_OVERVIEW", message="仪表盘")
        return respond_result("REPORT_INVENTORY", message="库存报告 (今日)")
    match = re.search(r"客户编号(\S+?)，消息内容：(QA-PROBE-\w+)", text)
    if match:
        params = {"customerId": match.group(1), "direction": "INTERNAL",
                  "messageContent": match.group(2) + " 实测关写入验证"}
        if MODE == "write_on_say":
            add_row(params)
        return respond_result("WECHAT_CREATE", status="WRITE_CONFIRM_REQUIRED", ai_mode="WRITE",
                              message="该操作会写入/修改数据，执行前需要确认。",
                              metadata={"previewParams": params})
    return respond_result(None, status="COMPLETED", message="闲聊")


def add_row(params, age_seconds=0):
    NEXT_ID[0] += 1
    stamp = time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(time.time() - age_seconds))
    ROWS.append({"id": NEXT_ID[0], "factoryId": "F006", "customerId": params["customerId"],
                 "direction": params["direction"], "messageContent": params["messageContent"],
                 "recordTime": stamp, "createdAt": stamp})


if MODE == "leftovers":
    add_row({"customerId": "cust-qa-1", "direction": "INTERNAL", "messageContent": "QA-PROBE-old 残留"}, 7200)
    add_row({"customerId": "cust-qa-1", "direction": "INTERNAL", "messageContent": "QA-PROBE-fresh 别人在跑"}, 60)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, code, obj, sse=False):
        if sse:
            body = "".join("event:%s\ndata:%s\n\n" % (name, json.dumps(data, ensure_ascii=False))
                           for name, data in obj).encode("utf-8")
            ctype = "text/event-stream"
        else:
            body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            ctype = "application/json"
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length).decode("utf-8")) if length else {}

    def _log(self):
        with open(LOG_FILE, "a", encoding="utf-8") as fh:
            fh.write("%s %s\n" % (self.command, self.path))

    def do_GET(self):
        self._log()
        if self.path == "/api/mobile/health":
            return self._send(200, {"status": "UP"})
        if self.path.startswith("/api/mobile/F006/customers"):
            return self._send(200, {"success": True, "data": {"content": [CUSTOMER]}})
        if self.path.startswith("/api/mobile/F006/customer/%s/wechat-record" % CUSTOMER["id"]):
            rows = [r for r in ROWS if r["customerId"] == CUSTOMER["id"]]
            return self._send(200, {"success": True, "data": {"content": rows, "totalElements": len(rows),
                                                              "count": len(rows)}})
        return self._send(404, {"success": False})

    def do_DELETE(self):
        self._log()
        match = re.match(r"/api/mobile/F006/customer/[^/]+/wechat-record/(\d+)$", self.path)
        if match:
            ROWS[:] = [r for r in ROWS if str(r["id"]) != match.group(1)]
            return self._send(200, {"success": True, "data": {"id": int(match.group(1))}})
        return self._send(404, {"success": False})

    def do_POST(self):
        self._log()
        body = self._body()
        if MODE == "slow" and "/ai-intents/" in self.path:
            time.sleep(2)
        if self.path == "/api/mobile/auth/unified-login":
            if MODE == "bad_login":
                return self._send(401, {"success": False, "message": "bad"})
            factory = "LIUSHANMEN" if MODE == "wrong_tenant" else "F006"
            return self._send(200, {"success": True, "data": {"accessToken": "stub-token", "factoryId": factory,
                                                              "username": body.get("username"), "role": "viewer"}})
        if self.path == "/api/mobile/F006/ai-intents/execute":
            return self._send(200, {"success": True, "data": recognize(body.get("userInput"), "execute")})
        if self.path == "/api/mobile/F006/ai-intents/execute/stream":
            data = recognize(body.get("userInput"), "stream")
            data["sessionId"] = body.get("sessionId")
            return self._send(200, [("start", {"sessionId": body.get("sessionId")}), ("result", data),
                                    ("complete", {"status": data["status"]})], sse=True)
        if self.path == "/api/mobile/F006/ai-intents/preview":
            params = body.get("context") or {}
            token = "tok-" + uuid.uuid4().hex
            TOKENS[token] = {"used": False, "params": params}
            preview = {"success": True, "data": {"canDo": True, "customerName": CUSTOMER["name"], "message": "✓ 准备补录"}}
            action = {"confirmToken": token, "commandDigest": "0" * 64, "expiresAt": "2099-01-01T00:00:00Z",
                      "previewData": preview}
            return self._send(200, {"success": True, "data": {"status": "PREVIEW", "intentCode": "WECHAT_CREATE",
                                                              "confirmableAction": action}})
        if self.path == "/api/mobile/F006/ai-intents/confirm":
            token = self.headers.get("X-Cretas-Confirmation-Token") or ""
            entry = TOKENS.get(token)
            if entry is None and MODE == "forged_says_ok":
                return self._send(200, {"success": True, "data": {"status": "SUCCESS", "message": "已补录"}})
            if entry is None and MODE == "forged_accepted" and TOKENS:
                entry = {"used": False, "params": list(TOKENS.values())[0]["params"]}
                add_row(entry["params"])
                return self._send(200, {"success": True, "data": {"status": "SUCCESS", "message": "已补录"}})
            if entry is None or entry["used"]:
                return self._send(200, {"success": False, "message": "令牌无效或已过期"})
            entry["used"] = True
            if MODE != "write_no_persist":
                add_row(entry["params"])
            if MODE == "confirm_drop":
                self.close_connection = True
                self.connection.shutdown(2)
                return None
            return self._send(200, {"success": True, "data": {"status": "SUCCESS", "message": "✓ 已补录"}})
        return self._send(404, {"success": False})


server = HTTPServer(("127.0.0.1", 0), Handler)
with open(PORT_FILE, "w", encoding="utf-8") as fh:
    fh.write(str(server.server_address[1]))
server.serve_forever()
