#!/usr/bin/env python3
"""Build a Google-enabled debug APK from verificar_offline.py output without Gradle.
A fallback build, not a replacement for the AGP manifest merger or Android device tests.
"""
import hashlib, json, os, shutil, subprocess, sys, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET
from version_app import read_version
ROOT=Path(__file__).resolve().parents[1]
VARIANT=os.environ.get('CONTROL_BUILD_VARIANT','cliente')
PRODUCTION=os.environ.get('CONTROL_PRODUCTION_BUILD','0')=='1'
if VARIANT!='cliente':raise ValueError('La app antigua está retirada. La única aplicación de gestión es cliente.')
NAMESPACE='com.example.controlprestamos'
APPLICATION_ID='com.example.controlprestamos.cliente'
OUT=ROOT/'app/build/offline-cliente'
SDK=Path(os.environ.get('ANDROID_HOME',str(Path.home() / 'Android/Sdk')))
TOOLS=SDK/'build-tools/36.0.0'
ANDROID='http://schemas.android.com/apk/res/android'
ET.register_namespace('android',ANDROID)
def run(args):
    result=subprocess.run([str(x) for x in args],cwd=ROOT)
    if result.returncode: raise SystemExit(result.returncode)
aars=[Path(x) for x in json.loads((OUT/'aars.json').read_text())]
manifest=ET.fromstring((ROOT/'app/src/main/AndroidManifest.xml').read_text().replace('${applicationId}',APPLICATION_ID))
manifest.set('package',APPLICATION_ID)
# AGP resolves relative component names against the namespace, not the applicationId suffix.
for node in manifest.iter():
    name=node.get('{'+ANDROID+'}name','')
    if name.startswith('.'):node.set('{'+ANDROID+'}name',NAMESPACE+name)
version_code, version_name = read_version()
manifest.set('{'+ANDROID+'}versionCode',str(version_code)); manifest.set('{'+ANDROID+'}versionName',version_name)
ET.SubElement(manifest,'uses-sdk',{'{'+ANDROID+'}minSdkVersion':'26','{'+ANDROID+'}targetSdkVersion':'37'})
app=manifest.find('application');app.set('{'+ANDROID+'}debuggable','false' if PRODUCTION else 'true')
packages=[]
def merge_children(parent,child):
    name=child.get('{'+ANDROID+'}name')
    existing=next((x for x in parent if x.tag==child.tag and x.get('{'+ANDROID+'}name')==name),None)
    if existing is None: parent.append(child)
    else:
        for item in child: merge_children(existing,item)
for lib in aars:
    path=lib/'AndroidManifest.xml'
    if not path.exists():continue
    tree=ET.fromstring(path.read_text().replace('${applicationId}',APPLICATION_ID))
    pkg=tree.get('package')
    if pkg:packages.append(pkg)
    for node in tree:
        if node.tag=='uses-permission':merge_children(manifest,node)
        if node.tag=='application':
            for child in node:
                if child.tag in ['provider','service','receiver','meta-data']:merge_children(app,child)
