"""训练样本规则与外发脱敏共用的文本规则常量 —— 与 Java ``TrainingSampleRules`` 逐字对齐 (改这里同时改 Java)。

放在 common 里: smartbi.services.training_consent (训练样本) 与 common.llm_egress_text (外发脱敏) 共用一份,
外发脱敏不必为了几个常量去加载整个 smartbi.services 包。
"""
from __future__ import annotations

import re

_ID_CARD_RE = re.compile(r"(?<![0-9A-Za-z])\d{17}[\dXx](?![0-9A-Za-z])", re.ASCII)
_BANK_CARD_RE = re.compile(r"(?<![0-9A-Za-z])\d{16,19}(?![0-9A-Za-z])", re.ASCII)
_PHONE_RE = re.compile(r"(?<![0-9A-Za-z])1[3-9]\d{9}(?![0-9A-Za-z])", re.ASCII)


# Specific customer names and person names (Steve 2026-10-24): a Q&A sample that
# names one is not stored at all. Marked proxies tuned to over-block.
_HAN = "一-龥"
_ORG_SUFFIX = r"(?:股份有限公司|有限公司|公司|集团|超市|商贸|商行|批发部|门市部|连锁|酒店|饭店|食品厂)"
_ORG_NAME_RE = re.compile(rf"[{_HAN}A-Za-z0-9]{{2,12}}{_ORG_SUFFIX}")
_SURNAMES = (
    "王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘蒋蔡余杜叶程苏"
    "魏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦方白邹孟熊秦邱江尹薛闫段雷侯龙史陶黎贺顾毛郝龚邵"
    "钱严覃武戴莫孔汤"  # 于 / 向 / 付 / 万 left out: mostly function words / units (测 2026-10-24: "负责人于今日")
)
_PERSON_ROLE = (r"(?:联系人|负责人|经办人|业务员|销售员|司机|操作员|质检员|审核人|审批人|签收人|"
                r"收货人|制单人|姓名|员工|工人|班长|组长|主管|仓管员?)")
_PERSON_RE = re.compile(
    rf"{_PERSON_ROLE}\s*[:：|]?\s*[{_SURNAMES}][{_HAN}]{{1,2}}"                       # 操作员：王五
    rf"|(?<![{_HAN}])[{_SURNAMES}][{_HAN}]?(?:先生|女士|小姐|经理|总监|老板|师傅|厂长)"  # 李经理 / 王老板
    rf"|(?<![{_HAN}])[{_SURNAMES}]总(?![量额数计成产体部结和监经])"                      # 王总
    rf"|(?:联系|找|通知|叫|告诉)(?!方式|方法|方面|电话|人)[{_SURNAMES}][{_HAN}]{{1,2}}(?=[\s，,。；;、:：（(]|$|\d)",  # 联系张三
    re.ASCII,  # \s / \d 与 Java 一样只认 ASCII (全角空格、全角数字不算)
)
