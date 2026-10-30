# Fetch timetable on this machine and push it to the repo (for Task Scheduler).
# Credentials come from env vars or .env.local next to this script (gitignored).
# Keep this file ASCII-only: Windows PowerShell 5 reads .ps1 as ANSI (GBK on zh-CN).
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $here '..\..')

$envFile = Join-Path $here '.env.local'
if (Test-Path $envFile) {
  Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([A-Z_]+)\s*=\s*(.*)$') {
      [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim(), 'Process')
    }
  }
}

if (-not $env:CT_USERNAME -or -not $env:CT_PASSWORD) {
  Write-Host 'ERROR: CT_USERNAME / CT_PASSWORD not set (use env vars or .env.local)'
  exit 1
}

$log = Join-Path $here 'sync.log'

Push-Location (Join-Path $root 'web')
try {
  $output = & node tools/fetch-timetable.js 2>&1
  $code = $LASTEXITCODE
  $output | ForEach-Object { Write-Host $_ }
  $output | Out-File -FilePath $log -Encoding utf8
} finally {
  Pop-Location
}

if ($code -ne 0) {
  Write-Host "ERROR: fetch failed (exit code $code), see $log"
  exit $code
}

Push-Location $root
try {
  git add web/public/data/timetable.json
  $changed = git status --porcelain -- web/public/data/timetable.json
  if ($changed) {
    git commit -m 'chore(web): sync timetable data' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'git commit failed' }
    git push
    if ($LASTEXITCODE -ne 0) { throw 'git push failed' }
    Write-Host 'OK: committed and pushed timetable update'
  } else {
    Write-Host 'OK: timetable unchanged'
  }
} finally {
  Pop-Location
}
