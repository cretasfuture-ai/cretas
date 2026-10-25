"""Financial Dashboard API — Chart generation, batch, analysis, PPT/PDF/Excel export."""
import logging
from typing import Optional, Dict, List, Any, Literal
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, Field, validator, root_validator
import pandas as pd
import io

from smartbi.services.financial_dashboard import FinancialDashboardService
from smartbi.services.financial.base import _sanitize_for_json
from smartbi.services.financial.export_conclusions import conclusion_rows as _conclusion_rows
from smartbi.services.factory_charts.export_guard import ExportGuardRoute, validate_export

logger = logging.getLogger(__name__)
router = APIRouter(route_class=ExportGuardRoute)

# Service singleton
dashboard_service = FinancialDashboardService()


class FactoryRecommendationRequest(BaseModel):
    source: Literal['upload', 'business'] = 'business'
    upload_id: Optional[int] = Field(None, ge=1)
    factory_id: Optional[str] = None
    year: int = Field(2026, ge=1900, le=2100)
    period_type: Literal['all', 'year', 'month_range'] = 'all'
    start_month: int = Field(1, ge=1, le=12)
    end_month: int = Field(12, ge=1, le=12)
    include_optional: bool = True
    use_ai: bool = True


class FactorySelectionRequest(BaseModel):
    factory_id: Optional[str] = None
    source: Literal['upload', 'business']
    selected_ids: List[str] = Field(..., max_items=100)

    @validator('selected_ids')
    def valid_selection(cls, value):
        if any(not item or len(item) > 300 for item in value) or len(value) != len(set(value)):
            raise ValueError('图表标识必须非空、唯一且不超过300字符')
        return value


@router.get('/factory-selection')
async def load_factory_selection(http_request: Request, source: Literal['upload', 'business'], factory_id: Optional[str] = None):
    from smartbi.services.factory_charts.selection import access
    return await access(http_request, factory_id, source)


@router.put('/factory-selection')
async def save_factory_selection(request: FactorySelectionRequest, http_request: Request):
    from smartbi.services.factory_charts.selection import access
    return await access(http_request, request.factory_id, request.source, request.selected_ids)


@router.post('/factory-recommendations')
async def factory_recommendations(request: FactoryRecommendationRequest, http_request: Request):
    from common.tenant_identity import require_same_factory
    from smartbi_compat._rbac_role import ANALYTICS_READ_ROLES
    from smartbi.api.factory_production_gold import _can_view_cost
    from starlette.concurrency import run_in_threadpool
    from smartbi.services.factory_charts import engine, sources, ranking, finance_sources

    factory_id = require_same_factory(http_request, request.factory_id)
    role = getattr(http_request.state, 'role', None)
    if role not in ANALYTICS_READ_ROLES:
        raise HTTPException(403, '当前身份没有经营分析查看权限')
    if request.start_month > request.end_month:
        raise HTTPException(422, '开始月份不能晚于结束月份')
    if request.period_type == 'year' and (request.start_month != 1 or request.end_month != 12):
        raise HTTPException(422, '全年口径必须为1至12月')
    can_price = _can_view_cost(role)
    errors, extra_gaps = [], []
    if request.source == 'upload':
        if not request.upload_id:
            raise HTTPException(422, '请先选择本厂上传报表')
        load_request = FinancialDashboardRequest(upload_id=request.upload_id)
        try:
            frame = await _load_data(load_request, factory_id)
        except DataSourceError as e:
            return e.response()
        if len(frame) > 20000:
            raise HTTPException(422, '报表超过两万行安全上限，请拆分报表后重试')
        datasets = [engine.Dataset(frame, f'upload-{request.upload_id} · 本厂上传报表',
                                   engine.is_financial(frame), load_request.sheet_name or '')]
    else:
        datasets, errors, extra_gaps = await sources.load(factory_id, request, can_price)
        from smartbi.services.factory_charts.finance_auth import full_factory_finance_access
        finance_allowed = can_price and await full_factory_finance_access(http_request, factory_id)
        extra_data, extra_errors, more_gaps = await finance_sources.load(factory_id, request, finance_allowed)
        datasets.extend(extra_data)
        errors.extend(extra_errors)
        extra_gaps.extend(more_gaps)
        if not datasets and errors:
            return JSONResponse(status_code=503, content={'success': False, 'message': '工厂数据来源读取失败，请重试', 'data': {'sourceErrors': errors}})
    candidates, gaps = await run_in_threadpool(engine.build, datasets, request, can_price)
    errors.extend({'source': gap['title'], 'message': gap['missing'][0]} for gap in gaps if gap.get('error'))
    gaps = [gap for gap in gaps if not gap.get('error')]
    candidates, order = await ranking.rank(candidates, request.use_ai, cache_scope=f'{factory_id}:{role}')
    from smartbi.services.factory_charts.selection_groups import default_selection
    label = '全部历史' if request.period_type == 'all' else f'{request.year}年{request.start_month}–{request.end_month}月'
    return _sanitize_for_json({'success': True, 'data': {
        'factoryId': factory_id, 'source': request.source,
        'sourceLabel': '本厂业务系统' if request.source == 'business' else f'本厂上传报表 #{request.upload_id}',
        'period': {'label': label}, 'ranking': order,
        'recommendations': candidates, 'defaultSelection': default_selection(candidates), 'unavailable': gaps + extra_gaps,
        'sourceErrors': errors,
    }})


