"""Read each factory Tool's declared ``getAccessMode()`` from Java source, without starting Java.

INTENT-DISTILL-20261111 F-pre. The distillation data marks every intent read-only or write from
this table, and Steve's 2026-11-11 ruling lets a local model answer read-only intents directly —
so a READ here must mean the Java class (or a superclass) literally returns ``AccessMode.READ``.

Java is the source of truth and is gated by ``ToolAccessModeDeclarationTest`` (every registered
tool declares in-class or via a superclass; interface default is WRITE). This scanner is the
second copy. It fails closed:

* comments are stripped before matching (``GateDiagnoseBatchTool`` has a comment inside the method,
  which the old one-line regex read as UNRESOLVED);
* a class without its own declaration inherits from its ``extends`` parent, followed through
  ``ai/tool`` sources (``PaginationPreviousTool extends PaginationNextTool``);
* a body that is anything other than one ``return AccessMode.X;`` is DYNAMIC, never guessed;
* a hierarchy that declares nothing is UNDECLARED (the Java default would be WRITE).

Only READ and WRITE are usable; callers must treat every other value as not read-only.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOL_ROOT = ROOT / 'backend/java/cretas-api/src/main/java/com/cretas/aims/ai/tool'
TOOL_IMPL = TOOL_ROOT / 'impl'
RESOLVED = ('READ', 'WRITE')

_DECL = re.compile(r'AccessMode\s+getAccessMode\s*\(\s*\)\s*\{')
_RETURN = re.compile(r'^\s*return\s+(?:ToolExecutor\s*\.\s*)?AccessMode\s*\.\s*(READ|WRITE)\s*;\s*$')
_NAME = re.compile(r'String\s+getToolName\s*\(\s*\)\s*\{\s*return\s+(?:"([^"]+)"|(\w+))\s*;\s*\}')
_CLASS = re.compile(r'\bclass\s+(\w+)(?:\s*<[^{]*?>)?(?:\s+extends\s+([\w.]+))?')


def strip_comments(src):
    """Remove // and /* */ comments, keeping string literals and text blocks intact."""
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j]); i = j
        elif src[i] == '"' or src[i] == "'":
            q, j = src[i], i + 1
            while j < n and src[j] != q:
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1]); i = j + 1
        elif src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2)
            end = n if j < 0 else j + 2
            out.append(' ' + '\n' * src.count('\n', i, end)); i = end   # keep line numbers stable
        else:
            out.append(src[i]); i += 1
    return ''.join(out)


def _body(src, start):
    depth, i = 1, start
    while i < len(src) and depth:
        depth += {'{': 1, '}': -1}.get(src[i], 0)
        i += 1
    return src[start:i - 1]


def own_declaration(code):
    """READ / WRITE / DYNAMIC from a comment-stripped class; None if the class declares nothing."""
    found = [m for m in _DECL.finditer(code)]
    if not found:
        return None
    if len(found) > 1:
        return 'DYNAMIC'          # nested/anonymous class declarations: do not guess which one
    m = _RETURN.match(_body(code, found[0].end()))
    return m.group(1) if m else 'DYNAMIC'


def _parse(path):
    code = strip_comments(path.read_text(encoding='utf-8'))
    cls = _CLASS.search(code)
    name = _NAME.search(code)
    tool = None
    if name and name.group(1):
        tool = name.group(1)
    elif name:   # return CONSTANT; -> the String constant declared in the same file
        const = re.search(r'String\s+' + re.escape(name.group(2)) + r'\s*=\s*"([^"]+)"\s*;', code)
        tool = const.group(1) if const else None
    abstract = bool(cls) and re.search(r'\babstract\s+class\s+' + re.escape(cls.group(1)) + r'\b', code)
    return dict(path=path, cls=cls.group(1) if cls else path.stem,
                parent=(cls.group(2).split('.')[-1] if cls and cls.group(2) else None),
                tool=tool, named=bool(name), abstract=bool(abstract), own=own_declaration(code))


def scan(tool_root=TOOL_ROOT, impl_root=TOOL_IMPL):
    """tool_name -> dict(mode, source, via). Only classes under ``impl_root`` with a literal tool name."""
    classes = {}
    for p in Path(tool_root).rglob('*.java'):
        info = _parse(p)
        classes.setdefault(info['cls'], []).append(info)
    out = {}
    impl_root = Path(impl_root).resolve()
    for infos in classes.values():
        for info in infos:
            if impl_root not in info['path'].resolve().parents:
                continue
            if not info['tool']:
                if info['named'] and not info['abstract']:   # a tool whose name this scanner cannot read
                    out['<unnamed:' + info['cls'] + '>'] = dict(mode='UNNAMED', source=info['path'].name, via=None)
                continue
            mode, via, cur, seen = None, None, info, set()
            while cur is not None and cur['cls'] not in seen:
                seen.add(cur['cls'])
                if cur['own'] is not None:
                    mode, via = cur['own'], cur['path']
                    break
                parents = classes.get(cur['parent'] or '', [])
                cur = parents[0] if len(parents) == 1 else None
                if len(parents) > 1:
                    mode = 'DYNAMIC'      # ambiguous simple name: refuse to pick
                    break
            mode = mode or 'UNDECLARED'
            rel = lambda p: (p.resolve().relative_to(ROOT.resolve()).as_posix()
                             if p and ROOT.resolve() in p.resolve().parents else (p.as_posix() if p else None))
            prev = out.get(info['tool'])
            row = dict(mode=mode, source=rel(info['path']), via=rel(via))
            if prev and prev['mode'] != mode:
                row = dict(row, mode='DYNAMIC', conflict=prev['source'])   # two classes, one name
            out[info['tool']] = row
    return out


def main(argv):
    table = scan()
    bad = {k: v for k, v in sorted(table.items()) if v['mode'] not in RESOLVED}
    counts = {}
    for v in table.values():
        counts[v['mode']] = counts.get(v['mode'], 0) + 1
    print(f'TOOL_ACCESS_SCAN tools={len(table)} ' + ' '.join(f'{k}={v}' for k, v in sorted(counts.items())))
    for k, v in bad.items():
        print(f'UNRESOLVED {k} {v}')
    if len(table) < 400:          # positive control: the scan really saw the tool tree
        print(f'TOOL_ACCESS_SCAN_TOO_SMALL {len(table)}')
        return 2
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
