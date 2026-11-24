# OceanFlow Seafood 2.0

Hệ thống quản lý nhập khẩu, xuất khẩu, điều chuyển, tồn kho đa kho, giao nhận và hồ sơ hải sản. Ứng dụng sử dụng Java/Spring Boot ở backend, PostgreSQL/PostGIS làm cơ sở dữ liệu và HTML/CSS/JavaScript ở frontend.

README này dành cho người clone project từ GitHub. Các lệnh dùng đường dẫn tương đối và có thể chạy trong bất kỳ thư mục nào.

## Chạy nhanh bằng Docker

Yêu cầu: Docker Desktop đang chạy; cổng `8080` và `5433` chưa bị chiếm.

```powershell
git clone <URL_REPOSITORY>
cd seafood-v2
docker compose up -d --build
```

Mở **http://localhost:8080**. Kiểm tra bằng `docker compose ps` và `docker compose logs --tail=100 app`.

## 1. Công nghệ và thư viện
### Backend
- Java 17 hoặc cao hơn.
- Spring Boot `3.3.5`.
- Spring Web: REST API và phục vụ static resources.
- Spring JDBC: truy cập PostgreSQL bằng JDBC.
- Spring Security Crypto: băm và kiểm tra mật khẩu.
- PostgreSQL JDBC Driver: kết nối PostgreSQL.
- OpenPDF `1.3.39`: tạo PDF chứng từ.
- Apache Commons CSV `1.12.0`: đọc/ghi CSV.
- Spring Boot Starter Test: thư viện kiểm thử Maven.
### Frontend
- HTML5, CSS3 và JavaScript thuần.
- Leaflet và OpenStreetMap cho bản đồ.
### Cơ sở dữ liệu và triển khai
- PostgreSQL.
- PostGIS image `postgis/postgis:17-3.5` khi chạy bằng Docker Compose.
- Docker và Docker Compose.
- Maven 3.9+ để biên dịch từ source.
- Eclipse Temurin JRE 17 trong Docker runtime image.

## 2. Cấu trúc project

```text
seafood-v2/
├── database/                  # Schema và dữ liệu mẫu
├── dist/seafood-2.0.0.jar     # JAR dùng để chạy nhanh/Docker
├── scripts/                   # Script chạy, build và backup
├── src/main/java/             # Mã nguồn Java
├── src/main/resources/        # Cấu hình, giao diện, ảnh, Leaflet, font PDF
├── tests/                     # Kiểm thử nếu có
├── compose.yaml               # PostgreSQL/PostGIS và app bằng Docker
├── Dockerfile                 # Runtime Docker sử dụng JAR trong dist
├── pom.xml                    # Cấu hình Maven và dependency
├── run.ps1                    # Chạy JAR local trên Windows
└── README.md
```

## 3. Yêu cầu môi trường

### Chạy từ JAR trên Windows

- Windows 10/11.
- Java Runtime hoặc JDK 17+.
- PostgreSQL đang chạy và có database `seafood`.
- Có file `dist/seafood-2.0.0.jar`.

### Build từ source

- JDK 17+.
- Maven 3.9+.
- Internet ở lần đầu để Maven tải dependency.
- PostgreSQL phù hợp với cấu hình kết nối.

### Chạy bằng Docker

- Docker Desktop đang chạy.
- Docker Compose v2+.
- Các cổng chưa bị chương trình khác sử dụng: `5433` cho PostgreSQL container và `8080` cho app container.

## 4. Cấu hình ứng dụng

File cấu hình:

```text
src/main/resources/application.properties
```

| Biến | Giá trị mặc định | Ý nghĩa |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5412/seafood` | JDBC URL PostgreSQL local |
| `DB_USER` | `seafood` | Tài khoản database |
| `DB_PASSWORD` | `SeafoodLocal2026` | Mật khẩu database local |
| `PORT` | `8081` | Cổng web local |

Ứng dụng đọc biến môi trường theo dạng:

```properties
spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5412/seafood}
spring.datasource.username=${DB_USER:seafood}
spring.datasource.password=${DB_PASSWORD:SeafoodLocal2026}
server.port=${PORT:8081}
```

Các cấu hình khác:

- Session timeout: 60 phút.
- Cookie session: `http-only`, `same-site=strict`.
- File upload tối đa: 10 MB; multipart request tối đa: 12 MB.
- Múi giờ JSON: `Asia/Ho_Chi_Minh`.

> Khi chạy Docker Compose, app dùng `jdbc:postgresql://db:5432/seafood` bên trong network Docker và mở tại `http://localhost:8080`. Khi chạy JAR bằng `run.ps1`, app dùng PostgreSQL tại `localhost:5412` và mở tại `http://localhost:8081`.

## 5. Cài đặt và chạy bằng Docker Compose

Docker Compose sử dụng `Dockerfile`, trong đó JAR được copy từ `dist/seafood-2.0.0.jar`. Repository cần có file JAR này; nếu bạn đã sửa source, hãy build và copy JAR theo mục 7 trước khi build Docker.

Mở PowerShell tại thư mục vừa clone:

```powershell
cd <THU_MUC_DA_CLONE>\seafood-v2
docker compose up -d --build
```

Kiểm tra:

