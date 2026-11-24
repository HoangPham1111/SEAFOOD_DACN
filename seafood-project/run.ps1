$env:DB_URL = 'jdbc:postgresql://localhost:5412/seafood'
$env:DB_USER = 'seafood'
$env:DB_PASSWORD = 'SeafoodLocal2026'
$env:PORT = '8081'

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Khoi dong he thong OceanFlow Seafood..." -ForegroundColor Green
Write-Host "  Port Web: http://localhost:$env:PORT" -ForegroundColor Yellow
Write-Host "  Database: $env:DB_URL" -ForegroundColor Gray
Write-Host "==========================================" -ForegroundColor Cyan

java -jar dist/seafood-2.0.0.jar
