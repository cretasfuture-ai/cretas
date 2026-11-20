"""Optional Windows CurrentUser DPAPI file. No secrets returned to shell output."""
import ctypes
import json
import os
import uuid
from ctypes import wintypes
from pathlib import Path

from .contracts import BlacklakeError


def load_local_secret(path):
    if os.name != "nt":
        raise BlacklakeError("DPAPI_REQUIRES_WINDOWS")

    class Blob(ctypes.Structure):
        _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]

    encrypted = Path(path).read_bytes()
    buffer = (ctypes.c_ubyte * len(encrypted)).from_buffer_copy(encrypted)
    source, output = Blob(len(encrypted), buffer), Blob()
    crypt = ctypes.windll.crypt32
    crypt.CryptUnprotectData.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p,
                                        ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p,
                                        wintypes.DWORD, ctypes.POINTER(Blob)]
    if not crypt.CryptUnprotectData(ctypes.byref(source), None, None, None, None,
                                   1, ctypes.byref(output)):
        raise BlacklakeError("LOCAL_SECRET_DECRYPT_FAILED")
    try:
        raw = ctypes.string_at(output.data, output.size)
        return json.loads(raw.decode("utf-8"))
    finally:
        ctypes.memset(output.data, 0, output.size)
        ctypes.windll.kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        ctypes.windll.kernel32.LocalFree(output.data)


def load_local_credentials(path):
    result = load_local_secret(path)
    return result["phone"], result["password"]


def save_local_secret(path, value):
    if os.name != "nt":
        raise BlacklakeError("DPAPI_REQUIRES_WINDOWS")

    class Blob(ctypes.Structure):
        _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_ubyte))]

    raw = json.dumps(value, ensure_ascii=False).encode()
    buffer = (ctypes.c_ubyte * len(raw)).from_buffer_copy(raw)
    source, output = Blob(len(raw), buffer), Blob()
    crypt = ctypes.windll.crypt32
    crypt.CryptProtectData.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p,
                                      ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    if not crypt.CryptProtectData(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(output)):
        raise BlacklakeError("LOCAL_SECRET_ENCRYPT_FAILED")
    try:
        # Resolve the parent before creating the sibling. Packaged Windows apps
        # may redirect an existing LocalAppData directory to another drive.
        destination = Path(path).parent.resolve() / Path(path).name
        temporary = destination.with_name(destination.name + "." + uuid.uuid4().hex + ".tmp")
        try:
            temporary.write_bytes(ctypes.string_at(output.data, output.size))
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
    finally:
        ctypes.memset(buffer, 0, len(raw))
        ctypes.windll.kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        ctypes.windll.kernel32.LocalFree(output.data)