class FinancialDashboardRequest(BaseModel):
    upload_id: Optional[int] = None
    raw_data: Optional[List[Dict[str, Any]]] = Field(None, max_items=50000)  # Direct data input, max 50k rows
    chart_type: str = "all"  # specific type, "all", or "auto"
    year: int = 2026
    period_type: str = "year"  # month|quarter|year|month_range
    start_month: int = Field(1, ge=1, le=12)
    end_month: int = Field(12, ge=1, le=12)
    # 租户以登录身份为准; 只有不带工厂的身份 (平台管理员) 才需要显式传。
    factory_id: Optional[str] = None
    filters: Optional[Dict[str, List[str]]] = None  # Fix 72: Global slicer filters {col: [values]}
    # raw_data 调用方可以传工作表名; upload_id 时以库里记录的工作表名为准
    sheet_name: Optional[str] = None


class AnalyzeRequest(BaseModel):
    chart_type: str
    analysis_context: str
    chart_result: Optional[Dict] = None
    follow_up_question: Optional[str] = None
    previous_analysis: Optional[str] = None


class PPTExportRequest(BaseModel):
    _export_limits = root_validator(pre=True, allow_reuse=True)(validate_export)
    chart_titles: Dict[str, str] = Field(default_factory=dict)
    period_label: Optional[str] = None
    report_title: Optional[str] = None
    upload_id: Optional[int] = None
    year: int = 2026
    period_type: str = "year"
    start_month: int = 1
    end_month: int = 12
    chart_images: Dict[str, str] = Field(default_factory=dict)  # {chartType: base64PNG}
    headline: Optional[Dict[str, Any]] = None
    findings: Optional[Dict[str, List[str]]] = None
    analysis_results: Dict[str, str] = Field(default_factory=dict)
    template: str = "default"
    company_name: str = "白垩纪科技"
    kpi_summary: Optional[Dict] = None


class DataSourceError(Exception):
    """数据源不可用: 带 HTTP 状态码与给用户看的原文。"""

    def __init__(self, status_code: int, message: str):
        super().__init__(message)
        self.status_code = status_code
        self.message = message

    def response(self) -> JSONResponse:
        return JSONResponse(status_code=self.status_code,
                            content={"success": False, "message": self.message, "error": self.message})


def _resolve_factory_id(http_request: Request, requested: Optional[str]) -> str:
    """租户取自登录身份 (JWTAuthMiddleware 写入 request.state); 请求体不能改写它。"""
    token_factory = getattr(http_request.state, "factory_id", None)
    if token_factory:
        if requested and requested != token_factory:
            logger.warning("financial-dashboard factory mismatch: token=%s body=%s", token_factory, requested)
            raise DataSourceError(403, "无权访问其他工厂的数据")
        return token_factory
    if requested:
        # 不带工厂的身份 (平台管理员 / 内部调用未带 X-Factory-Id) 必须显式指定
        return requested
    raise DataSourceError(400, "无法确定当前工厂，请重新登录")


