"""
食品知识库反馈收集 API
Food Knowledge Base Feedback Collection API.

Endpoints:
  POST /api/food-kb/feedback           - Submit explicit feedback
  GET  /api/food-kb/feedback/stats     - Feedback statistics
  POST /api/food-kb/feedback/log-query - Log query for implicit feedback detection
  GET  /api/food-kb/feedback/export    - Export feedback data as JSON
"""

import json
import logging
import os
from datetime import datetime, timedelta, timezone
from typing import Optional, List

import asyncpg
from fastapi import APIRouter, HTTPException, Query, Request
from pydantic import BaseModel, Field

logger = logging.getLogger(__name__)
router = APIRouter()


# ============================================================
# Tenant Scoping
# ============================================================
#
# 2026-08-25: 这四个端点原来一条租户过滤都没有 —— `/feedback/stats` 把所有租户
# 评分 <= 2 的**原话与 AI 回答**印在管理页上, `/feedback/export` 一次导出全平台
# 1000 行, `/feedback/log-query` 只按调用方自己传的 session_id 反查历史问句。
# 而 `/api/food-kb/feedback*` 不在 auth_middleware.PUBLIC_PREFIXES 里但 **在**
# DEMO_WRITE_ALLOW_SUFFIXES 里, 且两个读端点是 GET —— 演示租户 JWT (公开
# /mobile-ai/rest/ 页面发给任意匿名访客) 完全够得着。
#
# 修法照抄本仓既有范式 (smartbi/api/restaurant_feedback.py:44-77,
# smartbi/api/llm_fallback_admin.py:114-146):
#   ① 租户只从 JWT 取 (auth_middleware 注入 request.state.factory_id),
#      ⛔ 不接受 body / query 里调用方自己传的租户;
#   ② 取不到就 401 fail-closed, ⛔ 不放行成"查全表";
#   ③ 每条 SQL 显式 `WHERE factory_id = $n`, RLS GUC 只作纵深防御。


# 代码先于 migration 上线时的显式失败文案 —— ⛔ 不降级成"查全表",
# 宁可整个端点报错 (缺列 = 没有租户边界, 那就一行都不能给)。
_TENANT_MIGRATION_HINT = (
    "food_kb feedback tables are missing the factory_id column. "
    "Apply backend/python/food_kb/database/migrations/"
    "V20260825_01_food_kb_feedback_tenant_isolation.sql first."
)


def _require_factory_id(request: Request) -> str:
    """从 JWT 注入的 request.state 取租户; 取不到直接 401 (fail-closed)。"""
    factory_id = getattr(request.state, "factory_id", None)
    if not factory_id:
        raise HTTPException(status_code=401, detail="tenant context not set")
    return factory_id


def _jwt_user_id(request: Request) -> Optional[int]:
    """JWT 里的 userId。body 里那个是调用方可伪造的, 只在 JWT 没有时兜底。"""
    raw = getattr(request.state, "user_id", None)
    if raw is None:
        return None
    try:
        return int(raw)
    except (TypeError, ValueError):
        return None


async def _set_tenant_guc(conn: asyncpg.Connection, factory_id: str) -> None:
    """
    给这条连接设 app.factory_id, 让表上的 RLS 策略成为第二道闸。

    ⚠️ 第三个参数必须是 False (session 级)。写 True 是**事务级**, 而 asyncpg
    默认自动提交 —— 那样它只在 set_config 自己那条隐式事务里有效, 下一条查询
    就已经没有了 (本仓 2026-08-13 实测踩过, 读数整轮作废)。
    真正把住租户边界的是下面每条 SQL 里显式的 `factory_id = $n`。
    """
    await conn.execute("SELECT set_config('app.factory_id', $1, false)", factory_id)

# ============================================================
# Database Connection
# ============================================================

_pool: Optional[asyncpg.Pool] = None


