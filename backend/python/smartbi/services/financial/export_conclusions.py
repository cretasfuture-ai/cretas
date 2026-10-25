"""The visible rule conclusions, shared by Excel, PPT and PDF exports."""
from typing import Any, Dict, List, Optional


def conclusion_rows(headline: Optional[Dict[str, Any]],
                    findings: Optional[Dict[str, List[str]]]) -> List[List[str]]:
    rows: List[List[str]] = []
    text = (headline or {}).get("text")
    if text and str(text).strip():
        rows.append(["页首结论", str(text)])
    for key, title in (("good", "做得好的"), ("attention", "需要关注"), ("suggestions", "建议")):
        for line in (findings or {}).get(key) or []:
            if str(line).strip():
                rows.append([title, str(line)])
    return rows
