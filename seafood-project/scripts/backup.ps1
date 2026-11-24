$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force backups | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
docker compose exec -T db pg_dump -U seafood -d seafood -Fc -f /tmp/seafood.backup
if ($LASTEXITCODE -ne 0) { throw 'Sao lưu thất bại' }
docker compose cp db:/tmp/seafood.backup "backups/seafood-$stamp.backup"
Write-Host "Đã sao lưu: backups/seafood-$stamp.backup"