async def _load_data(request: FinancialDashboardRequest, factory_id: Optional[str]) -> pd.DataFrame:
    """Load data from raw_data, or from the uploaded rows of upload_id (one row_data per Excel row).

    upload_id 时把库里的工作表名写回 request.sheet_name (工作表名用于推断无科目表的口径)。
    """
    if request.raw_data:
        return pd.DataFrame(request.raw_data)

    if not request.upload_id:
        raise DataSourceError(400, "请先选择已上传的数据源")

    # 模块属性访问而不是 from-import: 测试与运行期替换连接池都要能生效
    import smartbi.config as smartbi_config
    try:
        pool = await smartbi_config.get_pg_pool()
        if pool is None:
            raise DataSourceError(503, "SmartBI 数据库未配置，无法读取上传数据")
        async with pool.acquire() as conn:
            rows = await conn.fetch(
                """
                SELECT d.row_data, d.sheet_name
                FROM smart_bi_dynamic_data d
                JOIN smart_bi_pg_excel_uploads u ON u.id = d.upload_id
                WHERE d.upload_id = $1 AND u.factory_id = $2 AND d.factory_id = $2
                ORDER BY d.row_index
                """,
                request.upload_id,
                factory_id,
            )
    except DataSourceError:
        raise
    except Exception as e:
        logger.error(f"Failed to load upload {request.upload_id} for {factory_id}: {e}", exc_info=True)
        raise DataSourceError(500, "读取上传数据失败，请稍后重试")

    if not rows:
        raise DataSourceError(404, f"未找到数据源 {request.upload_id}，或它不属于当前工厂")

    import json
    records = [json.loads(r["row_data"]) if isinstance(r["row_data"], str) else r["row_data"] for r in rows]
    sheet_names = {r["sheet_name"] for r in rows if r["sheet_name"]}
    if len(sheet_names) == 1:
        request.sheet_name = sheet_names.pop()
    return pd.DataFrame(records)


def _apply_filters(df: pd.DataFrame, filters: Optional[Dict[str, List[str]]]) -> pd.DataFrame:
    """Fix 72: Apply global slicer filters to DataFrame."""
    if not filters:
        return df
    for col, values in filters.items():
        if col in df.columns and values:
            df = df[df[col].isin(values)]
    return df


def _natural_sort_key(value: Any):
    """「1月 2月 … 10月」按数字顺序排, 不按字符串排成「10月 11月 12月 1月」。"""
    import re
    text = str(value)
    return [(0, int(part), "") if part.isdigit() else (1, 0, part) for part in re.split(r"(\d+)", text) if part]


def _extract_dimensions(df: pd.DataFrame) -> List[Dict[str, Any]]:
    """Fix 72: Extract available filter dimensions from DataFrame."""
    dims = []
    mapping = dashboard_service.normalizer.detect_columns(df.columns.tolist(), df)
    measures = set(mapping.actual_cols + mapping.budget_cols + mapping.last_year_cols
                   + mapping.last_year_budget_cols)
    # Amount-aware normalization may prefer revenue/cost over the ratio columns.
    # Those original percentage measures are still measures, never dimensions.
    measures.update(col for col in df.columns
                    if any(key in str(col).lower() for key in
                           dashboard_service.normalizer.RATIO_HEADER_KEYWORDS + ('ratio', 'margin', 'percent')))
    for col in df.columns:
        if col in measures:
            continue
        if df[col].dtype == 'object':
            unique_vals = df[col].dropna().unique().tolist()
            if 2 <= len(unique_vals) <= 20:
                dims.append({"name": col, "values": sorted(unique_vals, key=_natural_sort_key)})
    return dims