def _get_db_url() -> str:
    """Build database connection URL from environment variables."""
    from urllib.parse import quote
    user = os.getenv("FOOD_KB_POSTGRES_USER", "cretas_user")
    password = os.getenv("FOOD_KB_POSTGRES_PASSWORD", "")
    host = os.getenv("FOOD_KB_POSTGRES_HOST", "localhost")
    db = os.getenv("FOOD_KB_POSTGRES_DB", "cretas_db")
    port = os.getenv("FOOD_KB_POSTGRES_PORT", "5432")
    return f"postgresql://{quote(user, safe='')}:{quote(password, safe='')}@{host}:{port}/{db}"


async def _get_pool() -> Optional[asyncpg.Pool]:
    """Get or create connection pool. Returns None on failure."""
    global _pool
    if _pool is not None:
        return _pool
    try:
        # Also try using the food_kb_db_url from Settings if available
        try:
            from smartbi.config import get_settings
            db_url = get_settings().food_kb_db_url
        except Exception:
            db_url = _get_db_url()
        _pool = await asyncpg.create_pool(db_url, min_size=1, max_size=3)
        logger.info("Food KB feedback DB pool created")
        return _pool
    except Exception as e:
        logger.warning(f"Food KB feedback DB connection failed: {e}")
        return None


# ============================================================
# Request/Response Models
# ============================================================

class FeedbackRequest(BaseModel):
    query: str = Field(..., description="用户查询文本")
    answer: Optional[str] = Field(None, description="系统返回的回答")
    rating: int = Field(..., ge=1, le=5, description="评分 1-5")
    feedback_type: str = Field("explicit", description="反馈类型: explicit/implicit/expert")
    feedback_detail: Optional[dict] = Field(
        default_factory=dict,
        description='反馈详情: {"category": "inaccurate|incomplete|outdated|irrelevant", "comment": "..."}'
    )
    session_id: Optional[str] = Field(None, description="会话ID")
    user_id: Optional[int] = Field(None, description="用户ID")
    intent_code: Optional[str] = Field(None, description="意图编码, 如 FOOD_ADDITIVE_PRESERVATIVE")
    refined_from: Optional[str] = Field(None, description="原始意图, 如 FOOD_KNOWLEDGE_GENERAL")
    retrieved_doc_ids: Optional[List[int]] = Field(None, description="检索到的文档ID列表")
    retrieved_doc_titles: Optional[List[str]] = Field(None, description="检索到的文档标题列表")
    response_time_ms: Optional[int] = Field(None, description="响应时间(毫秒)")


class LogQueryRequest(BaseModel):
    query: str = Field(..., description="查询文本")
    session_id: Optional[str] = Field(None, description="会话ID")
    user_id: Optional[int] = Field(None, description="用户ID")
    top1_doc_id: Optional[int] = Field(None, description="最相关文档ID")
    top1_similarity: Optional[float] = Field(None, description="最高相似度")
    num_results: int = Field(0, description="返回结果数量")
    response_time_ms: Optional[int] = Field(None, description="响应时间(毫秒)")


# ============================================================
# Re-query Detection
# ============================================================

def _simple_text_similarity(text1: str, text2: str) -> float:
    """
    Simple token-based Jaccard similarity using jieba segmentation.
    Falls back to character overlap if jieba is unavailable.
    """
    try:
        import jieba
        tokens1 = set(t for t in jieba.cut(text1) if t.strip() and len(t.strip()) > 1)
        tokens2 = set(t for t in jieba.cut(text2) if t.strip() and len(t.strip()) > 1)
    except ImportError:
        # Fallback: character bigram overlap
        tokens1 = set(text1[i:i+2] for i in range(len(text1) - 1))
        tokens2 = set(text2[i:i+2] for i in range(len(text2) - 1))

    if not tokens1 or not tokens2:
        return 0.0

    intersection = tokens1 & tokens2
    union = tokens1 | tokens2
    return len(intersection) / len(union) if union else 0.0


# ============================================================
# Endpoints
# ============================================================

