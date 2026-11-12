#!/usr/bin/env python3
"""第二条出境出口的 SOCKS5 服务 (2026-11-12, INTENT-DISTILL-20261111 Luna 出口线)。

跑在美国 Mac mini (aurora) 上, 只监听 127.0.0.1:17897; 由 egress-tunnel-us.sh 的 SSH 反向隧道
静态转发成 47 上的 127.0.0.1:11439。aurora 在美国, 直连 OpenAI 不被拒 ⇒ 这条出口**不经过加拿大 mini、
不经过 Clash**, 与主出口 (11438 → 加拿大 mini Clash 7897) 没有共同的单点 (除了 47 自己)。

为什么不直接 `ssh -R 11439` 动态转发: 动态 SOCKS 不能限制目的地, 47 上任何本机进程都能借它打到
aurora 的 127.0.0.1 / 局域网服务。这里只放行白名单 host:443, 其它一律回 0x02 (规则不允许)。

只用标准库 (macOS 自带 python3), 无认证 (监听只在回环口, 进来的只有 SSH 隧道)。
"""
import asyncio
import os
import socket
import struct
import sys
import time

LISTEN = os.environ.get("EGRESS_SOCKS_LISTEN", "127.0.0.1")
PORT = int(os.environ.get("EGRESS_SOCKS_PORT", "17897"))
ALLOW = {h.strip().lower() for h in os.environ.get(
    "EGRESS_SOCKS_ALLOW", "api.openai.com:443").split(",") if h.strip()}
TIMEOUT = 10.0


def log(msg: str) -> None:
    sys.stderr.write(f"{time.strftime('%Y-%m-%dT%H:%M:%S')} {msg}\n")
    sys.stderr.flush()


async def pipe(r: asyncio.StreamReader, w: asyncio.StreamWriter) -> None:
    try:
        while True:
            data = await r.read(65536)
            if not data:
                break
            w.write(data)
            await w.drain()
    except Exception:
        pass
    finally:
        try:
            w.close()
        except Exception:
            pass


def reply(code: int) -> bytes:
    return b"\x05" + bytes([code]) + b"\x00\x01" + b"\x00\x00\x00\x00" + b"\x00\x00"


async def handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        ver, n = await asyncio.wait_for(reader.readexactly(2), TIMEOUT)
        methods = await reader.readexactly(n)
        if ver != 5 or 0 not in methods:
            writer.write(b"\x05\xff")
            await writer.drain()
            return
        writer.write(b"\x05\x00")
        await writer.drain()
        try:
            head = await asyncio.wait_for(reader.readexactly(4), TIMEOUT)
        except asyncio.IncompleteReadError:
            return  # 健康探针只做握手就断开: 正常
        _ver, cmd, _rsv, atyp = head
        if atyp == 3:
            ln = (await reader.readexactly(1))[0]
            host = (await reader.readexactly(ln)).decode("idna").lower()
        elif atyp == 1:
            host = socket.inet_ntoa(await reader.readexactly(4))
        elif atyp == 4:
            host = socket.inet_ntop(socket.AF_INET6, await reader.readexactly(16))
        else:
            writer.write(reply(8))
            await writer.drain()
            return
        port = struct.unpack("!H", await reader.readexactly(2))[0]
        target = f"{host}:{port}"
        if cmd != 1 or target not in ALLOW:
            log(f"DENY {target} cmd={cmd}")
            writer.write(reply(2))
            await writer.drain()
            return
        try:
            ur, uw = await asyncio.wait_for(asyncio.open_connection(host, port), TIMEOUT)
        except Exception as ex:
            log(f"UPSTREAM_FAIL {target} {type(ex).__name__}")
            writer.write(reply(5))
            await writer.drain()
            return
        writer.write(reply(0))
        await writer.drain()
        await asyncio.gather(pipe(reader, uw), pipe(ur, writer))
    except Exception:
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


async def main() -> None:
    server = await asyncio.start_server(handle, LISTEN, PORT)
    log(f"listening {LISTEN}:{PORT} allow={sorted(ALLOW)}")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
