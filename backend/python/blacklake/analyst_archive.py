"""Explicit local archive: no credential discovery, network calls or model execution."""
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3


FORBIDDEN = {'.env', '.env.local', '.env.test'}


def allowed(path):
    name = path.name.lower()
    return not (path.is_symlink() or name in FORBIDDEN or name.startswith('.env')
                or name.endswith(('.dpapi', '.pem', '.key', '-wal', '-shm', '.partial'))
                or any(word in name for word in ('secret', 'credential')))


def sha256(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def snapshot(sources, destination):
    """Sources are explicit (label, path) pairs; active SQLite uses online backup."""
    destination = Path(destination).resolve()
    if destination.exists():
        raise ValueError('ARCHIVE_DESTINATION_MUST_BE_NEW')
    sources = [(label, Path(path).resolve()) for label, path in sources]
    for label, path in sources:
        if Path(label).name != label or label in ('.', '..') or not path.exists():
            raise ValueError('ARCHIVE_SOURCE_INVALID')
        if path == destination or path in destination.parents:
            raise ValueError('ARCHIVE_RECURSION')
    destination.mkdir(parents=True)
    records = []
    for label, source in sources:
        paths = sorted(source.rglob('*')) if source.is_dir() else [source]
        for path in paths:
            if not path.is_file() or not allowed(path):
                continue
            if any(p.is_symlink() for p in path.parents if p != source.parent):
                raise ValueError('ARCHIVE_SYMLINK_PARENT')
            relative = Path(label) / (path.relative_to(source) if source.is_dir() else path.name)
            target = destination / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            if path.suffix.lower() in ('.sqlite3', '.sqlite', '.db'):
                with sqlite3.connect(path.as_uri() + '?mode=ro', uri=True) as original:
                    with sqlite3.connect(str(target)) as copy:
                        original.backup(copy)
                        if copy.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                            raise ValueError('ARCHIVE_DATABASE_INVALID')
            else:
                shutil.copyfile(path, target)
            records.append({'path': relative.as_posix(), 'bytes': target.stat().st_size, 'sha256': sha256(target)})
    manifest = {'files': records, 'credentialFilesExcluded': True, 'inferenceVerified': False}
    (destination / 'archive-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf8')
    verify(destination)
    return manifest


def verify(directory):
    directory = Path(directory).resolve()
    manifest = json.loads((directory / 'archive-manifest.json').read_text(encoding='utf8'))
    for record in manifest['files']:
        target = (directory / record['path']).resolve()
        if directory not in target.parents or target.is_symlink():
            raise ValueError('ARCHIVE_PATH_ESCAPE')
        if target.stat().st_size != record['bytes'] or sha256(target) != record['sha256']:
            raise ValueError('ARCHIVE_CHECKSUM_MISMATCH')
    return len(manifest['files'])
