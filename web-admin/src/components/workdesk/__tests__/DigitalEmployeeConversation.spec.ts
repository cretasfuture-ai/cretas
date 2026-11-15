import { defineComponent } from 'vue';
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import DigitalEmployeeConversation from '../DigitalEmployeeConversation.vue';

const ChatMessagesStub = defineComponent({
  props: { messages: { type: Array, default: () => [] } },
  template: '<div data-chat-messages><article v-for="message in messages" :key="message.id" :data-role="message.role"><slot name="content" :content="message.content" :role="message.role" /></article></div>',
});
const TextareaStub = defineComponent({
  props: { modelValue: { type: String, default: '' }, disabled: Boolean },
  emits: ['update:modelValue', 'keydown'],
  template: '<textarea v-bind="$attrs" :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" @keydown="$emit(\'keydown\', $event)" />',
});
const ButtonStub = defineComponent({
  props: { disabled: Boolean, type: { type: String, default: 'button' } },
  template: '<button v-bind="$attrs" :type="type" :disabled="disabled"><slot /></button>',
});
const AlertStub = defineComponent({
  props: { description: { type: String, default: '' } },
  template: '<div v-bind="$attrs"><span>{{ description }}</span><slot /><slot name="actions" /></div>',
});
const BadgeStub = defineComponent({
  props: { label: { type: [String, Number], default: '' } },
  template: '<span v-bind="$attrs">{{ label }}<slot /></span>',
});

function open(props: Partial<InstanceType<typeof DigitalEmployeeConversation>['$props']> = {}, slots = {}) {
  return mount(DigitalEmployeeConversation, {
    props: {
      modelValue: '',
      busy: false,
      error: '',
      response: '',
      resultTitle: '业务核对',
      employeeName: '数字文员',
      ...props,
    },
    slots,
    global: { stubs: { UChatMessages: ChatMessagesStub, UTextarea: TextareaStub, UButton: ButtonStub, UAlert: AlertStub, UBadge: BadgeStub } },
  });
}

describe('DigitalEmployeeConversation', () => {
  it('clears real user and assistant history, pending retry and draft on identity change', async () => {
    const wrapper = open({ scopeKey: 'A:43', modelValue: 'A企业的订单' });
    await wrapper.get('form').trigger('submit');
    await wrapper.setProps({ response: 'A企业的敏感结果', modelValue: '未发送草稿' });
    expect(wrapper.text()).toContain('A企业的敏感结果');
    await wrapper.setProps({ scopeKey: 'B:44', response: '', error: '', busy: false });
    expect(wrapper.find('[data-role="user"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="ai-chat-assistant-body"]').exists()).toBe(false);
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['']);
    await wrapper.setProps({ modelValue: '' });
    await wrapper.get('form').trigger('submit');
    expect(wrapper.emitted('send')).toHaveLength(1);
    wrapper.unmount();
  });
  it('sends the real question and keeps the user turn in the conversation', async () => {
    const wrapper = open({ modelValue: '查今天的待收数量' });

    await wrapper.get('form').trigger('submit');

    expect(wrapper.emitted('send')).toEqual([['查今天的待收数量']]);
    expect(wrapper.find('[data-role="user"]').text()).toContain('查今天的待收数量');
    expect(wrapper.find('[data-testid="ai-chat-input"]').element).toHaveProperty('disabled', true);
  });

  it('does not send twice while the request is busy and shows a real skeleton', async () => {
    const wrapper = open({ modelValue: '查库存', busy: true });

    await wrapper.get('form').trigger('submit');
    await wrapper.get('form').trigger('submit');

    expect(wrapper.emitted('send')).toBeUndefined();
    expect(wrapper.findAll('[data-testid="ai-chat-loading-skeleton"]')).toHaveLength(1);
    expect(wrapper.findAll('.loading-bars span')).toHaveLength(3);
  });

  it('keeps the failed input and retries without fabricating success', async () => {
    const wrapper = open({ modelValue: '查供应商报价' });
    await wrapper.get('form').trigger('submit');
    await wrapper.setProps({ error: '业务服务暂时不可用' });

    expect(wrapper.emitted('update:modelValue')).toContainEqual(['查供应商报价']);
    expect(wrapper.find('[data-testid="ai-chat-error-banner"]').text()).toContain('业务服务暂时不可用');
    expect(wrapper.find('[data-testid="ai-chat-error-banner"]').text()).not.toContain('已完成');

    await wrapper.get('[data-testid="ai-chat-retry"]').trigger('click');
    expect(wrapper.emitted('retry')).toHaveLength(1);
  });

  it('sanitizes assistant markdown and exposes non-answer action states', async () => {
    const wrapper = open({
      response: '**已核对** <script>window.__bad = true</script>',
      responseStatus: 'NEED_MORE_INFO',
    }, { action: '<button>打开原单据</button>' });

    await wrapper.vm.$nextTick();

    const assistant = wrapper.get('[data-testid="ai-chat-assistant-body"]');
    expect(assistant.text()).toContain('已核对');
    expect(assistant.find('script').exists()).toBe(false);
    expect(wrapper.text()).toContain('需要补充资料');
    expect(wrapper.text()).toContain('打开原单据');
  });

  it('clears only local history for a new conversation and emits reset', async () => {
    const wrapper = open({ modelValue: '查看订单' });
    await wrapper.get('form').trigger('submit');
    await wrapper.setProps({ response: '已查询订单', busy: false });
    await wrapper.get('[data-testid="ai-chat-new-conversation"]').trigger('click');

    expect(wrapper.emitted('reset')).toHaveLength(1);
    expect(wrapper.find('[data-role="user"]').exists()).toBe(false);
  });

  it('turns a server clarification choice into a new question without executing a business write', async () => {
    const wrapper = open({ response: '请明确查询事项', responseStatus: 'NEED_CLARIFICATION', clarificationChoices: ['今日待收货清单'] });
    await wrapper.findAll('button').find((button) => button.text() === '今日待收货清单')!.trigger('click');
    expect(wrapper.emitted('send')).toEqual([['今日待收货清单']]);
  });
});
