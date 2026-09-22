#!/usr/bin/env python3
"""Read-only verification of an offline Store database/artifact restore.

The checkpoint contains only digests/counts, never rows or bearer credentials.
It does not establish freshness unless compared with an independently retained
checkpoint from the last accepted, quiesced snapshot.
"""
import argparse
import hashlib
import json
import pathlib
import sqlite3
import sys


class InvalidBackup(Exception):
    pass


def file_hash(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def artifact(root, relative, expected_size, expected_hash):
    path = pathlib.PurePosixPath(relative)
    if (not relative or path.is_absolute() or '\\' in relative
            or any(part in ('', '.', '..') for part in relative.split('/'))):
        raise InvalidBackup('Unsafe artifact path in database')
    source = root.joinpath(*path.parts)
    # Refuse symlinks, including intermediate directories; backups must contain
    # actual retained bytes rather than references outside the snapshot.
    cursor = root
    for part in path.parts:
        cursor = cursor / part
        if cursor.is_symlink():
            raise InvalidBackup('Artifact symlink in snapshot')
    if (not source.is_file() or source.stat().st_size != expected_size
            or file_hash(source) != expected_hash):
        raise InvalidBackup('A retained artifact is missing or its size/hash differs')


def quote_identifier(value):
    return '"' + value.replace('"', '""') + '"'


def logical_digest(connection):
    """Logical contents, independent of SQLite page layout/WAL/checkpointing."""
    digest = hashlib.sha256()
    for schema in connection.execute('SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name'):
        digest.update(json.dumps(schema, separators=(',', ':')).encode())
        digest.update(b'\n')
    tables = connection.execute(
        "SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name"
    ).fetchall()
    counts = {}
    for name, schema in tables:
        digest.update(json.dumps([name, schema], separators=(',', ':')).encode())
        # Preserve NULs, BLOBs and real precision without writing rows to output.
        # SQL-side ordering avoids buffering every database row in Python.
        columns = connection.execute('PRAGMA table_info(' + quote_identifier(name) + ')').fetchall()
        fields = [quote_identifier(c[1]) for c in columns]
        projection = ','.join(
            "typeof({c}) || ':' || CASE WHEN typeof({c})='real' THEN printf('%!.26g',{c}) ELSE hex(CAST({c} AS BLOB)) END".format(c=c)
            for c in fields
        )
        order = ','.join(str(i + 1) + ' COLLATE BINARY' for i in range(len(columns)))
        rows = connection.execute('SELECT ' + projection + ' FROM ' + quote_identifier(name) + ' ORDER BY ' + order)
        count = 0
        for row in rows:
            digest.update(json.dumps(row, separators=(',', ':'), ensure_ascii=True).encode())
            digest.update(b'\n')
            count += 1
        counts[name] = count
    return digest.hexdigest(), counts


def verify(database, storage):
    database = database.resolve(strict=True)
    storage = storage.resolve(strict=True)
    if not database.is_file() or not storage.is_dir():
        raise InvalidBackup('Expected a database file and artifact directory')
    connection = sqlite3.connect(database.as_uri() + '?mode=ro', uri=True)
    try:
        connection.execute('PRAGMA query_only=ON')
        connection.execute('BEGIN')
        if connection.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
            raise InvalidBackup('SQLite integrity check failed')
        if connection.execute('PRAGMA foreign_key_check').fetchone() is not None:
            raise InvalidBackup('SQLite foreign-key check failed')
        digest, counts = logical_digest(connection)
        required = {'apps', 'app_versions', 'published_apk_identities', 'published_vpk_identities',
                    'paravoid_contracts', 'paravoid_grants', 'paravoid_streams', 'paravoid_heads',
                    'vpk_releases', 'acquisitions', 'upload_jobs', 'personalization_jobs',
                    'distribution_reviews', 'paravoid_installers'}
        if not required.issubset(counts):
            raise InvalidBackup('Database lacks the current distribution schema')
        checked = 0
        for query in (
            'SELECT apk_path,size,sha256 FROM app_versions',
            'SELECT archive_path,archive_size,archive_sha256 FROM vpk_releases',
        ):
            for path, size, sha256 in connection.execute(query):
                artifact(storage, path, size, sha256)
                checked += 1
        # Personalized acquisitions and upload working files expire and are not
        # immutable releases. Copy the entire storage tree to preserve retries;
        # this tool deliberately reports only retained installer/payload checks.
        return {'version': 1, 'database_sha256': digest, 'table_counts': counts,
                'retained_artifacts_verified': checked}
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', required=True, type=pathlib.Path)
    parser.add_argument('--storage', required=True, type=pathlib.Path)
    parser.add_argument('--expected-state', type=pathlib.Path,
                        help='Independently retained JSON checkpoint from the same quiesced snapshot')
    args = parser.parse_args()
    try:
        result = verify(args.database, args.storage)
        if args.expected_state is not None:
            with args.expected_state.open() as source:
                expected = json.load(source)
            if expected != result:
                raise InvalidBackup('Restored state differs from the retained checkpoint; do not serve it')
        print(json.dumps(result, indent=2, sort_keys=True))
    except (InvalidBackup, OSError, sqlite3.Error, ValueError) as error:
        # Do not print database rows, file contents or exception parameters.
        print(str(error) if isinstance(error, InvalidBackup) else 'Snapshot verification failed', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
