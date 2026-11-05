// @vitest-environment jsdom
import {mount} from '@vue/test-utils';
import {describe,it,expect} from 'vitest';
import AnalysisProgress from '../AnalysisProgress.vue';
describe('full analysis progress',()=>{
  it('does not count supplied overview facts as individually checked',()=>{
    const wrapper=mount(AnalysisProgress,{props:{status:'running',progress:{total:111,baseProvided:64,additionalReviewed:3,reviewed:8,remaining:103,mode:'all_facts',scope:'facts',phase:'queued'}}});
    expect(wrapper.text()).toContain('已核对 8 / 111 项');
    expect(wrapper.text()).toContain('还剩 103 项');
    expect(wrapper.get('progress').attributes('value')).toBe('8');
  });
  it('shows a plain blocker and folds the affected facts without exposing codes',async()=>{
    const wrapper=mount(AnalysisProgress,{props:{progress:{total:111,baseProvided:64,additionalReviewed:0,reviewed:0,remaining:111,mode:'all_facts',scope:'facts',phase:'retrying',blocker:{code:'ANALYST_OWNER',message:'处理部门没有按规定填写，正在纠正后重试。',facts:[{id:'F001',label:'牛肋条 · 资料覆盖'}]}}}});
    expect(wrapper.text()).toContain('正在纠正后重试');
    expect(wrapper.text()).not.toContain('ANALYST_OWNER');
    expect(wrapper.get('details').attributes('open')).toBeUndefined();
    expect(wrapper.text()).toContain('牛肋条 · 资料覆盖');
  });
  it('does not invent a percentage before the evidence plan exists',()=>{
    const wrapper=mount(AnalysisProgress,{props:{status:'running',stage:{phase:'retrying',message:'旧的等待提示'}}});
    expect(wrapper.text()).toContain('正在生成概览');
    expect(wrapper.text()).not.toContain('旧的等待提示');
    expect(wrapper.find('progress').exists()).toBe(false);
  });
});
