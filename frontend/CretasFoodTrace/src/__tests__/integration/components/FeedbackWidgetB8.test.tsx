// @ts-nocheck
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { TouchableOpacity } from 'react-native';
import { FeedbackWidget } from '../../../components/ai/FeedbackWidget';

const post = jest.fn();
jest.mock('../../../store/authStore', () => ({ useAuthStore: (selector?: (state: any) => unknown) => {
  const state = { getFactoryId: () => 'F006', tokens: { accessToken: 'token' } };
  return selector ? selector(state) : state;
} }));
jest.mock('../../../services/api/apiClient', () => ({ apiClient: { post: (...args: unknown[]) => post(...args) } }));
jest.mock('../../../components/ui/AppDialog', () => ({ appAlert: jest.fn() }));

function renderFeedback() {
  return render(<FeedbackWidget queryId="q1" question="查库存" answer="库存 0.9 kg" sessionId="s1" intentCode="MATERIAL_BATCH_QUERY" />);
}

describe('B8 FeedbackWidget actual component', () => {
  beforeEach(() => post.mockReset());

  it('submits all completed intent ratings with intentCode and sessionId', async () => {
    post.mockResolvedValue({ success: true, data: { success: true } });
    const view = renderFeedback();
    fireEvent.press(view.UNSAFE_getAllByType(TouchableOpacity)[1]);
    await waitFor(() => expect(post).toHaveBeenCalled());
    const submitCall = post.mock.calls.find((call: unknown[]) => String(call[0]).includes('/food-kb/feedback/submit'));
    expect(submitCall).toBeDefined();
    expect(submitCall[1]).toEqual(expect.objectContaining({ intentCode: 'MATERIAL_BATCH_QUERY', sessionId: 's1', helpful: true }));
  });

  it('does not claim nested Python failure as submitted and keeps retry path', async () => {
    post.mockResolvedValue({ success: true, data: { success: false, message: '数据库暂时不可用' } });
    const view = renderFeedback();
    fireEvent.press(view.UNSAFE_getAllByType(TouchableOpacity)[1]);
    await waitFor(() => expect(post).toHaveBeenCalled());
    expect(view.queryByText('已提交反馈，感谢您的帮助')).toBeNull();
    expect(view.UNSAFE_getAllByType(TouchableOpacity).length).toBeGreaterThan(0);
  });
});
