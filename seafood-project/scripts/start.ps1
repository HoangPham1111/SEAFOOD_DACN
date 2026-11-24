$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
docker compose up -d --build
if ($LASTEXITCODE -ne 0) { throw 'Docker chưa khởi động hoặc không tải được image. Xem HUONG_DAN_CAI_DAT.md.' }
Write-Host 'Đang khởi động. Mở http://localhost:8080 sau khoảng 20 giây.'
docker compose ps
