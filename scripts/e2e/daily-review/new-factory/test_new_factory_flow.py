from pathlib import Path
import ast
import importlib.util
import json
import pytest


ROOT = Path(__file__).parent
SOURCE = (ROOT / "new_factory_flow.py").read_text(encoding="utf-8")


def load_flow_module():
    spec = importlib.util.spec_from_file_location("p14_new_factory_flow_test", ROOT / "new_factory_flow.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_python_source_is_valid_and_no_metric_configurator_or_fixture_factory():
    ast.parse(SOURCE)
    assert "configure_metrics" not in SOURCE
    assert "VISION-ENTRY-A" in SOURCE  # guard rejects the old fixture identity
    assert "10064" in SOURCE


def test_runtime_contract_has_p14_ports_and_forbids_p10_clock_or_maven():
    runtime = (ROOT / "runtime.py").read_text(encoding="utf-8")
    for port in ("10064", "18096", "31764", "18394"):
        assert port in runtime
    assert "p10" in runtime.lower()
    assert "clockAgent" in runtime and "maven" in runtime


def test_flow_contract_has_receipt_object_guard_costs_and_valid_default_purposes():
    for purpose in ("LOGISTICS_DEFAULT", "WORKSHOP_DEFAULT", "PURCHASE_INBOUND_DEFAULT",
                    "SALES_OUTBOUND_DEFAULT", "PRODUCTION_RAW_DEFAULT"):
        assert purpose in SOURCE
    assert "target.port != 18394" in SOURCE
    assert "finish_costs" in SOURCE
    assert "upload-url" in SOURCE and '"/attachments"' in SOURCE
    assert '"FINISHED_DEFAULT"' not in SOURCE


def test_platform_bootstrap_is_preparation_only_and_uses_real_platform_routes():
    bootstrap = (ROOT / "bootstrap_platform.py").read_text(encoding="utf-8")
    ast.parse(bootstrap)
    assert "/api/platform/factories" in bootstrap
    assert "/api/mobile/auth/reset-password" in bootstrap
    assert "/api/internal/field-visibility/" in bootstrap
    assert "receipt_path.exists()" in bootstrap
    assert "does not match requested factory" in bootstrap
    assert "/api/mobile/" not in bootstrap.replace("/api/mobile/auth/", "")
    assert "platform-bootstrap.json" in bootstrap
    assert "businessWrites" in bootstrap


def test_flow_rejects_non_owned_host_before_reading_credentials(tmp_path):
    module = load_flow_module()
    credentials = tmp_path / "credentials.json"
    credentials.write_text(json.dumps({"password": "private"}), encoding="utf-8")
    with pytest.raises(ValueError, match="10064"):
        module.NewFactoryFlow("http://127.0.0.1:10060", credentials, tmp_path / "out", "P14_X", "p14_admin", tmp_path / "x.png")


def test_flow_rejects_fixture_factory_before_network(tmp_path):
    module = load_flow_module()
    credentials = tmp_path / "credentials.json"
    credentials.write_text(json.dumps({"password": "private"}), encoding="utf-8")
    with pytest.raises(ValueError, match="newly-created"):
        module.NewFactoryFlow("http://127.0.0.1:10064", credentials, tmp_path / "out", "VISION-ENTRY-A", "p14_admin", tmp_path / "x.png")


def test_flow_rejects_cross_factory_login_with_mocked_session(tmp_path, monkeypatch):
    module = load_flow_module()
    credentials = tmp_path / "credentials.json"
    credentials.write_text(json.dumps({"password": "private"}), encoding="utf-8")

    class Response:
        def raise_for_status(self):
            return None

        def json(self):
            return {"success": True, "data": {"factoryId": "OTHER_FACTORY", "username": "p14_admin", "role": "factory_super_admin", "token": "redacted"}}

    class Session:
        headers = {}

        def post(self, *args, **kwargs):
            return Response()

    monkeypatch.setattr(module.requests, "Session", Session)
    with pytest.raises(ValueError, match="factory mismatch"):
        module.NewFactoryFlow("http://127.0.0.1:10064", credentials, tmp_path / "out", "P14_X", "p14_admin", tmp_path / "x.png")