@router.post("/generate")
async def generate_chart(request: FinancialDashboardRequest, http_request: Request):
    """Generate a single financial chart or all charts."""
    try:
        factory_id = _resolve_factory_id(http_request, request.factory_id)
        df = await _load_data(request, factory_id)
        df = _apply_filters(df, request.filters)  # Fix 72
        result = dashboard_service.generate_chart(
            chart_type=request.chart_type,
            raw_data=df,
            year=request.year,
            period_type=request.period_type,
            start_month=request.start_month,
            end_month=request.end_month,
            sheet_name=request.sheet_name,
            filters=request.filters,
        )
        return _sanitize_for_json(result)
    except DataSourceError as e:
        return e.response()
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Chart generation failed: {e}", exc_info=True)
        return {"success": False, "error": "图表生成失败，请检查数据格式后重试"}


@router.post("/batch")
async def batch_generate(request: FinancialDashboardRequest, http_request: Request):
    """Generate all available charts (batch mode)."""
    try:
        request.chart_type = "all"
        factory_id = _resolve_factory_id(http_request, request.factory_id)
        df = await _load_data(request, factory_id)
        # Fix 72: Extract dimensions before filtering
        dimensions = _extract_dimensions(df)
        # Fix 72: Apply slicer filters
        df = _apply_filters(df, request.filters)
        result = dashboard_service.generate_dashboard(
            raw_data=df,
            year=request.year,
            period_type=request.period_type,
            start_month=request.start_month,
            end_month=request.end_month,
            sheet_name=request.sheet_name,
            filters=request.filters,
        )
        # Fix 72: Attach available dimensions to response
        if isinstance(result, dict):
            result["availableDimensions"] = dimensions
        return _sanitize_for_json(result)
    except DataSourceError as e:
        return e.response()
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Batch generation failed: {e}", exc_info=True)
        return {"success": False, "error": "批量生成失败，请检查数据格式后重试"}


@router.post("/analyze")
async def analyze_chart(request: AnalyzeRequest):
    """AI analysis for a single chart, with optional follow-up conversation."""
    try:
        from common.llm_router import call_chain, SLOT
        from common.llm_metrics import llm_caller_context

        system_msg = "你是服务于食品加工企业CFO的资深财务分析师。请用简洁的中文回复。"

        if request.follow_up_question and request.previous_analysis:
            # Follow-up: multi-turn conversation with history
            messages = [
                {"role": "system", "content": system_msg},
                {"role": "user", "content": (
                    f"请对以下财务数据进行分析：\n"
                    f"图表类型：{request.chart_type}\n"
                    f"数据摘要：{request.analysis_context}"
                )},
                {"role": "assistant", "content": request.previous_analysis},
                {"role": "user", "content": request.follow_up_question},
            ]
        else:
            # Initial analysis
            prompt = (
                f"请对以下财务数据进行专业分析：\n\n"
                f"图表类型：{request.chart_type}\n"
                f"数据摘要：{request.analysis_context}\n\n"
                f"请提供：\n"
                f"1. 核心发现（3-5条）\n"
                f"2. 风险预警（如有）\n"
                f"3. 改进建议（2-3条）\n\n"
                f"要求：结合食品加工行业特点，数据驱动，具体可操作。"
            )
            messages = [
                {"role": "system", "content": system_msg + "请用要点列表格式。"},
                {"role": "user", "content": prompt},
            ]

        # 走 call_chain(SLOT.INSIGHTS): 免费 fallback 链 + 熔断。原 OpenAI SDK 同步直连
        # (在 async endpoint 内阻塞) 改为 async router 调用。model 由 router 按 slot 注入。
        payload = {
            "messages": messages,
            "max_tokens": 2000,
            "temperature": 0.3,
        }
        with llm_caller_context("financial_dashboard_analyze"):
            data = await call_chain(SLOT.INSIGHTS, payload, timeout=60.0)

        analysis_text = data["choices"][0]["message"]["content"]
        return {
            "success": True,
            "chartType": request.chart_type,
            "analysis": analysis_text,
        }
    except Exception as e:
        logger.error(f"Analysis failed: {e}", exc_info=True)
        return {"success": False, "error": "AI 分析暂时不可用，请稍后重试"}


