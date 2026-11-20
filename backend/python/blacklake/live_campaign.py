"""Sequential one-account PoC probes; all stdout is already schema/counts only."""
import json
import subprocess
import sys


def main():
    cases = [
        ("project_second_page", ["--page", "2"]),
        ("project_updated_only", ["--updated-only"]),
        ("task_updated_only", ["--resource", "task", "--updated-only"]),
        ("task_parent_scope", ["--resource", "task", "--updated-only", "--parent-project"]),
        ("output_updated_only", ["--resource", "output", "--updated-only"]),
        ("output_parent_scope", ["--resource", "output", "--updated-only", "--parent-project"]),
        ("stock_in", ["--resource", "stock_order", "--order-type", "10"]),
        ("stock_out", ["--resource", "stock_order", "--order-type", "20"]),
    ]
    failed = []
    for label, arguments in cases:
        result = subprocess.run([sys.executable, "-m", "blacklake.live_probe", *arguments],
                                capture_output=True, text=True, timeout=100)
        try:
            report = json.loads(result.stdout)
        except ValueError:
            report = {"success": False, "data": None, "message": "PROBE_PROCESS_FAILED"}
        print(json.dumps({"case": label, "exit_code": result.returncode, "report": report}), flush=True)
        if result.returncode:
            failed.append(label)
    print(json.dumps({"failed_or_ineligible_cases": failed}), flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
