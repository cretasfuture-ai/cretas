"""点选 -> 精确表 PENDING 晋升任务的闸 (ai/jobs/promote_selections.py)。

每条正向断言配一条能让它红的对照; 阈值各有一条「刚好差一点」的反例。
"""
from ai.jobs import promote_selections as ps

F = "F006"


def _rows(intent, users, query="就是六十箱", norm="就是<量>", factory=F):
    """users: 每个元素一次点选 (None = 无 user_id)。"""
    return [{"factory_id": factory, "user_id": u, "query_text": query, "normalized_text": norm,
             "chosen_intent": intent} for u in users]


FT = {F: "FACTORY", "R1": "RESTAURANT", "F007": "FACTORY"}


def _go(rows, existing=None, ftypes=None):
    return ps.compute_promotions(rows, FT if ftypes is None else ftypes, existing or {})


# ------------------------------------------------------------------ 阈值
def test_proposal_when_thresholds_met():
    rep = _go(_rows("A", ["1", "2", "3"]))
    assert len(rep.proposals) == 1
    p = rep.proposals[0]
    assert (p.factory_id, p.business_type, p.normalized_text, p.intent_code) == (F, "FACTORY", "就是<量>", "A")
    assert p.observed_count == 3 and p.distinct_users == 3 and p.total_picks == 3
    assert p.sample_text == "就是六十箱"


def test_two_picks_is_not_enough():
    rep = _go(_rows("A", ["1", "2"]))
    assert rep.proposals == [] and rep.below_min_picks == 1


def test_one_user_is_not_enough_even_with_many_picks():
    rep = _go(_rows("A", ["1"] * 6))
    assert rep.proposals == [] and rep.below_min_users == 1


def test_share_07_is_not_enough():
    # 7 个 A + 3 个 B, 10 次点选, 3 个用户都点过 A -> 只差占比
    rep = _go(_rows("A", ["1", "2", "3", "1", "2", "3", "1"]) + _rows("B", ["4", "5", "6"]))
    assert rep.proposals == [] and rep.below_top_share == 1
    assert rep.below_min_picks == 0 and rep.below_min_users == 0


def test_share_exactly_08_passes():
    rep = _go(_rows("A", ["1", "2", "3", "1"]) + _rows("B", ["4"]))  # 4/5
    assert len(rep.proposals) == 1
    assert rep.proposals[0].share == 0.8 and rep.proposals[0].observed_count == 4


def test_users_counted_on_top_intent_not_whole_group():
    # A 一个人点 X 五次, 另一个人点 Y 一次: 整组有 2 个用户, 但支持 X 的只有 1 个 -> 不提议
    rep = _go(_rows("X", ["1"] * 5) + _rows("Y", ["2"]))
    assert rep.proposals == [] and rep.below_min_users == 1


def test_null_user_ids_do_not_count_as_distinct_users():
    rep = _go(_rows("A", [None, None, None, "1"]))
    assert rep.proposals == [] and rep.below_min_users == 1
    # 对照: 补一个真实的第二个用户就过 (证明上面不是被别的闸挡的)
    assert len(_go(_rows("A", [None, None, "2", "1"])).proposals) == 1


def test_sample_text_is_most_common_raw_query():
    rows = (_rows("A", ["1", "2"], query="就是六十箱") + _rows("A", ["3"], query="就这个 60箱"))
    assert _go(rows).proposals[0].sample_text == "就是六十箱"


def test_tie_between_intents_never_passes():
    rep = _go(_rows("A", ["1", "2", "3"]) + _rows("B", ["4", "5", "6"]))
    assert rep.proposals == [] and rep.below_top_share == 1


# ------------------------------------------------------------------ 已有键
def _ex(intent="A", status="APPROVED", source="TEACHER", count=0, bt="FACTORY", norm="就是<量>", fid=F):
    return {(fid, bt, norm): {"intent_code": intent, "status": status, "source": source, "observed_count": count}}


def test_existing_keys_untouched_in_every_status_and_source():
    rows = _rows("A", ["1", "2", "3"])
    for status in ("APPROVED", "REJECTED", "PENDING"):
        for source in ("HUMAN", "TEACHER"):
            rep = _go(rows, _ex(status=status, source=source))
            assert rep.proposals == [] and rep.refreshes == [], (status, source)
            assert sum(rep.existing_skipped.values()) == 1, (status, source)
    # 对照: 键不存在时同样的输入会提议 (证明上面是「已有键」挡的, 不是别的)
    assert len(_go(rows).proposals) == 1


def test_existing_different_intent_is_skipped_not_overwritten():
    rep = _go(_rows("A", ["1", "2", "3"]), _ex(intent="B", status="PENDING", source="SELECTION"))
    assert rep.proposals == [] and rep.refreshes == []
    assert rep.existing_skipped["PENDING/SELECTION/other-intent"] == 1


def test_own_pending_same_intent_gets_count_refresh_only():
    rep = _go(_rows("A", ["1", "2", "3", "1"]), _ex(status="PENDING", source="SELECTION", count=3))
    assert rep.proposals == []
    assert len(rep.refreshes) == 1 and rep.refreshes[0].observed_count == 4


def test_own_pending_same_count_not_refreshed():
    rep = _go(_rows("A", ["1", "2", "3"]), _ex(status="PENDING", source="SELECTION", count=3))
    assert rep.refreshes == []


def test_other_source_pending_not_refreshed():
    rep = _go(_rows("A", ["1", "2", "3", "1"]), _ex(status="PENDING", source="TEACHER", count=1))
    assert rep.refreshes == []


