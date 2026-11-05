"""Historical retired business identities, kept as a denial boundary.

MOCK_REST and DEMO_REST are the restaurant identities also documented by the
existing training-consent exclusion. Test and backup factory exclusions are
deliberately independent from business retirement.
"""

_RETIRED_IDS = frozenset({
    "MOCK_REST", "DEMO_REST",
    "R_DONGMENKOU_REAL", "R_HONGDEJI_REAL", "R_HUOGUO_GENERIC_REAL",
    "R_ILTEATRO_REAL", "R_JINCHUAN_HG_REAL", "R_JINRINIUSHI_REAL",
    "R_LINJIAYAN_REAL", "R_QINGHUAJIAO_REAL", "R_SHANGMA_HG_REAL",
    "R_XIMAXIANG_REAL", "R_XINBASHU_REAL", "R_YONGHE_REAL",
    "R_YOUZIYOUWEI_REAL", "R_YUJIUJING_REAL",
})
_RETIRED_PREFIXES = ("RES_", "REST_", "DEMO_REST_", "RESTAURANT_", "R_GML", "QHJ_")


def is_retired_tenant(factory_id: str) -> bool:
    normalized = str(factory_id or "").strip().upper()
    return normalized in _RETIRED_IDS or normalized.startswith(_RETIRED_PREFIXES)
