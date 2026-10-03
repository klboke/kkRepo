#!/usr/bin/env bash
set -euo pipefail

# Standalone and CI entrypoint: external Git remote, native LFS transfers to kkRepo.
export KKREPO_COMPAT_BASE_URL="${KKREPO_COMPAT_BASE_URL:-http://127.0.0.1:18090}"
export KKREPO_COMPAT_USERNAME="${KKREPO_COMPAT_USERNAME:-admin}"
export KKREPO_COMPAT_PASSWORD="${KKREPO_COMPAT_PASSWORD:-12345678}"
export GIT_TERMINAL_PROMPT=0
export GIT_LFS_E2E_REPOSITORY="gitlfs-client-$(date +%s)-$$"
artifact_dir="${CLIENT_E2E_ARTIFACT_DIR:-artifacts/client-e2e}"
mkdir -p "$artifact_dir"
artifact_dir="$(cd "$artifact_dir" && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/kkrepo-gitlfs-client.XXXXXX")"
trap 'rm -rf "$work"' EXIT

git lfs version | tee "$artifact_dir/git-lfs-version.txt"
git --version >> "$artifact_dir/git-lfs-version.txt"
python3 - <<'PY'
import base64, json, os, urllib.request
base=os.environ['KKREPO_COMPAT_BASE_URL'].rstrip('/')
repo=os.environ['GIT_LFS_E2E_REPOSITORY']
auth=base64.b64encode((os.environ['KKREPO_COMPAT_USERNAME']+':'+os.environ['KKREPO_COMPAT_PASSWORD']).encode()).decode()
request=urllib.request.Request(base+'/internal/repositories',
    json.dumps({'name':repo,'recipe':'gitlfs-hosted','online':True,'blobStoreName':'default','strictContentTypeValidation':False,'hosted':{'writePolicy':'ALLOW_ONCE'}}).encode(),
    {'Authorization':'Basic '+auth,'Content-Type':'application/json'},method='POST')
with urllib.request.urlopen(request) as response:
    assert response.status==201
PY

git init --bare --quiet "$work/remote.git"
git init --quiet -b main "$work/source"
cd "$work/source"
git config user.email 'lfs-e2e@example.invalid'
git config user.name 'Git LFS E2E'
# Keep credentials outside files and URLs. The helper receives them from the CI environment.
export GIT_CONFIG_COUNT=2
export GIT_CONFIG_KEY_0=credential.helper
export GIT_CONFIG_VALUE_0='!f() { printf "%s\n" "username=$KKREPO_COMPAT_USERNAME" "password=$KKREPO_COMPAT_PASSWORD"; }; f'
export GIT_CONFIG_KEY_1=credential.useHttpPath
export GIT_CONFIG_VALUE_1=true
git lfs install --local
git config -f .lfsconfig lfs.url "$KKREPO_COMPAT_BASE_URL/repository/$GIT_LFS_E2E_REPOSITORY/info/lfs"
git lfs track '*.bin'
python3 - <<'PY'
from pathlib import Path
Path('history.bin').write_bytes(b'first revision\n' * 700000)
PY
git add .gitattributes .lfsconfig history.bin
git commit --quiet -m 'First large asset revision'
first="$(git rev-parse HEAD)"
cp history.bin "$work/first.bin"
python3 - <<'PY'
from pathlib import Path
Path('history.bin').write_bytes(b'second revision\n' * 700000)
for index in range(3):
    Path(f'parallel-{index}.bin').write_bytes(f'parallel-{index}\n'.encode()*10000)
PY
cp history.bin "$work/second.bin"
git add '*.bin'
git commit --quiet -m 'Update large asset and add parallel transfers'
git remote add origin "$work/remote.git"
git push origin main
git lfs push --all origin
git lfs fsck
# A separate clone and empty LFS cache prove downloads come from the repository service.
GIT_LFS_SKIP_SMUDGE=1 git clone --quiet -b main "$work/remote.git" "$work/clone"
cd "$work/clone"
git lfs install --local
git lfs pull
cmp history.bin "$work/second.bin"
git lfs fetch --all
git checkout --quiet "$first"
cmp history.bin "$work/first.bin"
git lfs fsck

