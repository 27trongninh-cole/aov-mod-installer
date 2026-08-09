package com.modninstaller.kgvn;

// ═══════════════════════════════════════════════════════════════════
// 🔧 THÊM TOOL MỚI Ở ĐÂY — CHỈ CẦN THÊM 1 DÒNG, KHÔNG CẦN SỬA GÌ KHÁC
// ═══════════════════════════════════════════════════════════════════
// File này TÁCH RIÊNG khỏi MainActivity.java để khi MainActivity được sửa
// (thêm tính năng, sửa lỗi...) ở các lần cập nhật sau, danh sách tool của
// bạn không bị đụng vào / mất theo.
//
// Mỗi dòng bên dưới là 1 thẻ (card) sẽ tự động hiện trong khu "Công cụ khác".
// Copy nguyên 1 dòng bất kỳ, dán xuống dưới, rồi đổi 4 chỗ trong dấu ngoặc
// kép — xong, không cần đụng tới file XML hay MainActivity.
//
//     new ToolItem("Icon", "Tên hiển thị", "Mô tả ngắn", "Link webtool"),
//
// Icon: 1 emoji bất kỳ, ví dụ "🗺️" "⚡" "🎥" "🏛️"
// Tên hiển thị: tên tool, ví dụ "FPS Cao"
// Mô tả ngắn: 1 dòng phụ đề nhỏ bên dưới tên
// Link webtool: địa chỉ web sẽ mở ra khi bấm vào thẻ đó
//
// Nếu tool CHƯA có link thật (chỉ muốn để chỗ trước), điền link bất kỳ,
// ví dụ "coming_soon" — khi bấm sẽ tự hiện thông báo "Đang phát triển"
// thay vì cố mở web.
final class ToolsConfig {

    static final ToolItem[] OTHER_TOOLS = {
        new ToolItem("🗺️", "Map Texture Tool", "Thay thế texture bản đồ", "https://mapinity.onrender.com"),
        new ToolItem("📷", "Camera Xa", "Tạo file Camera tuỳ chỉnh", "https://camerinity.onrender.com"),
        // new ToolItem("🏛️", "Mod Sảnh", "Tùy chỉnh giao diện sảnh chờ", "coming_soon"),
    };

    private ToolsConfig() {
        // chỉ chứa hằng số, không tạo instance
    }

    // Cấu trúc dữ liệu cho 1 tool — không cần hiểu dòng này, chỉ cần biết nó
    // giữ đúng 4 thông tin ở trên.
    static final class ToolItem {
        final String icon, title, subtitle, url;
        ToolItem(String icon, String title, String subtitle, String url) {
            this.icon = icon; this.title = title; this.subtitle = subtitle; this.url = url;
        }
    }
}
