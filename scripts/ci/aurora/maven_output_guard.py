"""Keep ignored Maven failures from becoming green after Nested XML overwrite."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ANSI = re.compile(rb'\x1b\[[0-?]*[ -/]*[@-~]')
SUMMARY = re.compile(rb'^\[(?:INFO|WARNING|ERROR)\]\s+Tests run:\s*\d+,\s*Failures:\s*(\d+),\s*Errors:\s*(\d+)(?:,|$)')
UNTRUSTED_REPORT = 86


def has_reliable_red_xml(cwd):
    # Main's accepted baseline is currently empty. If XML still contains red,
    # the existing ratchet will reject it. A positive/absent baseline cannot
    # prove that an overwritten console failure was among its accepted IDs.
    baseline = cwd / 'known-failing-tests.txt'
    if not baseline.is_file():
        return False
    if any(line.strip() and not line.lstrip().startswith('#')
           for line in baseline.read_text().splitlines()):
        return False
    paths = list((cwd / 'target/surefire-reports').glob('TEST-*.xml'))
    if not paths:
        return False
    red = False
    for path in paths:
        root = ET.parse(path).getroot()
        # Match java-test-ratchet.py: counters or anonymous nodes do not
        # identify a failing test that its baseline comparison can reject.
        for case in root.iter('testcase'):
            cls = case.get('classname') or ''
            method = (case.get('name') or '').split('[', 1)[0].strip()
            if cls and method:
                red |= case.find('failure') is not None or case.find('error') is not None
    return red


def observe(stream, output, cwd):
    saw_red = False
    for line in stream:
        output.write(line)
        output.flush()
        match = SUMMARY.match(ANSI.sub(b'', line).lstrip(b'\r'))
        if match and (int(match[1]) or int(match[2])):
            saw_red = True
    if not saw_red:
        return 0
    try:
        if has_reliable_red_xml(cwd):
            return 0
    except (OSError, ValueError, ET.ParseError):
        pass
    print('CRETAS_MAVEN_REPORT_GUARD: Maven recorded failures/errors but final XML '
          'cannot safely account for them; refusing an ignored green result.',
          file=sys.stderr)
    return UNTRUSTED_REPORT


def main():
    try:
        return observe(sys.stdin.buffer, sys.stdout.buffer, Path.cwd())
    except Exception as exc:
        print('CRETAS_MAVEN_REPORT_GUARD: observer failed (' + type(exc).__name__ + ')',
              file=sys.stderr)
        return UNTRUSTED_REPORT


if __name__ == '__main__':
    raise SystemExit(main())
