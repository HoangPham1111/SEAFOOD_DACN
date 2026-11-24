# Hướng dẫn tiếp tục dự án

Đọc HUONG_DAN_CAI_DAT.md, docs/NGHIEP_VU_CHOT.md, docs/PROMPT_CAU_TRUC_TAT_CA_TRANG.md và docs/KET_QUA_KIEM_THU.md.

- Giữ Java/Spring Boot, PostgreSQL/PostGIS và HTML/CSS/JavaScript; Leaflet/OSM.
- Không coi mã hiện có là bằng chứng nghiệp vụ đã đúng; đọc quy tắc và kiểm thử trước/sau sửa.
- Không tự đổi phạm vi, dùng dữ liệu giả trên UI hoặc thay DB bằng localStorage.
- Không reset/xóa dữ liệu người dùng. Schema thay đổi cần migration, không rerun seed trên database đang dùng.
- Không tự duyệt, không thao tác kho ngoài phạm vi, không bỏ check server vì UI đã kiểm tra.
- Transaction giữ kg, thùng, sức chứa và lịch sử nhất quán; không oversell khi đồng thời.
- Hàng đang đi không phải hàng khả dụng; các trục trạng thái hải quan/chất lượng/di chuyển độc lập.
- Confirmed documents, attachments, movement, positions, audit bất biến. PDF dùng snapshot.
- Thông tin ngoại thương chỉ ghi kết quả bên ngoài có hồ sơ. Không thêm tích hợp thật ngoài phạm vi.
- Chỉ chạy tests/integration.py trên database thử nghiệm có dữ liệu seed, cần --allow-write.
- Sau sửa, chạy Maven package, kiểm thử liên quan, copy target JAR vào dist, cập nhật kết quả có bằng chứng.
- Không khẳng định production-ready hoặc đã kiểm thử những môi trường chưa chạy.
- Khi có mâu thuẫn nghiệp vụ thực sự ảnh hưởng dữ liệu, hỏi người dùng trước thay đổi.