@router.get("/templates")
async def list_templates():
    """List all available chart templates."""
    return {
        "success": True,
        "templates": dashboard_service.list_templates(),
    }


@router.post("/export-ppt")
async def export_ppt(request: PPTExportRequest):
    """Export financial dashboard to PPTX file."""
    try:
        from smartbi.services.ppt_generator import FinancialPPTGenerator
        generator = FinancialPPTGenerator()
        pptx_bytes = generator.generate(
            chart_images=request.chart_images,
            headline=request.headline,
            findings=request.findings,
            analysis_results=request.analysis_results,
            company_name=request.company_name,
            year=request.year,
            period_type=request.period_type,
            start_month=request.start_month,
            end_month=request.end_month,
            kpi_summary=request.kpi_summary,
            template=request.template,
            chart_titles=request.chart_titles,
            period_label=request.period_label,
            report_title=request.report_title,
        )
        from urllib.parse import quote
        filename = f"财务分析报告_{request.year}年{request.start_month}-{request.end_month}月.pptx"
        filename_ascii = f"report_{request.year}_{request.start_month}-{request.end_month}.pptx"
        filename_encoded = quote(filename)
        return StreamingResponse(
            io.BytesIO(pptx_bytes),
            media_type="application/vnd.openxmlformats-officedocument.presentationml.presentation",
            headers={
                "Content-Disposition": f"attachment; filename=\"{filename_ascii}\"; filename*=UTF-8''{filename_encoded}",  # noqa: E501
            },
        )
    except Exception as e:
        logger.error(f"PPT export failed: {e}", exc_info=True)
        return {"success": False, "error": "PPT 导出失败，请稍后重试"}


class ExcelExportRequest(BaseModel):
    _export_limits = root_validator(pre=True, allow_reuse=True)(validate_export)
    chart_titles: Dict[str, str] = Field(default_factory=dict)
    period_label: Optional[str] = None
    report_title: Optional[str] = None
    charts: List[Dict[str, Any]] = Field(default_factory=list)  # Chart results from batch
    analysis_results: Dict[str, str] = Field(default_factory=dict)
    # 看板上的页首结论与规则结论 (/batch 原样回传): 导出报告里要和页面上读到的是同一组话
    headline: Optional[Dict[str, Any]] = None
    findings: Optional[Dict[str, List[str]]] = None
    company_name: str = "白垩纪科技"
    year: int = 2026
    period_type: str = "year"
    start_month: int = 1
    end_month: int = 12


