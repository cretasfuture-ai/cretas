#!/usr/bin/env python3
"""电子秤模拟器 (纯标准库, 不进生产包)。

三件事:
  1. dump-fixtures  生成/校验 fixtures/frames.json (后端 Java 测试读它, 单一事实来源)
  2. serve          起一个 TCP 服务, 按协议帧格式持续发读数 (可断线重连 / 发坏帧)
  3. list           列出协议与场景

帧格式来源: backend/java/cretas-api/.../db/migration-pg-converted/V2026_01_04_11__scale_initial_data.sql
(柯力 D2008 / 耀华 XK3190 样例 / 通用 Modbus RTU)。这是「系统当前声称支持的格式」,
不是真秤抓包: 真秤的帧是否一致只能现场验, 见 docs/hardware/scale-field-bringup-checklist.md。
"""
from __future__ import annotations

import argparse
import json
import socket
import struct
import sys
import threading
import time
from pathlib import Path

FIXTURE_PATH = Path(__file__).parent / "fixtures" / "frames.json"
CRLF = b"\r\n"


# ---------------------------------------------------------------- 编码器
def keli_frame(kg: float, stable: bool = True, unit: str = "kg") -> bytes:
    """柯力 D2008: ±XXXXXX<sp>un<sp>S/U\\r\\n  (6 位整数, 1 位隐含小数)。"""
    raw = round(abs(kg) * 10)
    if raw > 999999:
        raise ValueError("keli: 超出 6 位显示, 请用 keli_overrange()")
    sign = "-" if kg < 0 else "+"
    return f"{sign}{raw:06d} {unit:<2} {'S' if stable else 'U'}".encode("ascii") + CRLF


def keli_overrange() -> bytes:
    # 代表性超量程帧: 重量域被字母占满。真秤的超量程记号需厂家确认 (清单 §6)。
    return b"+OOOOOO kg U" + CRLF


def yaohua_frame(kg: float, stable: bool = True, unit: str = "kg") -> bytes:
    """耀华 XK3190 种子样例: =±XXXXX.XX<sp>kg<sp>S/M\\r\\n (小数点显式, 2 位小数)。"""
    sign = "-" if kg < 0 else "+"
    return f"={sign}{abs(kg):08.2f} {unit:<2} {'S' if stable else 'M'}".encode("ascii") + CRLF


def crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def modbus_frame(kg: float, slave: int = 1, scale: int = 100) -> bytes:
    """通用 Modbus RTU 03 应答: [slave][03][04][int32 大端 = kg*100][CRC 低][CRC 高]。
    与后端实时解析器一致 (整数 / 100); 种子声明的 FLOAT32_ABCD 与实现不符, 见现状文档。"""
    body = bytes([slave, 0x03, 0x04]) + struct.pack(">i", round(kg * scale))
    return body + struct.pack("<H", crc16_modbus(body))


def with_bad_crc(frame: bytes) -> bytes:
    return frame[:-1] + bytes([frame[-1] ^ 0xFF])


# ---------------------------------------------------------------- 场景
# expect.ok=False 的帧必须被拒绝, 绝不能读成 0。
def scenarios() -> list[dict]:
    S: list[dict] = []

    def add(protocol, name, frame, ok, weight=None, unit=None, stable=None, reject=None, note=""):
        S.append({
            "protocol": protocol, "scenario": name, "hex": frame.hex().upper(),
            "ascii": frame.decode("ascii", "replace") if protocol != "MODBUS_RTU_STANDARD" else "",
            "expect": {"ok": ok, "weight": weight, "unit": unit, "stable": stable, "reject": reject},
            "note": note,
        })

    K = "KELI_D2008_ASCII"
    add(K, "stable", keli_frame(124.0), True, "124.0", "kg", True)
    add(K, "unstable", keli_frame(124.5, stable=False), True, "124.5", "kg", False,
        note="不稳定读数能解析, 但报工不得采用 (见 readScaleWeight)")
    add(K, "zero", keli_frame(0.0), True, "0.0", "kg", True, note="真实的 0 与「没读到」必须可区分")
    add(K, "negative_after_tare", keli_frame(-52.0), True, "-52.0", "kg", True,
        note="去皮后放回少量 / 皮重大于毛重 → 负数")
    add(K, "max_display", keli_frame(99999.9), True, "99999.9", "kg", True)
    add(K, "grams_unit", keli_frame(1500.0, unit="g"), True, "1500.0", "g", True,
        note="单位必须带到下游, 下游按 g→kg 换算 (缺口 21)")
    add(K, "overrange", keli_overrange(), False, reject="OVERRANGE")
    add(K, "truncated", keli_frame(124.0)[:6], False, reject="FRAME")
    add(K, "no_terminator", keli_frame(124.0)[:-2], False, reject="FRAME",
        note="断流时只收到半帧 (含完整字段但没有 CRLF)")
    add(K, "letter_in_digits", b"+00l240 kg S" + CRLF, False, reject="WEIGHT")
    add(K, "blank_weight", b"+       kg S" + CRLF, False, reject="WEIGHT")
    add(K, "bad_sign", b"*001240 kg S" + CRLF, False, reject="SIGN")
    add(K, "unknown_stable_flag", b"+001240 kg X" + CRLF, False, reject="STABLE",
        note="未知稳定标志不得当「不稳定」悄悄放行, 也不得当稳定")
    add(K, "binary_garbage", bytes([0xFF, 0xFE, 0x00, 0x80, 0x1B, 0x5B, 0x41]), False, reject="FRAME")
    add(K, "exponent_notation", b"+1E+003 kg S" + CRLF, False, reject="WEIGHT",
        note="BigDecimal 会把 1E+003 当成数, 严格解析必须拒绝")

    Y = "YAOHUA_XK3190_ASCII"
    add(Y, "stable", yaohua_frame(125.40), True, "125.40", "kg", True)
    add(Y, "motion", yaohua_frame(125.42, stable=False), True, "125.42", "kg", False)
    add(Y, "negative", yaohua_frame(-3.5), True, "-3.50", "kg", True)
    add(Y, "missing_start_marker", yaohua_frame(125.40)[1:], False, reject="FRAME")
    add(Y, "truncated", yaohua_frame(125.40)[:7], False, reject="FRAME")
    add(Y, "letter_in_digits", b"=+00l25.40 kg S" + CRLF, False, reject="WEIGHT")

    M = "MODBUS_RTU_STANDARD"
    add(M, "normal", modbus_frame(12.34), True, "12.34", "kg", True)
    add(M, "zero", modbus_frame(0.0), True, "0.00", "kg", True)
    add(M, "negative", modbus_frame(-5.2), True, "-5.20", "kg", True,
        note="int32 有符号: 不得读成 4294966776 / 100")
    add(M, "bad_crc", with_bad_crc(modbus_frame(12.34)), False, reject="CRC")
    add(M, "truncated", modbus_frame(12.34)[:6], False, reject="FRAME")
    add(M, "wrong_function", bytes([1, 0x04]) + modbus_frame(12.34)[2:], False, reject="FRAME",
        note="功能码被改但 CRC 不再匹配, 同样必须拒绝")
    exc = bytes([1, 0x83, 0x02])
    add(M, "exception_response", exc + struct.pack("<H", crc16_modbus(exc)), False, reject="FRAME",
        note="从站异常应答 (非法数据地址)")
    return S


