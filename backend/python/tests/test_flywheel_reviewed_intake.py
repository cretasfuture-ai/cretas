"""Reviewed intake must not launder teacher guesses or evaluation data into training."""
import copy
import importlib
import json
import sys
from pathlib import Path

import pytest

FINETUNE = Path(__file__).resolve().parents[3] / 'scripts' / 'finetune'
sys.path.insert(0, str(FINETUNE))
fw = importlib.import_module('flywheel_reviewed')


def write(path, rows):
    path.write_text(''.join(json.dumps(r, ensure_ascii=False)+'\n' for r in rows), encoding='utf-8')
    return path


@pytest.fixture
def files(tmp_path):
    labels = tmp_path/'labels.json'
    labels.write_text(json.dumps({'labels':['MATERIAL_BATCH_QUERY']}),encoding='utf-8')
    holdout = write(tmp_path/'holdout.jsonl',[{'text':'原料溯源信息','group_id':'protected-origin'}])
    row = {'record_id':'review-1','text':'检索特定原材料批次档案','label':'MATERIAL_BATCH_QUERY',
           'group_id':'new-origin','split':'train',
           'review':{'status':'approved','reviewer':'domain-reviewer','basis':'verified task boundary'},
           'provenance':{'source_kind':'synthetic','evidence_refs':['review-log-1']}}
    return tmp_path, labels, holdout, row


def load(files, row):
    root,labels,holdout,_=files
    return fw.load_reviewed(write(root/'reviewed.jsonl',[row]),labels,holdout)


def test_approved_record_keeps_audit_and_training_shape(files):
    row=files[-1]
    actual=load(files,row)
    assert actual[0]['text']==row['text'] and actual[0]['label']==row['label']
    assert actual[0]['flywheel_evidence']['record_id']=='review-1'


@pytest.mark.parametrize('change',[
    {'split':'holdout'}, {'review':{'status':'pending','teacher_probability':.99}},
])
def test_teacher_confidence_and_holdout_never_auto_approve(files,change):
    assert load(files,dict(files[-1],**change))==[]


@pytest.mark.parametrize('change',[
    {'text':'原料溯源信息'}, {'text':' 原料 溯源信息 '}, {'text':'原料溯源信息。'},
    {'group_id':'protected-origin'}, {'label':'retired_label'},
    {'review':{'status':'approved'}}, {'provenance':{'source_kind':'evaluation','evidence_refs':['baseline']}},
])
def test_invalid_or_leaking_review_fails_closed(files,change):
    with pytest.raises(ValueError):load(files,dict(files[-1],**change))


def test_all_historical_sources_are_subject_to_holdout(files):
    rows=[{'text':'原料溯源信息','label':'MATERIAL_BATCH_QUERY','source':s}
          for s in ['zpd','shadow','feedback','old_replay']]
    rows.append({'text':'保留一条不相干的独立样本','label':'MATERIAL_BATCH_QUERY','source':'zpd'})
    assert fw.exclude_holdout(rows,files[2])==[rows[-1]]


def test_contradictory_duplicate_rejected(files):
    root,labels,holdout,row=files
    with pytest.raises(ValueError):
        fw.load_reviewed(write(root/'dupes.jsonl',[row,row]),labels,holdout)


def test_empty_holdout_rejected(files):
    files[2].write_text('',encoding='utf-8')
    with pytest.raises(ValueError):load(files,files[-1])


def test_factory_rows_use_existing_consent_and_redaction(files,monkeypatch):
    gate=importlib.import_module('training_consent_gate')
    calls=[]
    def check(rows,keys):
        calls.append((rows,keys))
        return [dict(rows[0],user_input='经授权脱敏后的独立批次表达')]
    monkeypatch.setattr(gate,'apply_utterance_rule',check)
    row=copy.deepcopy(files[-1]);row['provenance']['source_kind']='factory';row['factory_id']='FACTORY_EXAMPLE'
    assert load(files,row)[0]['text']=='经授权脱敏后的独立批次表达'
    assert calls[0][0][0]['factory_id']=='FACTORY_EXAMPLE'
    monkeypatch.setattr(gate,'apply_utterance_rule',lambda *_:[])
    assert load(files,row)==[]
    def unavailable(*_):raise RuntimeError('consent unavailable')
    monkeypatch.setattr(gate,'apply_utterance_rule',unavailable)
    with pytest.raises(RuntimeError):load(files,row)


