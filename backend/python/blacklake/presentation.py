"""Readable display text only; never mutate facts, IDs, thresholds or AI cache inputs."""
import re
from decimal import Decimal


def readable_text(text):
    # Round standalone decimal amounts/ratios, never digits embedded in a code/name.
    text = re.sub(r'(?<![\w.])(-?\d+\.\d{3,})(?![\w.])',
                  lambda m: format(Decimal(m[0]), '.2f'), text)
    for old, new in (
        ('归集口径', '材料和工资有没有记全、记对'),
        ('核对归集', '核对成本记录'),
        ('已识别直接成本', '已记录的材料加人工成本'),
        ('同代码同单位', '相同产品编码、相同成品单位的'),
        ('候选信号，非已证实经营异常', '需要核对的提醒，还不能确定生产真的有问题'),
        ('复核阈值', '提醒标准'),
        ('上游成本校验未通过', '这张工单暂时还不能可靠地算单位成本'),
    ):
        text = text.replace(old, new)
    return text
