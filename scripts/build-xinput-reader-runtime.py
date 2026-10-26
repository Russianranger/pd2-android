#!/usr/bin/env python3
"""Build/check the opt-in i386 Wine 9 XInput reader, retaining the donor API/codec."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'runtime/xinput-reader'
OUTPUT = ROOT / 'app/src/main/assets/pd2/xinput-reader'
REVISION = 'wine9-xinput-reader-1'
ASSET = 'pd2/xinput-reader/xinput1_4.dll'
BASE_SHA = 'c8f692e7fe22fb922886026d97bf39549c8eac12629fee3e06f49078b34a60fa'
SOURCE_COMMIT = 'a4ef2bf8963fe4bfab390eebf73336364ce209bc'
VENDOR_SHA = 'b9def9ba88a9663aca2659c76d0693aed8c77f3c540129b0baf86c77f6d1601e'
VENDOR_BLOB = '01b229084ef65a4fd1bae832982626a0ecdb6183'
SPEC_SHA = 'ef26c5e55b36ffd4b2978b30b9ad21d4abd9ef71620134afc2b9bddfcea97e59'
PATCHED_SHA = '5a05fabaaf028749c306b76103451f72215ce69c0605e3876f9beb981f417ce0'
EXPORTS = {'DllMain': 1, 'XInputGetState': 2, 'XInputSetState': 3, 'XInputGetCapabilities': 4,
           'XInputEnable': 5, 'XInputGetBatteryInformation': 7, 'XInputGetKeystroke': 8,
           'XInputGetAudioDeviceIds': 10, 'XInputGetStateEx': 100, 'XInputGetCapabilitiesEx': 108}

def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

def source_checks() -> None:
    data = (SOURCE / 'vendor/main.c').read_bytes()
    if sha(data) != VENDOR_SHA or hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() != VENDOR_BLOB:
        raise RuntimeError('Pinned donor XInput source changed')
    if sha((SOURCE / 'vendor/xinput1_4.spec').read_bytes()) != SPEC_SHA:
        raise RuntimeError('Pinned donor export specification changed')

def replace_function(source: str, name: str, replacement: str) -> str:
    match = re.search(r'(?m)^static [^\n]+\b' + re.escape(name) + r'\([^;]*?\)\s*\{', source)
    if not match:
        raise RuntimeError('Missing qualified donor function ' + name)
    opening = source.index('{', match.start())
    depth = 1
    end = opening + 1
    while depth:
        if source[end] == '{': depth += 1
        if source[end] == '}': depth -= 1
        end += 1
    return source[:match.start()] + replacement + source[end:]

def render_source() -> str:
    source_checks()
    code = (SOURCE / 'vendor/main.c').read_text()
    # MinGW's public CRITICAL_SECTION_DEBUG layout differs from Wine's private
    # static initializer. Use the public initialization API at DLL attach.
    begin = code.index('static CRITICAL_SECTION_DEBUG controller_critsect_debug')
    end = code.index('static SOCKET server_sock', begin)
    code = code[:begin] + code[end:]
    code = code.replace('        DisableThreadLibraryCalls(inst);', '        InitializeCriticalSection(&controller.crit);\n        DisableThreadLibraryCalls(inst);')
    code = replace_function(code, 'controller_destroy', 'static void controller_destroy(void);')
    begin = code.index('static DWORD WINAPI controller_read_thread_proc(')
    end = code.index('\nBOOL WINAPI DllMain(', begin)
    code = code[:begin] + '#include "reader_lifecycle.h"\n' + code[end:]
    code = code.replace('char buffer[BUFFER_SIZE];', 'char buffer[BUFFER_SIZE] = {0};')
    code = code.replace('    ioctlsocket(server_sock, FIONBIO, &non_blocking);',
                        '    if (ioctlsocket(server_sock, FIONBIO, &non_blocking) == SOCKET_ERROR) return FALSE;')
    code = code.replace('static void get_gamepad_request(void)', 'static BOOL get_gamepad_request(void)')
    code = code.replace('    sendto(server_sock, buffer, BUFFER_SIZE, 0, (struct sockaddr*)&client_addr, sizeof(client_addr));',
                        '    return sendto(server_sock, buffer, BUFFER_SIZE, 0, (struct sockaddr*)&client_addr, sizeof(client_addr)) == BUFFER_SIZE;', 1)
    code = replace_function(code, 'xinput_get_state', '''static DWORD xinput_get_state(DWORD index, XINPUT_STATE *state)
{
    if (!state) return ERROR_BAD_ARGUMENTS;
    start_read_thread();
    if (index >= XUSER_MAX_COUNT) return ERROR_BAD_ARGUMENTS;
    return reader_get_state(index, state);
}''')
    # The donor .spec declares this as a stub; retain its public ABI and unsupported status.
    code += '''\nDWORD WINAPI XInputGetAudioDeviceIds(DWORD index, WCHAR *render, UINT *render_count, WCHAR *capture, UINT *capture_count)
{
    (void)index; (void)render; (void)render_count; (void)capture; (void)capture_count;
    return ERROR_NOT_SUPPORTED;
}\n'''
    return code

def pe_metadata(data: bytes) -> dict:
    if len(data) < 256 or data[:2] != b'MZ': raise RuntimeError('Expected a PE DLL')
    pe = struct.unpack_from('<I', data, 0x3c)[0]
    if data[pe:pe+4] != b'PE\0\0': raise RuntimeError('Invalid PE signature')
    machine, count = struct.unpack_from('<HH', data, pe+4)
    flags = struct.unpack_from('<H', data, pe+22)[0]
    opt = pe+24
    if machine != 0x14c or struct.unpack_from('<H', data, opt)[0] != 0x10b or not flags & 0x2000:
        raise RuntimeError('Expected PE32-I386 DLL')
    sections = []
    at = opt + struct.unpack_from('<H', data, pe+20)[0]
    for i in range(count):
        name, vsize, rva, size, offset = struct.unpack_from('<8sIIII', data, at+i*40)
        if offset+size > len(data): raise RuntimeError('Truncated PE section')
        sections.append((rva, size, offset))
    def off(rva: int) -> int:
        for start, size, offset in sections:
            if start <= rva < start+size: return offset+rva-start
        raise RuntimeError('Unbacked PE RVA')
    def string(rva: int) -> str:
        begin = off(rva); end = data.index(b'\0', begin)
        return data[begin:end].decode('ascii')
    export_rva, export_size = struct.unpack_from('<II', data, opt+96)
    exp = off(export_rva)
    base, functions, names, addresses, name_ptr, ordinal_ptr = struct.unpack_from('<IIIIII', data, exp+16)
    exports = {}
    for i in range(names):
        name = string(struct.unpack_from('<I', data, off(name_ptr)+4*i)[0])
        ordinal = struct.unpack_from('<H', data, off(ordinal_ptr)+2*i)[0]
        target = struct.unpack_from('<I', data, off(addresses)+4*ordinal)[0]
        if export_rva <= target < export_rva+export_size: raise RuntimeError('Unexpected export forwarder')
        exports[name] = base+ordinal
    imports = {}
    import_rva = struct.unpack_from('<I', data, opt+104)[0]
    if import_rva:
        at = off(import_rva)
        while any(data[at:at+20]):
            lookup, _, _, name_rva, first = struct.unpack_from('<IIIII', data, at)
            name = string(name_rva).lower(); functions_in = []; entry = off(lookup or first)
            while (value := struct.unpack_from('<I', data, entry)[0]):
                functions_in.append('#'+str(value & 0xffff) if value & 0x80000000 else string(value+2))
                entry += 4
            imports[name] = functions_in
            at += 20
    return {'machine': 'PE32-I386', 'dll': True, 'timestamp': struct.unpack_from('<I', data, pe+8)[0],
            'builtin_marker': data[0x40:0x51] == b'Wine builtin DLL\0', 'exports': exports, 'imports': imports,
            'checksum_offset': opt+64, 'checksum': struct.unpack_from('<I', data, opt+64)[0]}

def checksum(data: bytes, offset: int) -> int:
    image = bytearray(data); struct.pack_into('<I', image, offset, 0); value = 0
    for at in range(0, len(image), 2):
        value += image[at] | ((image[at+1] if at+1 < len(image) else 0) << 8)
        value = (value & 0xffff) + (value >> 16)
    return ((value & 0xffff)+(value >> 16))+len(image)

def validate_artifact(data: bytes, exact: bool = True) -> dict:
    info = pe_metadata(data)
    if info['exports'] != EXPORTS or not info['builtin_marker'] or info['timestamp'] != 0:
        raise RuntimeError('XInput exports, Wine marker or reproducible timestamp changed')
    if set(info['imports']) != {'kernel32.dll', 'msvcrt.dll', 'ws2_32.dll'}:
        raise RuntimeError('Unexpected XInput dependency')
    if info['checksum'] != checksum(data, info['checksum_offset']): raise RuntimeError('Invalid PE checksum')
    if exact and sha(data) != PATCHED_SHA: raise RuntimeError('Unknown XInput reader artifact')
    return info

def source_hashes() -> dict:
    files = [p for p in SOURCE.rglob('*') if p.is_file() and '/build/' not in str(p)] + [Path(__file__), ROOT / 'tests/test_xinput_reader_runtime.py']
    return {str(p.relative_to(ROOT)): sha(p.read_bytes()) for p in sorted(files)}

def manifest_for(data: bytes) -> dict:
    source_checks()
    return {'revision': REVISION, 'asset': ASSET, 'dllname': 'xinput1_4.dll', 'machine': 'PE32-I386',
            'base_sha256': BASE_SHA, 'sha256': sha(data), 'bytes': len(data), 'license': 'LGPL-2.1-or-later',
            'method': 'Pinned donor API/codec plus focused reader lifecycle source patch; MinGW i386 PE, Wine builtin marker',
            'source_commit': SOURCE_COMMIT, 'source_sha256': source_hashes(),
            'validation': validate_artifact(data),
            'telemetry': 'PD2_XINPUT_READER fixed aggregate schema; at most once per 2000ms; no control values',
            'scope': 'Opt-in matching 32-bit XInput consumer only; physical Save/Quit qualification pending'}

def validate_manifest(manifest: dict, artifact: Path) -> None:
    if manifest != manifest_for(artifact.read_bytes()): raise RuntimeError('XInput reader manifest/artifact/source is stale')

def build(compiler: str, output: Path) -> bytes:
    source_checks()
    with tempfile.TemporaryDirectory(prefix='pd2-xinput-build-') as temp:
        work = Path(temp)
        (work/'main.c').write_text(render_source())
        (work/'xinput.def').write_text('LIBRARY xinput1_4\nEXPORTS\n' + ''.join(f' {name} @{ordinal}\n' for name, ordinal in EXPORTS.items()))
        command = [compiler, '-std=gnu11', '-O2', '-shared', '-fno-strict-aliasing', '-static-libgcc',
                   '-fno-asynchronous-unwind-tables', '-fno-unwind-tables', '-s', '-include', str(SOURCE/'compat.h'),
                   '-I', str(SOURCE/'compat'), '-I', str(SOURCE), 'main.c', 'xinput.def', '-o', 'xinput1_4.dll',
                   '-lws2_32', '-Wl,--kill-at,--no-insert-timestamp,--enable-stdcall-fixup']
        subprocess.run(command, cwd=work, check=True)
        data = bytearray((work/'xinput1_4.dll').read_bytes())
        data[0x40:0x51] = b'Wine builtin DLL\0'
        struct.pack_into('<I', data, pe_metadata(data)['checksum_offset'], checksum(data, pe_metadata(data)['checksum_offset']))
        data = bytes(data)
        validate_artifact(data, exact=PATCHED_SHA != 'PENDING')
    output.mkdir(parents=True, exist_ok=True)
    (output/'xinput1_4.dll').write_bytes(data)
    if PATCHED_SHA != 'PENDING': (output/'manifest.json').write_text(json.dumps(manifest_for(data), indent=2)+'\n')
    return data

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler', default=os.environ.get('MINGW_CC', 'i686-w64-mingw32-gcc'))
    parser.add_argument('--output', type=Path, default=OUTPUT)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    if args.check:
        validate_manifest(json.loads((args.output/'manifest.json').read_text()), args.output/'xinput1_4.dll')
        print('XInput reader exact artifact, source and API/PE checks passed.')
    else:
        data = build(args.compiler, args.output)
        print(json.dumps({'revision': REVISION, 'sha256': sha(data), 'bytes': len(data)}, indent=2))

if __name__ == '__main__': main()
