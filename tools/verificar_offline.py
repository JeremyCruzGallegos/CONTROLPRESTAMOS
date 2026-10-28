#!/usr/bin/env python3
"""Offline compiler verifier for sandboxes where Gradle cannot open its lock sockets.
Uses cached Maven artifacts, Kotlin/Compose compilers, Android SDK and JUnit.
Does not replace Gradle's dependency resolution, manifest merge, lint or device tests.
"""
import os, re, json, shutil, subprocess, sys, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path(os.environ.get('CONTROL_MAVEN_CACHE', str(Path.home() / '.gradle/caches/modules-2/files-2.1')))
SDK = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/Sdk')))
VARIANT = os.environ.get('CONTROL_BUILD_VARIANT', 'cliente')
if VARIANT != 'cliente':
    raise ValueError('La app antigua está retirada. La única aplicación de gestión es cliente.')
OUT = ROOT / 'app/build/offline-cliente'
OUT.mkdir(parents=True, exist_ok=True)

def cached(group, artifact, version, extension='jar'):
    folder = CACHE / group / artifact / version
    files = [p for p in folder.rglob('*.' + extension) if p.name == f'{artifact}-{version}.{extension}']
    if not files: raise RuntimeError(f'No está en caché: {group}:{artifact}:{version} ({extension})')
    return files[0]

def run(args):
    result = subprocess.run([str(x) for x in args], cwd=ROOT)
    if result.returncode: raise SystemExit(result.returncode)

compiler = [cached('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.10'),
            cached('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.10'),
            cached('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.10'),
            cached('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10')]
for group, artifact in [('org.jetbrains', 'annotations')]:
    folder = CACHE / group / artifact
    files = [p for p in folder.rglob('*.jar') if not any(s in p.name for s in ['sources', 'javadoc'])]
    compiler.append(files[0])
compiler.append(cached('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'))
plugin = cached('org.jetbrains.kotlin', 'kotlin-compose-compiler-plugin-embeddable', '2.2.10')
stdlib = cached('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.10')
ns = {'m': 'http://maven.apache.org/POM/4.0.0'}
locked = dict((tuple(x.split(':')[:2]), x.split(':')[2]) for x in json.loads((ROOT/'tools/offline-dependencies.json').read_text()))
try:
    bom = ET.parse(cached('androidx.compose', 'compose-bom', '2026.02.01', 'pom'))
    versions = {(d.findtext('m:groupId', namespaces=ns), d.findtext('m:artifactId', namespaces=ns)): d.findtext('m:version', namespaces=ns) for d in bom.findall('.//m:dependency', ns)}
except RuntimeError:
    print('BOM no disponible: usando las versiones registradas en tools/offline-dependencies.json.', flush=True)
    versions = {key: value for key, value in locked.items() if key[0].startswith('androidx.compose')}
selected = {}
constraints = dict(locked)
def vkey(v): return tuple(int(x) for x in re.findall(r'\d+', v))
def resolve(g, a, v):
    v = versions.get((g, a), v).strip('[]')
    constraint = constraints.get((g,a))
    if constraint and vkey(constraint) > vkey(v): v = constraint
    if g == 'org.jetbrains.kotlin' and a.startswith('kotlin-stdlib'): v = '2.2.10'
    old = selected.get((g, a))
    if old and vkey(old) >= vkey(v): return
    selected[(g, a)] = v
    folder = CACHE / g / a / v
    mods = list(folder.rglob('*.module'))
    if mods:
        variants = json.loads(mods[0].read_text()).get('variants', [])
        choices = [x for x in variants if x.get('attributes', {}).get('org.gradle.usage') == 'java-runtime' and x.get('attributes', {}).get('org.jetbrains.kotlin.platform.type', 'androidJvm') in ['androidJvm', 'jvm'] and x.get('attributes', {}).get('org.gradle.category', 'library') == 'library']
        if choices:
            variant = next((x for x in choices if 'android' in x['name'].lower()), choices[0])
            if 'available-at' in variant:
                dep = variant['available-at']; resolve(dep['group'], dep['module'], dep['version'])
            for dep in variant.get('dependencyConstraints', []):
                version = dep.get('version', {}).get('requires')
                if version:
                    key = (dep['group'], dep['module'])
                    if key not in constraints or vkey(version) > vkey(constraints[key]): constraints[key] = version
                    if key in selected: resolve(*key, version)
            for dep in variant.get('dependencies', []):
                version = dep.get('version', {}); version = version.get('requires') or version.get('strictly') or version.get('prefers')
                if version: resolve(dep['group'], dep['module'], version)
            return
    poms = list(folder.rglob('*.pom'))
    if poms:
        for dep in ET.parse(poms[0]).findall('./m:dependencies/m:dependency', ns):
            if dep.findtext('m:scope', namespaces=ns) in ['test', 'provided']: continue
            group = dep.findtext('m:groupId', namespaces=ns); art = dep.findtext('m:artifactId', namespaces=ns); version = dep.findtext('m:version', namespaces=ns)
            if version and '${' not in version: resolve(group, art, version)

