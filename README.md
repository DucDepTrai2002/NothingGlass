# NothingGlass

Hiệu ứng kính (khúc xạ viền + tách màu + viền sáng) cho Control Center trong plugin Nothing Project (HyperOS 3).

- Code: `src/nothing/ducdeptrai/glass/`
- Mỗi lần push lên `main`, GitHub Actions build ra `glass.dex` và đăng vào **Releases**.
- Gộp vào plugin: thêm `glass.dex` thành `classesN.dex` mới, xoá class smali cũ `nothing/ducdeptrai/glass/GlassFgDrawable` nếu có.
- Điểm gọi (smali): `GlassFgDrawable.apply(View, float radius)` / `GlassFgDrawable.clear(View)`.

Nguyên lý tham khảo từ HyperLight (Kiminonawa): lấy ảnh nền phía sau làm texture cho RuntimeShader.
Bản 1 dùng hình nền hệ thống làm nguồn khúc xạ.
