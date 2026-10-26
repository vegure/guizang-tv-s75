#!/usr/bin/env python3
"""Reversible, opt-in S75 startup configuration. Default operation is read-only."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

PACKAGES = ('io.github.op7418.guizangtv', 'io.github.op7418.guizangtv.s75test')
KEYS = (('system', 'start_3rd_app'), ('system', 'mitv_device_customize_skip'),
        ('global', 'disable_back_home_whitelist'))
TIMEOUT = 15


class ToolError(Exception):
    pass


class CommandUncertain(ToolError):
    pass


class Adb:
    def __init__(self, serial):
        self.serial = serial

    def command(self, *parts):
        # adb joins remote arguments through a shell: quote every remote value.
        remote = shlex.join(parts)
        try:
            result = subprocess.run(['adb', '-s', self.serial, 'shell', remote],
                                    text=True, capture_output=True, timeout=TIMEOUT)
        except subprocess.TimeoutExpired as error:
            raise CommandUncertain('ADB timed out; remote result is uncertain') from error
        except OSError as error:
            raise ToolError('Cannot launch ADB') from error
        for marker in ('SecurityException', 'Permission denial', 'java.lang.', 'Error:'):
            if marker in result.stdout or marker in result.stderr:
                raise ToolError('ADB reported a device-side error')
        if result.returncode != 0:
            raise CommandUncertain('ADB command failed; remote result is uncertain (exit %s)' % result.returncode)
        return result.stdout.rstrip('\r\n')

    def identity(self):
        model = self.command('getprop', 'ro.product.model')
        sdk = self.command('getprop', 'ro.build.version.sdk')
        hardware = self.command('getprop', 'ro.serialno')
        if hardware.strip().lower() in ('', 'unknown', 'null'):
            hardware = self.command('getprop', 'ro.boot.serialno')
        if hardware.strip().lower() in ('', 'unknown', 'null'):
            hardware = ''
        firmware = self.command('getprop', 'ro.build.version.incremental')
        if model != 'MiTV-MFTR0' or sdk != '34':
            raise ToolError('Only MiTV-MFTR0 / Android 14 (SDK 34) is supported')
        return dict(serial=self.serial, model=model, sdk=sdk,
                    hardware_serial=hardware, firmware=firmware)

    def installed(self, package):
        if package not in PACKAGES:
            raise ToolError('Only the GuiZang release or S75 verification package is supported')
        if not self.command('pm', 'path', package).startswith('package:'):
            raise ToolError('The selected GuiZang package is not installed')

    def settings(self, namespace):
        values = {}
        for line in self.command('settings', 'list', namespace).splitlines():
            if not line:
                continue
            key, separator, value = line.partition('=')
            if not separator or not key or key in values:
                raise ToolError('Invalid or ambiguous settings list response')
            values[key] = value
        return values

    def read(self, namespace, key):
        values = self.settings(namespace)
        return dict(exists=key in values, value=values.get(key))

    def write(self, namespace, key, value):
        if (namespace, key) not in KEYS:
            raise ToolError('Refusing an unsupported settings key')
        if value['exists']:
            self.command('settings', 'put', namespace, key, value['value'])
        else:
            self.command('settings', 'delete', namespace, key)


def save_backup(path, data, *, create=False):
    payload = json.dumps(data, ensure_ascii=False, indent=2) + '\n'
    if create:
        # O_EXCL also rejects existing symlinks; never overwrite a prior backup.
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        return
    descriptor, temporary = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def inspect(adb, package):
    device = adb.identity()
    adb.installed(package)
    return dict(operation='inspect', model=device['model'], sdk=device['sdk'],
                firmware=device['firmware'], hardware_identity_available=bool(device['hardware_serial']),
                package=package, settings={ns + '.' + key: adb.read(ns, key) for ns, key in KEYS},
                effect='只读检查；设置存在或读回一致不代表开机/待机效果已经验证。')


def apply(adb, args):
    device = adb.identity()
    adb.installed(args.package)
    if not device['hardware_serial'] or not device['firmware']:
        raise ToolError('Cannot bind a reversible backup without hardware identity and firmware')
    selected = [(KEYS[0], args.package)]
    if args.skip_device_customization:
        selected.append((KEYS[1], 'true'))
    if args.experimental_standby:
        selected.append((KEYS[2], 'disable'))
    records = [dict(namespace=ns, key=key, previous=adb.read(ns, key),
                    applied=dict(exists=True, value=value), state='pending')
               for (ns, key), value in selected]
    backup = dict(version=1, created_at=datetime.now(timezone.utc).isoformat(),
                  **device, package=args.package, keys=records, complete=False)
    path = Path(args.backup)
    save_backup(path, backup, create=True)
    for record in records:
        ns, key = record['namespace'], record['key']
        if adb.read(ns, key) != record['previous']:
            raise ToolError('Settings changed before apply; no overwrite: ' + key)
        # Journal uncertainty before sending: a timeout, lost response, or host
        # interruption must not discard a remote write that can still arrive.
        record['state'] = 'write-uncertain'
        save_backup(path, backup)
        try:
            adb.write(ns, key, record['applied'])
        except CommandUncertain:
            raise
        except ToolError:
            record['state'] = 'write-started'
            save_backup(path, backup)
            raise
        record['state'] = 'write-started'
        save_backup(path, backup)
        if adb.read(ns, key) != record['applied']:
            raise ToolError('Settings readback mismatch; backup retained: ' + key)
        record['state'] = 'written'
        save_backup(path, backup)
    backup['complete'] = True
    save_backup(path, backup)
    return dict(operation='apply', backup=str(path), readback_verified=True,
                selected_settings=[record['namespace'] + '.' + record['key'] for record in records],
                skip_device_customization=args.skip_device_customization,
                experimental_standby=args.experimental_standby,
                effect='设置写入并读回一致；电视实际开机与待机效果未验证。'
                       + ('已选择实验待机选项：仅有其他机型证据，会影响第三方应用清理。'
                          if args.experimental_standby else '未选择实验待机选项。'))


def validate_backup(data):
    if not isinstance(data, dict) or data.get('version') != 1 or data.get('package') not in PACKAGES:
        raise ToolError('Unsupported backup format or package')
    for key in ('serial', 'model', 'sdk', 'hardware_serial', 'firmware'):
        if not isinstance(data.get(key), str) or not data[key]:
            raise ToolError('Backup is missing device identity')
    records = data.get('keys')
    if not isinstance(records, list) or not records or len(records) > 3:
        raise ToolError('Invalid backup settings')
    seen = set()
    expected = (data['package'], 'true', 'disable')
    states = ('pending', 'write-uncertain', 'write-started', 'written', 'unchanged', 'restore-started', 'restored')
    for record in records:
        if not isinstance(record, dict):
            raise ToolError('Invalid backup key record')
        pair = (record.get('namespace'), record.get('key'))
        if pair not in KEYS or pair in seen or record.get('state') not in states:
            raise ToolError('Unsupported or duplicate backup key/state')
        seen.add(pair)
        if record.get('applied') != dict(exists=True, value=expected[KEYS.index(pair)]):
            raise ToolError('Invalid applied value in backup')
        previous = record.get('previous', {})
        if not isinstance(previous, dict) or type(previous.get('exists')) is not bool or (
                previous['exists'] and not isinstance(previous.get('value'), str)) or (
                not previous['exists'] and previous.get('value') is not None):
            raise ToolError('Invalid original value in backup')


def restore(adb, args):
    path = Path(args.backup)
    data = json.loads(path.read_text(encoding='utf-8'))
    validate_backup(data)
    if data['serial'] != adb.serial:
        raise ToolError('Backup belongs to another ADB serial')
    device = adb.identity()
    if any(device[key] != data[key] for key in device):
        raise ToolError('Device hardware identity or firmware does not match backup')
    candidates = []
    # Preflight every conflict before restoring anything, then check again
    # immediately before each write so newer user changes are not overwritten.
    for record in data['keys']:
        if record['state'] in ('pending', 'unchanged', 'restored'):
            continue
        current = adb.read(record['namespace'], record['key'])
        if record['state'] == 'write-uncertain' and current == record['previous']:
            if record['previous'] != record['applied']:
                raise ToolError('Restore unresolved: remote write is still uncertain: ' + record['key'])
            record['state'] = 'restored'
            continue
        if record['state'] in ('write-started', 'restore-started') and current == record['previous']:
            record['state'] = 'restored'
            continue
        if current != record['applied']:
            raise ToolError('Restore conflict: current value changed: ' + record['key'])
        candidates.append(record)
    save_backup(path, data)
    for record in reversed(candidates):
        ns, key = record['namespace'], record['key']
        if adb.read(ns, key) != record['applied']:
            raise ToolError('Restore conflict: current value changed: ' + key)
        record['state'] = 'restore-started'
        save_backup(path, data)
        adb.write(ns, key, record['previous'])
        if adb.read(ns, key) != record['previous']:
            raise ToolError('Restore readback mismatch; backup retained: ' + key)
        record['state'] = 'restored'
        save_backup(path, data)
    return dict(operation='restore', backup=str(path), readback_verified=True,
                effect='已恢复备份原值；电视实际行为仍需验证。')


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0].startswith('-'):
        argv.insert(0, 'inspect')
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='operation', required=True)
    read = sub.add_parser('inspect', help='Read-only inspection (default)')
    write = sub.add_parser('apply', help='Explicitly apply selected settings with a new backup')
    undo = sub.add_parser('restore', help='Restore only settings that still match the applied values')
    for item in (read, write, undo):
        item.add_argument('--serial', required=True)
    read.add_argument('--package', default=PACKAGES[0], choices=PACKAGES)
    write.add_argument('--package', required=True, choices=PACKAGES)
    for item in (write, undo):
        item.add_argument('--backup', required=True)
    write.add_argument('--skip-device-customization', action='store_true',
                       help='Opt in: System mitv_device_customize_skip=true (device customization sync)')
    write.add_argument('--experimental-standby', action='store_true',
                       help='Opt in: Global disable_back_home_whitelist=disable (S75 effect unverified)')
    args = parser.parse_args(argv)
    try:
        adb = Adb(args.serial)
        if args.operation == 'inspect':
            result = inspect(adb, args.package)
        elif args.operation == 'apply':
            result = apply(adb, args)
        else:
            result = restore(adb, args)
    except (ToolError, OSError, ValueError, KeyError, TypeError) as error:
        print('ERROR: ' + str(error), file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    sys.exit(main())