# Exercise the native client with a URL-scoped GenericToken and an empty LFS cache.
read -r lfs_token_id lfs_token <<< "$(python3 - <<'PY'
import base64,json,os,urllib.request
base=os.environ['KKREPO_COMPAT_BASE_URL'].rstrip('/')
auth=base64.b64encode((os.environ['KKREPO_COMPAT_USERNAME']+':'+os.environ['KKREPO_COMPAT_PASSWORD']).encode()).decode()
request=urllib.request.Request(base+'/internal/security/api-keys/current',
    json.dumps({'domain':'GenericToken','displayName':'Native LFS E2E'}).encode(),
    {'Authorization':'Basic '+auth,'Content-Type':'application/json'},method='POST')
with urllib.request.urlopen(request) as response:
    created=json.load(response)
    print(created['apiKey']['id'],created['token'])
PY
)"
export GIT_CONFIG_COUNT=3
export GIT_CONFIG_KEY_2="http.$KKREPO_COMPAT_BASE_URL/repository/$GIT_LFS_E2E_REPOSITORY/.extraHeader"
export GIT_CONFIG_VALUE_2="Authorization: Bearer $lfs_token"
git config user.email 'lfs-e2e@example.invalid'
git config user.name 'Git LFS E2E'
printf 'GenericToken native-client upload\n' > token.bin
git add token.bin
git commit --quiet -m 'Publish with a CI token'
git lfs push origin HEAD
git -c "lfs.storage=$work/token-lfs" lfs fetch --all
export GIT_CONFIG_COUNT=2
unset GIT_CONFIG_KEY_2 GIT_CONFIG_VALUE_2 lfs_token
LFS_TOKEN_ID="$lfs_token_id" python3 - <<'PY'
import base64,os,urllib.request
base=os.environ['KKREPO_COMPAT_BASE_URL'].rstrip('/')
auth=base64.b64encode((os.environ['KKREPO_COMPAT_USERNAME']+':'+os.environ['KKREPO_COMPAT_PASSWORD']).encode()).decode()
request=urllib.request.Request(base+'/internal/security/api-keys/current/'+os.environ['LFS_TOKEN_ID'],
    headers={'Authorization':'Basic '+auth},method='DELETE')
with urllib.request.urlopen(request) as response: assert response.status==200
PY

python3 - "$artifact_dir/cleanup-gitlfs.json" <<'PY'
import base64,json,os,sys,urllib.request,urllib.error
base=os.environ['KKREPO_COMPAT_BASE_URL'].rstrip('/')
auth=base64.b64encode((os.environ['KKREPO_COMPAT_USERNAME']+':'+os.environ['KKREPO_COMPAT_PASSWORD']).encode()).decode()
headers={'Authorization':'Basic '+auth,'Content-Type':'application/json'}
with urllib.request.urlopen(urllib.request.Request(base+'/internal/cleanup/capabilities',headers=headers)) as response:
    caps=json.load(response)
cap=next(c for c in caps if c['format']=='gitlfs')
assert not cap['tryRunSupported'] and not cap['executeSupported']
with urllib.request.urlopen(urllib.request.Request(base+'/internal/repositories/'+os.environ['GIT_LFS_E2E_REPOSITORY'],headers=headers)) as response:
    repository=json.load(response)
payload={'name':'lfs-cleanup-must-fail','format':'gitlfs','criteria':{'lastDownloadedOlderThanDays':1},'repositoryIds':[repository['id']]}
try:
    urllib.request.urlopen(urllib.request.Request(base+'/internal/cleanup/policies',json.dumps(payload).encode(),headers,method='POST'))
    raise AssertionError('LFS cleanup policy was accepted')
except urllib.error.HTTPError as error:
    assert error.code==400
    assert 'not supported' in error.read().decode()
with open(sys.argv[1],'w') as f:json.dump({'automaticCleanupRejected':True,'capability':cap},f,indent=2)
PY
printf 'Git LFS push, repeat push, clone/pull, historical checkout, fetch --all, fsck and GenericToken passed.\n'
