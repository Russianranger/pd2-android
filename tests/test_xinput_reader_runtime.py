"""Qualify pinned source, PE exports, and executable production reader lifecycle."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('xinput_reader_build', ROOT/'scripts/build-xinput-reader-runtime.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)

def function(source, name):
    match = re.search(r'(?m)^static [^\n]+\b'+re.escape(name)+r'\([^;]*?\)\s*\{', source)
    assert match, name
    at=source.index('{', match.start()); depth=1; end=at+1
    while depth:
        depth += (source[end]=='{')-(source[end]=='}');end+=1
    return source[match.start():end]

class XInputReaderTests(unittest.TestCase):
    def test_pinned_donor_and_preserved_codec(self):
        build.source_checks()
        donor=(build.SOURCE/'vendor/main.c').read_text()
        modified=build.render_source()
        self.assertEqual(function(donor,'controller_update_state'),function(modified,'controller_update_state'))
        self.assertIn('return reader_get_state(index, state);',modified)
        self.assertNotIn('InitOnceExecuteOnce',modified)
        self.assertEqual(build.EXPORTS['XInputGetStateEx'],100)
        self.assertEqual(build.EXPORTS['XInputGetCapabilitiesEx'],108)

    def test_actual_source_lifecycle_failures_and_recovery(self):
        code=build.render_source()
        donor='\n'.join(function(code,name) for name in ['controller_init','controller_update_state'])
        template=(build.SOURCE/'test_reader.c').read_text()
        self.assertEqual(template.count('/* PD2_DONOR_DECODER */'),1)
        self.assertEqual(template.count('/* PD2_DONOR_GET_STATE */'),1)
        generated=template.replace('/* PD2_DONOR_DECODER */',donor).replace('/* PD2_DONOR_GET_STATE */',function(code,'xinput_get_state'))
        with tempfile.TemporaryDirectory(prefix='pd2-xinput-test-') as temp:
            root=Path(temp);(root/'reader_test.c').write_text(generated)
            command=['gcc','-std=gnu11','-O1','-g','-Wall','-Wextra','-Wno-unused-function','-Wno-misleading-indentation',
                     '-fno-strict-aliasing','-fsanitize=address,undefined','-fno-sanitize=alignment','-fno-omit-frame-pointer',
                     '-I',str(build.SOURCE),str(root/'reader_test.c'),'-o',str(root/'reader_test'),'-pthread']
            subprocess.run(command,check=True,capture_output=True,text=True)
            result=subprocess.run([str(root/'reader_test')],capture_output=True,text=True,timeout=20,
                                  env={**os.environ,'ASAN_OPTIONS':'detect_leaks=0:halt_on_error=1','UBSAN_OPTIONS':'halt_on_error=1'})
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            self.assertIn('PASS 14 actual source reader lifecycle scenarios',result.stdout)
            print(result.stdout,end='')

    def test_artifact_exact_hash_exports_imports_and_manifest(self):
        artifact=build.OUTPUT/'xinput1_4.dll'
        manifest=build.OUTPUT/'manifest.json'
        self.assertTrue(artifact.exists(),'Qualified DLL must be bundled')
        self.assertTrue(manifest.exists(),'Source-qualified manifest must be bundled')
        build.validate_manifest(json.loads(manifest.read_text()),artifact)
        self.assertEqual(build.validate_artifact(artifact.read_bytes())['exports'],build.EXPORTS)

    def test_corrupted_artifact_or_manifest_rejected(self):
        artifact=build.OUTPUT/'xinput1_4.dll';original=artifact.read_bytes()
        for at in [0,64,len(original)-1]:
            corrupt=bytearray(original);corrupt[at]^=1
            with self.subTest(offset=at),self.assertRaises(RuntimeError):build.validate_artifact(bytes(corrupt))
        manifest=json.loads((build.OUTPUT/'manifest.json').read_text())
        for key,value in {'revision':'other','base_sha256':'0'*64,'sha256':'0'*64,'source_sha256':{},'validation':{}}.items():
            changed=copy.deepcopy(manifest);changed[key]=value
            with self.subTest(field=key),self.assertRaises(RuntimeError):build.validate_manifest(changed,artifact)

if __name__=='__main__':unittest.main()
