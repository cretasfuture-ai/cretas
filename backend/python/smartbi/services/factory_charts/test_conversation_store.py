import pytest

from . import conversation, conversation_store
from . import details


@pytest.mark.asyncio
async def test_memory_store_owner_expiry_and_versioned_save():
    store = conversation_store.MemoryConversationStore()
    owner = ("F006", 1309, "factory_super_admin")
    context = await store.create(owner, "business", None, {"year": 2026}, conversation.ConversationContext)
    claimed = await store.claim(context.context_id, owner, conversation.ConversationContext)
    conversation.touch_context(claimed, "看产量", ["production.output:line"])
    await store.save(claimed)
    assert store.rows[context.context_id].history[-1]["question"] == "看产量"
    with pytest.raises(details.DetailError) as denied:
        await store.claim(context.context_id, ("F006", 1310, "factory_super_admin"), conversation.ConversationContext)
    assert denied.value.status_code == 403


@pytest.mark.asyncio
async def test_memory_store_does_not_refresh_on_invalid_owner():
    store = conversation_store.MemoryConversationStore()
    owner = ("F006", 1309, "factory_super_admin")
    context = await store.create(owner, "business", None, {}, conversation.ConversationContext)
    before = store.rows[context.context_id].expires_at
    with pytest.raises(details.DetailError):
        await store.claim(context.context_id, ("F006", 9999, "factory_super_admin"), conversation.ConversationContext)
    assert store.rows[context.context_id].expires_at == before


@pytest.mark.asyncio
async def test_context_payload_is_bounded_metadata_only():
    store = conversation_store.MemoryConversationStore()
    context = await store.create(("F006", 1309, "factory_super_admin"), "business", None, {}, conversation.ConversationContext)
    for index in range(12):
        conversation.touch_context(context, "q" * 2000, [str(i) for i in range(10)])
    assert len(context.history) == conversation.MAX_HISTORY
    assert all(len(item["question"]) <= 1000 for item in context.history)
    assert all(len(item["selectedIds"]) <= conversation.MAX_SELECTED_CHARTS for item in context.history)
    assert "facts" not in vars(context) and "rows" not in vars(context)