@router.post("/export-excel")
async def export_excel(request: ExcelExportRequest):
    """Export financial dashboard data to Excel with multiple sheets."""
    try:
        import openpyxl
        from openpyxl.styles import Font, PatternFill, Alignment, Border, Side

        wb = openpyxl.Workbook()
        if request.report_title:
            info = wb.create_sheet('报告信息')
            info.append(['报告', request.report_title])
            info.append(['工厂', request.company_name])
            info.append(['期间', request.period_label or f'{request.year}年{request.start_month}–{request.end_month}月'])
            info.append(['说明', '仅包含页面当前显示且选中的图表；逐图来源、单位与规则分析见明细及分析表'])

        # Header styles
        header_font = Font(bold=True, color="FFFFFF", size=11)
        header_fill = PatternFill(start_color="1B65A8", end_color="1B65A8", fill_type="solid")
        header_align = Alignment(horizontal="center", vertical="center")
        thin_border = Border(  # noqa: F841
            left=Side(style="thin", color="D9D9D9"),
            right=Side(style="thin", color="D9D9D9"),
            top=Side(style="thin", color="D9D9D9"),
            bottom=Side(style="thin", color="D9D9D9"),
        )

        # Sheet 1: 分析结论 (有页首结论或规则结论时)
        conclusion_rows = _conclusion_rows(request.headline, request.findings)
        if conclusion_rows:
            ws_conc = wb.active
            ws_conc.title = "分析结论"
            ws_conc.append(["类别", "内容"])
            for cell in ws_conc[1]:
                cell.font = header_font
                cell.fill = header_fill
                cell.alignment = header_align
            for row in conclusion_rows:
                ws_conc.append(row)
            ws_conc.column_dimensions["A"].width = 14
            ws_conc.column_dimensions["B"].width = 90
            ws_summary = wb.create_sheet(title="KPI汇总")
        else:
            ws_summary = wb.active
            ws_summary.title = "KPI汇总"
        ws_summary.append(["图表类型", "指标", "数值", "单位", "趋势"])
        for cell in ws_summary[1]:
            cell.font = header_font
            cell.fill = header_fill
            cell.alignment = header_align

        for chart in request.charts:
            chart_type = chart.get("chartType", "")
            title = chart.get("title", chart_type)
            for kpi in chart.get("kpis", []):
                ws_summary.append([
                    title,
                    kpi.get("label", ""),
                    kpi.get("value", ""),
                    kpi.get("unit", ""),
                    {"up": "↑", "down": "↓", "flat": "→"}.get(kpi.get("trend", ""), ""),
                ])

        # Auto-width for summary
        for col in ws_summary.columns:
            max_len = max(len(str(cell.value or "")) for cell in col) + 2
            ws_summary.column_dimensions[col[0].column_letter].width = min(max_len, 30)

        # Sheet per chart with tableData
        from smartbi.services.ppt_generator import CHART_DISPLAY_NAMES
        for chart in request.charts:
            chart_type = chart.get("chartType", "")
            table_data = chart.get("tableData")
            if not table_data:
                continue

            display_name = chart.get('title') or request.chart_titles.get(chart_type) or CHART_DISPLAY_NAMES.get(chart_type, chart_type)
            # Sheet name max 31 chars
            import re
            sheet_name = re.sub(r'[\\/*?:\[\]]', ' ', str(display_name)).strip()[:31] or '图表明细'
            ws = wb.create_sheet(title=sheet_name)
            if request.report_title:
                for label, value in [('来源',chart.get('source','')),('期间',chart.get('period',request.period_label or '')),
                                     ('单位',chart.get('unit','')),('规则依据',chart.get('reason',''))]:
                    ws.append([label,value])

            # 明细表里的金额按图表显示倍率缩放过 (万/亿), 必须写明单位, 否则拿去求和对账会错一万倍
            unit_note = _table_unit_note(chart)
            if unit_note:
                ws.append([unit_note])
            header_row = ws.max_row + 1 if unit_note or request.report_title else 1
            ws.append(table_data.get("headers", []))
            for cell in ws[header_row]:
                cell.font = header_font
                cell.fill = header_fill
                cell.alignment = header_align

            # 构建器的明细行有两种形状: {"label", "values"} 或整行列表
            for row in table_data.get("rows", []):
                if isinstance(row, dict):
                    ws.append([row.get("label", "")] + list(row.get("values") or []))
                elif isinstance(row, (list, tuple)):
                    ws.append(list(row))
                else:
                    raise ValueError(f"无法识别的明细行格式: {type(row).__name__}")

            for col in ws.columns:
                max_len = max(len(str(cell.value or "")) for cell in col) + 2
                ws.column_dimensions[col[0].column_letter].width = min(max_len, 25)

        # Sheet: AI Analysis
        if request.analysis_results:
            ws_ai = wb.create_sheet(title="逐图规则与来源" if request.report_title else "AI分析")
            ws_ai.append(["图表", "分析内容"])
            for cell in ws_ai[1]:
                cell.font = header_font
                cell.fill = header_fill
            for chart_type, analysis in request.analysis_results.items():
                display_name = request.chart_titles.get(chart_type) or CHART_DISPLAY_NAMES.get(chart_type, chart_type)
                ws_ai.append([display_name, analysis if request.report_title else analysis[:5000]])
            ws_ai.column_dimensions["A"].width = 20
            ws_ai.column_dimensions["B"].width = 80

        if request.report_title:
            # Treat uploaded strings as text in every sheet, including metadata.
            import re
            for sheet in wb:
                for row in sheet:
                    for cell in row:
                        if isinstance(cell.value, str) and re.match(r'^\s*[=+@-]', cell.value):
                            cell.value = "'" + cell.value
        output = io.BytesIO()
        wb.save(output)
        output.seek(0)

        from urllib.parse import quote
        filename = f"财务分析数据_{request.year}年{request.start_month}-{request.end_month}月.xlsx"
        filename_ascii = f"financial_data_{request.year}_{request.start_month}-{request.end_month}.xlsx"

        return StreamingResponse(
            output,
            media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            headers={
                "Content-Disposition": f"attachment; filename=\"{filename_ascii}\"; filename*=UTF-8''{quote(filename)}",
            },
        )
    except ImportError:
        return _export_error("服务器未安装 openpyxl，请联系管理员")
    except Exception as e:
        logger.error(f"Excel export failed: {e}", exc_info=True)
        return _export_error("Excel 导出失败，请稍后重试")


