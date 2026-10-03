import * as Location from 'expo-location';
import { getCurrentPositionWithTimeout } from '../currentPosition';

jest.mock('expo-location', () => ({ watchPositionAsync: jest.fn() }));
const watch = jest.mocked(Location.watchPositionAsync);
const fix: Location.LocationObject = {
  coords: { latitude: 1, longitude: 2, accuracy: 5, altitude: null, altitudeAccuracy: null, heading: null, speed: null },
  timestamp: 123,
};

beforeEach(() => { jest.useFakeTimers(); watch.mockReset(); });
afterEach(() => { jest.useRealTimers(); });

it('returns a real native fix and removes the listener after success', async () => {
  const remove = jest.fn();
  watch.mockResolvedValue({ remove });
  const pending = getCurrentPositionWithTimeout({});
  await Promise.resolve();
  watch.mock.calls[0]![1](fix);
  await expect(pending).resolves.toBe(fix);
  expect(remove).toHaveBeenCalledTimes(1);
  expect(jest.getTimerCount()).toBe(0);
});

it('times out and stops the listener instead of supplying a fake or cached position', async () => {
  const remove = jest.fn();
  watch.mockResolvedValue({ remove });
  const pending = getCurrentPositionWithTimeout({});
  const rejected = expect(pending).rejects.toThrow('timed out');
  await Promise.resolve();
  jest.advanceTimersByTime(15000);
  await rejected;
  watch.mock.calls[0]![1](fix);
  expect(remove).toHaveBeenCalledTimes(1);
});

it('cancels an active request and ignores later fixes', async () => {
  const remove = jest.fn();
  watch.mockResolvedValue({ remove });
  const controller = new AbortController();
  const pending = getCurrentPositionWithTimeout({}, controller.signal);
  const rejected = expect(pending).rejects.toThrow('cancelled');
  await Promise.resolve();
  controller.abort();
  await rejected;
  watch.mock.calls[0]![1](fix);
  expect(remove).toHaveBeenCalledTimes(1);
  expect(jest.getTimerCount()).toBe(0);
});

it('removes a listener that registers after its request has already timed out', async () => {
  const remove = jest.fn();
  let register!: (listener: Location.LocationSubscription) => void;
  watch.mockReturnValue(new Promise(resolve => { register = resolve; }));
  const pending = getCurrentPositionWithTimeout({});
  const rejected = expect(pending).rejects.toThrow('timed out');
  jest.advanceTimersByTime(15000);
  await rejected;
  register({ remove });
  await Promise.resolve();
  expect(remove).toHaveBeenCalledTimes(1);
});

it('does not start a native watcher for an already cancelled request', async () => {
  const controller = new AbortController();
  controller.abort();
  await expect(getCurrentPositionWithTimeout({}, controller.signal)).rejects.toThrow('cancelled');
  expect(watch).not.toHaveBeenCalled();
});

it('propagates provider failure and cleans up the deadline', async () => {
  watch.mockRejectedValue(new Error('GPS unavailable'));
  await expect(getCurrentPositionWithTimeout({})).rejects.toThrow('GPS unavailable');
  expect(jest.getTimerCount()).toBe(0);
});