ET.ElementTree(manifest).write(OUT/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
resources=[]
resource_folders=[x/'res' for x in aars]+[ROOT/'app/src/main/res']
for index,folder in enumerate(resource_folders):
    if folder.exists() and any(folder.rglob('*.*')):
        target=OUT/f'resources-{index}.zip'
        run([TOOLS/'aapt2','compile','--dir',folder,'-o',target]);resources+=['-R',str(target)]
java=OUT/'generated'
if java.exists():shutil.rmtree(java)
java.mkdir()
apk=OUT/'resources.apk'
run([TOOLS/'aapt2','link','--auto-add-overlay','-I',SDK/'platforms/android-37.0/android.jar','--manifest',OUT/'AndroidManifest.xml',
     '--java',java,'--custom-package',NAMESPACE,'--extra-packages',':'.join(sorted(set(packages))),'-o',apk]+resources)
rclasses=OUT/'rclasses'
if rclasses.exists():shutil.rmtree(rclasses)
rclasses.mkdir()
javafiles=list(java.rglob('*.java'))
run(['javac','-source','11','-target','11','-classpath',SDK/'platforms/android-37.0/android.jar','-d',rclasses]+javafiles)
if '--resources-only' in sys.argv:
    print('Recursos Android compilados; clases R generadas.')
    raise SystemExit(0)
appjar=OUT/'app-classes.jar'
run(['jar','cf',appjar,'-C',OUT/'classes','.','-C',rclasses,'.'])
classpath=[Path(x) for x in (OUT/'classpath.txt').read_text().split(':')]
# JUnit and Hamcrest are used only by the JVM verifier, never bundled into the app.
classpath=list(dict.fromkeys(x for x in classpath if x.name!='android.jar' and '/junit/' not in str(x) and '/org.hamcrest/' not in str(x)))
dex=OUT/'dex'
if dex.exists():shutil.rmtree(dex)
dex.mkdir()
run([TOOLS/'d8','--release' if PRODUCTION else '--debug','--min-api','26','--lib',SDK/'platforms/android-37.0/android.jar','--output',dex,appjar]+classpath)
unsigned=OUT/'controlprestamos-demo-unsigned.apk';shutil.copy2(apk,unsigned)
with zipfile.ZipFile(unsigned,'a',zipfile.ZIP_DEFLATED) as dest:
    existing=set(dest.namelist());services={}
    for file in dex.glob('*.dex'):dest.write(file,file.name);existing.add(file.name)
    for lib in aars:
        for prefix,folder in [('assets',lib/'assets'),('lib',lib/'jni')]:
            if folder.exists():
                for file in folder.rglob('*'):
                    if not file.is_file():continue
                    name=prefix+'/'+str(file.relative_to(folder))
                    if name not in existing:dest.write(file,name);existing.add(name)
    for file in classpath:
        if file.suffix!='.jar':continue
        with zipfile.ZipFile(file) as source:
            for name in source.namelist():
                if name.endswith(('.class','/')) or name.startswith('META-INF/') and not name.startswith('META-INF/services/'):continue
                if name.startswith('META-INF/services/'):
                    services[name]=services.get(name,b'')+source.read(name)+b'\n';continue
                if name not in existing:dest.writestr(name,source.read(name));existing.add(name)
    for name,data in services.items():dest.writestr(name,data)
aligned=OUT/'controlprestamos-demo-aligned.apk'
run([TOOLS/'zipalign','-f','-p','4',unsigned,aligned])
# Keep the installed app and registered Google OAuth certificate when Android Studio's key exists.
key=Path.home()/'.android/debug.keystore'
if not key.exists(): key=OUT/'demo-debug.keystore'
if PRODUCTION and not key.exists():raise SystemExit('La distribución requiere la clave existente para conservar la firma y OAuth.')
if not key.exists():run(['keytool','-genkeypair','-keystore',key,'-storepass','android','-keypass','android','-alias','androiddebugkey','-dname','CN=Android Debug,O=Android,C=US','-keyalg','RSA','-validity','10000'])
final=OUT/'controlprestamos-cliente.apk'
run([TOOLS/'apksigner','sign','--ks',key,'--ks-pass','pass:android','--out',final,aligned])
run([TOOLS/'apksigner','verify','--verbose',final])
if PRODUCTION:
    badging=subprocess.check_output([str(TOOLS/'aapt2'),'dump','badging',str(final)],text=True)
    if 'application-debuggable' in badging:raise SystemExit('El APK de distribución no debe permitir depuración.')
    distribution=ROOT/'app/build/distribucion';distribution.mkdir(parents=True,exist_ok=True)
    delivered=distribution/f'ControlPrestamos-Cliente-{version_name}.apk'
    shutil.copy2(final,delivered)
    digest=hashlib.sha256(delivered.read_bytes()).hexdigest()
    Path(str(delivered)+'.sha256').write_text(digest+'  '+delivered.name+'\n')
    Path(str(delivered)+'.verificacion.json').write_text(json.dumps({
        'applicationId':APPLICATION_ID,'versionName':version_name,'versionCode':version_code,
        'sha256':digest,'apkSignatureVerified':True,'debuggable':False,
        'signaturePolicy':'Existing installation certificate preserved; no key rotation',
        'googleOAuthDataAccessApproval':'Not independently verified',
        'deviceUsability':'User reports client tested usability and was satisfied'
    },indent=2)+'\n')
    print('APK de distribución:',delivered)
print('APK con SDK Google generado:',final)
