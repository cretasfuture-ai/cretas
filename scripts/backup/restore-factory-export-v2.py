#!/usr/bin/env python3
"""Fail-closed restore for a Cretas factory export v2 package.

The package carries tenant rows and safe source descriptors only.  A protected
pg_dump custom archive supplies the complete schema, constraints, functions and
migrations.  This command never invents DDL, never disables constraints, and
never chooses a production target implicitly.
"""

from __future__ import annotations

import argparse
from collections import Counter
from decimal import Decimal, InvalidOperation
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import zipfile
import copy
from datetime import date, datetime, time, timezone
from pathlib import Path, PurePosixPath
from typing import Any

import psycopg2
from psycopg2 import sql


class RestoreRefused(RuntimeError):
    pass


def fail(message: str) -> RestoreRefused:
    return RestoreRefused(message)


def _reject_constant(value: str) -> Any:
    raise ValueError(f"非有限 JSON 数值: {value}")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("重复 JSON 字段")
        result[key] = value
    return result


def parse_json_bytes(body: bytes, source: str) -> Any:
    try:
        return json.loads(body.decode("utf-8"), parse_float=Decimal, parse_int=int,
                          parse_constant=_reject_constant, object_pairs_hook=_unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError, InvalidOperation) as exc:
        raise fail(f"JSON 数值或编码不受支持: {source}") from exc


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = parse_json_bytes(path.read_bytes(), path.name)
    except (OSError, RestoreRefused) as exc:
        raise fail(f"无法读取 JSON: {path.name}") from exc
    if not isinstance(value, dict):
        raise fail(f"JSON 根节点不是对象: {path.name}")
    return value


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def verify_package(package: Path) -> tuple[dict[str, Any], zipfile.ZipFile]:
    if not package.is_file():
        raise fail("包文件不存在")
    try:
        archive = zipfile.ZipFile(package)
        infos = archive.infolist()
        names = [item.filename for item in infos]
        if len(names) != len(set(names)) or names.count("manifest.json") != 1:
            raise fail("ZIP 条目重复或缺少唯一 manifest.json")
        if any(item.is_dir() or item.flag_bits & 1 for item in infos):
            raise fail("ZIP 目录或加密条目不受支持")
        manifest = parse_json_bytes(archive.read("manifest.json"), "manifest.json")
    except (OSError, KeyError, zipfile.BadZipFile, RestoreRefused) as exc:
        raise fail("导出包损坏或缺少 manifest.json") from exc
    if not isinstance(manifest, dict) or manifest.get("format") != "cretas-factory-export/2":
        raise fail("仅支持 cretas-factory-export/2")
    redaction = manifest.get("redaction", {})
    if redaction.get("algorithm") != "HMAC-SHA256" or redaction.get("keyExported") is not False:
        raise fail("脱敏契约未知或 key 被错误导出")
    entries = manifest.get("files")
    if not isinstance(entries, list) or not all(isinstance(item, dict) for item in entries):
        raise fail("manifest.files 格式错误")
    paths = [entry.get("path") for entry in entries]
    if any(not isinstance(path, str) for path in paths) or len(paths) != len(set(paths)):
        raise fail("manifest.files 存在重复或非法路径")
    listed = set(paths)
    actual = set(archive.namelist()) - {"manifest.json"}
    if listed != actual:
        raise fail("manifest.files 与 ZIP 实际文件集合不一致")
    for entry in entries:
        path = entry.get("path")
        if (not isinstance(path, str) or not path or "\\" in path or ":" in path
                or PurePosixPath(path).is_absolute()
                or any(part in {".", "..", ""} for part in path.split("/"))):
            raise fail("包内路径不安全")
        body = archive.read(path)
        if sha256_bytes(body) != entry.get("sha256"):
            raise fail(f"包内文件校验失败: {path}")
        if type(entry.get("bytes")) is not int or entry["bytes"] != len(body):
            raise fail(f"包内文件大小不一致: {path}")
    databases = manifest.get("databases")
    if (not isinstance(databases, list) or len(databases) != 2
            or any(not isinstance(item, dict) for item in databases)
            or {item.get("database") for item in databases} != {"CRETAS", "SMARTBI"}):
        raise fail("必须明确且唯一地登记 CRETAS/SMARTBI 两库")
    if not isinstance(manifest.get("factoryId"), str) or not manifest["factoryId"].strip():
        raise fail("缺少明确的工厂身份")
    for database in ("cretas", "smartbi"):
        for metadata in ("schema", "migrations"):
            if f"{database}/metadata/{metadata}.json" not in listed:
                raise fail("缺少已登记的源结构或迁移元数据")
    return manifest, archive


def safe_db_name(value: str) -> None:
    if not value or not (value.startswith("restore_") or value.startswith("cretas_restore_")):
        raise fail("目标数据库必须是 restore_ / cretas_restore_ 前缀")
    if not value.replace("_", "").isalnum():
        raise fail("目标数据库名不安全")


def run_tool(pg_bin: Path, tool: str, args: list[str]) -> str:
    executable = pg_bin / (tool + (".exe" if os.name == "nt" else ""))
    if not executable.is_file():
        raise fail(f"缺少 PostgreSQL 工具: {tool}")
    result = subprocess.run([str(executable), *args], capture_output=True, text=True, check=False)
    if result.returncode:
        # Do not include stderr: it may contain a caller-supplied connection string.
        raise fail(f"{tool} 失败，退出码 {result.returncode}")
    return result.stdout


def restore_args(args: argparse.Namespace, database: str, baseline: Path, section: str) -> list[str]:
    dbname = args.cretas_target_db if database == "CRETAS" else args.smartbi_target_db
    return ["--host", args.target_host, "--port", str(args.target_port), "--username", args.target_user,
            "--no-owner", "--no-acl", "--exit-on-error", f"--section={section}", "--dbname", dbname,
            str(baseline)]


