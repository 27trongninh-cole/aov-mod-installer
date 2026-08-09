package com.modinstaller;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

// Quản lý thư mục lưu mod "riêng tư" của app (bên trong bộ nhớ app, không phải
// Download/ công khai) + file JSON đi kèm ghi lại tên/thời gian tạo/phiên bản
// game lúc tải cho từng mod — dữ liệu này KHÔNG thể lấy được từ bản thân file
// .zip (hệ thống chỉ cho biết lastModified, không biết phiên bản game lúc đó),
// nên phải tự ghi lại ngay lúc tải.
final class ModManifest {

    private static final String MODS_DIR_NAME = "CreatedMods";
    private static final String MANIFEST_FILE_NAME = "mods_manifest.json";

    private ModManifest() {
    }

    // Thư mục riêng của app để chứa các mod đã tải qua WebView — KHÔNG dùng
    // Download/ModNinstaller/ (công khai, app khác đọc/ghi được, dễ bị người
    // dùng lỡ tay xoá/di chuyển) nữa.
    static File getModsDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), MODS_DIR_NAME);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File getManifestFile(Context ctx) {
        return new File(getModsDir(ctx), MANIFEST_FILE_NAME);
    }

    static final class ModEntry {
        final String fileName;
        final long createdTime;
        final String gameVersion; // "" nếu không rõ lúc tải

        ModEntry(String fileName, long createdTime, String gameVersion) {
            this.fileName = fileName;
            this.createdTime = createdTime;
            this.gameVersion = gameVersion;
        }

        File file(Context ctx) {
            return new File(getModsDir(ctx), fileName);
        }
    }

    // Đọc toàn bộ manifest — nếu file chưa tồn tại (chưa tải mod nào) hoặc lỗi
    // parse, trả về danh sách rỗng thay vì crash.
    static synchronized List<ModEntry> loadAll(Context ctx) {
        List<ModEntry> result = new ArrayList<>();
        File f = getManifestFile(ctx);
        if (!f.exists()) return result;
        try {
            String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(content);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                result.add(new ModEntry(
                    o.optString("fileName", ""),
                    o.optLong("createdTime", 0),
                    o.optString("gameVersion", "")));
            }
        } catch (Exception ignored) {
            // Manifest hỏng/không đọc được — coi như rỗng, không chặn app dùng tiếp
        }
        return result;
    }

    private static synchronized void saveAll(Context ctx, List<ModEntry> entries) {
        JSONArray arr = new JSONArray();
        for (ModEntry e : entries) {
            JSONObject o = new JSONObject();
            try {
                o.put("fileName", e.fileName);
                o.put("createdTime", e.createdTime);
                o.put("gameVersion", e.gameVersion);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        try (FileWriter w = new FileWriter(getManifestFile(ctx))) {
            w.write(arr.toString());
        } catch (Exception ignored) {
        }
    }

    // Gọi ngay sau khi 1 file mod .zip mới được lưu vào getModsDir() — ghi lại
    // tên + thời điểm hiện tại + phiên bản game hiện tại (đọc từ MainActivity
    // ngay lúc tải, KHÔNG phải lúc cài — 2 thời điểm này có thể khác nhau nếu
    // người dùng tải mod rồi để đó, sau đó game mới cập nhật phiên bản).
    static synchronized void addEntry(Context ctx, String fileName, String gameVersionAtDownload) {
        List<ModEntry> entries = loadAll(ctx);
        // Nếu trùng tên file (hiếm khi xảy ra, VD tải đè), thay entry cũ
        entries.removeIf(e -> e.fileName.equals(fileName));
        entries.add(new ModEntry(fileName, System.currentTimeMillis(), gameVersionAtDownload));
        saveAll(ctx, entries);
    }

    // Xoá cả file mod lẫn dòng ghi trong manifest — gọi khi người dùng bấm nút
    // Xoá ở màn "Các Mod đã tạo".
    static synchronized void deleteEntry(Context ctx, String fileName) {
        List<ModEntry> entries = loadAll(ctx);
        entries.removeIf(e -> e.fileName.equals(fileName));
        saveAll(ctx, entries);
        new File(getModsDir(ctx), fileName).delete();
    }
}