def dump_fixtures(out: Path, check: bool) -> int:
    text = json.dumps(scenarios(), ensure_ascii=False, indent=2) + "\n"
    if check:
        if not out.exists() or out.read_text(encoding="utf-8") != text:
            print(f"DRIFT: {out} 与模拟器当前输出不一致, 请运行 dump-fixtures 重新生成", file=sys.stderr)
            return 1
        print(f"OK: {out} 与模拟器一致 ({len(scenarios())} 个场景)")
        return 0
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(f"wrote {out} ({len(scenarios())} 个场景)")
    return 0


# ---------------------------------------------------------------- TCP 服务
def stream_frames(protocol: str, scenario: str):
    """返回 (frame_bytes, 间隔秒) 的迭代器 (无限循环)。"""
    if scenario == "weigh_cycle":
        # 放上货物: 抖动 → 稳定 → 保持 → 取走回零
        ramp = [30.0, 90.0, 121.0, 123.5, 124.3, 124.0]
        while True:
            for w in ramp:
                yield _encode(protocol, w, stable=False), 0.2
            for _ in range(8):
                yield _encode(protocol, 124.0, stable=True), 0.2
            yield _encode(protocol, 0.0, stable=True), 0.2
    elif scenario == "corrupt_mix":
        good = _encode(protocol, 124.0, stable=True)
        bad = [good[:5], with_bad_crc(good) if protocol == "MODBUS_RTU_STANDARD" else b"+00l240 kg S" + CRLF,
               bytes([0xFF, 0xFE, 0x80])]
        while True:
            yield good, 0.2
            for b in bad:
                yield b, 0.2
    else:  # steady
        while True:
            yield _encode(protocol, 124.0, stable=True), 0.5


def _encode(protocol: str, kg: float, stable: bool) -> bytes:
    if protocol == "KELI_D2008_ASCII":
        return keli_frame(kg, stable)
    if protocol == "YAOHUA_XK3190_ASCII":
        return yaohua_frame(kg, stable)
    if protocol == "MODBUS_RTU_STANDARD":
        return modbus_frame(kg)
    raise SystemExit(f"未知协议 {protocol}")


def serve(protocol: str, host: str, port: int, scenario: str, drop_after: int) -> None:
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((host, port))
    srv.listen(4)
    print(f"[sim] {protocol}/{scenario} 监听 {host}:{port}  drop_after={drop_after or '不断线'}", flush=True)

    def handle(conn: socket.socket, addr) -> None:
        print(f"[sim] 连接 {addr}", flush=True)
        sent = 0
        try:
            for frame, delay in stream_frames(protocol, scenario):
                conn.sendall(frame)
                sent += 1
                if drop_after and sent >= drop_after:
                    print(f"[sim] 发完 {sent} 帧, 主动断线 (模拟断线重连)", flush=True)
                    return
                time.sleep(delay)
        except OSError:
            pass
        finally:
            conn.close()

    while True:
        c, a = srv.accept()
        threading.Thread(target=handle, args=(c, a), daemon=True).start()


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("dump-fixtures")
    d.add_argument("--out", type=Path, default=FIXTURE_PATH)
    d.add_argument("--check", action="store_true", help="只校验不写文件 (漂移则退出码 1)")
    s = sub.add_parser("serve")
    s.add_argument("--protocol", default="KELI_D2008_ASCII",
                   choices=["KELI_D2008_ASCII", "YAOHUA_XK3190_ASCII", "MODBUS_RTU_STANDARD"])
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=9100)
    s.add_argument("--scenario", default="weigh_cycle", choices=["weigh_cycle", "corrupt_mix", "steady"])
    s.add_argument("--drop-after", type=int, default=0, help="发 N 帧后断开连接, 0=不断")
    sub.add_parser("list")
    a = p.parse_args()
    if a.cmd == "dump-fixtures":
        return dump_fixtures(a.out, a.check)
    if a.cmd == "serve":
        serve(a.protocol, a.host, a.port, a.scenario, a.drop_after)
        return 0
    for sc in scenarios():
        print(f"{sc['protocol']:<22} {sc['scenario']:<22} ok={sc['expect']['ok']}  {sc['hex']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
