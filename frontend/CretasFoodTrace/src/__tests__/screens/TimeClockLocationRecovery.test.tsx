import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import * as Location from 'expo-location';
import TimeClockScreen from '../../screens/attendance/TimeClockScreen';
import { getCurrentPositionWithTimeout } from '../../utils/currentPosition';
import { timeclockApiClient } from '../../services/api/timeclockApiClient';

jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) =>
  key === 'common.retry' ? require('../../i18n/locales/zh-CN/hr.json').common.retry : key }) }));
jest.mock('../../store/authStore', () => ({
  useAuthStore: () => ({ user: { id: 42, userType: 'factory', factoryId: 'F006', factoryUser: { factoryId: 'F006' } } }),
}));
jest.mock('../../utils/currentPosition', () => ({ getCurrentPositionWithTimeout: jest.fn() }));
jest.mock('expo-location', () => ({ requestForegroundPermissionsAsync: jest.fn(), Accuracy: { High: 4 }, PermissionStatus: { GRANTED: 'granted', DENIED: 'denied' } }));
jest.mock('../../services/api/timeclockApiClient', () => ({
  timeclockApiClient: { getTodayRecord: jest.fn(), clockIn: jest.fn(), clockOut: jest.fn() },
}));

const permission = jest.mocked(Location.requestForegroundPermissionsAsync);
const position = jest.mocked(getCurrentPositionWithTimeout);

beforeEach(() => {
  permission.mockReset(); position.mockReset();
  permission.mockResolvedValue({ status: Location.PermissionStatus.GRANTED, granted: true, canAskAgain: true, expires: 'never' });
  jest.mocked(timeclockApiClient.getTodayRecord).mockResolvedValue({ success: true, code: 200, message: 'ok', data: null });
});

it('shows permission recovery and keeps clock writes blocked when permission is denied', async () => {
  permission.mockResolvedValue({ status: Location.PermissionStatus.DENIED, granted: false, canAskAgain: true, expires: 'never' });
  render(<TimeClockScreen />);
  await screen.findByText('attendance.timeClock.locationPermissionRequired');
  expect(screen.getByTestId('timeclock-location-retry')).toBeTruthy();
  expect(screen.getByText('重试')).toBeTruthy();
  expect(screen.queryByText('attendance.timeClock.locating')).toBeNull();
  expect(position).not.toHaveBeenCalled();
  fireEvent.press(screen.getByText('attendance.timeClock.clockInBtn'));
  expect(timeclockApiClient.clockIn).not.toHaveBeenCalled();
});

it('allows retry after timeout, then shows the successful real fix without auto clocking', async () => {
  position.mockRejectedValueOnce(new Error('Location request timed out'));
  position.mockResolvedValueOnce({
    coords: { latitude: 1, longitude: 2, accuracy: 5, altitude: null, altitudeAccuracy: null, heading: null, speed: null },
    timestamp: 123,
  });
  render(<TimeClockScreen />);
  await screen.findByText('attendance.timeClock.locationUnavailable');
  fireEvent.press(screen.getByTestId('timeclock-location-retry'));
  await screen.findByText('attendance.timeClock.locationSuccess');
  expect(screen.queryByTestId('timeclock-location-retry')).toBeNull();
  expect(position).toHaveBeenCalledTimes(2);
  expect(timeclockApiClient.clockIn).not.toHaveBeenCalled();
  expect(timeclockApiClient.clockOut).not.toHaveBeenCalled();
});

it('aborts the pending native request when the screen is unmounted', async () => {
  position.mockReturnValue(new Promise(() => {}));
  const { unmount } = render(<TimeClockScreen />);
  await waitFor(() => expect(position).toHaveBeenCalledTimes(1));
  const signal = position.mock.calls[0]![1];
  unmount();
  expect(signal?.aborted).toBe(true);
});
