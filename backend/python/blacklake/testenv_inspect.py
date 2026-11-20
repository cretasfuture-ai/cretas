"""Read-only WSL test-environment metadata; never print configuration values."""
import json
from pathlib import Path
import psycopg2


def environment():
    path = Path('/opt/cretas-testenv/testenv.env')
    values = {}
    for line in path.read_text().splitlines():
        if '=' in line and not line.startswith('#'):
            key, value = line.split('=', 1)
            values[key] = value.strip().strip('"').strip("'")
    if values.get('PYTHON_ENV') != 'test' or values.get('DB_HOST') != '127.0.0.1' or values.get('DB_NAME') != 'cretas_testenv':
        raise RuntimeError('VERIFIED_TEST_ENVIRONMENT_REQUIRED')
    return values


if __name__ == '__main__':
    saved = environment()
    connection = psycopg2.connect(host=saved['DB_HOST'], port=saved['DB_PORT'], dbname=saved['DB_NAME'],
                                  user='cretas_user', password=saved['DB_PASSWORD'])
    with connection:
        with connection.cursor() as cur:
            cur.execute("SELECT table_name,column_name,data_type,is_nullable,column_default "
                        "FROM information_schema.columns WHERE table_schema='public' "
                        "AND table_name IN ('factories','users','platform_admins') ORDER BY table_name,ordinal_position")
            print(json.dumps({'columns':cur.fetchall()},default=str))
    connection.close()
