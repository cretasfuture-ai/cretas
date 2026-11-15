"""出题/打分用的凭证加载: 只进进程内存, 不打印、不落盘、不进 git。

- 千问 (阿里云 DashScope 兼容接口): 经 ssh 读服务器 .env.prod 的 LLM_ALIYUN_{A,B}_API_KEY / _BASE_URL (与 ai_live_probe 读服务器凭证同一做法)
- OpenAI: 本机 ~/.codex/secrets/cretas-openai-eval.env (评测专用 key; ⛔ 不用线上 Luna 的 cretas-openai-prod.env)
"""
import os
import pathlib
import subprocess

SERVER = os.environ.get("INTENT_ACC_SERVER", "root@47.100.235.168")
ENV_PROD = "/www/wwwroot/cretas/.env.prod"
DEFAULT_ALIYUN_BASE = "https://dashscope.aliyuncs.com/compatible-mode/v1"


def _parse(text):
    out = {}
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def aliyun(account="A"):
    names = [f"LLM_ALIYUN_{account}_API_KEY", f"LLM_ALIYUN_{account}_BASE_URL"]
    cmd = ["ssh", "-o", "BatchMode=yes", SERVER, "grep -E '^(%s)=' %s" % ("|".join(names), ENV_PROD)]
    r = subprocess.run(cmd, capture_output=True, timeout=60)
    if r.returncode != 0:
        raise RuntimeError("读服务器千问凭证失败 rc=%s" % r.returncode)
    env = _parse(r.stdout.decode("utf-8", "replace"))
    key = env.get(names[0])
    if not key:
        raise RuntimeError("服务器上没有 " + names[0])
    return key, env.get(names[1]) or DEFAULT_ALIYUN_BASE


def openai_eval():
    p = pathlib.Path.home() / ".codex/secrets/cretas-openai-eval.env"
    env = _parse(p.read_text("utf-8-sig"))
    if not env.get("OPENAI_API_KEY"):
        raise RuntimeError("OPENAI_API_KEY 未配置")
    return env["OPENAI_API_KEY"], env.get("OPENAI_PROJECT_ID") or None