@router.post("/feedback")
async def submit_feedback(http_request: Request, request: FeedbackRequest) -> dict:
    """
    提交显式反馈

    用户对知识库回答的评分和详细反馈。
    支持 explicit(用户评价)、implicit(系统检测)、expert(专家审核) 三种类型。
    """
    factory_id = _require_factory_id(http_request)
    user_id = _jwt_user_id(http_request)
    if user_id is None:
        user_id = request.user_id

    pool = await _get_pool()
    if pool is None:
        return {"success": False, "message": "Database not available", "id": None}

    try:
        async with pool.acquire() as conn:
            await _set_tenant_guc(conn, factory_id)
            row = await conn.fetchrow(
                """
                INSERT INTO food_kb_feedback
                    (factory_id, query, answer, retrieved_doc_ids, retrieved_doc_titles,
                     rating, feedback_type, feedback_detail,
                     session_id, user_id, intent_code, refined_from, response_time_ms)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb, $9, $10, $11, $12, $13)
                RETURNING id
                """,
                factory_id,
                request.query,
                request.answer,
                request.retrieved_doc_ids,
                request.retrieved_doc_titles,
                request.rating,
                request.feedback_type,
                json.dumps(request.feedback_detail or {}, ensure_ascii=False),
                request.session_id,
                user_id,
                request.intent_code,
                request.refined_from,
                request.response_time_ms,
            )
            feedback_id = row["id"]
            logger.info(
                f"Feedback recorded: id={feedback_id}, rating={request.rating}, "
                f"type={request.feedback_type}, query='{request.query[:50]}'"
            )
            return {"success": True, "id": feedback_id}
    except asyncpg.UndefinedTableError:
        logger.warning("food_kb_feedback table does not exist. Run the migration first.")
        return {
            "success": False,
            "message": "Feedback table not found. Please run migration_20260213_food_kb_feedback.sql",
            "id": None,
        }
    except asyncpg.UndefinedColumnError:
        logger.warning(_TENANT_MIGRATION_HINT)
        return {"success": False, "message": _TENANT_MIGRATION_HINT, "id": None}
    except Exception as e:
        logger.error(f"Failed to save feedback: {e}")
        return {"success": False, "message": str(e), "id": None}


@router.get("/feedback/stats")
async def feedback_stats(http_request: Request) -> dict:
    """
    反馈统计 (仅本租户)

    返回总数、平均评分、按类型/评分分布、最近低评分查询。
    """
    factory_id = _require_factory_id(http_request)

    pool = await _get_pool()
    if pool is None:
        return {"success": False, "message": "Database not available"}

    try:
        async with pool.acquire() as conn:
            await _set_tenant_guc(conn, factory_id)

            # Total and average
            summary = await conn.fetchrow(
                "SELECT COUNT(*) as total, COALESCE(AVG(rating), 0) as avg_rating "
                "FROM food_kb_feedback WHERE factory_id = $1",
                factory_id,
            )

            # By type
            type_rows = await conn.fetch(
                "SELECT feedback_type, COUNT(*) as cnt FROM food_kb_feedback "
                "WHERE factory_id = $1 GROUP BY feedback_type",
                factory_id,
            )
            by_type = {row["feedback_type"]: row["cnt"] for row in type_rows}

            # By rating
            rating_rows = await conn.fetch(
                "SELECT rating, COUNT(*) as cnt FROM food_kb_feedback WHERE factory_id = $1 AND rating IS NOT NULL GROUP BY rating ORDER BY rating",  # noqa: E501
                factory_id,
            )
            by_rating = {str(row["rating"]): row["cnt"] for row in rating_rows}

            # Recent low ratings (rating <= 2) — 这一段回的是**用户原话和 AI 回答**,
            # 跨租户读出去就是把别家客户的问题印在我们自己的管理页上。
            low_rows = await conn.fetch(
                """
                SELECT id, query, answer, rating, feedback_type, feedback_detail, created_at
                FROM food_kb_feedback
                WHERE factory_id = $1 AND rating <= 2
                ORDER BY created_at DESC
                LIMIT 5
                """,
                factory_id,
            )
            recent_low = []
            for row in low_rows:
                entry = dict(row)
                # Convert datetime to ISO string for JSON serialization
                if entry.get("created_at"):
                    entry["created_at"] = entry["created_at"].isoformat()
                # Parse JSONB feedback_detail if it's a string
                if isinstance(entry.get("feedback_detail"), str):
                    try:
                        entry["feedback_detail"] = json.loads(entry["feedback_detail"])
                    except (json.JSONDecodeError, TypeError):
                        pass
                recent_low.append(entry)

            return {
                "success": True,
                "total": summary["total"],
                "avg_rating": round(float(summary["avg_rating"]), 2),
                "by_type": by_type,
                "by_rating": by_rating,
                "recent_low_ratings": recent_low,
            }
    except asyncpg.UndefinedTableError:
        logger.warning("food_kb_feedback table does not exist.")
        return {
            "success": False,
            "message": "Feedback table not found. Please run migration_20260213_food_kb_feedback.sql",
        }
    except asyncpg.UndefinedColumnError:
        logger.warning(_TENANT_MIGRATION_HINT)
        return {"success": False, "message": _TENANT_MIGRATION_HINT}
    except Exception as e:
        logger.error(f"Failed to get feedback stats: {e}")
        return {"success": False, "message": str(e)}


