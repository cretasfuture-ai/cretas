import { describe, expect, it } from 'vitest';
import { pythonProxyTarget } from '../../tooling/blacklakePythonTarget';

describe('isolated Blacklake Python proxy', () => {
  it('overrides an inherited legacy target only in blacklake mode', () => {
    const env = { VITE_PYTHON_URL: 'http://127.0.0.1:8093', VITE_BLACKLAKE_PYTHON_URL: 'http://127.0.0.1:8095' };
    expect(pythonProxyTarget('blacklake', env)).toBe('http://127.0.0.1:8095');
    expect(pythonProxyTarget('production', env)).toBe('http://127.0.0.1:8093');
    expect(pythonProxyTarget('development', env)).toBe('http://127.0.0.1:8093');
  });
  it('preserves existing configuration and defaults', () => {
    expect(pythonProxyTarget('blacklake', { VITE_PYTHON_URL: 'http://127.0.0.1:8093' })).toBe('http://127.0.0.1:8093');
    expect(pythonProxyTarget('blacklake', {})).toBe('http://localhost:8083');
  });
  it.each(['https://remote.example', 'http://127.0.0.1:10010', 'http://127.0.0.1:8095/api', 'http://name:8095'])('rejects unexpected experiment targets: %s', target => {
    expect(() => pythonProxyTarget('blacklake', { VITE_BLACKLAKE_PYTHON_URL: target })).toThrow('approved loopback');
  });
});
