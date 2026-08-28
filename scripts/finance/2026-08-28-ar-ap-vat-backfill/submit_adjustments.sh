#!/bin/bash
# ============================================================================
# AR/AP 价税口径存量补差 —— 提交 PENDING 调整 (需要第二人审批才生效)
# ============================================================================
#
# ⛔⛔⛔ 这个脚本默认什么都不会真的执行 —— 不带 --confirm-submit 只会打印将要
#        发送的请求 (dry-run)。这是有意设计, 不要改成默认执行。
#
# 前置条件 (三条全部满足才能带 --confirm-submit 跑):
#   1. PR #292 (fix(finance+rbac): 应收/应付挂账改用价税合计) 已合入 main 并部署到 prod。
#      核对方式: 部署后的下一笔发货/入库自动挂账金额应已是价税合计, 而不是本脚本
#      要修正的这种未税额。⛔ 没部署之前跑本脚本 = 用新口径纠正旧数据的同时,
#      系统还在用旧口径继续产生新的缺口, 边补边漏。
#   2. 已重新跑过同目录下 audit_current_shortfall.sql §8, 用**最新**的目标清单替换
#      下面 TARGETS 数组 —— 这份清单是 2026-08-28 当天的快照, 部署越晚, 期间新增的
#      发货/入库会产生更多缺口, 必须用重新查出来的清单, 不要照抄这份旧的。
#   3. 已让第二个人知晓 —— 提交的是 PENDING, 需要在 财务→调整审批 里用另一个账号
#      (与提交人不同, 4 眼原则) 批准才会真正改变客户/供应商余额。
#
# 通道: 只调用 ArApController 的 /adjustment 端点 (插入 PENDING 的
#       AR_ADJUSTMENT/AP_ADJUSTMENT, 不动余额), 全程不触碰数据库。
#       端点: POST /api/mobile/{factoryId}/finance/adjustment?counterpartyType=CUSTOMER|SUPPLIER
#       Body: {"counterpartyId": "...", "amount": <正数补差额>, "remark": "..."}
#
# ⛔ 排除在外、需要人工单独处理的 3 类 (理由见同目录 ../../../docs/finance/
#    2026-08-28-ar-ap-vat-shortfall-backfill-plan.md §4/§6):
#   - 3 条 AR (SO-20260820-0001 / SO-20260819-0002 / SO-20260828-0003) 已有对应的
#     SALES_RETURN, 且 return_orders 无 tax_amount 列 —— 直接补整单口径的税额差会
#     与"退货本身也少冲了税"叠加出错误结果 (SO-20260828-0003 是全额退货, 净差应
#     接近 0, 不是 +28.76)。
#   - 1 条 AP (AP-20260817-0702, ¥100.00) 的 source_id/purchase_order_id 都指向不
#     存在的记录 (孤儿行), 无法重算正确税额, 需要单独立案排查数据完整性问题,
#     不是税口径问题。
#
# 用法:
#   bash submit_adjustments.sh                     # dry-run, 只打印
#   bash submit_adjustments.sh --confirm-submit     # 真的提交 (仍是 PENDING, 不动余额)
#
# 环境变量:
#   CRETAS_API_BASE   默认 http://47.100.235.168:10010
#   CRETAS_AUTH_TOKEN 提交人的 Bearer token (必须, 未设置则拒绝执行)
# ============================================================================

set -euo pipefail

CONFIRM_SUBMIT=0
if [ "${1:-}" = "--confirm-submit" ]; then
    CONFIRM_SUBMIT=1
fi

API_BASE="${CRETAS_API_BASE:-http://47.100.235.168:10010}"

