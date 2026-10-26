import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

SCRIPT = Path(__file__).with_name('s75_startup.py')
PACKAGE = 'io.github.op7418.guizangtv.s75test'
SERIAL = '192.0.2.20:5555'
FAKE = r'''#!PYTHON
import json, os, shlex, sys
from pathlib import Path
p=Path(os.environ['FAKE_ADB_STATE']); s=json.loads(p.read_text())
a=sys.argv[1:]
with open(os.environ['FAKE_ADB_LOG'],'a') as log: log.write(json.dumps(a)+'\n')
if len(a)<4 or a[:1]!=['-s'] or a[2]!='shell': sys.exit(4)
c=shlex.split(a[3])
def save():p.write_text(json.dumps(s))
if c[:1]==['getprop']:
 print(s.get({'ro.product.model':'model','ro.build.version.sdk':'sdk','ro.serialno':'hardware','ro.boot.serialno':'boot_hardware','ro.build.version.incremental':'firmware'}[c[1]], {'ro.product.model':'MiTV-MFTR0','ro.build.version.sdk':'34','ro.serialno':'TV-UNIT-1','ro.boot.serialno':'','ro.build.version.incremental':'OS3.0.106.0.UFTMATV'}[c[1]]))
elif c[:2]==['pm','path']:
 print('package:/data/app/example/base.apk' if s.get('installed',True) else '')
elif c[:2]==['settings','list']:
 if s.pop('fail_next_list',False):save();sys.exit(7)
 for k,v in s.get(c[2],{}).items():print(k+'='+v)
elif c[:2]==['settings','put']:
 if s.get('fail_put_key')==c[3]:print('SecurityException: denied by fake device');sys.exit(8)
 if s.get('disconnect_put_key')==c[3]:sys.exit(8)
 if len(c)!=5:sys.exit(9)
 s.setdefault(c[2],{})[c[3]]=c[4]
 if s.get('fail_readback_key')==c[3]:s['fail_next_list']=True
 save()
elif c[:2]==['settings','delete']:
 s.setdefault(c[2],{}).pop(c[3],None);save()
else:sys.exit(5)
'''