def _export_error(message: str) -> JSONResponse:
    # 非 200: 前端据此不会把错误 JSON 当成 .xlsx 下载并提示成功
    return JSONResponse(status_code=500, content={"success": False, "message": message, "error": message})


def _table_unit_note(chart: Dict[str, Any]) -> Optional[str]:
    """图表明细的金额单位说明 (元 / 万元 / 亿元)。"""
    scale = (chart.get("metadata") or {}).get("scale")
    suffix = scale.get("suffix") if isinstance(scale, dict) else scale
    if suffix in ("万", "亿"):
        return f"金额单位：{suffix}元（百分比列除外）"
    if suffix == "":
        return "金额单位：元（百分比列除外）"
    return None


class PDFExportRequest(BaseModel):
    _export_limits = root_validator(pre=True, allow_reuse=True)(validate_export)
    chart_titles: Dict[str, str] = Field(default_factory=dict)
    period_label: Optional[str] = None
    report_title: Optional[str] = None
    chart_images: Dict[str, str] = Field(default_factory=dict)  # {chartType: base64PNG}
    headline: Optional[Dict[str, Any]] = None
    findings: Optional[Dict[str, List[str]]] = None
    analysis_results: Dict[str, str] = Field(default_factory=dict)
    company_name: str = "白垩纪科技"
    year: int = 2026
    period_type: str = "year"
    start_month: int = 1
    end_month: int = 12
    kpi_summary: Optional[Dict] = None


@router.post("/export-pdf")
async def export_pdf(request: PDFExportRequest):
    """Export financial dashboard to PDF with chart images and analysis."""
    try:
        from smartbi.services.pdf_generator import FinancialPDFGenerator
        generator = FinancialPDFGenerator()
        pdf_bytes = generator.generate(
            chart_images=request.chart_images,
            headline=request.headline,
            findings=request.findings,
            analysis_results=request.analysis_results,
            company_name=request.company_name,
            year=request.year,
            period_type=request.period_type,
            start_month=request.start_month,
            end_month=request.end_month,
            kpi_summary=request.kpi_summary,
            chart_titles=request.chart_titles,
            period_label=request.period_label,
            report_title=request.report_title,
        )

        from urllib.parse import quote
        filename = f"财务分析报告_{request.year}年{request.start_month}-{request.end_month}月.pdf"
        filename_ascii = f"report_{request.year}_{request.start_month}-{request.end_month}.pdf"

        return StreamingResponse(
            io.BytesIO(pdf_bytes),
            media_type="application/pdf",
            headers={
                "Content-Disposition": f"attachment; filename=\"{filename_ascii}\"; filename*=UTF-8''{quote(filename)}",
            },
        )
    except ImportError as e:
        logger.error(f"PDF export import failed: {e}")
        return {"success": False, "error": "PDF 导出依赖未安装，请联系管理员"}
    except Exception as e:
        logger.error(f"PDF export failed: {e}", exc_info=True)
        return {"success": False, "error": "PDF 导出失败，请稍后重试"}
