package com.modninstaller.kgvn;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// ═══════════════════════════════════════════════════════════════════
// 🔧 THÊM/SỬA TOOL — GIỜ KHÔNG CẦN BUILD LẠI APK NỮA
// ═══════════════════════════════════════════════════════════════════
// Danh sách "Công cụ khác" giờ ưu tiên tải từ file tools.json trên repo
// (MainActivity.fetchToolsConfig(), tự chạy mỗi lần mở/quay lại app) — sửa
// file đó trên repo là cập nhật được cho MỌI người dùng ngay lập tức, không
// cần build/phát hành lại APK, y hệt cơ chế announcement.txt/config.json.
//
// Định dạng tools.json — 1 mảng JSON, mỗi phần tử 1 tool:
// [
//   {"icon": "🗺️", "title": "Map Texture Tool", "subtitle": "Thay thế texture bản đồ", "url": "https://mapinity.onrender.com"},
//   {"icon": "📷", "title": "Camera Xa", "subtitle": "Tạo file Camera tuỳ chỉnh", "url": "https://camerinity.onrender.com"}
// ]
//
// Icon: 1 emoji bất kỳ. Url: "coming_soon" nếu tool chưa có link thật —
// bấm vào sẽ tự hiện "Đang phát triển" thay vì cố mở web.
//
// DEFAULT_TOOLS bên dưới CHỈ dùng khi app chưa từng tải được tools.json lần
// nào (VD lần đầu mở app mà chưa có mạng) — coi như bản "dự phòng" đóng gói
// sẵn trong APK. Một khi đã tải tools.json thành công dù chỉ 1 lần, app sẽ
// dùng bản đã lưu cache thay vì DEFAULT_TOOLS, kể cả khi mất mạng ở lần mở
// sau (xem getCachedOrDefault()).
final class ToolsConfig {

    private static final String PREF_NAME = "mod_ninstaller";
    private static final String PREF_TOOLS_JSON_CACHE = "tools_json_cache";

    static final ToolItem[] DEFAULT_TOOLS = {
        new ToolItem("🗺️", "Map Texture Tool", "Thay thế texture bản đồ", "https://mapinity.onrender.com"),
        new ToolItem("📷", "Camera Xa", "Tạo file Camera tuỳ chỉnh", "https://camerinity.onrender.com"),
        // new ToolItem("🏛️", "Mod Sảnh", "Tùy chỉnh giao diện sảnh chờ", "coming_soon"),
    };

    private ToolsConfig() {
        // chỉ chứa hằng số + hàm tiện ích, không tạo instance
    }

    // Cấu trúc dữ liệu cho 1 tool.
    static final class ToolItem {
        final String icon, title, subtitle, url;
        ToolItem(String icon, String title, String subtitle, String url) {
            this.icon = icon; this.title = title; this.subtitle = subtitle; this.url = url;
        }
    }

    // Gọi ngay lúc mở app (trước khi kịp tải mạng) để hiện được NGAY LẬP TỨC,
    // không phải chờ fetchToolsConfig() tải xong mới có gì để hiện. Ưu tiên
    // bản tools.json đã tải + lưu cache thành công lần gần nhất; nếu chưa từng
    // có (cài app lần đầu, chưa có mạng lần nào) thì dùng DEFAULT_TOOLS.
    static List<ToolItem> getCachedOrDefault(Context ctx) {
        String cachedJson = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_TOOLS_JSON_CACHE, "");
        if (!cachedJson.isEmpty()) {
            List<ToolItem> parsed = parseFromJson(cachedJson);
            if (parsed != null && !parsed.isEmpty()) return parsed;
        }
        List<ToolItem> fallback = new ArrayList<>();
        for (ToolItem t : DEFAULT_TOOLS) fallback.add(t);
        return fallback;
    }

    // Parse mảng JSON tools.json thành danh sách ToolItem. Trả về null nếu
    // JSON hỏng hoàn toàn (không phải mảng hợp lệ) — từng phần tử lỗi riêng lẻ
    // bên trong mảng thì bỏ qua phần tử đó, không huỷ cả danh sách.
    static List<ToolItem> parseFromJson(String rawJson) {
        try {
            JSONArray arr = new JSONArray(rawJson);
            List<ToolItem> result = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String icon = o.optString("icon", "🔧");
                String title = o.optString("title", "");
                String subtitle = o.optString("subtitle", "");
                String url = o.optString("url", "");
                if (title.isEmpty() || url.isEmpty()) continue; // thiếu thông tin bắt buộc → bỏ qua
                result.add(new ToolItem(icon, title, subtitle, url));
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }
}