def test_factories_kept_separate():
    rows = (_rows("A", ["1", "2", "3"], factory=F) + _rows("B", ["1", "2", "3"], factory="F007")
            + _rows("A", ["1", "2"], factory="R1"))
    rep = _go(rows)
    got = {(p.factory_id, p.intent_code) for p in rep.proposals}
    assert got == {(F, "A"), ("F007", "B")}  # R1 只有 2 次点选, 且不并入 F006 的 A
    # F006 已有键不影响 F007
    rep2 = _go(rows, _ex())
    assert {(p.factory_id, p.intent_code) for p in rep2.proposals} == {("F007", "B")}


def test_business_type_follows_java_domain_mapping():
    rows = [r for f in ("F", "R", "B", "H", "C", "L") for r in _rows("A", ["1", "2", "3"], factory=f)]
    ft = {"F": "FACTORY", "R": "RESTAURANT", "B": "BRANCH", "H": "HEADQUARTERS", "C": "CENTRAL_KITCHEN",
          "L": "LOGISTICS"}
    got = {p.factory_id: p.business_type for p in _go(rows, ftypes=ft).proposals}
    assert got == {"F": "FACTORY", "R": "RESTAURANT", "B": "RESTAURANT", "H": "FACTORY", "C": "FACTORY",
                   "L": "LOGISTICS"}


def test_unknown_factory_or_type_is_skipped_not_defaulted():
    rows = _rows("A", ["1", "2", "3"], factory="GHOST") + _rows("A", ["1", "2", "3"], factory="WEIRD")
    rep = _go(rows, ftypes={"WEIRD": "SPACESHIP"})
    assert rep.proposals == [] and rep.unknown_factory == 2


# ------------------------------------------------------------------ IO: dry-run 不写 / apply 只写 PENDING
class _Conn:
    def __init__(self, pool):
        self.pool = pool

    async def fetch(self, sql, *args):
        self.pool.fetches.append(sql)
        if sql == ps.SELECTIONS_SQL:
            return self.pool.selection_rows
        if sql == ps.FACTORY_TYPES_SQL:
            return [{"id": k, "type": v} for k, v in FT.items()]
        if sql == ps.EXISTING_SQL:
            return self.pool.existing_rows
        raise AssertionError("unexpected fetch: %s" % sql)

    async def execute(self, sql, *args):
        self.pool.executes.append((sql, args))
        return "INSERT 0 1"

    def transaction(self):
        class _Tx:
            async def __aenter__(s):
                return s

            async def __aexit__(s, *a):
                return False
        return _Tx()


class _Pool:
    def __init__(self, selection_rows, existing_rows=()):
        self.selection_rows = selection_rows
        self.existing_rows = list(existing_rows)
        self.fetches = []
        self.executes = []

    def acquire(self):
        pool = self

        class _Ctx:
            async def __aenter__(s):
                return _Conn(pool)

            async def __aexit__(s, *a):
                return False
        return _Ctx()


async def test_dry_run_reads_but_never_executes():
    pool = _Pool(_rows("A", ["1", "2", "3"]))
    rep = await ps.run(pool)  # 缺省 apply=False
    assert len(rep.proposals) == 1          # 必发生的对照: 确实算出了提议、确实读了库
    assert ps.SELECTIONS_SQL in pool.fetches
    assert pool.executes == []              # 但一条写都没有


async def test_apply_writes_only_pending_inserts_with_on_conflict():
    pool = _Pool(_rows("A", ["1", "2", "3"]))
    await ps.run(pool, apply=True)
    assert len(pool.executes) == 1
    sql, args = pool.executes[0]
    assert "'PENDING'" in sql and "'SELECTION'" in sql and "APPROVED" not in sql
    assert "ON CONFLICT (factory_id, business_type, normalized_text) DO NOTHING" in sql
    assert args == (F, "FACTORY", "就是<量>", "A", "就是六十箱", 3)


async def test_apply_refresh_is_guarded_and_never_sets_status():
    existing = [{"factory_id": F, "business_type": "FACTORY", "normalized_text": "就是<量>",
                 "intent_code": "A", "status": "PENDING", "source": "SELECTION", "observed_count": 3}]
    pool = _Pool(_rows("A", ["1", "2", "3", "1"]), existing)
    await ps.run(pool, apply=True)
    assert len(pool.executes) == 1
    sql, _ = pool.executes[0]
    assert sql == ps.REFRESH_COUNT_SQL
    set_clause = sql.split("SET", 1)[1].split("WHERE", 1)[0]
    assert "status" not in set_clause
    assert "status = 'PENDING'" in sql and "source = 'SELECTION'" in sql and "observed_count < $5" in sql


async def test_apply_with_existing_human_row_writes_nothing():
    existing = [{"factory_id": F, "business_type": "FACTORY", "normalized_text": "就是<量>",
                 "intent_code": "A", "status": "APPROVED", "source": "HUMAN", "observed_count": 0}]
    pool = _Pool(_rows("A", ["1", "2", "3"]), existing)
    rep = await ps.run(pool, apply=True)
    assert rep.existing_skipped["APPROVED/HUMAN"] == 1   # 必发生: 确实看到了那一行
    assert pool.executes == []


async def test_empty_log_is_zero_with_denominator_in_report():
    pool = _Pool([])
    rep = await ps.run(pool, apply=True)
    assert rep.log_rows == 0 and pool.executes == []
    assert "selection log rows read: 0" in ps.format_report(rep, applied=True)