def test_real_exporter_wires_intake_and_shared_gate(files,monkeypatch,tmp_path):
    exporter=importlib.import_module('export_training_data')
    root,labels,holdout,row=files
    reviewed=write(root/'reviewed.jsonl',[row]);output=root/'result.jsonl'
    monkeypatch.setattr(exporter,'load_zpd_data',lambda *_:[{'text':'原料溯源信息','label':'MATERIAL_BATCH_QUERY','source':'zpd','confidence':.9}])
    def no_db():raise exporter.psycopg2.OperationalError('offline')
    monkeypatch.setattr(exporter,'get_db_connection',no_db)
    seen=[]
    def shared_gate(rows):seen.extend(rows);return rows
    monkeypatch.setattr(exporter,'drop_records_naming_protected_terms',shared_gate)
    monkeypatch.setattr(sys,'argv',['export_training_data','--reviewed-flywheel-file',str(reviewed),
        '--flywheel-labels-file',str(labels),'--flywheel-holdout-file',str(holdout),'--output',str(output)])
    exporter.main()
    records=fw.read_jsonl(output)
    assert records and all(r['text']!='原料溯源信息' for r in records)
    assert any(r['source']=='flywheel_reviewed' for r in seen)
    assert records[0]['flywheel_evidence']['record_id']=='review-1'
    def deny(_):raise RuntimeError('protected names unreadable')
    monkeypatch.setattr(exporter,'drop_records_naming_protected_terms',deny)
    with pytest.raises(RuntimeError):exporter.main()


def test_partial_flywheel_arguments_fail_before_sources(files,monkeypatch):
    exporter=importlib.import_module('export_training_data')
    monkeypatch.setattr(sys,'argv',['export_training_data','--reviewed-flywheel-file','missing'])
    with pytest.raises(SystemExit) as exc:exporter.main()
    assert exc.value.code==2


@pytest.mark.parametrize('catalog',[
    {'labels':'MATERIAL_BATCH_QUERY'}, {'labels':{'MATERIAL_BATCH_QUERY':0}},
    {'labels':['A','A']}, {'labels':['']}, {'labels':['A'],'num_labels':2},
    {'label_to_id':{'A':True}}, {'label_to_id':{'A':0,'B':0}}, {'label_to_id':{'A':2}},
])
def test_malformed_label_catalog_rejected(files,catalog):
    files[1].write_text(json.dumps(catalog),encoding='utf-8')
    with pytest.raises(ValueError):load(files,files[-1])


def test_synthetic_flag_cannot_bypass_factory_consent(files,monkeypatch):
    gate=importlib.import_module('training_consent_gate')
    seen=[]
    def denied(rows,keys):seen.extend(rows);return []
    monkeypatch.setattr(gate,'apply_utterance_rule',denied)
    row=dict(files[-1],factory_id='WITHDRAWN_FACTORY')
    assert load(files,row)==[]
    assert seen[0]['factory_id']=='WITHDRAWN_FACTORY'


@pytest.mark.parametrize('source',['zpd','shadow','feedback','old_replay'])
def test_conflicting_label_across_sources_rejected(files,source):
    reviewed=load(files,files[-1])[0]
    old={'text':' '+reviewed['text']+' ','label':'DIFFERENT','source':source}
    with pytest.raises(ValueError):fw.reject_conflicts([reviewed,old])


def test_default_export_filters_durable_registry_and_refuses_output_alias(files,monkeypatch):
    exporter=importlib.import_module('export_training_data')
    default=FINETUNE/'data/evaluation_holdout.jsonl'
    held=fw.read_jsonl(default)[0]['text']
    rows=[{'text':held,'label':'MATERIAL_BATCH_QUERY','source':'zpd','confidence':1},
          {'text':'完全独立的新训练表达','label':'MATERIAL_BATCH_QUERY','source':'zpd','confidence':1}]
    monkeypatch.setattr(exporter,'load_zpd_data',lambda *_:rows)
    def offline():raise exporter.psycopg2.OperationalError('offline')
    monkeypatch.setattr(exporter,'get_db_connection',offline)
    monkeypatch.setattr(exporter,'drop_records_naming_protected_terms',lambda r:r)
    output=files[0]/'default-output.jsonl'
    monkeypatch.setattr(sys,'argv',['export','--output',str(output)])
    exporter.main()
    assert all(r['text']!=held for r in fw.read_jsonl(output))
    original=default.read_bytes()
    monkeypatch.setattr(sys,'argv',['export','--output',str(default)])
    with pytest.raises(SystemExit):exporter.main()
    assert default.read_bytes()==original


def test_each_flywheel_input_is_protected_from_output_alias(files,monkeypatch):
    exporter=importlib.import_module('export_training_data')
    root,labels,holdout,row=files
    reviewed=write(root/'reviewed.jsonl',[row])
    for output in [labels,holdout,reviewed]:
        original=output.read_bytes()
        monkeypatch.setattr(sys,'argv',['export','--reviewed-flywheel-file',str(reviewed),
            '--flywheel-labels-file',str(labels),'--flywheel-holdout-file',str(holdout),'--output',str(output)])
        with pytest.raises(SystemExit):exporter.main()
        assert output.read_bytes()==original


def test_no_sources_is_failure_even_if_old_export_exists(files,monkeypatch):
    exporter=importlib.import_module('export_training_data')
    output=files[0]/'stale.jsonl';output.write_text('old export must not be consumed',encoding='utf-8')
    monkeypatch.setattr(exporter,'load_zpd_data',lambda *_:[])
    def offline():raise exporter.psycopg2.OperationalError('offline')
    monkeypatch.setattr(exporter,'get_db_connection',offline)
    monkeypatch.setattr(sys,'argv',['export','--output',str(output)])
    with pytest.raises(SystemExit) as exc:exporter.main()
    assert exc.value.code==2  # pipeline set -euo pipefail stops before training
