#!/usr/bin/env python3
"""Create isolated Nexus/kkRepo LFS fixtures and verify hosted import plus resume."""
import base64,hashlib,json,os,time,urllib.request,urllib.error
SOURCE=os.environ.get('NEXUS_COMPAT_BASE_URL','http://127.0.0.1:28090').rstrip('/')
TARGET=os.environ.get('KKREPO_COMPAT_BASE_URL','http://127.0.0.1:18090').rstrip('/')
ARTIFACT=__import__('pathlib').Path(os.environ.get('CLIENT_E2E_ARTIFACT_DIR','artifacts/git-lfs-migration'))
ARTIFACT.mkdir(parents=True,exist_ok=True)
NAME='gitlfs-migration-'+str(int(time.time()))

def call(base,path,method='GET',data=None):
    prefix='NEXUS' if base==SOURCE else 'KKREPO'
    username=os.environ.get(prefix+'_COMPAT_USERNAME','admin')
    password=os.environ.get(prefix+'_COMPAT_PASSWORD','admin123' if base==SOURCE else '12345678')
    auth=base64.b64encode((username+':'+password).encode()).decode()
    headers={'Authorization':'Basic '+auth,'Content-Type':'application/vnd.git-lfs+json' if '/repository/' in path else 'application/json'}
    raw=json.dumps(data).encode() if isinstance(data,(dict,list)) else data
    if isinstance(data,bytes):headers['Content-Type']='application/octet-stream'
    req=urllib.request.Request(base+path,raw,headers,method=method)
    try:r=urllib.request.urlopen(req,timeout=120)
    except urllib.error.HTTPError as e:raise AssertionError((base,path,e.code,e.read().decode()))
    raw=r.read()
    return json.loads(raw) if raw and 'json' in r.headers.get('Content-Type','') else raw


call(SOURCE,'/service/rest/v1/repositories/gitlfs/hosted','POST',{'name':NAME,'online':True,'storage':{'blobStoreName':'default','strictContentTypeValidation':False,'writePolicy':'ALLOW_ONCE'}})
fixtures=[b'old history revision\n'*500000,b'latest history revision\n'*500000,b'']
objects=[{'oid':hashlib.sha256(data).hexdigest(),'size':len(data)} for data in fixtures]
for obj,data in zip(objects,fixtures):
    result=call(SOURCE,'/repository/'+NAME+'/info/lfs/objects/batch','POST',{'operation':'upload','objects':[obj]})
    href=result['objects'][0]['actions']['upload']['href']
    call(SOURCE,href.removeprefix(SOURCE),'PUT',data)
call(TARGET,'/internal/repositories','POST',{'name':NAME,'recipe':'gitlfs-hosted','online':True,'blobStoreName':'default','strictContentTypeValidation':False,'hosted':{'writePolicy':'ALLOW_ONCE'}})
request={'sourceBaseUrl':SOURCE,'sourceUsername':os.environ.get('NEXUS_COMPAT_USERNAME','admin'),'sourcePassword':os.environ.get('NEXUS_COMPAT_PASSWORD','admin123'),'repositories':[NAME],'pageSize':100,'concurrency':2,'checksumValidation':True}
preflight=call(TARGET,'/internal/migration/nexus/preflight','POST',request)
assert 'gitlfs' in json.dumps(preflight), 'LFS absent from preflight'
print('preflight completed',flush=True)
status=call(TARGET,'/internal/migration/nexus/repository-data/start','POST',request)
job=status['jobId'];print('job',job,flush=True)
base='/internal/migration/nexus/repository-data/jobs/'+str(job)
for _ in range(60):
    status=call(TARGET,base)
    if status['discoveredAssets']==len(objects):break
    time.sleep(1)
call(TARGET,base+'/packages/start','POST')
for _ in range(60):
    status=call(TARGET,base)
    print({k:v for k,v in status.items() if not isinstance(v,(list,dict))},flush=True)
    if status.get('migratedAssets',0)>=len(objects) or status.get('failedAssets',0)>0:break
    time.sleep(2)
(ARTIFACT/'git-lfs-migration.json').write_text(json.dumps({k:v for k,v in status.items() if k not in ('sourceProfile','migrationPlan')},indent=2)+'\n')
assert status['migratedAssets']==len(objects),status
for obj,data in zip(objects,fixtures):
    assert call(TARGET,'/repository/'+NAME+'/'+obj['oid'])==data
    call(TARGET,'/repository/'+NAME+'/'+obj['oid']+'/verify','POST',obj)
call(TARGET,base+'/metadata/start','POST')
call(TARGET,base+'/packages/start','POST')
time.sleep(2)
status=call(TARGET,base)
assert status['migratedAssets']==len(objects) and status['failedAssets']==0,status
print('Migration bytes, SHA-256, empty object and resume verified for',NAME,flush=True)
