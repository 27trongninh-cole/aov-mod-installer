package com.modinstaller;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private static final int SHIZUKU_PERMISSION_CODE = 100;
    private static final String CONFIG_URL = "https://raw.githubusercontent.com/27trongninh-cole/aov-mod-installer/main/config.json";
    private static final String DATA_PATH = "/storage/emulated/0/Android/data/com.garena.game.kgvn/files";
    private static final String RESOURCES_PATH = DATA_PATH + "/Resources";
    private static final String BACKUP_PATH = DATA_PATH + "/Resources_ninfinity_backup";
    private static final String PREF_NAME = "mod_ninstaller";
    private static final String PREF_HASH = "resources_hash";

    private TextView tvShizukuStatus;
    private android.widget.LinearLayout btnFixResources;
    private android.widget.LinearLayout btnInstallMod;
    private android.widget.LinearLayout btnRemoveMod;
    private ProgressBar progressBar;
    private TextView tvGameVersion;
    private TextView tvResourcesStatus;

    // Progress dialog
    private AlertDialog progressDialog;
    private TextView tvProgressMsg;
    private TextView tvProgressPercent;
    private ProgressBar progressBarDialog;

    private String resourcesUrl = null;
    private String resourcesHash = null;
    private File rishFile = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
        (requestCode, grantResult) -> {
            if (requestCode == SHIZUKU_PERMISSION_CODE) {
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    updateShizukuStatus(true);
                    executor.execute(this::initRish);
                } else {
                    updateShizukuStatus(false);
                    showToast("Shizuku từ chối quyền. Vui lòng thử lại.");
                }
            }
        };

    private final ActivityResultLauncher<String[]> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                setButtonsEnabled(false);
                showProgress(true);
                executor.execute(() -> installMod(uri));
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        btnFixResources = findViewById(R.id.btn_fix_resources);
        btnInstallMod = findViewById(R.id.btn_install_mod);
        btnRemoveMod = findViewById(R.id.btn_remove_mod);
        progressBar = findViewById(R.id.progress_bar);
        tvGameVersion = findViewById(R.id.tv_game_version);
        tvResourcesStatus = findViewById(R.id.tv_resources_status);

        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        btnFixResources.setOnClickListener(v -> {
            if (!checkShizuku()) return;
            new AlertDialog.Builder(this)
                .setTitle("Fix Resources")
                .setMessage("App sẽ thay thế thư mục Resources. Tiếp tục?")
                .setPositiveButton("Tiếp tục", (d, w) -> {
                    setButtonsEnabled(false);
                    showProgress(true);
                    executor.execute(this::fixResources);
                })
                .setNegativeButton("Hủy", null)
                .show();
        });

        btnInstallMod.setOnClickListener(v -> {
            if (!checkShizuku()) return;
            filePickerLauncher.launch(new String[]{"application/zip", "application/x-zip-compressed"});
        });

        btnRemoveMod.setOnClickListener(v -> {
            if (!checkShizuku()) return;
            new AlertDialog.Builder(this)
                .setTitle("Xóa tất cả Mod")
                .setMessage("App sẽ khôi phục Resources gốc. Tiếp tục?")
                .setPositiveButton("Tiếp tục", (d, w) -> {
                    setButtonsEnabled(false);
                    showProgress(true);
                    executor.execute(this::removeMod);
                })
                .setNegativeButton("Hủy", null)
                .show();
        });

        checkShizukuAndInit();

        // Công cụ tạo mod
        findViewById(R.id.btn_tool_map).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://mapdes.onrender.com"));
            startActivity(intent);
        });
    }

    // ─── Progress Dialog ─────────────────────────────────────────

    private void showProgressDialog(String title) {
        mainHandler.post(() -> {
            android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding(60, 40, 60, 20);

            tvProgressMsg = new TextView(this);
            tvProgressMsg.setText(title);
            tvProgressMsg.setTextSize(14);
            tvProgressMsg.setTextColor(0xFFFFFFFF);
            tvProgressMsg.setPadding(0, 0, 0, 16);
            layout.addView(tvProgressMsg);

            progressBarDialog = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progressBarDialog.setMax(100);
            progressBarDialog.setProgress(0);
            progressBarDialog.setIndeterminate(false);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 24);
            progressBarDialog.setLayoutParams(lp);
            layout.addView(progressBarDialog);

            tvProgressPercent = new TextView(this);
            tvProgressPercent.setText("0%");
            tvProgressPercent.setTextSize(12);
            tvProgressPercent.setTextColor(0xFF888888);
            tvProgressPercent.setGravity(android.view.Gravity.END);
            tvProgressPercent.setPadding(0, 8, 0, 0);
            layout.addView(tvProgressPercent);

            progressDialog = new AlertDialog.Builder(this)
                .setView(layout)
                .setCancelable(false)
                .create();
            progressDialog.show();
        });
    }

    private void updateProgressDialog(String msg, int percent) {
        mainHandler.post(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                if (tvProgressMsg != null) tvProgressMsg.setText(msg);
                if (progressBarDialog != null) progressBarDialog.setProgress(percent);
                if (tvProgressPercent != null) tvProgressPercent.setText(percent + "%");
            }
        });
    }

    private void dismissProgressDialog() {
        mainHandler.post(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
                progressDialog = null;
            }
        });
    }

    // ─── Shizuku ────────────────────────────────────────────────

    private void checkShizukuAndInit() {
        if (!Shizuku.pingBinder()) {
            updateShizukuStatus(false);
            showToast("Shizuku chưa chạy. Hãy mở Shizuku và bấm Start.");
            return;
        }
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            updateShizukuStatus(true);
            executor.execute(this::initRish);
        } else {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
        }
    }

    private boolean checkShizuku() {
        if (!Shizuku.pingBinder()) {
            mainHandler.post(() ->
                new AlertDialog.Builder(this)
                    .setTitle("Shizuku chưa chạy")
                    .setMessage("Cần mở Shizuku và bấm Start trước khi sử dụng tính năng này.")
                    .setPositiveButton("Mở Shizuku", (d, w) -> {
                        try {
                            startActivity(getPackageManager()
                                .getLaunchIntentForPackage("moe.shizuku.privileged.api"));
                        } catch (Exception e) {
                            showToast("Không tìm thấy app Shizuku. Hãy cài Shizuku trước!");
                        }
                    })
                    .setNegativeButton("Hủy", null)
                    .show()
            );
            return false;
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mainHandler.post(() ->
                new AlertDialog.Builder(this)
                    .setTitle("Chưa có quyền Shizuku")
                    .setMessage("App cần được Shizuku cấp quyền để hoạt động.")
                    .setPositiveButton("Cấp quyền", (d, w) ->
                        Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE))
                    .setNegativeButton("Hủy", null)
                    .show()
            );
            return false;
        }
        return true;
    }

    // ─── Init rish ───────────────────────────────────────────────

    private void initRish() {
        try {
            rishFile = new File(getFilesDir(), "rish");
            File rishDex = new File(getFilesDir(), "rish_shizuku.dex");

            // Xóa file cũ trước (rish_shizuku.dex bị chmod 400 sau lần chạy đầu)
            if (rishFile.exists()) rishFile.delete();
            if (rishDex.exists()) {
                rishDex.setWritable(true);
                rishDex.delete();
            }

            extractAsset("rish", rishFile);
            extractAsset("rish_shizuku.dex", rishDex);
            rishFile.setExecutable(true);
            fetchConfig();
        } catch (Exception e) {
            showToast("Lỗi khởi tạo rish: " + e.getMessage());
        }
    }

    private void extractAsset(String assetName, File dest) throws IOException {
        try (InputStream in = getAssets().open(assetName);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    // ─── Shell via rish ──────────────────────────────────────────

    private String runShellOutput(String cmd) {
        try {
            if (rishFile == null || !rishFile.exists()) initRish();
            ProcessBuilder pb = new ProcessBuilder("sh", rishFile.getAbsolutePath(), "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "Exception: " + e.getMessage();
        }
    }

    private boolean runShell(String cmd) {
        try {
            if (rishFile == null || !rishFile.exists()) initRish();
            ProcessBuilder pb = new ProcessBuilder("sh", rishFile.getAbsolutePath(), "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            new BufferedReader(new InputStreamReader(p.getInputStream()))
                .lines().forEach(l -> {});
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean fileExists(String path) {
        try {
            if (rishFile == null || !rishFile.exists()) initRish();
            ProcessBuilder pb = new ProcessBuilder(
                "sh", rishFile.getAbsolutePath(), "-c",
                "[ -e \"" + path + "\" ] && echo yes || echo no");
            Process p = pb.start();
            String out = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            p.waitFor();
            return "yes".equals(out != null ? out.trim() : "");
        } catch (Exception e) {
            return false;
        }
    }

    // ─── Config ──────────────────────────────────────────────────

    private void fetchConfig() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(CONFIG_URL).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            JSONObject json = new JSONObject(sb.toString());
            resourcesUrl = json.getString("resources_url");
            resourcesHash = json.optString("resources_hash", "");
            String gameVersion = json.optString("game_version", "N/A");

            mainHandler.post(() -> {
                if (tvGameVersion != null) tvGameVersion.setText(gameVersion);
            });
        } catch (Exception e) {
            resourcesUrl = null;
            resourcesHash = null;
            mainHandler.post(() -> {
                if (tvGameVersion != null) tvGameVersion.setText("Không tải được");
            });
        }
        updateResourcesStatus();
    }

    private void updateResourcesStatus() {
        executor.execute(() -> {
            File backupZip = new File(getFilesDir(), "resources_backup.zip");
            boolean hasBackup = backupZip.exists();
            boolean isFixed = hasBackup && fileExists(RESOURCES_PATH);
            boolean isOriginal = !hasBackup && fileExists(RESOURCES_PATH);

            String status;
            int color;
            if (isFixed && hasBackup) {
                status = "✅ Đã Fix";
                color = 0xFF00CC66;
            } else if (isOriginal) {
                status = "⚠️ Chưa Fix";
                color = 0xFFFFAA00;
            } else {
                status = "❓ Không xác định";
                color = 0xFF888888;
            }

            mainHandler.post(() -> {
                if (tvResourcesStatus != null) {
                    tvResourcesStatus.setText(status);
                    tvResourcesStatus.setTextColor(color);
                }
            });
        });
    }

    // ─── Hash ────────────────────────────────────────────────────

    private String md5OfFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int len;
            while ((len = fis.read(buf)) > 0) md.update(buf, 0, len);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String getSavedHash() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(PREF_HASH, "");
    }

    private void saveHash(String hash) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit().putString(PREF_HASH, hash).apply();
    }

    // ─── Tính năng 1: Fix Resources ──────────────────────────────

    private void fixResources() {
        try {
            if (resourcesUrl == null) {
                showDialog("Lỗi", "Không lấy được config từ server. Kiểm tra kết nối mạng.");
                return;
            }

            File backupZip = new File(getFilesDir(), "resources_backup.zip");
            String savedHash = getSavedHash();
            boolean hashMatch = !resourcesHash.isEmpty()
                && resourcesHash.equals(savedHash)
                && backupZip.exists();

            if (!hashMatch) {
                showProgressDialog("Đang tải Resources...");
                updateProgressDialog("Đang tải Resources từ server...", 0);
                downloadFileWithProgress(resourcesUrl, backupZip);
                updateProgressDialog("Kiểm tra file...", 95);

                String downloadedHash = md5OfFile(backupZip);
                if (!resourcesHash.isEmpty() && !resourcesHash.equals(downloadedHash)) {
                    backupZip.delete();
                    dismissProgressDialog();
                    showDialog("Lỗi", "File tải về bị lỗi (hash không khớp). Thử lại.");
                    return;
                }
                saveHash(downloadedHash.isEmpty() ? resourcesHash : downloadedHash);
                updateProgressDialog("Tải xong!", 100);
                dismissProgressDialog();
            }

            showProgressDialog("Đang cài đặt...");
            updateProgressDialog("Chuẩn bị thư mục...", 10);

            boolean backupExists = fileExists(BACKUP_PATH);
            if (!backupExists) {
                updateProgressDialog("Đổi tên Resources gốc...", 20);
                boolean renamed = runShell("mv \"" + RESOURCES_PATH + "\" \"" + BACKUP_PATH + "\"");
                if (!renamed) {
                    dismissProgressDialog();
                    showDialog("Lỗi", "Không thể đổi tên thư mục Resources.");
                    return;
                }
            }

            updateProgressDialog("Chuẩn bị giải nén...", 35);
            // Copy zip ra external cache để rish có thể đọc
            File tmpZip = new File(getExternalCacheDir(), "mod_ninstaller_tmp.zip");
            copyFile(backupZip, tmpZip);

            updateProgressDialog("Đang giải nén vào game...", 55);
            // rish unzip thẳng vào Android/data, ZIP có cấu trúc Resources/...
            boolean copied = runShell("unzip -o \"" + tmpZip.getAbsolutePath() + "\" -d \"" + DATA_PATH + "\"");

            updateProgressDialog("Dọn dẹp...", 90);
            tmpZip.delete();

            updateProgressDialog("Hoàn tất!", 100);
            dismissProgressDialog();

            if (copied) {
                updateResourcesStatus();
                showDialog("Thành công ✅", "Fix Resources thành công! Khởi động lại game để thấy thay đổi.");
            } else {
                showDialog("Lỗi", "Copy Resources thất bại. Thử lại.");
            }

        } catch (Exception e) {
            dismissProgressDialog();
            showDialog("Lỗi", "Đã xảy ra lỗi: " + e.getMessage());
        } finally {
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                showProgress(false);
            });
        }
    }

    // ─── Tính năng 2: Cài file Mod ───────────────────────────────

    private void installMod(Uri zipUri) {
        try {
            showProgressDialog("Đang cài mod...");
            updateProgressDialog("Đang copy file mod...", 20);

            // Copy zip ra external cache để rish có thể đọc
            File tmpZip = new File(getExternalCacheDir(), "mod_tmp.zip");
            try (InputStream is = getContentResolver().openInputStream(zipUri);
                 OutputStream os = new FileOutputStream(tmpZip)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
            }

            updateProgressDialog("Đang phát hiện cấu trúc...", 40);
            // Detect cấu trúc ZIP để biết unzip vào đâu
            String unzipDest = detectUnzipDest(zipUri);

            updateProgressDialog("Đang cài mod vào game...", 70);
            boolean success = runShell("unzip -o \"" + tmpZip.getAbsolutePath() + "\" -d \"" + unzipDest + "\"");
            tmpZip.delete();

            updateProgressDialog("Hoàn tất!", 100);
            dismissProgressDialog();

            if (success) {
                showDialog("Thành công ✅", "Cài mod thành công! Khởi động lại game để thấy thay đổi.");
            } else {
                showDialog("Lỗi", "Cài mod thất bại. Hãy chạy Fix Resources trước rồi thử lại.");
            }

        } catch (Exception e) {
            dismissProgressDialog();
            showDialog("Lỗi", "Đã xảy ra lỗi: " + e.getMessage());
        } finally {
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                showProgress(false);
            });
        }
    }

    private String detectUnzipDest(Uri zipUri) {
        // Dựa vào cấu trúc ZIP để xác định thư mục đích
        // Dạng 1: com.garena.game.kgvn/files/Resources/... → unzip vào Android/data/
        // Dạng 2: files/Resources/... → unzip vào Android/data/com.garena.game.kgvn/
        // Dạng 3: Resources/... → unzip vào Android/data/com.garena.game.kgvn/files/
        try (InputStream is = getContentResolver().openInputStream(zipUri);
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry = zis.getNextEntry();
            if (entry != null) {
                String name = entry.getName();
                if (name.startsWith("com.garena.game.kgvn/")) {
                    return "/storage/emulated/0/Android/data/";
                } else if (name.startsWith("files/")) {
                    return "/storage/emulated/0/Android/data/com.garena.game.kgvn/";
                } else if (name.startsWith("Resources/")) {
                    return DATA_PATH + "/";
                }
            }
        } catch (Exception e) {
            // fallback
        }
        return DATA_PATH + "/";
    }

    // ─── Tính năng 3: Xóa Mod ────────────────────────────────────

    private void removeMod() {
        try {
            File backupZip = new File(getFilesDir(), "resources_backup.zip");
            if (!backupZip.exists()) {
                showDialog("Lỗi", "Không có backup Resources. Hãy chạy Fix Resources trước.");
                return;
            }

            showProgressDialog("Đang xóa mod...");
            updateProgressDialog("Đang xóa Resources hiện tại...", 20);
            boolean deleted = runShell("rm -rf \"" + RESOURCES_PATH + "\"");
            if (!deleted) {
                dismissProgressDialog();
                showDialog("Lỗi", "Không thể xóa Resources hiện tại.");
                return;
            }

            updateProgressDialog("Đang giải nén Resources gốc...", 40);
            // Copy zip ra external cache để rish có thể đọc
            File tmpZip2 = new File(getExternalCacheDir(), "mod_ninstaller_tmp.zip");
            copyFile(backupZip, tmpZip2);

            updateProgressDialog("Đang khôi phục...", 65);
            boolean restored = runShell("unzip -o \"" + tmpZip2.getAbsolutePath() + "\" -d \"" + DATA_PATH + "\"");

            updateProgressDialog("Dọn dẹp...", 90);
            tmpZip2.delete();

            runShell("rm -rf \"" + BACKUP_PATH + "\"");

            updateProgressDialog("Hoàn tất!", 100);
            dismissProgressDialog();

            if (restored) {
                updateResourcesStatus();
                showDialog("Thành công ✅", "Đã xóa mod và khôi phục Resources gốc!");
            } else {
                showDialog("Lỗi", "Khôi phục Resources thất bại.");
            }

        } catch (Exception e) {
            dismissProgressDialog();
            showDialog("Lỗi", "Đã xảy ra lỗi: " + e.getMessage());
        } finally {
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                showProgress(false);
            });
        }
    }

    // ─── Helper: Copy file ────────────────────────────────────────

    private void copyFile(File src, File dest) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    // ─── Helper: Download with progress ──────────────────────────

    private void downloadFileWithProgress(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);
        conn.setInstanceFollowRedirects(true);

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM
                || status == 307 || status == 308) {
            String newUrl = conn.getHeaderField("Location");
            conn = (HttpURLConnection) new URL(newUrl).openConnection();
        }

        long totalSize = conn.getContentLengthLong();

        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            long downloaded = 0;
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
                downloaded += len;
                if (totalSize > 0) {
                    int percent = (int) (downloaded * 90 / totalSize); // 0-90%
                    String sizeMB = String.format("%.1f / %.1f MB",
                        downloaded / 1024f / 1024f, totalSize / 1024f / 1024f);
                    updateProgressDialog("Đang tải... " + sizeMB, percent);
                }
            }
        }
    }

    // ─── Helper: Unzip ────────────────────────────────────────────

    private void unzip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            extractZip(zis, destDir);
        }
    }

    private void unzipFromUri(Uri uri, File destDir) throws IOException {
        try (InputStream is = getContentResolver().openInputStream(uri);
             ZipInputStream zis = new ZipInputStream(is)) {
            extractZip(zis, destDir);
        }
    }

    private void extractZip(ZipInputStream zis, File destDir) throws IOException {
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            File outFile = new File(destDir, entry.getName());
            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else {
                outFile.getParentFile().mkdirs();
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                }
            }
            zis.closeEntry();
        }
    }

    private void deleteDir(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) for (File c : children) deleteDir(c);
        }
        dir.delete();
    }

    // ─── Helper: UI ───────────────────────────────────────────────

    private void updateShizukuStatus(boolean granted) {
        mainHandler.post(() -> {
            if (granted) {
                tvShizukuStatus.setText("●");
                tvShizukuStatus.setTextColor(0xFF00CC66);
                tvShizukuStatus.setTooltipText("Shizuku: Sẵn sàng");
            } else {
                tvShizukuStatus.setText("●");
                tvShizukuStatus.setTextColor(0xFFE94560);
                tvShizukuStatus.setTooltipText("Shizuku: Chưa kết nối");
            }
        });
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void showDialog(String title, String msg) {
        mainHandler.post(() ->
            new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        );
    }

    private void setButtonsEnabled(boolean enabled) {
        mainHandler.post(() -> {
            btnFixResources.setEnabled(enabled);
            btnFixResources.setAlpha(enabled ? 1f : 0.5f);
            btnInstallMod.setEnabled(enabled);
            btnInstallMod.setAlpha(enabled ? 1f : 0.5f);
            btnRemoveMod.setEnabled(enabled);
            btnRemoveMod.setAlpha(enabled ? 1f : 0.5f);
        });
    }

    private void showProgress(boolean show) {
        mainHandler.post(() -> progressBar.setVisibility(show ? View.VISIBLE : View.GONE));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        executor.shutdown();
    }
}
