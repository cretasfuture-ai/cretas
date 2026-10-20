"""精确匹配表的归一化 (整句相等, 不是子串): 三档。

N1 去标点空白、小写
N2 + 参数抽象: 已知实体名 -> <物料>/<产品>/<工序>/...; 数量+单位 -> <量>; 单号/批次号 -> <号>; 日期词 -> <日期>
N3 + 去口头禅 (那个/嗯/就是/哎/啊/呃/哈/吧/呢/了 等仅作语气的词)
⚠️ N2/N3 依旧是**整句相等**, 不放宽成「包含」—— 与「子串命中从不授权」的规矩一致。
"""
from __future__ import annotations

import json
import re
from functools import lru_cache

from bailian import OUT

PUNCT = re.compile(r"[\s，。！？、,.!?~…：:；;\"'“”‘’（）()【】\[\]…—\-]+")
NUM = r"(?:\d+(?:\.\d+)?|[零一二两三四五六七八九十百千万半]+)"
UNIT = r"(?:斤|公斤|千克|kg|g|克|吨|箱|袋|桶|件|包|个|盒|瓶|托|卷|板|只|块|份|条|根|筐|框|kg)"
QTY = re.compile(NUM + r"\s*" + UNIT, re.I)
ID = re.compile(r"(?:po|so|pc|b)-?\d{3,}|\d{1,2}号那批|B\d{6,}", re.I)
DATE = re.compile(r"明天|后天|下周[一二三四五六日天]|周[一二三四五六日天]|这周[一二三四五六日天]|\d{1,2}号")
FILLERS = ["那个", "这个", "就是", "嗯", "呃", "哎", "啊", "哈", "吧", "呢", "呀", "嘛", "咯", "啦", "一下"]


@lru_cache(maxsize=1)
def _entities() -> list[tuple[str, str]]:
    v = json.loads((OUT / "vocab.json").read_text(encoding="utf-8"))
    names = ["张伟", "王芳", "李强", "刘洋", "陈静", "杨磊", "赵敏", "黄涛", "周杰", "吴霞", "老王", "小李", "小刘", "老张"]
    pairs = []
    for key, tag in (("material", "<物料>"), ("product", "<产品>"), ("process", "<工序>"),
                     ("supplier", "<供应商>"), ("customer", "<客户>"), ("equipment", "<设备>")):
        pairs += [(x.lower(), tag) for x in v.get(key, []) if isinstance(x, str) and len(x) >= 2]
    pairs += [(x, "<人名>") for x in names] + [("盒马", "<客户>"), ("永辉", "<客户>"), ("食堂", "<客户>")]
    return sorted(set(pairs), key=lambda p: -len(p[0]))  # 长名优先


def n1(text: str) -> str:
    return PUNCT.sub("", text.strip().lower())


def n2(text: str) -> str:
    s = n1(text)
    s = ID.sub("<号>", s)
    s = QTY.sub("<量>", s)
    s = DATE.sub("<日期>", s)
    for name, tag in _entities():
        if name in s:
            s = s.replace(name, tag)
    return s


def n3(text: str) -> str:
    s = n2(text)
    for f in FILLERS:
        s = s.replace(f, "")
    s = re.sub(r"(.)\1{2,}", r"\1", s)  # 「到了到了到了」类重复的单字压缩
    return s or n2(text)


LEVELS = {"N1": n1, "N2": n2, "N3": n3}
