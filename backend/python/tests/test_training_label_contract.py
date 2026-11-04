"""Exercise production mapping validation and the pipeline's actual training argv without training."""
import argparse
import ast
import importlib
import json
import re
import shlex
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

FINETUNE=Path(__file__).resolve().parents[3]/'scripts/finetune'
sys.path.insert(0,str(FINETUNE))
contract=importlib.import_module('training_label_contract')


def make_model(tmp_path):
    mapping={'B':0,'A':1}  # Deliberately NOT alphabetical; ID reorder is a regression.
    (tmp_path/'label_mapping.json').write_text(json.dumps({'label_to_id':mapping}),encoding='utf-8')
    (tmp_path/'config.json').write_text(json.dumps({'id2label':{'0':'B','1':'A'}}),encoding='utf-8')
    return mapping


def test_subset_training_keeps_entire_existing_head_and_ids(tmp_path):
    expected=make_model(tmp_path)
    assert contract.resolve_mapping(tmp_path,None,[{'label':'A'}])==expected


def test_label_id_drift_is_not_head_resize(tmp_path):
    make_model(tmp_path)
    supplied=tmp_path/'requested.json'
    supplied.write_text(json.dumps({'label_to_id':{'A':0,'B':1}}),encoding='utf-8')
    with pytest.raises(ValueError):contract.resolve_mapping(tmp_path,supplied,[{'label':'A'}])


def test_unknown_class_or_head_mismatch_fails_before_loading_weights(tmp_path):
    make_model(tmp_path)
    with pytest.raises(ValueError):contract.resolve_mapping(tmp_path,None,[{'label':'C'}])
    (tmp_path/'config.json').write_text(json.dumps({'num_labels':3}),encoding='utf-8')
    with pytest.raises(ValueError):contract.resolve_mapping(tmp_path,None,[{'label':'A'}])


def test_missing_model_or_sidecar_fails_closed(tmp_path):
    with pytest.raises(ValueError):contract.resolve_mapping('unconfigured/model',None,[{'label':'A'}])
    (tmp_path/'config.json').write_text('{"num_labels":2}',encoding='utf-8')
    with pytest.raises(FileNotFoundError):contract.resolve_mapping(tmp_path,None,[{'label':'A'}])


def test_replay_cannot_be_same_path_or_identical_corpus(tmp_path):
    new=tmp_path/'new.jsonl';new.write_text('new dataset',encoding='utf-8')
    copy=tmp_path/'copy.jsonl';copy.write_bytes(new.read_bytes())
    for old in [new,copy]:
        with pytest.raises(ValueError):contract.validate_replay_inputs(new,old)
    copy.write_text('distinct historical dataset',encoding='utf-8')
    contract.validate_replay_inputs(new,copy)


def test_equal_size_swapped_config_is_rejected_and_generic_is_explicit(tmp_path):
    expected=make_model(tmp_path)
    for conf in [{'id2label':{'0':'A','1':'B'}},
                 {'num_labels':2,'label2id':{'A':0,'B':1}}]:
        (tmp_path/'config.json').write_text(json.dumps(conf),encoding='utf-8')
        with pytest.raises(ValueError):contract.resolve_mapping(tmp_path,None,[{'label':'A'}])
    conf={'id2label':{'0':'LABEL_0','1':'LABEL_1'},'label2id':{'LABEL_0':0,'LABEL_1':1}}
    (tmp_path/'config.json').write_text(json.dumps(conf),encoding='utf-8')
    assert contract.resolve_mapping(tmp_path,None,[{'label':'A'}])==expected


