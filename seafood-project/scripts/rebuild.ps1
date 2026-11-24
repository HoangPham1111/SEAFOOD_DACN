$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
mvn clean package
if ($LASTEXITCODE -ne 0) { throw 'Biên dịch thất bại. Cần JDK 17+ và Maven 3.9+.' }
New-Item -ItemType Directory -Force dist | Out-Null
Copy-Item target/seafood-2.0.0.jar dist/seafood-2.0.0.jar -Force
docker compose up -d --build app
