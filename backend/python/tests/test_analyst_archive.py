import sqlite3

import pytest

from blacklake.analyst_archive import snapshot, verify


def test_sqlite_wal_is_backed_up_and_credentials_excluded(tmp_path):
    source = tmp_path / 'source'
    source.mkdir()
    db = sqlite3.connect(source / 'active.sqlite3')
    db.execute('PRAGMA journal_mode=WAL')
    db.execute('CREATE TABLE x (value TEXT)')
    db.execute("INSERT INTO x VALUES ('latest')")
    db.commit()
    (source / '.env').write_text('not-for-archive')
    (source / 'account.dpapi').write_bytes(b'not-for-archive')
    (source / 'prompt.json').write_text('{}')
    target = tmp_path / 'archive'
    manifest = snapshot([('experiment', source)], target)
    assert len(manifest['files']) == 2
    with sqlite3.connect(target / 'experiment/active.sqlite3') as copy:
        assert copy.execute('SELECT * FROM x').fetchall() == [('latest',)]
    db.close()
    (target / 'experiment/prompt.json').write_text('changed')
    with pytest.raises(ValueError, match='CHECKSUM'):
        verify(target)


def test_archive_cannot_overwrite_or_recurse(tmp_path):
    with pytest.raises(ValueError, match='RECURSION'):
        snapshot([('source', tmp_path)], tmp_path / 'nested')
    with pytest.raises(ValueError, match='NEW'):
        snapshot([], tmp_path)
