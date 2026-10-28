# Box Cleaner

Ứng dụng dọn rác cho Android box chạy Android 8.1 (API 27).

## Chức năng
- Liệt kê app, chọn nhiều app rồi **Vô hiệu hóa** (khuyên dùng, bật lại được) hoặc **Gỡ cài đặt**.
- **Xóa cache** tất cả app một lần.
- **Khôi phục** app đã tắt hoặc đã gỡ.
- Danh sách bảo vệ: launcher, bàn phím đang dùng, SystemUI, Settings, Google Play Services, các gói điều khiển phần cứng Amlogic (`com.droidlogic`, `com.amlogic`)... không cho chọn.
- Mặc định chỉ hiện app có biểu tượng khởi chạy; chế độ "TẤT CẢ gói" nguy hiểm hơn.

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
