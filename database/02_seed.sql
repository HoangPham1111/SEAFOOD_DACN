INSERT INTO warehouse(code,name,address,capacity,temperature,location) VALUES
('KHO-A','Kho TP. Hồ Chí Minh','Thành phố Hồ Chí Minh',2000000,-20,ST_SetSRID(ST_MakePoint(106.721,10.776),4326)),
('KHO-B','Kho Cần Thơ','Thành phố Cần Thơ',1800000,-20,ST_SetSRID(ST_MakePoint(105.746,10.045),4326)),
('KHO-C','Kho Đà Nẵng','Thành phố Đà Nẵng',1800000,-20,ST_SetSRID(ST_MakePoint(108.220,16.067),4326));
INSERT INTO app_user(email,password,name,role,warehouse_id,status) VALUES ('admin@demo.vn','$2b$12$DmMYJ1q9X31wQyAvCAR5k.nalsxMPCTULUbkExQpzhcGwimF9fXki','Quản trị viên','ADMIN',NULL,'ACTIVE');
INSERT INTO app_user(email,password,name,role,warehouse_id,status) VALUES ('dieuphoi@demo.vn','$2b$12$DmMYJ1q9X31wQyAvCAR5k.nalsxMPCTULUbkExQpzhcGwimF9fXki','Điều phối xuất nhập khẩu','DISPATCH',NULL,'ACTIVE');
INSERT INTO app_user(email,password,name,role,warehouse_id,status) VALUES ('khoa@demo.vn','$2b$12$DmMYJ1q9X31wQyAvCAR5k.nalsxMPCTULUbkExQpzhcGwimF9fXki','Nhân viên kho A','WAREHOUSE',1,'ACTIVE');
INSERT INTO app_user(email,password,name,role,warehouse_id,status) VALUES ('khob@demo.vn','$2b$12$DmMYJ1q9X31wQyAvCAR5k.nalsxMPCTULUbkExQpzhcGwimF9fXki','Nhân viên kho B','WAREHOUSE',2,'ACTIVE');
INSERT INTO app_user(email,password,name,role,warehouse_id,status) VALUES ('khoc@demo.vn','$2b$12$DmMYJ1q9X31wQyAvCAR5k.nalsxMPCTULUbkExQpzhcGwimF9fXki','Nhân viên kho C','WAREHOUSE',3,'ACTIVE');
INSERT INTO app_user(email,password,name,role,warehouse_id,status) VALUES ('admin2@demo.vn','$2b$12$DmMYJ1q9X31wQyAvCAR5k.nalsxMPCTULUbkExQpzhcGwimF9fXki','Quản trị viên dự phòng','ADMIN',NULL,'ACTIVE');
INSERT INTO partner(code,name,kind,country,address,email,phone)
SELECT 'DT-'||lpad(i::text,3,'0'),(CASE WHEN i<=40 THEN 'Nhà cung cấp ' WHEN i<=80 THEN 'Khách hàng ' ELSE 'Vận tải ' END)||i,
CASE WHEN i<=40 THEN 'SUPPLIER' WHEN i<=80 THEN 'CUSTOMER' ELSE 'CARRIER' END,
CASE WHEN i<=40 THEN (ARRAY['NO','JP','IN','EC'])[1+i%4] WHEN i<=60 THEN 'VN' WHEN i<=80 THEN 'US' ELSE 'VN' END,
'Địa chỉ mô phỏng '||i,'partner'||i||'@example.com','090'||lpad(i::text,7,'0') FROM generate_series(1,100) i;
INSERT INTO sku(code,name,name_en,category,pack_kg,weight_tolerance,temp_min,temp_max,min_days,warning_days,min_stock)
SELECT 'SKU-'||lpad(i::text,3,'0'),(ARRAY['Tôm thẻ','Cá hồi','Mực ống','Cá ngừ','Bạch tuộc','Cua biển','Sò điệp','Cá thu'])[1+i%8]||' - quy cách '||i,
(ARRAY['Whiteleg shrimp','Salmon','Squid','Tuna','Octopus','Crab','Scallop','Mackerel'])[1+i%8]||' - grade '||i,
(ARRAY['Tôm','Cá','Mực','Cá','Bạch tuộc','Cua','Sò','Cá'])[1+i%8],10,15,-25,-18,7,30,300 FROM generate_series(1,200) i;
INSERT INTO lot(code,sku_id,supplier_id,origin,manufactured,expires)
SELECT 'LOT-'||lpad(i::text,5,'0'),1+(i-1)%200,1+(i-1)%40,'NO',current_date-180,current_date+CASE WHEN i%50=0 THEN -10 WHEN i%17=0 THEN 15 ELSE 90+i%250 END FROM generate_series(1,1000) i;
INSERT INTO stock(warehouse_id,lot_id,kg,boxes,blocked,blocked_boxes)
SELECT w,l.id,(50+l.id%100)*10,50+l.id%100,CASE WHEN l.expires<current_date OR l.id%37=0 THEN (50+l.id%100)*10 ELSE 0 END,CASE WHEN l.expires<current_date OR l.id%37=0 THEN 50+l.id%100 ELSE 0 END FROM generate_series(1,3) w CROSS JOIN lot l;
INSERT INTO movement(warehouse_id,lot_id,kind,delta,delta_boxes,before_kg,after_kg,actor_id,reason) SELECT warehouse_id,lot_id,'OPENING',kg,boxes,0,kg,1,'Số dư đầu kỳ mô phỏng' FROM stock;
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('CONTAINER','DEMU0000016','CONTAINER mẫu 1','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('CONTAINER','DEMU0000021','CONTAINER mẫu 2','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('CONTAINER','DEMU0000037','CONTAINER mẫu 3','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('CONTAINER','DEMU0000042','CONTAINER mẫu 4','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('CONTAINER','DEMU0000058','CONTAINER mẫu 5','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('TRACTOR','51H-10001','TRACTOR mẫu 1','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('TRACTOR','51H-10002','TRACTOR mẫu 2','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('TRACTOR','51H-10003','TRACTOR mẫu 3','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('TRACTOR','51H-10004','TRACTOR mẫu 4','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('TRACTOR','51H-10005','TRACTOR mẫu 5','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('TRAILER','51R-20001','TRAILER mẫu 1','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('TRAILER','51R-20002','TRAILER mẫu 2','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('TRAILER','51R-20003','TRAILER mẫu 3','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('TRAILER','51R-20004','TRAILER mẫu 4','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('TRAILER','51R-20005','TRAILER mẫu 5','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('DRIVER','DRV-001','DRIVER mẫu 1','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('DRIVER','DRV-002','DRIVER mẫu 2','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('DRIVER','DRV-003','DRIVER mẫu 3','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('DRIVER','DRV-004','DRIVER mẫu 4','KHO-A',current_date+730);
INSERT INTO resource(kind,code,name,place,license_expiry) VALUES ('DRIVER','DRV-005','DRIVER mẫu 5','KHO-A',current_date+730);
INSERT INTO demand(warehouse_id,sku_id,kind,kg,reason,created_by) SELECT 1,1+i%200,CASE WHEN i%2=0 THEN 'SHORTAGE' ELSE 'SURPLUS' END,100+i*10,'Nhu cầu mô phỏng '||i,3 FROM generate_series(1,30)i;
INSERT INTO operation(code,kind,source_id,destination_id,partner_id,origin_place,destination_place,planned_date,reason,created_by)
SELECT 'DEMO-'||lpad(i::text,4,'0'),CASE WHEN i<=20 THEN 'IMPORT' WHEN i<=40 THEN 'EXPORT' WHEN i<=60 THEN 'DOMESTIC' ELSE 'TRANSFER' END,
CASE WHEN i<=20 THEN NULL ELSE 1 END,CASE WHEN i<=20 THEN 3 WHEN i>60 THEN 2 ELSE NULL END,
CASE WHEN i<=20 THEN 1 WHEN i<=40 THEN 61 WHEN i<=60 THEN 41 ELSE NULL END,
CASE WHEN i<=20 THEN 'PORT-CATLAI' ELSE 'KHO-A' END,CASE WHEN i<=20 THEN 'KHO-C' WHEN i<=40 THEN 'PORT-CATLAI' WHEN i<=60 THEN 'CUSTOMER-41' ELSE 'KHO-B' END,
current_date+3,'Yêu cầu mẫu chưa duyệt',CASE WHEN i<=20 THEN 2 ELSE 3 END FROM generate_series(1,80)i;
INSERT INTO operation_line(operation_id,sku_id,lot_id,kg,boxes,price) SELECT id,1,CASE WHEN kind='IMPORT' THEN 1 ELSE NULL END,100,10,10 FROM operation;
INSERT INTO audit(actor_id,action,entity,details) VALUES(1,'SEED','SYSTEM','{"note":"Dữ liệu hoàn toàn mô phỏng; mã DEM không xác nhận đăng ký BIC"}');