def test_pipeline_exact_training_argv_is_accepted(monkeypatch):
    shell=(FINETUNE/'pipeline.sh').read_text(encoding='utf-8')
    command=re.search(r'run python "\$SCRIPT_DIR/incremental_finetune.py"(.*?)\n\n',shell,re.S).group(0)
    command=command.replace('\\\n',' ').replace('$SCRIPT_DIR','/scripts').replace('$DATA_DIR','/data').replace('$MERGED','/merged.jsonl')
    argv=shlex.split(command)[2:]
    module=ast.parse((FINETUNE/'incremental_finetune.py').read_text(encoding='utf-8'))
    main=next(n for n in module.body if isinstance(n,ast.FunctionDef) and n.name=='main')
    captured=[]
    ns={'argparse':argparse,'FinetuneConfig':SimpleNamespace,'run_finetuning':captured.append}
    exec(compile(ast.Module(body=[main],type_ignores=[]),'incremental_finetune.main','exec'),ns)
    monkeypatch.setattr(sys,'argv',argv)
    ns['main']()
    assert captured[0].label_mapping_path.endswith('/final/label_mapping.json')
    assert captured[0].new_data_path=='/merged.jsonl'
    assert captured[0].freeze_layers==8
    assert captured[0].old_data_path is None  # merged corpus must not replay itself


@pytest.mark.parametrize('conflicting',[True,False])
def test_direct_trainer_rejects_conflicts_and_dedupes_before_model_load(tmp_path,monkeypatch,conflicting):
    import random
    module=ast.parse((FINETUNE/'incremental_finetune.py').read_text(encoding='utf-8'))
    function=next(n for n in module.body if isinstance(n,ast.FunctionDef) and n.name=='run_finetuning')
    rows=[{'text':'独立样本表达XYZ','label':'A','flywheel_evidence':{'record_id':'reviewed'}},
          {'text':' 独立样本表达XYZ ','label':'B' if conflicting else 'A'}]
    captured=[]
    class ReachedMapping(RuntimeError):pass
    def mapping(model,provided,records):
        captured.extend(records)
        raise ReachedMapping('stopped before loading weights')
    monkeypatch.setattr(contract,'resolve_mapping',mapping)
    ns={'__file__':str(FINETUNE/'incremental_finetune.py'),'FinetuneConfig':SimpleNamespace,
        'Path':Path,'random':random,'torch':SimpleNamespace(manual_seed=lambda _:None),
        'logger':SimpleNamespace(info=lambda *_:None),'load_data':lambda _:rows,
        'merge_and_sample_data':lambda new,old,ratio:new+(old or [])}
    exec(compile(ast.Module(body=[function],type_ignores=[]),'trainer.guard','exec'),ns)
    config=SimpleNamespace(seed=42,new_data_path=str(tmp_path/'new.jsonl'),old_data_path=None,
                           replay_ratio=.5,model_path=str(tmp_path),label_mapping_path=None)
    if conflicting:
        with pytest.raises(ValueError):ns['run_finetuning'](config)
        assert not captured
    else:
        with pytest.raises(ReachedMapping):ns['run_finetuning'](config)
        assert len(captured)==1
        assert captured[0]['flywheel_evidence']['record_id']=='reviewed'


def test_freeze_uses_backbone_for_bert_and_roberta():
    module=ast.parse((FINETUNE/'incremental_finetune.py').read_text(encoding='utf-8'))
    function=next(n for n in module.body if isinstance(n,ast.FunctionDef) and n.name=='freeze_model_layers')
    class Param:
        requires_grad=True
        def numel(self):return 1
    params=[Param() for _ in range(4)]
    layers=[SimpleNamespace(parameters=lambda p=p:[p]) for p in params[1:]]
    model=SimpleNamespace(base_model=SimpleNamespace(embeddings=SimpleNamespace(parameters=lambda:[params[0]]),
                         encoder=SimpleNamespace(layer=layers)),parameters=lambda:params)
    ns={'logger':SimpleNamespace(info=lambda *_:None)}
    exec(compile(ast.Module(body=[function],type_ignores=[]),'trainer.freeze','exec'),ns)
    ns['freeze_model_layers'](model,1)
    assert [p.requires_grad for p in params]==[False,False,True,True]
