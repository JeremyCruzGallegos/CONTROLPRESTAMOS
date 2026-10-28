#!/usr/bin/env python3
"""Configura una instalación NUEVA desde un JSON local, sin modificar ningún libro."""
import argparse,json,re
from pathlib import Path
from urllib.parse import urlsplit
ROOT=Path(__file__).resolve().parents[1]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--config',type=Path,default=ROOT/'config/proyecto.local.json');p.add_argument('--check',action='store_true');a=p.parse_args()
 c=json.loads(a.config.read_text());book=c.get('spreadsheetId','')
 if not re.fullmatch(r'[A-Za-z0-9_-]{20,}',book) or 'REPLACE' in book: p.error('Indica el ID del libro NUEVO ya inicializado')
 for key in ['supportEmail','adminEmail']:
  if not re.fullmatch(r'[^\s@]+@[^\s@]+\.[^\s@]+',c.get(key,'')):p.error('Completa '+key)
 for key in ['publicOrigin','privacyBaseUrl']:
  u=urlsplit(c.get(key,''))
  if u.scheme!='https' or not u.hostname or u.username or u.password or u.query or u.fragment:p.error('URL HTTPS inválida: '+key)
  if key=='publicOrigin' and u.path not in ('','/'):p.error('publicOrigin debe ser solo el origen HTTPS')
 if not c.get('oauthWebClientId','').endswith('.apps.googleusercontent.com'):p.error('Completa oauthWebClientId con el cliente WEB')
 if a.check:print('Configuración local válida; no se escribió ningún archivo.');return
 identity=ROOT/'app/src/main/java/com/example/controlprestamos/data/google/SheetsIdentity.kt'
 identity.write_text(re.sub(r'const val CLIENT_ID = "[^"]+"','const val CLIENT_ID = '+json.dumps(book),identity.read_text()))
 connection={'productionBookId':book,'testBookId':'','deploymentId':'','scriptScopes':[],'copyWritesVerified':False}
 (ROOT/'app/src/main/res/raw/google_connection.json').write_text(json.dumps(connection,indent=2)+'\n')
 for name in ['app','consultas']:
  (ROOT/name/'src/main/res/raw/consultas_connection.json').write_text(json.dumps({'apiBaseUrl':c['publicOrigin'].rstrip('/'),'supportEmail':c['supportEmail']},indent=2)+'\n')
 wrangler=ROOT/'consultas-api/wrangler.toml';s=wrangler.read_text()
 for key,value in {'PUBLIC_ORIGIN':c['publicOrigin'].rstrip('/'),'GOOGLE_CLIENT_ID':c['oauthWebClientId'],'ADMIN_EMAIL':c['adminEmail'],'SUPPORT_EMAIL':c['supportEmail'],'SPREADSHEET_ID':book}.items():s=re.sub(r'^'+key+r' = .*$',key+' = '+json.dumps(value),s,flags=re.M)
 wrangler.write_text(s)
 branding=ROOT/'app/src/main/java/com/example/controlprestamos/ui/Branding.kt';s=branding.read_text().replace('https://tu-dominio.example',c['privacyBaseUrl'].rstrip('/'));branding.write_text(s)
 print('Configuración aplicada SOLO a esta copia local. No se creó, leyó ni modificó un libro remoto.')
 print('Configura los secretos del Worker y compila los APK. No publiques proyecto.local.json ni claves.')
if __name__=='__main__':main()
