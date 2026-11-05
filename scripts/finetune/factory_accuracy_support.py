"""Source-bound semantics and fast conservative overlap detection for offline ML."""
from collections import Counter, defaultdict
from difflib import SequenceMatcher
import json
from pathlib import Path
import numpy as np
from flywheel_reviewed import normalized

WORK=Path(__file__).resolve().parents[2]
BOUNDARIES=Path(__file__).parent/'data/factory_semantic_boundaries.json'


def semantics(catalog, path=BOUNDARIES):
    policy=json.loads(Path(path).read_text(encoding='utf-8'))
    rows={r['intent_code']:r for r in catalog['canonical_intents']}
    aliases={}
    def routes(row):
        return row.get('skill_tools') if row.get('route_type')=='SKILL' else [row.get('tool_name')]
    for group in policy['equivalent_routes']:
        preferred=group['preferred']
        if preferred not in rows or routes(rows[preferred])!=[group['tool']]:
            raise ValueError('Preferred route no longer matches source catalog')
        for alias in group['aliases']:
            if alias not in rows or routes(rows[alias])!=[group['tool']] or alias in aliases or alias==preferred:
                raise ValueError('Equivalence no longer supported by exact single-Tool binding')
            aliases[alias]=preferred
    if any(k not in rows for k in policy['label_notes']):
        raise ValueError('Boundary note references absent label')
    return policy,aliases


class OverlapIndex:
    """Character-bag upper bound cannot miss a SequenceMatcher>=.85 match.

    Unlike approximate nearest-neighbor filtering, this is a conservative bound
    on the number of matching characters. Only candidate pairs use difflib.
    """
    def __init__(self, rows):
        self.texts=[normalized(r['text']) for r in rows]
        self.groups={r['group_id'] for r in rows if r.get('group_id')}
        self.lengths=np.asarray([len(t) for t in self.texts],dtype=np.int32)
        postings=defaultdict(list)
        for i,text in enumerate(self.texts):
            for char,count in Counter(text).items():postings[char].append((i,count))
        self.postings={char:(np.asarray([i for i,_ in values],dtype=np.int32),np.asarray([c for _,c in values],dtype=np.int32)) for char,values in postings.items()}

    def overlaps(self,row,threshold=.85):
        if row.get('group_id') in self.groups:return True
        text=normalized(row['text'])
        if not text:raise ValueError('Empty utterance')
        counts=np.zeros(len(self.texts),dtype=np.int32)
        for char,count in Counter(text).items():
            if char in self.postings:
                ids,other=self.postings[char];counts[ids]+=np.minimum(count,other)
        possible=np.flatnonzero(2*counts>=threshold*(len(text)+self.lengths))
        return any(SequenceMatcher(None,text,self.texts[i]).ratio()>=threshold for i in possible)


def verify_disjoint(left,right):
    index=OverlapIndex(right)
    for row in left:
        if index.overlaps(row):raise ValueError('Protected text/family overlap: '+row.get('record_id','unknown'))
