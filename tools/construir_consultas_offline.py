#!/usr/bin/env python3
"""Build the independent consultations app with the existing cached Android toolchain.

Uses resources/dependencies already extracted by verificar_offline.py. No financial
app classes, Google Sheets credentials or Google authorization SDK are packaged.
"""
import os
import argparse
import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET
import re

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / 'consultas'
OUT = MODULE / 'build/offline'
BASE = ROOT / 'app/build/offline-cliente'
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
TOOLS = SDK / 'build-tools/36.0.0'
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'
NAMESPACE = 'com.example.controlprestamos.consultas'
ANDROID = 'http://schemas.android.com/apk/res/android'

def run(args):
    subprocess.run([str(item) for item in args], cwd=ROOT, check=True)

def cached(group, artifact, version):
    return next((CACHE / group / artifact / version).rglob(f'{artifact}-{version}.jar'))

args = argparse.ArgumentParser()
args.add_argument('--api-url', help='HTTPS origin of the deployed consultations Worker')
options = args.parse_args()
OUT.mkdir(parents=True, exist_ok=True)
if not (BASE / 'classpath.txt').exists():
    raise SystemExit('Faltan las dependencias preparadas: ejecutar tools/verificar_offline.py primero.')

def allowed(path):
    text = str(path)
    return not any(value in text for value in ('com.google.android.gms', 'com.google.android.libraries', '/junit/', '/org.hamcrest/'))

aars = [Path(path) for path in json.loads((BASE / 'aars.json').read_text()) if allowed(path)]
classpath = [Path(path) for path in (BASE / 'classpath.txt').read_text().split(':') if allowed(path) and not path.endswith('/rclasses')]
source_res = MODULE / 'src/main/res'
if options.api_url is not None:
    origin = options.api_url.rstrip('/')
    parsed = urlsplit(origin)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password or parsed.path or parsed.query or parsed.fragment:
        raise SystemExit('--api-url debe ser un origen HTTPS sin ruta, credenciales ni parámetros.')
    staged = OUT / 'app-res'
    if staged.exists(): shutil.rmtree(staged)
    shutil.copytree(source_res, staged)
    config = json.loads((staged / 'raw/consultas_connection.json').read_text())
    config['apiBaseUrl'] = origin
    (staged / 'raw/consultas_connection.json').write_text(json.dumps(config))
    source_res = staged

ET.register_namespace('android', ANDROID)
manifest = ET.parse(MODULE / 'src/main/AndroidManifest.xml').getroot()
manifest.set('package', NAMESPACE)
gradle_config = (MODULE / 'build.gradle.kts').read_text()
version_code = int(re.search(r'versionCode\s*=\s*(\d+)', gradle_config).group(1))
version_name = re.search(r'versionName\s*=\s*"([0-9.]+)"', gradle_config).group(1)
manifest.set(f'{{{ANDROID}}}versionCode', str(version_code))
manifest.set(f'{{{ANDROID}}}versionName', version_name)
for node in manifest.iter():
    name = node.get(f'{{{ANDROID}}}name', '')
    if name.startswith('.'): node.set(f'{{{ANDROID}}}name', NAMESPACE + name)
ET.SubElement(manifest, 'uses-sdk', {f'{{{ANDROID}}}minSdkVersion': '26', f'{{{ANDROID}}}targetSdkVersion': '37'})
app = manifest.find('application')
app.set(f'{{{ANDROID}}}debuggable', 'true')
packages = set()

def merge(parent, child):
    name = child.get(f'{{{ANDROID}}}name')
    existing = next((node for node in parent if node.tag == child.tag and node.get(f'{{{ANDROID}}}name') == name), None)
    if existing is None:
        parent.append(child)
    else:
        # Several libraries contribute metadata to the same Startup provider.
        # Dropping later children breaks eager ProcessLifecycle initialization.
        for attribute, value in child.attrib.items():
            if attribute not in existing.attrib: existing.set(attribute, value)
        for descendant in child: merge(existing, descendant)

for library in aars:
    path = library / 'AndroidManifest.xml'
    if not path.exists(): continue
    tree = ET.fromstring(path.read_text().replace('${applicationId}', NAMESPACE))
    if tree.get('package'): packages.add(tree.get('package'))
    for node in tree:
        if node.tag in ('uses-permission', 'permission'): merge(manifest, node)
        if node.tag == 'application':
            for child in node:
                if child.tag in ('provider', 'service', 'receiver', 'meta-data'): merge(app, child)
startup = next(node for node in app if node.tag == 'provider' and node.get(f'{{{ANDROID}}}name') == 'androidx.startup.InitializationProvider')
initializers = {node.get(f'{{{ANDROID}}}name') for node in startup if node.tag == 'meta-data'}
assert {'androidx.emoji2.text.EmojiCompatInitializer', 'androidx.lifecycle.ProcessLifecycleInitializer',
        'androidx.profileinstaller.ProfileInstallerInitializer'} <= initializers, 'Missing AndroidX startup metadata'
ET.ElementTree(manifest).write(OUT / 'AndroidManifest.xml' , encoding='utf-8', xml_declaration=True)
resources = []
for index, folder in enumerate([item / 'res' for item in aars] + [source_res]):
    if folder.exists() and any(folder.rglob('*.*')):
        target = OUT / f'resources-{index}.zip'
        run([TOOLS / 'aapt2', 'compile', '--dir', folder, '-o', target])
        resources += ['-R', target]
