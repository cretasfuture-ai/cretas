"""CurrentUser DPAPI for local-only labor evidence; no plaintext fallback."""
import base64
import ctypes
import json
import os
from ctypes import wintypes


def _crypt(value:bytes,encrypt:bool)->bytes:
    if os.name!='nt':raise RuntimeError('LABOR_PRIVATE_STORAGE_REQUIRES_WINDOWS_DPAPI')
    class Blob(ctypes.Structure):
        _fields_=[('size',wintypes.DWORD),('data',ctypes.POINTER(ctypes.c_ubyte))]
    source_buffer=(ctypes.c_ubyte*len(value)).from_buffer_copy(value)
    source,output=Blob(len(value),source_buffer),Blob()
    crypt=ctypes.windll.crypt32
    if encrypt:
        f=crypt.CryptProtectData
        f.argtypes=[ctypes.POINTER(Blob),ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,wintypes.DWORD,ctypes.POINTER(Blob)]
        ok=f(ctypes.byref(source),None,None,None,None,1,ctypes.byref(output))
    else:
        f=crypt.CryptUnprotectData
        f.argtypes=[ctypes.POINTER(Blob),ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,ctypes.c_void_p,wintypes.DWORD,ctypes.POINTER(Blob)]
        ok=f(ctypes.byref(source),None,None,None,None,1,ctypes.byref(output))
    try:
        if not ok:raise RuntimeError('LABOR_PRIVATE_STORAGE_CRYPTO_FAILED')
        return ctypes.string_at(output.data,output.size)
    finally:
        ctypes.memset(source_buffer,0,len(value))
        if output.data:
            ctypes.memset(output.data,0,output.size)
            ctypes.windll.kernel32.LocalFree.argtypes=[ctypes.c_void_p]
            ctypes.windll.kernel32.LocalFree(output.data)


def seal(value:dict)->str:
    return 'dpapi-v1:'+base64.b64encode(_crypt(json.dumps(value,ensure_ascii=False,separators=(',',':')).encode(),True)).decode('ascii')


def unseal(value:str)->dict:
    if not isinstance(value,str) or not value.startswith('dpapi-v1:'):raise RuntimeError('LABOR_PRIVATE_STORAGE_FORMAT_INVALID')
    result=json.loads(_crypt(base64.b64decode(value[9:],validate=True),False))
    if not isinstance(result,dict):raise RuntimeError('LABOR_PRIVATE_STORAGE_INVALID')
    return result
