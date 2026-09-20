#!/usr/bin/env python3
"""Create isolated, resource-limited databases and seed the million-row usage benchmark.

No existing database/container is modified. Containers are retained for the JVM/HTTP
benchmark and removed only by an explicit `cleanup` invocation of this script.
"""
import argparse
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / 'target' / 'admin-storage-performance'
PASSWORD = 'usage-perf-local-only'

def run(args, **kwargs):
    return subprocess.run(args, check=True, **kwargs)

def client(backend, name, sql, stdout):
    if backend == 'mysql':
        args = ['docker','exec','-i','-e','MYSQL_PWD='+PASSWORD,name,'mysql','-uroot','kkrepo_usage_perf']
    else:
        args = ['docker','exec','-i',name,'psql','-v','ON_ERROR_STOP=1','-U','usageperf','-d','kkrepo_usage_perf']
    run(args,input=sql,text=True,stdout=stdout,stderr=subprocess.STDOUT)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('backend',choices=['mysql','postgresql','cleanup'])
    args=parser.parse_args()
    if args.backend == 'cleanup':
        for backend in ['mysql','postgresql']:
            subprocess.run(['docker','rm','-f','-v','kkrepo-usage-perf-'+backend],check=False)
        return
    backend=args.backend;name='kkrepo-usage-perf-'+backend
    REPORT.mkdir(parents=True,exist_ok=True)
    if subprocess.run(['docker','inspect',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
        raise SystemExit(name+' already exists; use the existing fixture or run cleanup first')
    cmd=['docker','run','-d','--name',name,'--cpus','2','--memory','3g']
    if backend=='mysql':
        cmd+=['-p','127.0.0.1:23306:3306','-e','MYSQL_ROOT_PASSWORD='+PASSWORD,'-e','MYSQL_DATABASE=kkrepo_usage_perf','mysql:8.0','--innodb-buffer-pool-size=1G']
    else:
        cmd+=['-p','127.0.0.1:25432:5432','-e','POSTGRES_PASSWORD='+PASSWORD,'-e','POSTGRES_USER=usageperf','-e','POSTGRES_DB=kkrepo_usage_perf','postgres:12','-c','shared_buffers=1GB']
    run(cmd)
    with (REPORT/(backend+'-setup.txt')).open('w') as log:
        for attempt in range(90):
            try:
                client(backend,name,'SELECT 1;',log)
                break
            except subprocess.CalledProcessError:
                time.sleep(1)
        else:
            raise SystemExit('Database did not become ready')
        migrations=ROOT/('persistence-mysql' if backend=='mysql' else 'persistence-postgresql')/'src/main/resources/db/migration'/backend
        for path in sorted(migrations.glob('V*.sql'),key=lambda p:int(p.name.split('__')[0][1:])):
            print('Applying',backend,path.name,flush=True)
            client(backend,name,path.read_text(),log)
        print('Seeding 1,000,000 blobs + 1,000,000 assets on',backend,flush=True)
        started=time.monotonic()
        client(backend,name,(ROOT/'scripts/perf'/('admin-storage-usage-'+backend+'.sql')).read_text(),log)
        print('Seed complete:',backend,round(time.monotonic()-started,1),'seconds',flush=True)
        client(backend,name,'SELECT VERSION();',log)
    print('Plans and setup log:',REPORT/(backend+'-setup.txt'))

if __name__=='__main__':main()