def baseline_info(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise fail(f"缺少受保护 schema baseline: {path.name}")
    sidecar = path.with_suffix(path.suffix + ".json")
    info = read_json(sidecar)
    if info.get("format") != "cretas-factory-schema-baseline/1":
        raise fail(f"baseline sidecar 格式不受支持: {sidecar.name}")
    if info.get("archiveSha256") != sha256_bytes(path.read_bytes()):
        raise fail(f"baseline archive 校验失败: {path.name}")
    if info.get("schemaFingerprint") is None or info.get("migrationFingerprint") is None or info.get("postgresMajor") is None:
        raise fail(f"baseline 缺少结构/迁移指纹: {sidecar.name}")
    return info


def metadata_for(archive: zipfile.ZipFile, database: str) -> tuple[dict[str, Any], dict[str, Any]]:
    prefix = database.lower()
    schema = parse_json_bytes(archive.read(f"{prefix}/metadata/schema.json"), f"{prefix}/metadata/schema.json")
    migrations = parse_json_bytes(archive.read(f"{prefix}/metadata/migrations.json"), f"{prefix}/metadata/migrations.json")
    if schema.get("restoreContract") != "COMPATIBLE_SCHEMA_REQUIRED":
        raise fail(f"{database} 缺少兼容 schema contract")
    if schema.get("rawDdlIncluded") is not False:
        raise fail(f"{database} 包含未允许的 raw DDL")
    if not schema.get("fingerprint") or not migrations.get("fingerprint"):
        raise fail(f"{database} 缺少结构/迁移指纹")
    if schema.get("snapshot", {}).get("crossDatabaseAtomic") is not False:
        raise fail(f"{database} 错误宣称跨数据库原子快照")
    if schema.get("externalReferences"):
        raise fail(f"{database} 存在包外 FK 依赖，必须提供兼容依赖基线")
    return schema, migrations


def validate_database_manifest(archive: zipfile.ZipFile, manifest: dict[str, Any], database: str,
                               schema: dict[str, Any]) -> None:
    db = next((item for item in manifest.get("databases", []) if item.get("database") == database), None)
    if not isinstance(db, dict):
        raise fail(f"manifest 缺少 {database}")
    table_entries = db.get("tables")
    schema_entries = schema.get("tables")
    if (not isinstance(table_entries, list) or not isinstance(schema_entries, list)
            or any(not isinstance(item, dict) for item in table_entries + schema_entries)):
        raise fail("表清单格式错误")
    declared = {item.get("table"): item for item in table_entries}
    source = {item.get("table"): item for item in schema_entries}
    if len(declared) != len(table_entries) or len(source) != len(schema_entries):
        raise fail("重复表清单")
    exported_categories = {"BUSINESS", "FACTORY_ROOT", "AUDIT", "TRAINING"}
    if any(item.get("category") not in exported_categories | {"COPY", "RECEIPT", "LEDGER"}
           for item in schema_entries):
        raise fail("未知表分类不能推测导出范围")
    exported = {name for name, item in source.items() if item["category"] in exported_categories}
    if set(declared) != exported:
        raise fail(f"{database} manifest.tables 与 schema.tables 不闭合")
    for table, item in declared.items():
        rows = table_json(archive, database, table)
        if (type(item.get("rows")) is not int or item["rows"] != len(rows)
                or source[table].get("rows") != len(rows)
                or item.get("category") != source[table]["category"]):
            raise fail(f"{database}.{table} 声明行数不一致")
        files = item.get("files", [])
        expected_path = f"{database.lower()}/json/{table}.json"
        if (not isinstance(files, list) or any(not isinstance(file, dict) for file in files)
                or sum(file.get("path") == expected_path for file in files) != 1):
            raise fail(f"{database}.{table} 缺少 JSON 文件声明")
        global_files = {file["path"]: file for file in manifest["files"]}
        if len({file.get("path") for file in files}) != len(files):
            raise fail(f"{database}.{table} 重复文件引用")
        for file in files:
            registered = global_files.get(file.get("path")) if isinstance(file, dict) else None
            if (not registered or file.get("sha256") != registered["sha256"]
                    or file.get("path") not in {expected_path, f"{database.lower()}/csv/{table}.csv"}):
                raise fail(f"{database}.{table} 文件描述不一致")
        if global_files[expected_path].get("rows") != len(rows):
            raise fail(f"{database}.{table} JSON 文件行数与表不一致")
        columns = [column["column"] for column in schema.get("columns", [])
                   if column.get("table") == table]
        if not columns or len(columns) != len(set(columns)):
            raise fail(f"{database}.{table} 列描述缺失或重复")
        if any(set(row) != set(columns) for row in rows):
            raise fail(f"{database}.{table} JSON 字段与源列不闭合")
        if source[table]["category"] == "FACTORY_ROOT":
            primary_key = source[table].get("primaryKeyColumn")
            if len(rows) != 1 or rows[0].get(primary_key) != manifest["factoryId"]:
                raise fail("工厂根记录与请求工厂不一致")
    expected_json = {f"{database.lower()}/json/{table}.json" for table in exported}
    actual_json = {file["path"] for file in manifest["files"]
                   if file["path"].startswith(f"{database.lower()}/json/")}
    if expected_json != actual_json:
        raise fail(f"{database} JSON 文件与完整表清单不闭合")
    if type(db.get("rows")) is not int or db["rows"] != sum(item["rows"] for item in declared.values()):
        raise fail(f"{database} 总行数不一致")
    if type(db.get("foreignHitCount")) is not int or db["foreignHitCount"] != 0:
        raise fail(f"{database} 源范围已有跨工厂命中，不能宣称隔离还原")
    for key, kind in (("sourceSchema", "schema"), ("migrationMetadata", "migrations")):
        path = f"{database.lower()}/metadata/{kind}.json"
        descriptor = db.get(key, {})
        registered = next(file for file in manifest["files"] if file["path"] == path)
        if (descriptor.get("path") != path or descriptor.get("sha256") != registered["sha256"]
                or descriptor.get("sourceTime") is not True):
            raise fail(f"{database} 元数据引用不一致")


def connect(args: argparse.Namespace, database: str):
    dbname = args.cretas_target_db if database == "CRETAS" else args.smartbi_target_db
    safe_db_name(dbname)
    conn = psycopg2.connect(host=args.target_host, port=args.target_port, dbname=dbname,
                             user=args.target_user, connect_timeout=10)
    try:
        verify_owned_connection(conn, args)
        with conn.cursor() as cur:
            cur.execute("SELECT pg_get_userbyid(datdba)=current_user FROM pg_database WHERE datname=current_database()")
            if cur.fetchone()[0] is not True:
                raise fail("目标数据库不属于指定恢复角色")
    except Exception:
        conn.close()
        raise
    return conn


def process_identity(pid: int) -> dict[str, Any]:
    """Read OS identity without signals, including Windows where kill(0) is unsafe."""
    if type(pid) is not int or pid <= 1:
        raise fail("无效的私有 postmaster PID")
    if os.name == "nt":
        import ctypes
        from ctypes import wintypes
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
        kernel.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                                     wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            raise fail("无法只读核验私有 postmaster 进程")
        try:
            creation, exit_time, system_time, user_time = (wintypes.FILETIME() for _ in range(4))
            if not kernel.GetProcessTimes(handle, ctypes.byref(creation), ctypes.byref(exit_time),
                                          ctypes.byref(system_time), ctypes.byref(user_time)):
                raise fail("无法读取 postmaster 创建时间")
            size = wintypes.DWORD(32768)
            buffer = ctypes.create_unicode_buffer(size.value)
            if not kernel.QueryFullProcessImageNameW(handle, 0, buffer, ctypes.byref(size)):
                raise fail("无法读取 postmaster 可执行文件")
            return {"pid": pid, "startToken": str((creation.dwHighDateTime << 32) | creation.dwLowDateTime),
                    "executable": os.path.normcase(str(Path(buffer.value).resolve()))}
        finally:
            kernel.CloseHandle(handle)
    stat_path = Path(f"/proc/{pid}/stat")
    try:
        start_token = stat_path.read_text().rsplit(")", 1)[1].split()[19]
        executable = str(Path(f"/proc/{pid}/exe").resolve(strict=True))
    except (OSError, IndexError) as exception:
        raise fail("当前系统不能只读核验 postmaster 身份") from exception
    return {"pid": pid, "startToken": start_token, "executable": executable}


def verify_owned_connection(conn, args: argparse.Namespace) -> dict[str, Any]:
    receipt_path = getattr(args, "ownership_receipt", None)
    if receipt_path is None:
        raise fail("实际还原必须提供新建私有 PG 的 ownership receipt")
    owner = read_json(Path(receipt_path))
    if (owner.get("format") != "cretas-owned-restore-pg/1"
            or owner.get("purpose") != "factory-export-restore"
            or not isinstance(owner.get("nonce"), str)
            or re.fullmatch(r"[0-9a-f]{64}", owner["nonce"]) is None
            or not owner.get("taskId") or not owner.get("ownerThreadId")
            or owner.get("authMethod") != "scram-sha-256"):
        raise fail("私有 PG ownership receipt 不完整")
    with conn.cursor() as cur:
        cur.execute("""SELECT system_identifier::text,current_setting('data_directory'),
            extract(epoch FROM pg_postmaster_start_time())::text,host(inet_server_addr()),
            inet_server_port(),current_user,pg_is_in_recovery(),
            current_setting('primary_conninfo',true),current_setting('restore_command',true),
            current_setting('archive_mode'),current_setting('listen_addresses')
            FROM pg_control_system()""")
        actual = cur.fetchone()
    if actual[0] != str(owner.get("systemIdentifier")) or actual[0] != str(args.target_system_id):
        raise fail("目标 system identifier 与独立初始化回执不符")
    data_directory = Path(actual[1]).resolve(strict=True)
    expected_directory = Path(str(owner.get("dataDirectory", ""))).resolve(strict=True)
    if data_directory != expected_directory:
        raise fail("目标真实 PGDATA 与 ownership receipt 不符")
    marker = read_json(data_directory / ".cretas-restore-owner.json")
    for key in ("format", "purpose", "nonce", "taskId", "ownerThreadId", "systemIdentifier", "dataDirectory", "authMethod"):
        if marker.get(key) != owner.get(key):
            raise fail("PGDATA ownership nonce/来源不匹配")
    if (actual[3] not in {"127.0.0.1", "::1"} or actual[4] != args.target_port
            or actual[5] != args.target_user or owner.get("port") != args.target_port
            or owner.get("databaseUser") != args.target_user):
        raise fail("目标真实地址/端口/角色不是登记的私有实例")
    if actual[6] or actual[7] or actual[8] or actual[9] != "off":
        raise fail("恢复目标存在复制/归档配置，拒绝写入")
    if not set(item.strip() for item in actual[10].split(",")) <= {"127.0.0.1", "::1", "localhost"}:
        raise fail("私有实例暴露于非 loopback 地址")
    with conn.cursor() as cur:
        cur.execute("SELECT auth_method,error FROM pg_hba_file_rules")
        rules = cur.fetchall()
        if not rules or any(method != "scram-sha-256" or error for method, error in rules):
            raise fail("私有实例 HBA 不是完整 SCRAM 认证策略")
        cur.execute("SELECT rolpassword LIKE 'SCRAM-SHA-256$%%' FROM pg_authid WHERE rolname=current_user")
        if cur.fetchone()[0] is not True:
            raise fail("私有实例角色没有 SCRAM 密码验证器")
    pid_lines = (data_directory / "postmaster.pid").read_text().splitlines()
    if (len(pid_lines) < 4 or int(pid_lines[0]) != owner.get("pid")
            or Path(pid_lines[1]).resolve(strict=True) != data_directory
            or pid_lines[2] != str(owner.get("pidStartEpoch"))
            or int(pid_lines[3]) != args.target_port
            or actual[2] != str(owner.get("sqlStartEpoch"))):
        raise fail("私有 postmaster PID/启动时间已变化")
    identity = process_identity(int(pid_lines[0]))
    if identity != owner.get("processIdentity"):
        raise fail("postmaster 进程已退出或 PID 被复用")
    executable = Path(args.pg_bin) / ("postgres.exe" if os.name == "nt" else "postgres")
    if identity["executable"] != os.path.normcase(str(executable.resolve(strict=True))):
        raise fail("postmaster 不属于指定 PostgreSQL 安装")
    return owner


def quote_table(table: str) -> sql.Identifier:
    if not table or not table.replace("_", "").isalnum():
        raise fail(f"不安全表名: {table}")
    return sql.Identifier(table)


def table_json(archive: zipfile.ZipFile, database: str, table: str) -> list[dict[str, Any]]:
    value = parse_json_bytes(archive.read(f"{database.lower()}/json/{table}.json"),
                             f"{database.lower()}/json/{table}.json")
    if not isinstance(value, list) or any(not isinstance(row, dict) for row in value):
        raise fail(f"{database}.{table} 行 JSON 不是对象数组")
    return value


def canonical_value(value: Any) -> Any:
    if value is None:
        return ["null"]
    if isinstance(value, bool):
        return ["bool", value]
    if isinstance(value, int) and not isinstance(value, bool):
        return ["int", str(value)]
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise fail("非有限 NUMERIC")
        sign, digits, exponent = value.as_tuple()
        digits = list(digits)
        while len(digits) > 1 and digits[-1] == 0:
            digits.pop()
            exponent += 1
        if not any(digits):
            sign, digits, exponent = 0, [0], 0
        return ["decimal", sign, digits, exponent]
    if isinstance(value, float):
        if value != value or value in (float("inf"), float("-inf")):
            raise fail("检测到 NaN/Infinity，拒绝恢复")
        return ["float64", struct.pack('>d', value).hex()]
    if isinstance(value, (bytes, bytearray, memoryview)):
        return ["bytes", bytes(value).hex()]
    if hasattr(value, "isoformat"):
        return [type(value).__name__, value.isoformat()]
    if isinstance(value, (list, tuple)):
        return ["array", [canonical_value(item) for item in value]]
    if isinstance(value, dict):
        return ["object", [[key, canonical_value(value[key])] for key in sorted(value)]]
    if isinstance(value, str):
        return ["text", value]
    raise fail(f"未绑定的值类型: {type(value).__name__}")


def typed_value(value: Any, descriptor: dict[str, Any]) -> Any:
    """Canonicalize using the source column type, never a lossy str fallback."""
    if value is None:
        return ["sql-null"]
    dtype = descriptor.get("dataType")
    try:
        if dtype in {"smallint", "integer", "bigint"}:
            if type(value) is not int:
                raise ValueError("integer wire type")
            return [dtype, str(value)]
        if dtype in {"numeric", "decimal"}:
            if isinstance(value, bool):
                raise ValueError("boolean is not a decimal")
            return [dtype, canonical_value(Decimal(str(value)))]
        if dtype in {"real", "double precision"}:
            parsed = float(value)
            if parsed != parsed or parsed in (float("inf"), float("-inf")):
                raise ValueError("nonfinite float")
            return [dtype, struct.pack(">f" if dtype == "real" else ">d", parsed).hex()]
        if dtype == "boolean":
            if type(value) is not bool:
                raise ValueError("boolean wire type")
            return [dtype, value]
        if dtype in {"character", "character varying", "text"}:
            if not isinstance(value, str):
                raise ValueError("text wire type")
            return [dtype, value]
        if dtype == "uuid":
            import uuid
            return [dtype, str(uuid.UUID(str(value)))]
        if dtype == "bytea":
            if isinstance(value, str):
                if not value.startswith("\\x"):
                    raise ValueError("bytea requires hexadecimal wire encoding")
                value = bytes.fromhex(value[2:])
            if not isinstance(value, (bytes, bytearray, memoryview)):
                raise ValueError("binary wire type")
            return [dtype, bytes(value).hex()]
        if dtype in {"json", "jsonb"}:
            if not isinstance(value, str):
                raise ValueError("JSON readback must use its exact text representation")
            return [dtype, canonical_value(parse_json_bytes(value.encode("utf-8"), "JSON column"))]
        if dtype == "USER-DEFINED" and descriptor.get("udtName") == "vector":
            if not isinstance(value, str):
                raise ValueError("vector requires PostgreSQL text encoding")
            components = json.loads(value, parse_int=Decimal, parse_float=Decimal,
                                    parse_constant=_reject_constant)
            if not isinstance(components, list) or not components:
                raise ValueError("vector must contain scalar components")
            bits = []
            for component in components:
                if not isinstance(component, Decimal) or not component.is_finite():
                    raise ValueError("vector component must be a finite number")
                packed = struct.pack(">f", float(component))
                if not __import__("math").isfinite(struct.unpack(">f", packed)[0]):
                    raise ValueError("vector float32 component overflow")
                bits.append(packed.hex())
            return ["pgvector-float32", bits]
        if dtype == "date":
            parsed = date.fromisoformat(value) if isinstance(value, str) else value
            if type(parsed) is not date:
                raise ValueError("date wire type")
            return [dtype, parsed.isoformat()]
        if dtype in {"timestamp without time zone", "timestamp with time zone"}:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00")) if isinstance(value, str) else value
            if type(parsed) is not datetime:
                raise ValueError("timestamp wire type")
            aware = parsed.utcoffset() is not None
            if aware != (dtype == "timestamp with time zone"):
                raise ValueError("timestamp zone contract")
            if aware:
                parsed = parsed.astimezone(timezone.utc)
            return [dtype, parsed.isoformat(timespec="microseconds")]
        if dtype in {"time without time zone", "time with time zone"}:
            parsed = time.fromisoformat(value.replace("Z", "+00:00")) if isinstance(value, str) else value
            if type(parsed) is not time or (parsed.utcoffset() is not None) != (dtype == "time with time zone"):
                raise ValueError("time zone contract")
            return [dtype, parsed.isoformat(timespec="microseconds")]
        if dtype == "ARRAY":
            base = {"_int4": "integer", "_int8": "bigint", "_text": "text"}.get(descriptor.get("udtName"))
            if base is None or not isinstance(value, (list, tuple)):
                raise ValueError("unbound array element type")
            def array_node(item):
                if isinstance(item, (list, tuple)):
                    return ["array", [array_node(child) for child in item]]
                return typed_value(item, {"dataType": base})
            return [dtype, array_node(value)]
    except (ValueError, TypeError, OverflowError, InvalidOperation) as exception:
        raise fail(f"{dtype} 列值不能无损解释") from exception
    raise fail(f"未绑定的 PostgreSQL 类型: {dtype}/{descriptor.get('udtName')}")


def row_hash(row: dict[str, Any], columns: list[str], descriptors: dict[str, dict[str, Any]] | None = None) -> str:
    if set(row) != set(columns):
        raise fail("行字段集合与列集合不一致")
    payload = [[column, typed_value(row[column], descriptors[column])
                if descriptors is not None else canonical_value(row[column])]
               for column in columns]
    # A structured encoding supplies unambiguous boundaries for both names and
    # nested values; user text containing '|', '=', ',' cannot collide.
    encoded = json.dumps(payload, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def multiset_hash(rows: list[dict[str, Any]], columns: list[str], descriptors: dict[str, dict[str, Any]] | None = None) -> str:
    return hashlib.sha256("\n".join(sorted(row_hash(row, columns, descriptors) for row in rows))
                         .encode("ascii")).hexdigest()


def bind_value(value: Any, descriptor: dict[str, Any]) -> Any:
    typed_value(value, descriptor)  # Refuse unbound/lossy inputs before INSERT.
    if value is None:
        return None
    dtype = descriptor["dataType"]
    if dtype == "bytea":
        raw = bytes.fromhex(value[2:]) if isinstance(value, str) else bytes(value)
        return psycopg2.Binary(raw)
    if dtype in {"numeric", "decimal"}:
        return Decimal(str(value))
    if dtype in {"real", "double precision"}:
        # A quoted input string is parsed directly as float4/float8. Going
        # through a NUMERIC literal would lose the sign of negative zero.
        return str(value)
    return value


def sha256_text(value: Any) -> str | None:
    if value is None:
        return None
    return hashlib.sha256(str(value).encode("utf-8")).hexdigest()


def read_target_structure(conn, schema: dict[str, Any]) -> dict[str, Any]:
    source_schema = schema.get("sourceSchema", "public")
    names = sorted({str(item.get("table")) for item in schema.get("tables", [])}
                   | {str(item.get("table")) for item in schema.get("columns", [])})
    if not names:
        raise fail("结构描述没有表")
    placeholders = ",".join("%s" for _ in names)
    with conn.cursor() as cur:
        cur.execute("SELECT table_name,column_name,ordinal_position,data_type,udt_name,is_nullable,"
                    "column_default,character_maximum_length,numeric_precision,numeric_scale,datetime_precision,"
                    "is_identity,identity_generation,is_generated,generation_expression,format_type(pa.atttypid,pa.atttypmod) "
                    "FROM information_schema.columns c "
                    "LEFT JOIN pg_namespace pn ON pn.nspname=c.table_schema "
                    "LEFT JOIN pg_class pc ON pc.relnamespace=pn.oid AND pc.relname=c.table_name "
                    "LEFT JOIN pg_attribute pa ON pa.attrelid=pc.oid AND pa.attname=c.column_name AND NOT pa.attisdropped "
                    "WHERE table_schema=%s AND table_name IN (" + placeholders + ") "
                    "ORDER BY table_name,ordinal_position", [source_schema, *names])
        columns = []
        for row in cur.fetchall():
            columns.append({"table": row[0], "column": row[1], "ordinal": row[2], "dataType": row[3],
                            "udtName": row[4], "nullable": row[5] == "YES", "defaultFingerprint": sha256_text(row[6]),
                            "characterMaxLength": row[7], "numericPrecision": row[8], "numericScale": row[9],
                            "datetimePrecision": row[10], "identity": row[11] == "YES",
                            "identityGeneration": row[12], "generated": row[13] == "ALWAYS",
                            "generationFingerprint": sha256_text(row[14]),
                            "typeSignatureFingerprint": sha256_text(row[15])})
        if any(c["dataType"] == "USER-DEFINED" and c["udtName"] == "vector" for c in columns):
            cur.execute("SELECT count(*) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid "
                        "JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_type t ON t.oid=a.atttypid "
                        "WHERE n.nspname=%s AND c.relname IN (" + placeholders + ") AND t.typname='vector' "
                        "AND NOT EXISTS(SELECT 1 FROM pg_depend d JOIN pg_extension e ON e.oid=d.refobjid "
                        "WHERE d.objid=t.oid AND d.classid='pg_type'::regclass AND d.deptype='e' AND e.extname='vector')",
                        [source_schema, *names])
            if cur.fetchone()[0] != 0:
                raise fail("目标 vector 类型不属于真实 pgvector extension")
        cur.execute("SELECT count(*) FROM information_schema.table_constraints WHERE table_schema=%s AND table_name IN (" + placeholders + ")",
                    [source_schema, *names])
        constraint_count = cur.fetchone()[0]
        cur.execute("SELECT tc.table_name,tc.constraint_name,tc.constraint_type,kcu.column_name,kcu.ordinal_position,"
                    "ccu.table_name,ccu.column_name,rc.update_rule,rc.delete_rule,"
                    "(SELECT md5(pg_get_constraintdef(pc.oid)) FROM pg_constraint pc JOIN pg_class pr ON pr.oid=pc.conrelid "
                    "JOIN pg_namespace pn ON pn.oid=pr.relnamespace WHERE pn.nspname=tc.table_schema AND pr.relname=tc.table_name "
                    "AND pc.conname=tc.constraint_name LIMIT 1) FROM information_schema.table_constraints tc "
                    "LEFT JOIN information_schema.key_column_usage kcu ON kcu.constraint_schema=tc.constraint_schema "
                    "AND kcu.constraint_name=tc.constraint_name AND kcu.table_name=tc.table_name "
                    "LEFT JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_schema=tc.constraint_schema "
                    "AND ccu.constraint_name=tc.constraint_name LEFT JOIN information_schema.referential_constraints rc "
                    "ON rc.constraint_schema=tc.constraint_schema AND rc.constraint_name=tc.constraint_name "
                    "WHERE tc.table_schema=%s AND tc.table_name IN (" + placeholders + ") "
                    "ORDER BY tc.table_name,tc.constraint_name,kcu.ordinal_position", [source_schema, *names])
        constraints = [{"table": r[0], "name": "__NOT_NULL__" if r[2] == "CHECK" and r[1] and r[1].endswith("_not_null") and r[1].split("_")[0].isdigit() else r[1], "type": r[2], "column": r[3], "ordinal": r[4],
                        "referencedTable": r[5], "referencedColumn": r[6], "updateRule": r[7], "deleteRule": r[8],
                        "definitionFingerprint": r[9]} for r in cur.fetchall()]
        constraints.sort(key=lambda item: (str(item.get("table")), str(item.get("name")), str(item.get("column")),
                                           str(item.get("ordinal")), str(item.get("referencedColumn"))))
        cur.execute("SELECT t.relname,i.relname,x.indisunique,x.indisprimary,x.indisvalid,md5(pg_get_indexdef(i.oid)),"
                    "array_remove(array_agg(a.attname ORDER BY k.ordinality),NULL) FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace "
                    "JOIN pg_index x ON x.indrelid=t.oid JOIN pg_class i ON i.oid=x.indexrelid LEFT JOIN LATERAL "
                    "unnest(x.indkey) WITH ORDINALITY k(attnum,ordinality) ON TRUE LEFT JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=k.attnum "
                    "WHERE n.nspname=%s AND t.relname IN (" + placeholders + ") GROUP BY t.relname,i.relname,i.oid,x.indisunique,x.indisprimary,x.indisvalid "
                    "ORDER BY t.relname,i.relname", [source_schema, *names])
        indexes = [{"table": r[0], "name": r[1], "unique": r[2], "primary": r[3], "valid": r[4],
                    "fingerprint": r[5], "columns": r[6] or []} for r in cur.fetchall()]
        cur.execute("SELECT c.relname,t.tgname,p.proname,md5(pg_get_functiondef(t.tgfoid)),md5(pg_get_triggerdef(t.oid)) "
                    "FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_proc p ON p.oid=t.tgfoid "
                    "WHERE n.nspname=%s AND NOT t.tgisinternal AND c.relname IN (" + placeholders + ") ORDER BY c.relname,t.tgname",
                    [source_schema, *names])
        triggers = [{"table": r[0], "name": r[1], "function": r[2], "functionFingerprint": r[3], "triggerFingerprint": r[4]}
                    for r in cur.fetchall()]
        cur.execute("SELECT s.sequence_name,s.data_type,s.start_value,s.minimum_value,s.maximum_value,s.increment,s.cycle_option,p.cache_size,p.last_value,"
                    "t.relname,a.attname FROM information_schema.sequences s JOIN pg_namespace sn ON sn.nspname=s.sequence_schema JOIN pg_class seq "
                    "ON seq.relnamespace=sn.oid AND seq.relname=s.sequence_name LEFT JOIN pg_sequences p ON p.schemaname=s.sequence_schema "
                    "AND p.sequencename=s.sequence_name LEFT JOIN pg_depend d ON d.objid=seq.oid AND d.deptype='a' LEFT JOIN pg_class t ON t.oid=d.refobjid "
                    "LEFT JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=d.refobjsubid WHERE s.sequence_schema=%s ORDER BY s.sequence_name", (source_schema,))
        sequences = []
        for r in cur.fetchall():
            cur.execute(sql.SQL("SELECT is_called FROM {}.{} ").format(sql.Identifier(source_schema), sql.Identifier(r[0])))
            sequences.append({"name": r[0], "dataType": r[1], "startValue": r[2], "minimumValue": r[3], "maximumValue": r[4],
                              "increment": r[5], "cycle": r[6], "cacheSize": r[7], "lastValue": r[8], "isCalled": cur.fetchone()[0],
                              "ownedTable": r[9], "ownedColumn": r[10]})
    return {"columns": columns, "constraints": constraints, "indexes": indexes,
            "triggers": triggers, "sequences": sequences}


def structural_fingerprint(conn, schema: dict[str, Any]) -> str:
    actual_structure = read_target_structure(conn, schema)
    result = copy.deepcopy(schema)
    columns = actual_structure["columns"]
    if len(columns) != len(schema.get("columns", [])):
        raise fail("目标列结构与 schema 指纹描述不一致")
    # Compare every independently queried definition/property. In particular,
    # never omit CHECK or trigger/function hashes merely to accommodate a
    # formatting difference: a same-name changed definition must be refused.
    for label in ("constraints", "indexes", "triggers", "sequences"):
        actual = actual_structure[label]
        if actual != schema.get(label, []):
            raise fail(f"目标 {label} 完整定义与源描述不一致")
        result[label] = actual
    result["columns"] = columns
    stable = {k: v for k, v in result.items() if k not in {"fingerprint", "snapshot", "tables"}}
    stable_tables = []
    for table in result.get("tables", []):
        stable_tables.append({k: v for k, v in table.items() if k not in {"rows", "depth"}})
    stable["tables"] = stable_tables
    encoded = json.dumps(stable, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def import_rows(conn, archive: zipfile.ZipFile, database: str, manifest: dict[str, Any], schema: dict[str, Any]) -> None:
    factory_id = manifest.get("factoryId")
    columns_by_table: dict[str, list[dict[str, Any]]] = {}
    for column in schema.get("columns", []):
        columns_by_table.setdefault(column["table"], []).append(column)
    for table_entry in next(d for d in manifest["databases"] if d["database"] == database).get("tables", []):
        table = table_entry["table"]
        rows = table_json(archive, database, table)
        columns = columns_by_table.get(table, [])
        if not columns:
            raise fail(f"{database}.{table} 缺少列描述")
        names = [c["column"] for c in columns]
        generated = [c for c in columns if c.get("generated") is True]
        if any(c.get("dataType") == "USER-DEFINED" and c.get("udtName") != "vector" for c in columns):
            raise fail(f"{database}.{table} 含未绑定的 USER-DEFINED 类型，拒绝恢复")
        insert_columns = [c for c in columns if c.get("generated") is not True]
        insert_names = [c["column"] for c in insert_columns]
        tenant_columns = next((t for t in schema["tables"] if t["table"] == table), {}).get("tenantColumns", [])
        for row in rows:
            for tenant_column in tenant_columns:
                if tenant_column in row and row[tenant_column] is not None and row[tenant_column] != factory_id:
                    raise fail(f"发现跨租户行: {database}.{table}.{tenant_column}")
        if not rows:
            continue
        if generated and any(c.get("generationFingerprint") is None for c in generated):
            raise fail(f"{database}.{table} generated 列缺少表达式指纹")
        identity_always = any(c.get("identityGeneration") == "ALWAYS" for c in insert_columns)
        prefix = sql.SQL("INSERT INTO {} ({}) ").format(
            quote_table(table), sql.SQL(",").join(map(sql.Identifier, insert_names)))
        if identity_always:
            prefix += sql.SQL("OVERRIDING SYSTEM VALUE ")
        statement = prefix + sql.SQL("VALUES ({})").format(
            sql.SQL(",").join(sql.Placeholder() for _ in insert_names))
        with conn.cursor() as cur:
            for row in rows:
                cur.execute(statement, [bind_value(row[c["column"]], c) for c in insert_columns])
    # The caller commits all table/ledger rows together before post-data DDL.


def verify_target(conn, archive: zipfile.ZipFile, database: str, manifest: dict[str, Any], schema: dict[str, Any]) -> None:
    db_manifest = next(d for d in manifest["databases"] if d["database"] == database)
    if structural_fingerprint(conn, schema) != schema.get("fingerprint"):
        raise fail(f"{database} 目标结构指纹不一致")
    # The pre-data archive has now installed the baseline objects; verify every
    # exported row as a typed multiset, not merely a count.
    with conn.cursor() as cur:
        for table_entry in db_manifest.get("tables", []):
            table = table_entry["table"]
            expected_rows = table_json(archive, database, table)
            columns = [c["column"] for c in schema.get("columns", []) if c.get("table") == table]
            descriptors = {c["column"]: c for c in schema.get("columns", []) if c.get("table") == table}
            if not columns:
                raise fail(f"{database}.{table} 缺少列描述")
            expressions = [sql.SQL("{}::text").format(sql.Identifier(column))
                           if descriptors[column]["dataType"] in {"json", "jsonb"}
                           else sql.Identifier(column) for column in columns]
            cur.execute(sql.SQL("SELECT {} FROM {} ").format(
                sql.SQL(",").join(expressions), quote_table(table)))
            actual_rows = [dict(zip(columns, row)) for row in cur.fetchall()]
            expected_hash = multiset_hash(expected_rows, columns, descriptors)
            actual_hash = multiset_hash(actual_rows, columns, descriptors)
            if len(actual_rows) != len(expected_rows) or actual_hash != expected_hash:
                raise fail(f"{database}.{table} 行内容指纹不一致")
        cur.execute("SELECT count(*) FROM pg_constraint WHERE NOT convalidated")
        if cur.fetchone()[0]:
            raise fail(f"{database} 存在未验证约束")
        cur.execute("SELECT count(*) FROM pg_class WHERE relkind = 'S'")
        sequence_count = cur.fetchone()[0]
        expected_sequences = schema.get("sequences", [])
        if sequence_count < len(expected_sequences):
            raise fail(f"{database} 序列数量不足")
        for sequence in expected_sequences:
            name = sequence.get("name")
            cur.execute(sql.SQL("SELECT last_value, is_called FROM {} ").format(quote_table(name)))
            state = cur.fetchone()
            if state is None or (sequence.get("lastValue") is not None and str(state[0]) != str(sequence["lastValue"])) \
                    or (sequence.get("isCalled") is not None and bool(state[1]) != bool(sequence["isCalled"])):
                raise fail(f"{database}.{name} 序列状态不一致")


def ensure_empty_target(conn, database: str) -> None:
    with conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                    "WHERE n.nspname <> 'information_schema' AND n.nspname !~ '^pg_' "
                    "AND c.relkind IN ('r','p','S','v','m','f')")
        if cur.fetchone()[0] != 0:
            raise fail(f"{database} 目标不是空库，拒绝覆盖既有对象")
        cur.execute("SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                    "WHERE n.nspname <> 'information_schema' AND n.nspname !~ '^pg_'")
        if cur.fetchone()[0] != 0:
            raise fail(f"{database} 目标已有用户函数，拒绝覆盖")


def set_target_schema(conn, schema_name: str) -> None:
    if not isinstance(schema_name, str) or not schema_name.replace("_", "").isalnum():
        raise fail("目标 schema 名称不安全")
    with conn.cursor() as cur:
        cur.execute(sql.SQL("SET search_path TO {}, pg_catalog").format(sql.Identifier(schema_name)))


def verify_migration_presence(conn, migrations: dict[str, Any], schema_name: str = "public") -> None:
    if not migrations.get("available"):
        raise fail("源库没有可验证的 migration ledger")
    ledger = migrations.get("ledgerTable")
    if not isinstance(ledger, str) or not ledger:
        raise fail("migration ledger 缺少明确表名")
    with conn.cursor() as cur:
        cur.execute("SELECT to_regclass(%s)", (f"{schema_name}.{ledger}",))
        if cur.fetchone()[0] is None:
            raise fail(f"目标缺少 migration ledger: {ledger}")


def restore_migrations(conn, migrations: dict[str, Any], schema_name: str = "public") -> None:
    if not migrations.get("available"):
        raise fail("源库没有可验证的 migration ledger")
    ledger = migrations.get("ledgerTable")
    rows = migrations.get("rows")
    if not isinstance(ledger, str) or not isinstance(rows, list):
        raise fail("migration ledger 内容不完整")
    table = sql.Identifier(schema_name, ledger)
    if migrations.get("ledgerKind") == "SMARTBI":
        fields = ["filename", "version", "checksum", "applied_at", "applied_by", "duration_ms"]
        statement = sql.SQL("INSERT INTO {} ({}) VALUES ({})").format(
            table, sql.SQL(",").join(map(sql.Identifier, fields)),
            sql.SQL(",").join(sql.Placeholder() for _ in fields))
        with conn.cursor() as cur:
            for row in rows:
                cur.execute(statement, [row.get("script"), row.get("version"), row.get("checksum"),
                                        row.get("installedOn"), row.get("installedBy"), row.get("executionTime")])
        return
    with conn.cursor() as cur:
        cur.execute("SELECT column_name FROM information_schema.columns "
                    "WHERE table_schema=%s AND table_name=%s ORDER BY ordinal_position",
                    (schema_name, ledger))
        actual_columns = [row[0] for row in cur.fetchall()]
        required = ["installed_rank", "version", "description", "type", "script", "checksum",
                    "installed_by", "installed_on", "execution_time", "success"]
        if not set(required).issubset(actual_columns):
            raise fail(f"migration ledger 列结构不兼容: {ledger}")
        fields = ["installed_rank", "version", "description", "type", "script", "checksum",
                  "installed_by", "installed_on", "execution_time", "success"]
        statement = sql.SQL("INSERT INTO {} ({}) VALUES ({})").format(
            table, sql.SQL(",").join(map(sql.Identifier, fields)),
            sql.SQL(",").join(sql.Placeholder() for _ in fields))
        for row in rows:
            values = [row.get("installedRank"), row.get("version"), row.get("description"),
                      row.get("type"), row.get("script"), row.get("checksum"),
                      row.get("installedBy") or "factory-export-v2", row.get("installedOn"),
                      row.get("executionTime"), row.get("success")]
            cur.execute(statement, values)


def restore_sequence_state(conn, schema: dict[str, Any], schema_name: str = "public") -> None:
    for sequence in schema.get("sequences", []):
        name = sequence.get("name")
        if not isinstance(name, str):
            raise fail("序列描述缺少名称")
        if sequence.get("lastValue") is not None:
            with conn.cursor() as cur:
                cur.execute("SELECT setval(%s::regclass, %s, %s)",
                            (f"{schema_name}.{name}", sequence.get("lastValue"),
                             bool(sequence.get("isCalled"))))


def migration_digest(rows: list[dict[str, Any]]) -> str:
    fields = ["installedRank", "version", "description", "type", "script", "checksum",
              "installedBy", "installedOn", "executionTime", "success"]
    lines = []
    for row in rows:
        normalized = dict(row)
        if isinstance(normalized.get("installedOn"), str):
            value = normalized["installedOn"].replace("T", " ", 1)
            try:
                parsed = datetime.fromisoformat(value)
                if parsed.tzinfo is None:
                    parsed = parsed.replace(tzinfo=timezone.utc)
                normalized["installedOn"] = parsed.astimezone(timezone.utc).isoformat()
            except ValueError:
                normalized["installedOn"] = value
        lines.append([[field, canonical_value(normalized.get(field))] for field in fields])
    return hashlib.sha256(json.dumps(lines, ensure_ascii=False, separators=(",", ":")).encode("utf-8")).hexdigest()


def verify_migration_rows(conn, migrations: dict[str, Any], schema_name: str = "public") -> None:
    ledger = migrations.get("ledgerTable")
    expected = migrations.get("rows")
    if not isinstance(ledger, str) or not isinstance(expected, list):
        raise fail("migration ledger 内容不完整")
    if migrations.get("ledgerKind") == "SMARTBI":
        fields = ["filename", "version", "checksum", "applied_at", "applied_by", "duration_ms"]
        with conn.cursor() as cur:
            cur.execute(sql.SQL("SELECT {} FROM {} ORDER BY applied_at,filename").format(
                sql.SQL(",").join(map(sql.Identifier, fields)), sql.Identifier(schema_name, ledger)))
            actual = [{"installedRank": i + 1, "version": row[1], "description": row[0], "type": "SMARTBI",
                       "script": row[0], "checksum": row[2], "installedBy": row[4],
                       "installedOn": row[3].isoformat(sep=" ") if row[3] else None,
                       "executionTime": row[5], "success": True} for i, row in enumerate(cur.fetchall())]
        if len(actual) != len(expected) or migration_digest(actual) != migration_digest(expected):
            raise fail(f"migration ledger 内容指纹不一致: {ledger}")
        return
    fields = ["installed_rank", "version", "description", "type", "script", "checksum",
              "installed_by", "installed_on", "execution_time", "success"]
    with conn.cursor() as cur:
        cur.execute(sql.SQL("SELECT {} FROM {} ORDER BY installed_rank").format(
            sql.SQL(",").join(map(sql.Identifier, fields)), sql.Identifier(schema_name, ledger)))
        actual = []
        for row in cur.fetchall():
            actual.append({"installedRank": row[0], "version": row[1], "description": row[2],
                           "type": row[3], "script": row[4], "checksum": row[5],
                           "installedBy": row[6], "installedOn": row[7].isoformat(sep=" ") if row[7] else None, "executionTime": row[8],
                           "success": row[9]})
    if len(actual) != len(expected) or migration_digest(actual) != migration_digest(expected):
        raise fail(f"migration ledger 内容指纹不一致: {ledger}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path)
    parser.add_argument("--cretas-schema-baseline", type=Path)
    parser.add_argument("--smartbi-schema-baseline", type=Path)
    parser.add_argument("--baseline-manifest", type=Path)
    parser.add_argument("--target-host", required=True)
    parser.add_argument("--target-port", type=int, required=True)
    parser.add_argument("--target-user", required=True)
    parser.add_argument("--target-system-id", required=True)
    parser.add_argument("--cretas-target-db")
    parser.add_argument("--smartbi-target-db")
    parser.add_argument("--pg-bin", type=Path, required=True)
    parser.add_argument("--ownership-receipt", type=Path)
    parser.add_argument("--verify-owner-only", action="store_true")
    parser.add_argument("--receipt", type=Path, help="私有逐库阶段和清理回执")
    parser.add_argument("--allow-pg13-to-pg17", action="store_true",
                        help="显式选择独立 PG13→PG17 恢复；仍须完整结构/类型/行/迁移核验")
    parser.add_argument("--apply", action="store_true", help="执行私有目标恢复；默认仅校验包与 baseline")
    args = parser.parse_args()
    if args.target_host not in {"127.0.0.1", "localhost", "::1"} or args.target_port < 1024:
        raise fail("仅允许本机高端口目标")
    if args.verify_owner_only:
        with psycopg2.connect(host=args.target_host, port=args.target_port, dbname="postgres",
                              user=args.target_user, connect_timeout=10) as control:
            verify_owned_connection(control, args)
        print(json.dumps({"status": "OWNED_CLUSTER_VERIFIED", "businessWrites": 0}))
        return 0
    if any(getattr(args, field) is None for field in ("package", "cretas_schema_baseline",
            "smartbi_schema_baseline", "baseline_manifest", "cretas_target_db", "smartbi_target_db")):
        parser.error("还原模式必须明确 package、两个 baseline、baseline manifest 和两个 target db")
    if args.cretas_target_db == args.smartbi_target_db:
        raise fail("两个目标必须是不同的独立数据库")
    manifest, archive = verify_package(args.package)
    baseline_manifest = read_json(args.baseline_manifest)
    baselines = {"CRETAS": args.cretas_schema_baseline, "SMARTBI": args.smartbi_schema_baseline}
    for database, baseline in baselines.items():
        schema, migrations = metadata_for(archive, database)
        validate_database_manifest(archive, manifest, database, schema)
        info = baseline_info(baseline)
        expected = baseline_manifest.get(database, {})
        if info.get("schemaFingerprint") != schema["fingerprint"] or expected.get("schemaFingerprint") != schema["fingerprint"]:
            raise fail(f"{database} schema fingerprint 不匹配")
        if info.get("migrationFingerprint") != migrations["fingerprint"] or expected.get("migrationFingerprint") != migrations["fingerprint"]:
            raise fail(f"{database} migration fingerprint 不匹配")
        listing = run_tool(args.pg_bin, "pg_restore", ["--list", str(baseline)])
        provenance = re.search(r"^;\s*Dumped from database version:\s*(\d+)", listing, re.MULTILINE)
        if provenance is None or int(provenance.group(1)) != int(info["postgresMajor"]):
            raise fail(f"{database} baseline 原始数据库 major 与 archive provenance 不符")
    if not args.apply:
        print(json.dumps({"status": "VALIDATED_ONLY", "format": manifest["format"]}, ensure_ascii=False))
        return 0
    connections: dict[str, Any] = {}
    states = {database: {"database": args.cretas_target_db if database == "CRETAS" else args.smartbi_target_db,
                         "stage": "NOT_STARTED", "initiallyEmptyVerified": False,
                         "partialTargetRequiresDestroy": False, "cleanup": "NOT_NEEDED"}
              for database in baselines}
    receipt: dict[str, Any] = {"status": "RESTORE_FAILED", "crossDatabaseAtomic": False,
                              "databases": states}
    succeeded = False
    try:
        # Validate BOTH destinations before the first pg_restore. A failure in
        # the second database must not leave the first silently half-restored.
        for database in baselines:
            conn = connect(args, database)
            connections[database] = conn
            with conn.cursor() as cur:
                cur.execute("SELECT oid FROM pg_database WHERE datname=current_database()")
                states[database]["actualDbId"] = cur.fetchone()[0]
                cur.execute("SELECT pg_try_advisory_lock(hashtextextended(current_database(),0))")
                if cur.fetchone()[0] is not True:
                    raise fail(f"{database} 已有还原执行者")
            ensure_empty_target(conn, database)
            states[database]["initiallyEmptyVerified"] = True
            states[database]["stage"] = "PREFLIGHT_VERIFIED"
            conn.commit()
        for database, baseline in baselines.items():
            schema, migrations = metadata_for(archive, database)
            info = baseline_info(baseline)
            conn = connections[database]
            set_target_schema(conn, schema.get("sourceSchema", "public"))
            with conn.cursor() as cur:
                cur.execute("SELECT current_setting('server_version_num')")
                target_major = int(cur.fetchone()[0]) // 10000
            source_major = int(info["postgresMajor"])
            if source_major != target_major:
                if not (args.allow_pg13_to_pg17 and source_major == 13 and target_major == 17):
                    raise fail(f"{database} PostgreSQL major 不兼容")
                states[database]["explicitMajorUpgrade"] = {"source": source_major,
                    "target": target_major, "verification": "PENDING_FULL_RESTORE"}
            conn.commit()
            states[database]["stage"] = "PRE_DATA_STARTED"
            states[database]["partialTargetRequiresDestroy"] = True
            run_tool(args.pg_bin, "pg_restore", restore_args(args, database, baseline, "pre-data"))
            states[database]["stage"] = "ROWS_STARTED"
            import_rows(conn, archive, database, manifest, schema)
            restore_migrations(conn, migrations, schema.get("sourceSchema", "public"))
            restore_sequence_state(conn, schema, schema.get("sourceSchema", "public"))
            # Rows and migration ledger form one transaction. Sequence state
            # is non-transactional in PostgreSQL, hence explicit DB cleanup on
            # every failure (including later post-data verification).
            conn.commit()
            states[database]["stage"] = "POST_DATA_STARTED"
            run_tool(args.pg_bin, "pg_restore", restore_args(args, database, baseline, "post-data"))
            verify_migration_presence(conn, migrations, schema.get("sourceSchema", "public"))
            verify_migration_rows(conn, migrations, schema.get("sourceSchema", "public"))
            verify_target(conn, archive, database, manifest, schema)
            conn.commit()
            states[database]["stage"] = "VERIFIED"
            if "explicitMajorUpgrade" in states[database]:
                states[database]["explicitMajorUpgrade"]["verification"] = "CONTENT_STRUCTURE_TYPES_MIGRATIONS_VERIFIED"
        succeeded = True
        receipt["status"] = "RESTORED_AND_VERIFIED"
        for state in states.values():
            state["partialTargetRequiresDestroy"] = False
        return 0
    finally:
        for conn in connections.values():
            try:
                conn.rollback()
            finally:
                conn.close()
        if not succeeded and any(state["partialTargetRequiresDestroy"] for state in states.values()):
            # Only explicitly registered, freshly verified empty databases in
            # the SAME owned cluster are eligible. Never FORCE or terminate
            # another session. A blocked DROP remains an explicit failure.
            control = None
            try:
                control = psycopg2.connect(host=args.target_host, port=args.target_port,
                    dbname="postgres", user=args.target_user, connect_timeout=10)
                verify_owned_connection(control, args)
                control.commit()
                control.autocommit = True
                for state in states.values():
                    if not state["partialTargetRequiresDestroy"]:
                        continue
                    try:
                        with control.cursor() as cur:
                            cur.execute("SELECT oid,pg_get_userbyid(datdba) FROM pg_database WHERE datname=%s",
                                        (state["database"],))
                            identity = cur.fetchone()
                            if identity != (state["actualDbId"], args.target_user):
                                raise fail("目标数据库已替换或Owner变化，拒绝清理")
                            cur.execute("SET lock_timeout='3s'")
                            cur.execute(sql.SQL("DROP DATABASE {}").format(sql.Identifier(state["database"])))
                            cur.execute("SELECT count(*) FROM pg_database WHERE datname=%s", (state["database"],))
                            if cur.fetchone()[0] != 0:
                                raise fail("目标删除后仍存在")
                        state["cleanup"] = "DESTROYED_AND_VERIFIED"
                        state["partialTargetRequiresDestroy"] = False
                    except Exception:
                        state["cleanup"] = "FAILED_REQUIRES_OWNER_ACTION"
            except Exception:
                for state in states.values():
                    if state["partialTargetRequiresDestroy"]:
                        state["cleanup"] = "REFUSED_IDENTITY_OR_CONNECTION"
            finally:
                if control is not None:
                    control.close()
        archive.close()
        body = json.dumps(receipt, ensure_ascii=False, indent=2)
        if args.receipt is not None:
            args.receipt.write_text(body + "\n", encoding="utf-8")
        print(body)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RestoreRefused as exc:
        print(f"RESTORE_REFUSED: {exc}", file=sys.stderr)
        raise SystemExit(2)
    except psycopg2.Error as exc:
        # PostgreSQL DETAIL may contain complete business rows or credentials.
        # Only SQLSTATE is safe here; per-database stage/cleanup is in receipt.
        code = exc.pgcode if exc.pgcode and re.fullmatch(r"[0-9A-Z]{5}", exc.pgcode) else "UNKNOWN"
        print(f"RESTORE_DATABASE_ERROR sqlstate={code}", file=sys.stderr)
        raise SystemExit(3)
