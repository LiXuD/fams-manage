"""Exercise competing administrator sessions against the task-owned real HTTP/MySQL stack."""
import concurrent.futures
import http.cookiejar
import json
import subprocess
import threading
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    runtime = subprocess.Popen(['rtk', 'proxy', 'python3', str(ROOT / 'scripts/run-d6-15-browser-runtime.py')],
        cwd=ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    try:
        while True:
            line = runtime.stdout.readline()
            if not line: raise RuntimeError('Owned runtime did not become ready')
            print(line.strip(), flush=True)
            if line.startswith('READY '): break
        def login(username):
            jar = http.cookiejar.CookieJar()
            opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
            request = urllib.request.Request('http://127.0.0.1:8080/api/v1/auth/login',
                json.dumps({'username': username, 'password': 'correct-horse-battery-staple'}).encode(),
                {'Content-Type': 'application/json'}, method='POST')
            with opener.open(request, timeout=15) as reply: token = json.load(reply)['csrfToken']
            return '; '.join(c.name + '=' + c.value for c in jar), token
        employee, admin, warehouse, admin2 = [login(n) for n in ['employee','admin','warehouse','admin']]
        def call(actor, path, body=None, version=None, key=None):
            headers = {'Cookie': actor[0]}
            method = 'GET' if version is None else 'POST'
            if method == 'POST': headers.update({'X-CSRF-Token': actor[1], 'Idempotency-Key': key or str(uuid.uuid4()), 'If-Match-Version': str(version)})
            if body is not None: headers['Content-Type'] = 'application/json'
            request = urllib.request.Request('http://127.0.0.1:8080/api/v1/transfers' + path,
                None if body is None else json.dumps(body).encode(), headers, method=method)
            try:
                with urllib.request.urlopen(request, timeout=20) as reply: return reply.status, json.load(reply)
            except urllib.error.HTTPError as error: return error.code, json.load(error)
        def write(actor, row, action, body=None):
            status, result = call(actor, '/' + row['id'] + action, body, row['version'])
            assert status == 200, (status, result)
            return result
        status, row = call(employee, '', {'reason':'D6-15 concurrent reassignment proof', 'items':[{
            'assetId':'10','assetVersion':0,'target':{'owningOrganizationId':'1','custodyType':'PERSONAL',
            'usingOrganizationId':'2','employeeId':'4','warehouseId':None,'locationId':'2',
            'referenceVersions':{'owningOrganization':0,'usingOrganization':0,'employee':0,'location':0}}}]},0)
        assert status == 200, (status,row)
        row=write(employee,row,'/submit');row=write(admin,row,'/decision',{'approved':True,'reason':'Approved scope'})
        row=write(warehouse,row,'/claim')
        status,snapshot=call(admin,'/'+row['id']+'/reassignment');assert status==200
        body={'processorAccountId':'4','ownershipVersion':snapshot['ownershipVersion'],'reason':'Absence',
            'evidence':'Checked ready physical item and active lock; no unsynchronized results',
            'unsynchronizedResultsChecked':True,'items':[{'id':i['id'],'version':i['version'],
                'status':i['itemStatus'],'lockVersion':i['lockVersion']} for i in snapshot['items']]}
        barrier=threading.Barrier(2)
        def compete(actor):
            barrier.wait(timeout=10)
            return call(actor,'/'+row['id']+'/reassignment',body,row['version'])
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            results=list(executor.map(compete,[admin,admin2]))
        assert sorted(s for s,_ in results)==[200,409],results
        status,final=call(admin,'/'+row['id']);assert status==200
        assert final['processorAccountId']=='4' and final['ownershipVersion']==1
        assert sum(e['eventType']=='CONTROLLED_REASSIGNMENT' for e in final['events'])==1
        print(json.dumps({'competingStatuses':sorted(s for s,_ in results),'processorAccountId':final['processorAccountId'],
            'ownershipVersion':final['ownershipVersion'],'reassignmentEvents':1},ensure_ascii=False),flush=True)
        runtime.stdin.write('counts\nstop\n');runtime.stdin.flush()
        print(runtime.communicate(timeout=60)[0],flush=True)
        assert runtime.returncode==0
    finally:
        if runtime.poll() is None:
            runtime.stdin.write('stop\n');runtime.stdin.flush()
            print(runtime.communicate(timeout=60)[0],flush=True)
            if runtime.returncode: raise RuntimeError('Owned stack cleanup failed')

if __name__=='__main__': main()
