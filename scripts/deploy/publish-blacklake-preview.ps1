param([Parameter(Mandatory=$true)][string]$ReceiptRoot)
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Force -Path $ReceiptRoot | Out-Null
$taskConfig=Join-Path $PSScriptRoot 'blacklake-preview-test.nginx.conf'
$taskRemoteScript=@'
set -eu
target=/www/server/panel/vhost/nginx/test.cretaceousfuture.com.conf
candidate=/tmp/cretas-blacklake-preview.nginx.conf
backup=/www/server/panel/vhost/nginx/test.cretaceousfuture.com.conf.blacklake-preview-backup
lock=/tmp/cretas-blacklake-preview-publish.lock
exec 9>"$lock"
flock -n 9 || exit 2
# Only replace the retired test vhost or our own previous preview configuration.
grep -qE 'return 410|Standalone test preview' "$target" || { echo 'Unexpected test-vhost owner; stopped'; exit 3; }
if grep -q 'Standalone test preview' "$target"; then
    test -f "$backup" || { echo 'Missing retired-test backup; stopped'; exit 3; }
else
    if [ ! -f "$backup" ]; then cp -p "$target" "$backup"; fi
fi
grep -q 'return 410' "$backup" || { echo 'Invalid retired-test backup; stopped'; exit 3; }
old=$(mktemp /tmp/cretas-blacklake-preview-old.XXXXXX)
cp -p "$target" "$old"
cp "$candidate" "$target"
if ! /www/server/nginx/sbin/nginx -t; then cp -p "$old" "$target"; exit 4; fi
if ! /www/server/nginx/sbin/nginx -s reload; then cp -p "$old" "$target"; /www/server/nginx/sbin/nginx -s reload; exit 5; fi
sha256sum "$target" "$backup"
echo BLACKLAKE_PREVIEW_TEST_VHOST=activated
'@
$taskScript=Join-Path $ReceiptRoot 'publish-preview.sh'
[IO.File]::WriteAllText($taskScript,($taskRemoteScript.Replace("`r`n","`n")+"`n"),[Text.UTF8Encoding]::new($false))
& scp.exe -q $taskConfig 'root@139.196.165.140:/tmp/cretas-blacklake-preview.nginx.conf'
if($LASTEXITCODE -ne 0){throw 'Preview config upload failed'}
& scp.exe -q $taskScript 'root@139.196.165.140:/tmp/cretas-blacklake-preview-publish.sh'
if($LASTEXITCODE -ne 0){throw 'Preview publisher upload failed'}
& ssh.exe -o BatchMode=yes 'root@139.196.165.140' 'bash /tmp/cretas-blacklake-preview-publish.sh' *> (Join-Path $ReceiptRoot 'nginx-publish.log')
if($LASTEXITCODE -ne 0){throw 'Preview activation failed; inspect local receipt'}
Get-Content -LiteralPath (Join-Path $ReceiptRoot 'nginx-publish.log') -Tail 4
