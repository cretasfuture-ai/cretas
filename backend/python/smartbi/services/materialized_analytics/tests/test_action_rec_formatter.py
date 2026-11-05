from smartbi.services.materialized_analytics.action_rec_formatter import format_action_rec, format_data_insufficient

def test_action_rec_preserves_four_fields():
    out = format_action_rec("工厂A", "产出提升 3-5%", "复核生产节拍", "本月内")
    assert all(x in out for x in ("工厂A", "产出提升 3-5%", "复核生产节拍", "本月内", "建议:", "前置:"))

def test_action_rec_keeps_object_braces_for_readable_factory_advice():
    assert "【生产线A】" in format_action_rec("生产线A", "成本下降 10%", "复核物料定额", "下季度")

def test_data_insufficient_keeps_requested_next_action():
    assert "数据不足" in format_data_insufficient("补充产能数据", "联系生产组")

def test_data_insufficient_has_honest_default_next_action():
    assert "补充数据来源" in format_data_insufficient("补充产能数据")
