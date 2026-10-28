"""Query-only original-row pages bound to an authenticated chart snapshot."""
from typing import List, Optional, Union

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field, StrictBool, StrictFloat, StrictInt, StrictStr

from smartbi.services.factory_charts import details, detail_store
from smartbi.services.factory_charts.selection import identity
from smartbi.api.factory_production_gold import _can_view_cost

router = APIRouter()


class _StrictModel(BaseModel):
    class Config:
        extra = 'forbid'


class DetailFilter(_StrictModel):
    field: str = Field(..., min_length=1, max_length=200)
    value: Union[StrictStr, StrictInt, StrictFloat, StrictBool, None]


class DetailPoint(_StrictModel):
    name: str = Field(..., min_length=1, max_length=400)
    seriesName: Optional[str] = Field(None, max_length=400)
    dataIndex: Optional[int] = Field(None, ge=0, le=20000)


class FactoryDetailsRequest(_StrictModel):
    context_id: str = Field(..., min_length=8, max_length=128)
    factory_id: Optional[str] = None
    page: int = Field(1, ge=1, le=20000)
    page_size: int = Field(50, ge=1, le=100)
    filters: List[DetailFilter] = Field(default_factory=list, max_length=8)
    point: Optional[DetailPoint] = None


@router.post('/factory-details')
async def factory_details(payload: FactoryDetailsRequest, request: Request):
    factory, user = identity(request, payload.factory_id)
    role = getattr(request.state, 'role', None)
    owner = (factory, user, role)
    can_price = _can_view_cost(role)
    try:
        needs_finance = await detail_store.requires_finance_shared(payload.context_id, owner)
        finance_allowed = not needs_finance
        if needs_finance and can_price:
            from smartbi.services.factory_charts.finance_auth import full_factory_finance_access
            finance_allowed = await full_factory_finance_access(request, factory)
        return await detail_store.page_shared(payload.model_dump(exclude_none=True), owner, can_price, finance_allowed)
    except details.DetailError as exc:
        raise HTTPException(exc.status_code, exc.detail) from None