@router.post("/feedback/log-query")
async def log_query(http_request: Request, request: LogQueryRequest) -> dict:
    """
    记录查询日志（用于隐式反馈检测）

    同一 session 中 5 分钟内出现相似查询（前10字符相同 或 jieba token Jaccard > 0.6），
    自动标记为隐式负面反馈。
    """
    factory_id = _require_factory_id(http_request)
    user_id = _jwt_user_id(http_request)
    if user_id is None:
        user_id = request.user_id

    pool = await _get_pool()
    if pool is None:
        return {"success": False, "message": "Database not available"}

    is_requery = False
    similar_recent_query = None

    try:
        async with pool.acquire() as conn:
            await _set_tenant_guc(conn, factory_id)

            # Check for re-query if session_id provided
            if request.session_id:
                five_min_ago = datetime.now(timezone.utc) - timedelta(minutes=5)
                # session_id 完全由调用方提供 —— 不带租户条件的话, 猜中/重放
                # 别家的 session_id 就能把别家的问句原文读回来 (返回在
                # similar_recent_query 里)。租户条件必须来自 JWT。
                recent_rows = await conn.fetch(
                    """
                    SELECT id, query FROM food_kb_query_log
                    WHERE factory_id = $1 AND session_id = $2 AND created_at >= $3
                    ORDER BY created_at DESC
                    LIMIT 10
                    """,
                    factory_id,
                    request.session_id,
                    five_min_ago,
                )

                for row in recent_rows:
                    prev_query = row["query"]
                    # Check 1: first 10 characters match
                    prefix_match = (
                        request.query[:10] == prev_query[:10]
                        if len(request.query) >= 10 and len(prev_query) >= 10
                        else request.query == prev_query
                    )
                    # Check 2: token similarity > 0.6
                    token_sim = _simple_text_similarity(request.query, prev_query)

                    if prefix_match or token_sim > 0.6:
                        is_requery = True
                        similar_recent_query = prev_query
                        break

            # Insert query log
            await conn.execute(
                """
                INSERT INTO food_kb_query_log
                    (factory_id, query, session_id, user_id, top1_doc_id, top1_similarity,
                     num_results, response_time_ms)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                """,
                factory_id,
                request.query,
                request.session_id,
                user_id,
                request.top1_doc_id,
                request.top1_similarity,
                request.num_results,
                request.response_time_ms,
            )

            # If re-query detected, auto-create implicit negative feedback
            if is_requery:
                await conn.execute(
                    """
                    INSERT INTO food_kb_feedback
                        (factory_id, query, rating, feedback_type, feedback_detail,
                         session_id, user_id)
                    VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7)
                    """,
                    factory_id,
                    request.query,
                    2,  # Low rating for re-query
                    "implicit",
                    json.dumps({
                        "category": "requery",
                        "similar_query": similar_recent_query,
                        "auto_detected": True,
                    }, ensure_ascii=False),
                    request.session_id,
                    user_id,
                )
                logger.info(
                    f"Re-query detected: session={request.session_id}, "
                    f"query='{request.query[:40]}', similar_to='{similar_recent_query[:40] if similar_recent_query else ''}'"  # noqa: E501
                )

        return {
            "success": True,
            "is_requery": is_requery,
            "similar_recent_query": similar_recent_query,
        }
    except asyncpg.UndefinedTableError:
        logger.warning("food_kb_query_log table does not exist.")
        return {
            "success": False,
            "message": "Query log table not found. Please run migration_20260213_food_kb_feedback.sql",
            "is_requery": False,
            "similar_recent_query": None,
        }
    except asyncpg.UndefinedColumnError:
        logger.warning(_TENANT_MIGRATION_HINT)
        return {
            "success": False,
            "message": _TENANT_MIGRATION_HINT,
            "is_requery": False,
            "similar_recent_query": None,
        }
    except Exception as e:
        logger.error(f"Failed to log query: {e}")
        return {"success": False, "message": str(e), "is_requery": False, "similar_recent_query": None}