# ----------------------------------------------------------------------------
# 目标清单 —— 2026-08-28 audit_current_shortfall.sql §8 的输出快照 (9 AR + 18 AP)
# 字段: factoryId|counterpartyType|counterpartyId|amount|remark
# ----------------------------------------------------------------------------
TARGETS=(
  "F006|CUSTOMER|1a99316b-d8f9-4059-9493-6041ae2a54d1|1300.13|VAT补差-AR-20260824-5001(SO-20260824-0003)"
  "F006|CUSTOMER|d00cffd5-25c0-4261-947a-ddeae8c939a1|201.33|VAT补差-AR-20260828-3725(SO-20260828-0006)"
  "F006|CUSTOMER|3e8cc819-0d5b-4e4c-b327-c1fe50fd4c9e|65.00|VAT补差-AR-20260819-8688(SO-20260819-0001)"
  "F006|CUSTOMER|d00cffd5-25c0-4261-947a-ddeae8c939a1|57.52|VAT补差-AR-20260828-3232(SO-20260828-0008)"
  "F006|CUSTOMER|d00cffd5-25c0-4261-947a-ddeae8c939a1|57.52|VAT补差-AR-20260828-9918(SO-20260828-0009)"
  "F006|CUSTOMER|d00cffd5-25c0-4261-947a-ddeae8c939a1|11.51|VAT补差-AR-20260828-7217(SO-20260828-0013)"
  "F006|CUSTOMER|3e8cc819-0d5b-4e4c-b327-c1fe50fd4c9e|5.75|VAT补差-AR-20260825-7914(SO-20260825-0003)"
  "F006|CUSTOMER|d00cffd5-25c0-4261-947a-ddeae8c939a1|5.75|VAT补差-AR-20260828-9821(SO-20260828-0012)"
  "F006|CUSTOMER|3e8cc819-0d5b-4e4c-b327-c1fe50fd4c9e|0.01|VAT补差-AR-20260825-4807(SO-20260825-0004)"
  "F006|SUPPLIER|844d8c69-ed89-4a7f-b850-1749456d0777|247497.75|VAT补差-AP-20260821-0946(RCV-20260821-9003)"
  "F006|SUPPLIER|844d8c69-ed89-4a7f-b850-1749456d0777|2070.00|VAT补差-AP-20260820-9164(RCV-20260820-7285)"
  "F006|SUPPLIER|844d8c69-ed89-4a7f-b850-1749456d0777|1800.00|VAT补差-AP-20260820-3286(RCV-20260820-2342)"
  "F006|SUPPLIER|844d8c69-ed89-4a7f-b850-1749456d0777|1800.00|VAT补差-AP-20260820-9170(RCV-20260820-8056)"
  "F006|SUPPLIER|844d8c69-ed89-4a7f-b850-1749456d0777|1575.00|VAT补差-AP-20260820-1718(RCV-20260820-1721)"
  "F006|SUPPLIER|844d8c69-ed89-4a7f-b850-1749456d0777|1575.00|VAT补差-AP-20260820-4845(RCV-20260820-3101)"
  "LIUSHANMEN|SUPPLIER|757236b4-72ca-452d-8302-da2a8fe4b500|937.80|VAT补差-AP-20260825-0153(RCV-20260825-8613)"
  "LIUSHANMEN|SUPPLIER|757236b4-72ca-452d-8302-da2a8fe4b500|842.20|VAT补差-AP-20260825-8856(RCV-20260825-7509)"
  "F006|SUPPLIER|844d8c69-ed89-4a7f-b850-1749456d0777|360.00|VAT补差-AP-20260820-9327(RCV-20260820-5960)"
  "F006|SUPPLIER|844d8c69-ed89-4a7f-b850-1749456d0777|165.14|VAT补差-AP-20260828-8052(RCV-20260828-1480)"
  "F006|SUPPLIER|770815f3-942e-4419-b7f1-875e9858ea3b|141.70|VAT补差-AP-20260817-8585(RCV-20260817-6060)"
  "F006|SUPPLIER|770815f3-942e-4419-b7f1-875e9858ea3b|140.53|VAT补差-AP-20260817-7802(RCV-20260817-0066)"
  "F006|SUPPLIER|770815f3-942e-4419-b7f1-875e9858ea3b|43.93|VAT补差-AP-20260819-0166(RCV-20260819-2264)"
  "F006|SUPPLIER|844d8c69-ed89-4a7f-b850-1749456d0777|22.50|VAT补差-AP-20260820-5723(RCV-20260820-9777)"
  "F006|SUPPLIER|770815f3-942e-4419-b7f1-875e9858ea3b|1.79|VAT补差-AP-20260820-1701(RCV-20260820-9967)"
  "F006|SUPPLIER|844d8c69-ed89-4a7f-b850-1749456d0777|0.16|VAT补差-AP-20260824-7380(RCV-20260824-5243)"
  "F006|SUPPLIER|844d8c69-ed89-4a7f-b850-1749456d0777|0.11|VAT补差-AP-20260823-5830(RCV-20260823-4722)"
  "F006|SUPPLIER|770815f3-942e-4419-b7f1-875e9858ea3b|0.07|VAT补差-AP-20260818-8499(RCV-20260818-0925)"
)

echo "=== AR/AP VAT 补差提交脚本 ==="
echo "模式: $([ "$CONFIRM_SUBMIT" = 1 ] && echo '真的提交 (PENDING, 需人工审批)' || echo 'DRY-RUN (只打印)')"
echo "目标条数: ${#TARGETS[@]} (9 AR + 18 AP, 已排除 3 条退货重叠 AR + 1 条孤儿 AP)"
echo ""

if [ "$CONFIRM_SUBMIT" = 1 ] && [ -z "${CRETAS_AUTH_TOKEN:-}" ]; then
    echo "拒绝执行: 未设置 CRETAS_AUTH_TOKEN (提交人的登录 token)" >&2
    exit 1
fi

total=0
for row in "${TARGETS[@]}"; do
    IFS='|' read -r factoryId counterpartyType counterpartyId amount remark <<< "$row"
    url="${API_BASE}/api/mobile/${factoryId}/finance/adjustment?counterpartyType=${counterpartyType}"
    body=$(printf '{"counterpartyId":"%s","amount":%s,"remark":"%s"}' "$counterpartyId" "$amount" "$remark")

    if [ "$CONFIRM_SUBMIT" = 1 ]; then
        echo "POST $url"
        curl -sS -X POST "$url" \
            -H "Authorization: Bearer ${CRETAS_AUTH_TOKEN}" \
            -H "Content-Type: application/json" \
            -d "$body"
        echo ""
    else
        echo "[dry-run] POST $url"
        echo "[dry-run]   body: $body"
    fi
    total=$(echo "$total + $amount" | bc)
done

echo ""
echo "合计补差金额: $total"
echo ""
echo "下一步: 让另一个账号 (finance:approve_adjustment 权限, 且不同于提交人) 到"
echo "  财务 → 调整审批 里逐条核对后批准 —— 或调用"
echo "  POST /api/mobile/{factoryId}/finance/adjustments/pending 先查看队列。"
