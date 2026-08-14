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
// Định dạng tools.json — 1 mảng JSON, mỗi phần tử 1 tool. Có 2 KIỂU tool
// (field "type", KHÔNG bắt buộc — thiếu field này mặc định coi là "webview"
// để tương thích ngược với tools.json cũ):
//
// 1) "webview" (mặc định) — bấm vào mở web tool trong app, y hệt từ trước
//    tới giờ:
//    {"icon": "🗺️", "title": "Map Texture Tool", "subtitle": "Thay thế texture bản đồ",
//     "url": "https://mapinity.onrender.com"}
//
// 2) "download" — bấm vào TẢI THẲNG file theo "url" (không mở web nào cả),
//    lưu luôn vào "Các Mod đã tạo" — dùng cho mod đã đóng gói sẵn, có link
//    tải trực tiếp (không phải trang web tạo mod tương tác). Cần thêm field
//    "filename" (tên file .zip sẽ lưu — PHẢI có đuôi .zip để hiện đúng trong
//    "Các Mod đã tạo" và để cảnh báo lệch phiên bản hoạt động).
//    {"icon": "🎥", "title": "Camera Chuẩn 2026", "subtitle": "Tải file Camera mới nhất",
//     "url": "https://github.com/user/repo/releases/download/v1/camera.zip",
//     "filename": "camera_2026.zip", "type": "download"}
//
//    LƯU Ý VỀ "url" CHO KIỂU "download": chỉ hoạt động với link TẢI THẲNG file
//    (không qua trang trung gian, không cần đăng nhập/JS) — link Google Drive
//    dạng đã chỉnh (giống resources_url trong config.json), GitHub Releases,
//    Dropbox có "?dl=1", hoặc CDN/web server tĩnh trả file trực tiếp đều dùng
//    được. Link Google Drive kiểu chia sẻ thường (.../file/d/ID/view), Mega.nz,
//    hoặc link cần đăng nhập — KHÔNG dùng được.
//
// Icon: 1 emoji bất kỳ. Url (kiểu "webview"): "coming_soon" nếu tool chưa có
// link thật — bấm vào sẽ tự hiện "Đang phát triển" thay vì cố mở web.
//
// DEFAULT_TOOLS bên dưới CHỈ dùng khi app chưa từng tải được tools.json lần
// nào (VD lần đầu mở app mà chưa có mạng) — coi như bản "dự phòng" đóng gói
// sẵn trong APK. Một khi đã tải tools.json thành công dù chỉ 1 lần, app sẽ
// dùng bản đã lưu cache thay vì DEFAULT_TOOLS, kể cả khi mất mạng ở lần mở
// sau (xem getCachedOrDefault()).
final class ToolsConfig {

    private static final String PREF_NAME = "mod_ninstaller";
    private static final String PREF_TOOLS_JSON_CACHE = "tools_json_cache";

    static final String TYPE_WEBVIEW = "webview";
    static final String TYPE_DOWNLOAD = "download";

    static final ToolItem[] DEFAULT_TOOLS = {
        new ToolItem("🗺️", "Map Texture Tool", "Thay thế texture bản đồ", "https://mapinity.onrender.com", TYPE_WEBVIEW, ""),
        new ToolItem("📷", "Camera Xa", "Tạo file Camera tuỳ chỉnh", "https://camerinity.onrender.com", TYPE_WEBVIEW, ""),
        // new ToolItem("🏛️", "Mod Sảnh", "Tùy chỉnh giao diện sảnh chờ", "coming_soon", TYPE_WEBVIEW, ""),
    };

    private ToolsConfig() {
        // chỉ chứa hằng số + hàm tiện ích, không tạo instance
    }

    // Cấu trúc dữ liệu cho 1 tool. "filename" chỉ có ý nghĩa với type=download
    // (tên file sẽ lưu vào "Các Mod đã tạo"), rỗng với type=webview.
    static final class ToolItem {
        final String icon, title, subtitle, url, type, filename;
        ToolItem(String icon, String title, String subtitle, String url, String type, String filename) {
            this.icon = icon; this.title = title; this.subtitle = subtitle; this.url = url;
            this.type = type; this.filename = filename;
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
                String type = o.optString("type", TYPE_WEBVIEW);
                String filename = o.optString("filename", "");
                if (title.isEmpty() || url.isEmpty()) continue; // thiếu thông tin bắt buộc → bỏ qua
                if (TYPE_DOWNLOAD.equals(type) && (filename.isEmpty() || !filename.toLowerCase().endsWith(".zip"))) {
                    continue; // type=download BẮT BUỘC có filename dạng .zip, thiếu thì bỏ qua tool này
                }
                result.add(new ToolItem(icon, title, subtitle, url, type, filename));
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }
}
