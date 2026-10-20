"""老师 (qwen3.8-max-0902) 走服务器上的 aliyun B 账号: 本地拼 payload -> scp -> 服务器执行 -> 拉回 -> 写本地缓存。

密钥不出服务器 (见 remote_exec_server.py)。缓存命名空间带 "@B-strict", 与本地账号/旧提示词的缓存分开。
系统提示用实测更好的严格写法 (首 token 是字母的概率 0.55 -> 0.74)。
"""
from __future__ import annotations

import hashlib
import json
import math
import random
import subprocess
from pathlib import Path

from bailian import OUT
from mine_eval import INSTR, ROLES, Chooser, Shortlist, intent_text

MODEL = "qwen3.8-max-0902"
NS = f"{MODEL}@B-strict"  # 缓存命名空间保持不变: B/C 同模型同提示词
HOST = "root@47.100.235.168"
RDIR = "/tmp/jev_remote"
PY = "/www/wwwroot/cretas/code/backend/python/venv-current/bin/python"
HERE = Path(__file__).resolve().parent
STRICT = "你是业务系统的请求分类器。只输出一个大写选项字母，不要换行、不要空格、不要任何其他字符。"
LETTERS = "ABCDEFGHIJKLMNOPQRST"


def _run_remote(jobs: list[dict], tag: str) -> list[dict]:
    if not jobs:
        return []
    fin = OUT / f"remote_{tag}_in.jsonl"
    fout = OUT / f"remote_{tag}_out.jsonl"
    with fin.open("w", encoding="utf-8") as f:
        for j in jobs:
            f.write(json.dumps(j, ensure_ascii=False) + "\n")
    ssh = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=20", HOST]
    subprocess.run(ssh + [f"mkdir -p {RDIR}"], check=True, stdin=subprocess.DEVNULL)
    subprocess.run(["scp", "-q", "-o", "BatchMode=yes", str(HERE / "remote_exec_server.py"), str(fin), f"{HOST}:{RDIR}/"], check=True)
    cmd = (f"cd {RDIR} && sed -i 's/^ACCOUNTS = .*/ACCOUNTS = [\"B\", \"C\"]/' remote_exec_server.py && "
           f"{PY} remote_exec_server.py {MODEL} {fin.name} {fout.name} > {tag}.log 2>&1; tail -1 {tag}.log")
    r = subprocess.run(ssh + [cmd], capture_output=True, text=True, stdin=subprocess.DEVNULL)
    if "done" not in r.stdout:
        raise RuntimeError(f"remote exec did not finish: {r.stdout[-300:]} {r.stderr[-300:]}")
    subprocess.run(["scp", "-q", "-o", "BatchMode=yes", f"{HOST}:{RDIR}/{fout.name}", str(fout)], check=True)
    return [json.loads(l) for l in fout.read_text(encoding="utf-8").splitlines() if l.strip()]


class RemoteShortlist(Shortlist):
    def __init__(self, intents: dict):
        super().__init__(NS, intents)

    def batch(self, items):
        jobs, keys = [], []
        for role, text in items:
            key = hashlib.sha1(f"{self.model}|{role}|{text}".encode()).hexdigest()
            if key in self.cache or key in keys:
                continue
            system = ("你是食品加工厂业务系统的意图粗筛器。下面是全部意图目录（代码｜名称）：\n" + self.catalog +
                      '\n\n只输出 JSON：{"candidates": ["代码1", "代码2", ...]}，最多 8 个，按可能性从高到低；都不像就输出空数组。')
            user = f"用户身份：食品加工厂{role}（{ROLES[role][1]}）\n用户说：{text}"
            jobs.append({"key": key, "payload": {"temperature": 0, "enable_thinking": False,
                                                  "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}]}})
            keys.append(key)
        for r in _run_remote(jobs, "short"):
            codes = None
            if r["content"]:
                try:
                    txt = r["content"].strip().strip("`").removeprefix("json").strip()
                    codes = [c for c in (json.loads(txt).get("candidates") or []) if c in self.codes][:8]
                except Exception:  # noqa: BLE001
                    codes = None
            self.cache[r["key"]] = codes
            with self.path.open("a", encoding="utf-8") as f:
                f.write(json.dumps({"key": r["key"], "codes": codes, "err": r.get("err")}, ensure_ascii=False) + "\n")
        return [self.get_cached(role, text) for role, text in items]

    def get_cached(self, role, text):
        return self.cache.get(hashlib.sha1(f"{self.model}|{role}|{text}".encode()).hexdigest())


class RemoteChooser(Chooser):
    def __init__(self, intents: dict):
        self.model, self.intents = NS, intents
        self.path = OUT / f"llm_cache_{NS}.jsonl"
        self.cache = {}
        if self.path.exists():
            for line in self.path.read_text(encoding="utf-8").splitlines():
                r = json.loads(line)
                self.cache[r["key"]] = r

    def _prep(self, role, text, cands):
        cs = cands[:]
        random.Random(text).shuffle(cs)
        key = hashlib.sha1(f"{self.model}|{role}|{text}|{','.join(cs)}".encode()).hexdigest()
        return cs, key

    def batch(self, items):
        jobs, meta = [], {}
        for role, text, cands in items:
            cs, key = self._prep(role, text, cands)
            if key in self.cache or key in meta:
                continue
            letters = LETTERS[: len(cs)]
            opts = "\n".join(f"{L}. {c}：{intent_text(self.intents[c])[:60]}" for L, c in zip(letters, cs))
            state = f"用户身份：食品加工厂{role}（{ROLES[role][1]}）\n用户说：{text}"
            user = f"{state}\n\n问题：{INSTR}\n选项：\n{opts}\n\n只输出一个选项字母。"
            jobs.append({"key": key, "payload": {"max_tokens": 1, "temperature": 0, "logprobs": True, "top_logprobs": 5,
                                                  "enable_thinking": False,
                                                  "messages": [{"role": "system", "content": STRICT}, {"role": "user", "content": user},
                                                               {"role": "assistant", "content": "答案：", "partial": True}]}})
            meta[key] = (letters, cs)
        for r in _run_remote(jobs, "choose"):
            letters, cs = meta[r["key"]]
            agg = {}
            for t in r.get("top_logprobs") or []:
                L = t["token"].strip().upper()
                if len(L) == 1 and L in letters:
                    agg[L] = agg.get(L, 0.0) + math.exp(t["logprob"])
            if agg:
                z = sum(agg.values())
                probs = {c: agg.get(L, 0.0) / z for L, c in zip(letters, cs)}
                best = max(probs, key=lambda c: probs[c])
                out = {"key": r["key"], "choice": best, "conf": probs[best], "err": None}
            else:
                out = {"key": r["key"], "choice": None, "conf": 0.0, "err": r.get("err") or "no letter in top-5"}
            self.cache[r["key"]] = out
            with self.path.open("a", encoding="utf-8") as f:
                f.write(json.dumps(out, ensure_ascii=False) + "\n")
        return [self.cache[self._prep(role, text, cands)[1]] for role, text, cands in items]
