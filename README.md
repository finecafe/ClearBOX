# Box Cleaner

Ứng dụng dọn rác cho Android box chạy Android 8.1 (API 27).

## Chức năng
- Liệt kê app, chọn nhiều app rồi **Vô hiệu hóa** (khuyên dùng, bật lại được) hoặc **Gỡ cài đặt**.
- **Xóa cache** tất cả app một lần.
- **Khôi phục** app đã tắt hoặc đã gỡ.
- Danh sách bảo vệ lõi: launcher, bàn phím đang dùng, SystemUI, Settings, Google Play Services, các gói điều khiển phần cứng Amlogic (`com.droidlogic`, `com.amlogic`)... không cho chọn và **không thể bỏ**.
- **Bảo vệ tùy chỉnh (mới):** chọn app rồi bấm **Bảo vệ app đã chọn** để ghim thêm; bấm **Quản lý danh sách bảo vệ** để bỏ ghim. Danh sách ghim được lưu lại trên box.
- Mặc định chỉ hiện app có biểu tượng khởi chạy; chế độ "TẤT CẢ gói" nguy hiểm hơn.

## Giao diện (mới)
- Giao diện tối, chữ lớn, tối ưu cho điều khiển từ xa: nút và dòng danh sách có viền sáng khi được trỏ tới.
- Màn hình rộng (TV) xếp nút thành 4 cột, màn hình hẹp 2 cột; có bộ đếm "Đã chọn N / M".
- Biểu tượng thùng rác + tia sáng (adaptive icon) và banner 320x180dp cho màn hình chính Android TV.

## Quyền root
Box `userdebug` / `test-keys` thường có sẵn `su`. Có root thì app tự vô hiệu hóa/gỡ/xóa cache.
Không có root thì app chỉ mở trang cài đặt từng app để bạn bấm thủ công.

## Dựng file APK
**Cách 1 – GitHub Actions (không cần cài gì):**
1. Tạo repository mới trên GitHub, tải toàn bộ thư mục này lên (nhánh `main`).
2. Vào tab **Actions** → chạy **Build APK** (tự chạy sau khi push).
3. Mở lần chạy xong → mục **Artifacts** → tải `BoxCleaner-apk` → giải nén ra `app-debug.apk`.

**Cách 2 – Android Studio:** mở thư mục này, chọn **Build → Build APK(s)**.

## Cài lên box
- Chép `app-debug.apk` vào USB, mở bằng trình quản lý file trên box (cho phép "Nguồn không xác định"), hoặc
- `adb connect IP_của_box:5555` rồi `adb install app-debug.apk`.