generated = OUT / 'generated'
if generated.exists(): shutil.rmtree(generated)
generated.mkdir()
run([TOOLS / 'aapt2', 'link', '--auto-add-overlay', '-I', SDK / 'platforms/android-37.0/android.jar',
     '--manifest', OUT / 'AndroidManifest.xml', '--java', generated, '--custom-package', NAMESPACE,
     '--extra-packages', ':'.join(sorted(packages)), '-o', OUT / 'resources.apk'] + resources)
rclasses = OUT / 'rclasses'
if rclasses.exists(): shutil.rmtree(rclasses)
rclasses.mkdir()
run(['javac', '--release', '11', '-classpath', SDK / 'platforms/android-37.0/android.jar', '-d', rclasses] + sorted(generated.rglob('*.java')))
compiler = [cached('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.10'),
            cached('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.10'),
            cached('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.10'),
            cached('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
            cached('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
            next((CACHE / 'org.jetbrains/annotations').rglob('annotations-23.0.0.jar'))]
cmd = ['java', '-Xmx2g', '-cp', ':'.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
       '-no-stdlib', '-no-reflect', '-jvm-target', '11', '-classpath', ':'.join(map(str, classpath + [rclasses]))]
classes = OUT / 'classes'
if classes.exists(): shutil.rmtree(classes)
classes.mkdir()
print('Compilando ControlPrestamos Consultas…', flush=True)
run(cmd + ['-Xplugin=' + str(cached('org.jetbrains.kotlin', 'kotlin-compose-compiler-plugin-embeddable', '2.2.10')),
           '-d', classes] + (sorted((MODULE / 'src/main/java').rglob('*.kt')) + sorted((ROOT / 'shared/consultas/src/main/java').rglob('*.kt')) + sorted((ROOT / 'shared/presentation/src/main/java').rglob('*.kt'))))
tests = sorted((MODULE / 'src/test/java').rglob('*Test.kt'))
if tests:
    test_classes = OUT / 'test-classes'; test_classes.mkdir(exist_ok=True)
    junit = cached('junit', 'junit', '4.13.2'); hamcrest = cached('org.hamcrest', 'hamcrest-core', '1.3')
    test_cp = classpath + [rclasses, classes, junit, hamcrest]
    run(cmd[:-2] + ['-classpath', ':'.join(map(str, test_cp)), '-Xfriend-paths=' + str(classes), '-d', test_classes] + tests)
    runtime = [path for path in test_cp if path.name != 'android.jar'] + [test_classes, BASE / 'test-json-classes']
    names = ['.'.join(path.relative_to(MODULE / 'src/test/java').with_suffix('').parts) for path in tests]
    run(['java', '-cp', ':'.join(map(str, runtime)), 'org.junit.runner.JUnitCore'] + names)
run(['jar', 'cf', OUT / 'app.jar', '-C', classes, '.', '-C', rclasses, '.'])
dex = OUT / 'dex'
if dex.exists(): shutil.rmtree(dex)
dex.mkdir()
runtime_jars = list(dict.fromkeys(path for path in classpath if path.name != 'android.jar'))
run([TOOLS / 'd8', '--debug', '--min-api', '26', '--lib', SDK / 'platforms/android-37.0/android.jar', '--output', dex, OUT / 'app.jar'] + runtime_jars)
unsigned = OUT / 'unsigned.apk'; shutil.copyfile(OUT / 'resources.apk', unsigned)
with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as archive:
    existing = set(archive.namelist()); services = {}
    for path in dex.glob('*.dex'): archive.write(path, path.name); existing.add(path.name)
    for library in aars:
        for prefix, folder in [('assets', library / 'assets'), ('lib', library / 'jni')]:
            for path in folder.rglob('*') if folder.exists() else []:
                name = prefix + '/' + str(path.relative_to(folder))
                if path.is_file() and name not in existing: archive.write(path, name); existing.add(name)
    for jar in runtime_jars:
        if jar.suffix != '.jar': continue
        with zipfile.ZipFile(jar) as source:
            for name in source.namelist():
                if name.endswith(('.class', '/')) or name.startswith('META-INF/') and not name.startswith('META-INF/services/'): continue
                if name.startswith('META-INF/services/'):
                    services[name] = services.get(name, b'') + source.read(name) + b'\n'; continue
                if name not in existing: archive.writestr(name, source.read(name)); existing.add(name)
    for name, value in services.items(): archive.writestr(name, value)
run([TOOLS / 'zipalign', '-f', '-p', '4', unsigned, OUT / 'aligned.apk'])
final = OUT / f'ControlPrestamos-Consultas-{version_name}.apk'
run([TOOLS / 'apksigner', 'sign', '--ks', Path.home() / '.android/debug.keystore', '--ks-pass', 'pass:android', '--out', final, OUT / 'aligned.apk'])
run([TOOLS / 'apksigner', 'verify', '--verbose', final])
digest = hashlib.sha256(final.read_bytes()).hexdigest()
(OUT / (final.name + '.sha256')).write_text(digest + '  ' + final.name + '\n')
distribution = MODULE / 'build/distribucion'
distribution.mkdir(parents=True, exist_ok=True)
delivered = distribution / final.name
shutil.copy2(final, delivered)
shutil.copy2(OUT / (final.name + '.sha256'), distribution / (final.name + '.sha256'))
print('APK:', final)
print('APK de distribución:', delivered)
print('SHA-256:', digest)