@router.get("/feedback/export")
async def export_feedback(
    http_request: Request,
    since: Optional[str] = Query(None, description="Start date (YYYY-MM-DD)"),
    feedback_type: Optional[str] = Query(None, alias="type", description="Filter by feedback_type"),
    min_rating: int = Query(1, ge=1, le=5, description="Minimum rating"),
    max_rating: int = Query(5, ge=1, le=5, description="Maximum rating"),
) -> list:
    """
    导出本租户的反馈数据

    支持按日期、类型、评分区间过滤。
    返回 JSON 数组，可用于离线分析。
    """
    factory_id = _require_factory_id(http_request)

    pool = await _get_pool()
    if pool is None:
        return []

    try:
        # 租户条件是 $1 且**恒在**: 下面 where_clause 是 f-string 拼的,
        # 之前 conditions 的任何一支都到不了租户维度 —— 一次导出 1000 行全平台。
        conditions = ["factory_id = $1", "rating >= $2", "rating <= $3"]
        params: list = [factory_id, min_rating, max_rating]
        param_idx = 4

        if since:
            try:
                since_dt = datetime.strptime(since, "%Y-%m-%d").replace(tzinfo=timezone.utc)
                conditions.append(f"created_at >= ${param_idx}")
                params.append(since_dt)
                param_idx += 1
            except ValueError:
                pass  # Ignore invalid date format

        if feedback_type:
            conditions.append(f"feedback_type = ${param_idx}")
            params.append(feedback_type)
            param_idx += 1

        where_clause = " AND ".join(conditions)

        async with pool.acquire() as conn:
            await _set_tenant_guc(conn, factory_id)
            rows = await conn.fetch(
                f"""
                SELECT id, query, answer, retrieved_doc_ids, retrieved_doc_titles,
                       rating, feedback_type, feedback_detail, session_id, user_id,
                       intent_code, refined_from, response_time_ms, created_at
                FROM food_kb_feedback
                WHERE {where_clause}
                ORDER BY created_at DESC
                LIMIT 1000
                """,
                *params,
            )

        results = []
        for row in rows:
            entry = dict(row)
            if entry.get("created_at"):
                entry["created_at"] = entry["created_at"].isoformat()
            if isinstance(entry.get("feedback_detail"), str):
                try:
                    entry["feedback_detail"] = json.loads(entry["feedback_detail"])
                except (json.JSONDecodeError, TypeError):
                    pass
            results.append(entry)

        return results
    except asyncpg.UndefinedTableError:
        logger.warning("food_kb_feedback table does not exist.")
        return []
    except asyncpg.UndefinedColumnError:
        logger.warning(_TENANT_MIGRATION_HINT)
        return []
    except Exception as e:
        logger.error(f"Failed to export feedback: {e}")
        return []