class S75StartupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.state = self.root / 'device.json'
        self.log = self.root / 'adb.jsonl'
        self.backup = self.root / 'backup.json'
        self.adb = self.root / 'adb'
        self.adb.write_text(FAKE.replace('#!PYTHON', '#!' + sys.executable))
        self.adb.chmod(0o700)
        self.env = dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ['PATH'],
                        FAKE_ADB_STATE=str(self.state), FAKE_ADB_LOG=str(self.log))
        self.save({'system': {}, 'global': {}})

    def save(self, state):
        self.state.write_text(json.dumps(state))

    def load(self):
        return json.loads(self.state.read_text())

    def run_tool(self, command, *args, serial=SERIAL):
        return subprocess.run([sys.executable, str(SCRIPT), command, '--serial', serial, *args],
                              env=self.env, text=True, capture_output=True, timeout=10)

    def apply(self, *flags):
        return self.run_tool('apply', '--package', PACKAGE, '--backup', str(self.backup), *flags)

    def restore(self, serial=SERIAL):
        return self.run_tool('restore', '--backup', str(self.backup), serial=serial)

    def commands(self):
        return [json.loads(line)[3] for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def writes(self):
        return [c for c in self.commands() if c.startswith(('settings put ', 'settings delete '))]

    def test_inspect_is_read_only_and_reports_absent_empty_literal_null(self):
        self.save({'system': {'start_3rd_app': '', 'mitv_device_customize_skip': 'null'}, 'global': {}})
        result = self.run_tool('inspect', '--package', PACKAGE)
        self.assertEqual(0, result.returncode, result.stderr)
        data = json.loads(result.stdout)
        self.assertEqual({'exists': True, 'value': ''}, data['settings']['system.start_3rd_app'])
        self.assertEqual({'exists': True, 'value': 'null'}, data['settings']['system.mitv_device_customize_skip'])
        self.assertEqual({'exists': False, 'value': None}, data['settings']['global.disable_back_home_whitelist'])
        self.assertEqual([], self.writes())

    def test_apply_restore_preserves_absent_empty_literal_null_and_quotes(self):
        for exists, value in [(False, None), (True, ''), (True, 'null'), (True, "old.app'; $(touch injected)")]:
            with self.subTest(exists=exists, value=value):
                self.backup.unlink(missing_ok=True)
                original = {'start_3rd_app': value} if exists else {}
                self.save({'system': original, 'global': {}})
                result = self.apply()
                self.assertEqual(0, result.returncode, result.stderr)
                backup = json.loads(self.backup.read_text())
                self.assertEqual({'exists': exists, 'value': value}, backup['keys'][0]['previous'])
                self.assertEqual(0o600, stat.S_IMODE(self.backup.stat().st_mode))
                self.assertEqual(PACKAGE, self.load()['system']['start_3rd_app'])
                result = self.restore()
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(original, self.load()['system'])
                self.assertFalse((self.root / 'injected').exists())

    def test_optional_settings_are_explicit_and_restore_together(self):
        result = self.apply('--skip-device-customization', '--experimental-standby')
        self.assertEqual(0, result.returncode, result.stderr)
        state = self.load()
        self.assertEqual('true', state['system']['mitv_device_customize_skip'])
        self.assertEqual('disable', state['global']['disable_back_home_whitelist'])
        self.assertIn('未验证', result.stdout)
        result = self.restore()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, self.load()['system'])
        self.assertEqual({}, self.load()['global'])

    def test_default_invocation_is_read_only(self):
        result = subprocess.run([sys.executable, str(SCRIPT), '--serial', SERIAL,
                                 '--package', PACKAGE], env=self.env, text=True,
                                capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('inspect', json.loads(result.stdout)['operation'])
        self.assertEqual([], self.writes())

    def test_missing_or_unknown_hardware_identity_rejects_apply(self):
        for value in ['', 'unknown']:
            with self.subTest(value=value):
                self.save({'system': {}, 'global': {}, 'hardware': value, 'boot_hardware': ''})
                result = self.apply()
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.backup.exists())
                self.assertEqual([], self.writes())

    def test_malformed_backup_is_rejected_without_traceback(self):
        for malformed in [[], None, {'version': 1, 'package': PACKAGE, 'keys': [None]}]:
            with self.subTest(value=malformed):
                self.backup.write_text(json.dumps(malformed))
                result = self.restore()
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn('Traceback', result.stderr)
                self.assertEqual([], self.writes())

    def test_adb_timeout_is_bounded_and_reported_as_failure(self):
        spec = importlib.util.spec_from_file_location('s75_under_test', SCRIPT)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        with patch.object(module.subprocess, 'run', side_effect=subprocess.TimeoutExpired('adb', 15)) as run:
            with self.assertRaises(module.ToolError):
                module.Adb(SERIAL).command('settings', 'list', 'system')
            self.assertEqual(15, run.call_args.kwargs['timeout'])
            self.assertNotIn('shell', run.call_args.kwargs)

    def test_default_apply_changes_only_target(self):
        result = self.apply()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({'start_3rd_app': PACKAGE}, self.load()['system'])
        self.assertEqual({}, self.load()['global'])
        self.assertEqual(1, len(self.writes()))
        self.assertEqual(['system.start_3rd_app'], json.loads(result.stdout)['selected_settings'])

    def test_existing_backup_cannot_be_overwritten_or_cause_writes(self):
        self.backup.write_text('keep me')
        result = self.apply()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('keep me', self.backup.read_text())
        self.assertEqual([], self.writes())

    def test_wrong_model_or_sdk_is_rejected_before_writes(self):
        for field, value in [('model', 'MiTV-MTSU0'), ('sdk', '33')]:
            with self.subTest(field=field):
                self.save({'system': {}, 'global': {}, field: value})
                result = self.apply()
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.backup.exists())
                self.assertEqual([], self.writes())

    def test_restore_rejects_changed_hardware_or_firmware(self):
        for field, value in [('hardware', 'ANOTHER-TV'), ('firmware', 'OS3.0.999')]:
            with self.subTest(field=field):
                self.backup.unlink(missing_ok=True)
                self.save({'system': {}, 'global': {}})
                self.assertEqual(0, self.apply().returncode)
                changed = self.load(); changed[field] = value; self.save(changed)
                before = len(self.writes())
                self.assertNotEqual(0, self.restore().returncode)
                self.assertEqual(changed, self.load())
                self.assertEqual(before, len(self.writes()))

    def test_unrelated_package_is_rejected(self):
        result = self.run_tool('apply', '--package', 'other.package', '--backup', str(self.backup))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.writes())

    def test_restore_rejects_different_serial(self):
        self.assertEqual(0, self.apply().returncode)
        before = self.load()
        result = self.restore(serial='OTHER:5555')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, self.load())

    def test_readback_failure_is_not_success_and_backup_can_restore(self):
        self.save({'system': {}, 'global': {}, 'fail_readback_key': 'start_3rd_app'})
        result = self.apply()
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(self.backup.exists())
        self.assertEqual(PACKAGE, self.load()['system']['start_3rd_app'])
        state = self.load(); state.pop('fail_readback_key'); self.save(state)
        result = self.restore()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, self.load()['system'])

    def test_timeout_must_not_discard_a_later_remote_write(self):
        spec = importlib.util.spec_from_file_location('s75_delayed_test', SCRIPT)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        real_run = subprocess.run
        def delayed_timeout(argv, **kwargs):
            if argv[-1].startswith('settings put '):
                self.assertEqual('write-uncertain', json.loads(self.backup.read_text())['keys'][0]['state'])
                raise subprocess.TimeoutExpired('adb', 15)
            return real_run(argv, **kwargs)
        args = SimpleNamespace(package=PACKAGE, backup=str(self.backup),
                               skip_device_customization=False, experimental_standby=False)
        with patch.dict(os.environ, self.env), patch.object(module.subprocess, 'run', side_effect=delayed_timeout):
            with self.assertRaises(module.ToolError):
                module.apply(module.Adb(SERIAL), args)
        self.assertEqual('write-uncertain', json.loads(self.backup.read_text())['keys'][0]['state'])
        late = self.load(); late['system']['start_3rd_app'] = PACKAGE; self.save(late)
        result = self.restore()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, self.load()['system'])

    def test_uncertain_restore_does_not_report_original_value_as_complete(self):
        self.save({'system': {}, 'global': {}, 'disconnect_put_key': 'start_3rd_app'})
        self.assertNotEqual(0, self.apply().returncode)
        result = self.restore()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('uncertain', result.stderr.lower())
        self.assertEqual('write-uncertain', json.loads(self.backup.read_text())['keys'][0]['state'])
        self.assertEqual({}, self.load()['system'])

    def test_disconnect_keeps_a_later_remote_write_restorable(self):
        self.save({'system': {}, 'global': {}, 'disconnect_put_key': 'start_3rd_app'})
        self.assertNotEqual(0, self.apply().returncode)
        late = self.load(); late.pop('disconnect_put_key'); late['system']['start_3rd_app'] = PACKAGE; self.save(late)
        result = self.restore()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, self.load()['system'])

    def test_restore_still_works_after_test_package_is_uninstalled(self):
        self.assertEqual(0, self.apply('--experimental-standby').returncode)
        state = self.load(); state['installed'] = False; self.save(state)
        result = self.restore()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, self.load()['system'])
        self.assertEqual({}, self.load()['global'])

    def test_partial_failure_restores_only_the_written_keys(self):
        self.save({'system': {}, 'global': {}, 'fail_put_key': 'mitv_device_customize_skip'})
        result = self.apply('--skip-device-customization', '--experimental-standby')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(PACKAGE, self.load()['system']['start_3rd_app'])
        self.assertEqual({}, self.load()['global'])
        result = self.restore()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({}, self.load()['system'])

    def test_restore_refuses_to_overwrite_later_changes(self):
        self.assertEqual(0, self.apply('--experimental-standby').returncode)
        changed = self.load(); changed['global']['disable_back_home_whitelist'] = 'user-choice'; self.save(changed)
        before = len(self.writes())
        result = self.restore()
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(changed, self.load())
        self.assertEqual(before, len(self.writes()))

if __name__ == '__main__':
    unittest.main()