```powershell
docker compose ps
docker compose logs --tail=100 app
docker compose logs --tail=100 db
```

Mở `http://localhost:8080`.

Lần khởi tạo database đầu tiên, Docker mount `database/01_schema.sql` và `database/02_seed.sql`. Các script init không tự chạy lại khi volume đã tồn tại.

Dừng app nhưng giữ dữ liệu:

```powershell
docker compose down
```

> Không chạy `docker compose down -v` trên database đang dùng nếu chưa sao lưu, vì lệnh này xóa volume PostgreSQL.

## 6. Cài đặt và chạy JAR local bằng PowerShell

PostgreSQL local cần có:

```text
Host: localhost:5412
Database: seafood
User: seafood
Password: SeafoodLocal2026
```

Chạy bằng script:

```powershell
cd <THU_MUC_DA_CLONE>\seafood-v2
powershell -ExecutionPolicy Bypass -File .\run.ps1
```

Mở `http://localhost:8081`.

Chạy thủ công:

```powershell
$env:DB_URL='jdbc:postgresql://localhost:5412/seafood'
$env:DB_USER='seafood'
$env:DB_PASSWORD='SeafoodLocal2026'
$env:PORT='8081'
java -jar .\dist\seafood-2.0.0.jar
```

## 7. Build và chạy từ source bằng Maven

```powershell
cd <THU_MUC_DA_CLONE>\seafood-v2
mvn clean package
```

JAR được tạo tại `target/seafood-2.0.0.jar`. Cập nhật JAR dùng bởi `run.ps1`/Docker:

```powershell
New-Item -ItemType Directory -Force .\dist | Out-Null
Copy-Item .\target\seafood-2.0.0.jar .\dist\seafood-2.0.0.jar -Force
```

Chạy Maven với đúng database:

```powershell
$env:DB_URL='jdbc:postgresql://localhost:5412/seafood'
$env:DB_USER='seafood'
$env:DB_PASSWORD='SeafoodLocal2026'
$env:PORT='8081'
mvn spring-boot:run
```

Mở `http://localhost:8081`.

> Nên đặt lại các biến môi trường trong cùng cửa sổ PowerShell trước khi chạy Maven. Biến môi trường cũ có thể làm ứng dụng kết nối nhầm database hoặc port.

## 8. Tài khoản demo

Mật khẩu demo mặc định:

```text
Demo@2026!
```

| Email | Vai trò |
|---|---|
| `admin@demo.vn` | Quản trị viên |
| `admin2@demo.vn` | Quản trị viên dự phòng |
| `dieuphoi@demo.vn` | Nhân viên điều phối |
| `khoa@demo.vn` | Nhân viên kho A |
| `khob@demo.vn` | Nhân viên kho B |
| `khoc@demo.vn` | Nhân viên kho C |

Không mở tài khoản demo trực tiếp ra Internet.

## 9. Kiểm tra database

Khi database chạy bằng Docker Compose:

```powershell
docker compose exec -T db psql -U seafood -d seafood -c "select email, role, status from app_user;"
```

Kiểm tra container:

```powershell
docker compose ps
```

## 10. Kiểm thử

Các test trong `tests/` có thể yêu cầu database thử nghiệm đã seed và có thể tạo dữ liệu mới. Chỉ chạy trên môi trường thử nghiệm, không chạy tùy tiện trên database người dùng.

Ví dụ:

```powershell
python .\tests\integration.py http://localhost:8080 --allow-write
node .\tests\browser.cjs
```

Test trình duyệt có thể yêu cầu Node.js, Playwright và app đang chạy. Không chạy integration test nếu chưa xác định đúng database và port.

## 11. Sao lưu database

Khi PostgreSQL chạy bằng Docker Compose:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\backup.ps1
```

Script tạo thư mục `backups/` và file backup theo thời gian.

## 12. Xử lý lỗi thường gặp

### Kết nối nhầm database

Đóng app đang chạy và đặt lại:

```powershell
$env:DB_URL='jdbc:postgresql://localhost:5412/seafood'
$env:DB_USER='seafood'
$env:DB_PASSWORD='SeafoodLocal2026'
$env:PORT='8081'
mvn spring-boot:run
```

### Port 8081 đã được sử dụng

Tắt app cũ hoặc đổi port:

```powershell
$env:PORT='8082'
mvn spring-boot:run
```

### Port 8080 đã được sử dụng khi chạy Docker

```powershell
docker compose ps
docker ps
```

Dừng app cũ trước khi chạy lại.

### Giao diện còn dùng JavaScript/CSS cũ

Dùng `Ctrl + Shift + R`. Nếu cần, mở DevTools và chọn `Empty Cache and Hard Reload`.

## 13. Lưu ý an toàn dữ liệu

- Không xóa Docker volume PostgreSQL nếu chưa có backup.
- Không chạy lại `database/02_seed.sql` trên database đang sử dụng.
- Không thay database thật bằng localStorage hoặc dữ liệu giả trên UI.
- Khi thay đổi schema, cần migration phù hợp thay vì sửa trực tiếp rồi seed lại.
- Chỉ xác nhận dữ liệu nghiệp vụ qua API/server; không bỏ qua kiểm tra backend vì UI đã kiểm tra.
- Cấu hình trong project là cấu hình local/trình diễn, không mặc định là production.