for (g, a), v in locked.items(): resolve(g, a, v)
for g, a, v in [('androidx.activity','activity-compose','1.8.0'), ('androidx.core','core-ktx','1.10.1'),
                ('androidx.lifecycle','lifecycle-runtime-ktx','2.6.1'), ('androidx.lifecycle','lifecycle-viewmodel','2.6.1'),
                ('androidx.compose.material3','material3','1.4.0'), ('androidx.compose.ui','ui-tooling-preview','1.10.4'),
                ('com.google.android.gms','play-services-auth','22.0.0'),
                ('org.hamcrest','hamcrest-core','1.3'), ('junit','junit','4.13.2')]: resolve(g,a,v)
# Re-resolve transitive dependencies after version conflict selection.
classpath = [SDK / 'platforms/android-37.0/android.jar', stdlib]
aars=[]
for (g,a),v in sorted(selected.items()):
    folder = CACHE/g/a/v
    jars = [p for p in folder.rglob('*.jar') if p.name == f'{a}-{v}.jar']
    archives = [p for p in folder.rglob('*.aar') if True]
    if not jars and not archives and not list(folder.rglob('*.module')) and not list(folder.rglob('*.pom')):
        # Cached Gradle variants can retain the binary without the common module descriptor.
        variants = [(g, a+'-android'), (g, a+'-jvm')]
        if not any(key in selected for key in variants) and not a.endswith('-bom') and a != 'kotlin-stdlib-common':
            raise RuntimeError(f'Falta la dependencia offline: {g}:{a}:{v}')
    classpath.extend(jars)
    for archive in archives:
        target = OUT/'libraries'/f'{g}.{a}-{v}'
        target.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(archive) as z: z.extractall(target)
        classpath.extend([p for p in target.rglob('*.jar') if p.name not in ['lint.jar','inspector.jar']])
        aars.append(target)
(OUT/'dependencies.json').write_text(json.dumps([f'{g}:{a}:{v}' for (g,a),v in sorted(selected.items())], indent=2))
(OUT/'classpath.txt').write_text(':'.join(str(p) for p in classpath))
(OUT/'aars.json').write_text(json.dumps([str(p) for p in aars]))
# Generate real resource IDs before compiling Kotlin sources that reference R.
run([sys.executable, ROOT/'tools/empaquetar_demo_offline.py', '--resources-only'])
classpath.append(OUT/'rclasses')
cmd=['java','-Xmx2g','-cp',':'.join(str(p) for p in compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
     '-no-stdlib','-no-reflect','-jvm-target','11','-classpath',':'.join(str(p) for p in classpath)]
main = OUT/'classes'
if main.exists(): shutil.rmtree(main)
main.mkdir()
print('Compilando Kotlin + Compose con dependencias locales…', flush=True)
run(cmd+['-Xplugin='+str(plugin),'-d',main]+(sorted(ROOT.glob('app/src/main/java/**/*.kt')) + sorted(ROOT.glob('shared/consultas/src/main/java/**/*.kt')) + sorted(ROOT.glob('shared/presentation/src/main/java/**/*.kt'))))
tests = OUT/'test-classes'
if tests.exists(): shutil.rmtree(tests)
tests.mkdir()
print('Compilando pruebas…', flush=True)
# Match Gradle's test compilation: tests may inspect internal declarations in the app module.
run(cmd[:-2]+['-classpath',':'.join(str(p) for p in classpath+[main]),'-Xfriend-paths='+str(main),'-d',tests]+sorted(ROOT.glob('app/src/test/java/**/*.kt')))
print('Ejecutando JUnit…', flush=True)
# android.jar is excluded from runtime: domain and repository tests must be pure JVM.
runtime = [str(p) for p in classpath if p.name != 'android.jar'] + [str(main),str(tests)]
# Repository tests need the real Android JSON implementation, not android.jar's stubs.
# Compile the installed AOSP SDK sources only for the JVM runner; never package them in the APK.
json_sources = sorted((SDK/'sources').glob('android-*/org/json/JSONObject.java'))
if not json_sources:
    raise RuntimeError('Instala Android SDK Sources para ejecutar las pruebas JSON offline')
json_src = OUT/'test-json-src'; json_classes = OUT/'test-json-classes'
json_src.mkdir(exist_ok=True); json_classes.mkdir(exist_ok=True)
for source in sorted(json_sources[-1].parent.glob('*.java')):
    content = re.sub(r'^import (?:static )?(?:android\.|libcore\.).*;\s*$', '', source.read_text(), flags=re.M)
    content = re.sub(r'@(NonNull|Nullable|UnsupportedAppUsage|SystemApi)\b(?:\([^)]*\))?', '', content)
    (json_src/source.name).write_text(content)
run(['javac', '--release', '11', '-d', json_classes] + sorted(json_src.glob('*.java')))
runtime.insert(0, str(json_classes))
test_classes = ['.'.join(p.relative_to(ROOT/'app/src/test/java').with_suffix('').parts)
                for p in sorted(ROOT.glob('app/src/test/java/**/*Test.kt'))]
run(['java','-cp',':'.join(runtime),'org.junit.runner.JUnitCore'] + test_classes)
print('Compilación Kotlin/Compose y pruebas JVM correctas.', flush=True)
