"""建厂对话第二批厂规开关: 问了才写阈值, 没问到/按系统默认不出现; 题面与选项原文和 Java 访谈一致。"""
import re
from pathlib import Path

from factory_assembly import generation as g

JAVA = Path(__file__).resolve().parents[2] / "java/cretas-api/src/main/java/com/cretas/aims/blueprint/interview/FactoryBuildInterviewService.java"


def _items(d):
    items, skipped = g._factory_switch_thresholds(d)
    return {t["key"]: t["value"] for t in items}, skipped


def test_every_asked_switch_becomes_its_threshold_value():
    d = ("按袋、盒下的计划能不能核对产线产能：按产品净含量折成公斤核对\n"
         "缺料补齐后缺料处理怎么收尾：补齐后系统自动关闭并留痕\n"
         "质检表里次要项不合格怎么判：只有关键项不合格整张才算不合格\n"
         "财务批准后的销售订单还能不能取消：能取消，要写原因\n"
         "成品入库实收比报工多怎么办：可以多收，但要写原因\n"
         "插单要不要一律审批：一律审批\n"
         "客供料收货能不能多收：可以多收，按多收规则处理\n")
    got, skipped = _items(d)
    assert got == {
        "production.capacity.package_unit_mode": "NET_CONTENT",
        "shortage.case.close_mode": "AUTO",
        "quality.inspection.fail_scope": "CRITICAL_ONLY",
        "sales.order.cancel_after_approval": "1",
        "production.receipt.over_receive_mode": "ALWAYS_WITH_REASON",
        "production.urgent_insert.always_approval": "1",
        "inventory.customer_supplied.allow_over_receive": "1",
    }
    assert skipped == []


def test_negative_choices_are_written_as_explicit_values():
    got, _ = _items("财务批准后的销售订单还能不能取消：不能取消，只能走驳回或退款\n"
                    "插单要不要一律审批：只有影响大的才审批\n"
                    "客供料收货能不能多收：不能多收\n")
    assert got == {"sales.order.cancel_after_approval": "0",
                   "production.urgent_insert.always_approval": "0",
                   "inventory.customer_supplied.allow_over_receive": "0"}


def test_default_or_unasked_answers_write_nothing():
    assert _items("") == ({}, [])
    d = "".join(f"{label}：按系统默认\n" for label in (
        "按袋、盒下的计划能不能核对产线产能", "缺料补齐后缺料处理怎么收尾", "质检表里次要项不合格怎么判",
        "财务批准后的销售订单还能不能取消", "成品入库实收比报工多怎么办", "插单要不要一律审批",
        "客供料收货能不能多收"))
    assert _items(d) == ({}, [])
    # 认不出的说法不猜
    assert _items("财务批准后的销售订单还能不能取消：差不多吧") == ({}, [])


def test_over_receive_rate_only_with_the_rate_option_and_as_a_fraction():
    got, skipped = _items("成品入库实收比报工多怎么办：多收不超过一定比例可以收\n允许多收的比例：5%\n")
    assert got == {"production.receipt.over_receive_mode": "WITHIN_RATE",
                   "production.receipt.over_receive_rate": "0.05"}
    assert skipped == []
    got, _ = _items("成品入库实收比报工多怎么办：多收不超过一定比例可以收\n允许多收的比例：12.5%\n")
    assert got["production.receipt.over_receive_rate"] == "0.125"


def test_over_receive_rate_that_cannot_land_is_reported_not_guessed():
    # 没选按比例那一项 -> 不写比例, 如实报告
    got, skipped = _items("成品入库实收比报工多怎么办：不允许多收\n允许多收的比例：5%\n")
    assert got == {"production.receipt.over_receive_mode": "BLOCK"}
    assert skipped == ["允许多收的比例:5%"]
    for ans in ("150%", "0%", "5%到10%", "差不多"):
        got, skipped = _items("成品入库实收比报工多怎么办：多收不超过一定比例可以收\n允许多收的比例：" + ans)
        assert "production.receipt.over_receive_rate" not in got, ans
        assert skipped == ["允许多收的比例:" + ans], ans
    # 没说 / 说没有 = 不报告
    assert _items("成品入库实收比报工多怎么办：多收不超过一定比例可以收\n允许多收的比例：没有\n")[1] == []


def test_labels_and_option_texts_match_the_java_interview():
    """python 按题面 label 和选项原文解析; Java 改了任何一边而这边没跟, 答案就会静默落空。"""
    java = JAVA.read_text(encoding="utf-8")
    for pat, key, options in g._FACTORY_SWITCHES:
        label = pat.pattern.split("[：:]")[0]
        assert f'"{label}"' in java, label
        for prefix, _value in options:
            assert f'"{prefix}"' in java, (label, prefix)
    assert '"允许多收的比例"' in java
    assert re.search(r'"按系统默认"', java)
