package com.modinstaller;

import android.app.AlertDialog;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

    private TextView tvShizukuStatus;
    private Button btnFixResources;
    private Button btnInstallMod;
    private Button btnRemoveMod;
    private ProgressBar progressBar;

    private String resourcesUrl = null;
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

        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        btnFixResources.setOnClickListener(v -> {
            if (!checkShizuku()) return;
            new AlertDialog.Builder(this)
                .setTitle("Fix Resources")
                .setMessage("App sẽ tải và thay thế thư mục Resources. Quá trình này có thể mất vài phút. Tiếp tục?")
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
                .setMessage("App sẽ xóa Resources đã thay thế và khôi phục Resources gốc. Tiếp tục?")
                .setPositiveButton("Tiếp tục", (d, w) -> {
                    setButtonsEnabled(false);
                    showProgress(true);
                    executor.execute(this::removeMod);
                })
                .setNegativeButton("Hủy", null)
                .show();
        });

        checkShizukuAndInit();
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
            showToast("Shizuku chưa chạy!");
            return false;
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showToast("Chưa có quyền Shizuku!");
            Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
            return false;
        }
        return true;
    }

    // ─── Init rish từ assets ─────────────────────────────────────

    private void initRish() {
        try {
            // Extract rish từ assets vào cache
            rishFile = new File(getFilesDir(), "rish");
            File rishDex = new File(getFilesDir(), "rish_shizuku.dex");

            extractAsset("rish", rishFile);
            extractAsset("rish_shizuku.dex", rishDex);

            // chmod +x
            rishFile.setExecutable(true);

            // Fetch config
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

    private boolean runShell(String cmd) {
        try {
            if (rishFile == null || !rishFile.exists()) {
                initRish();
            }
            ProcessBuilder pb = new ProcessBuilder(rishFile.getAbsolutePath(), "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Đọc output để tránh buffer block
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
                rishFile.getAbsolutePath(), "-c",
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
        } catch (Exception e) {
            resourcesUrl = null;
        }
    }

    // ─── Tính năng 1: Fix Resources ──────────────────────────────

    private void fixResources() {
        try {
            if (resourcesUrl == null) {
                showDialog("Lỗi", "Không lấy được config từ server. Kiểm tra kết nối mạng và thử lại.");
                return;
            }

            boolean backupExists = fileExists(BACKUP_PATH);
            if (!backupExists) {
                boolean renamed = runShell("mv \"" + RESOURCES_PATH + "\" \"" + BACKUP_PATH + "\"");
                if (!renamed) {
                    showDialog("Lỗi", "Không thể đổi tên thư mục Resources.");
                    return;
                }
            }

            File zipFile = new File(getCacheDir(), "Resources.zip");
            downloadFile(resourcesUrl, zipFile);

            runShell("mkdir -p \"" + RESOURCES_PATH + "\"");

            File tmpDir = new File(getCacheDir(), "res_tmp");
            if (tmpDir.exists()) deleteDir(tmpDir);
            tmpDir.mkdirs();

            unzip(zipFile, tmpDir);

            boolean copied = runShell("cp -rT \"" + tmpDir.getAbsolutePath() + "\" \"" + RESOURCES_PATH + "\"");

            zipFile.delete();
            deleteDir(tmpDir);

            if (copied) {
                showDialog("Thành công ✅", "Fix Resources thành công! Khởi động lại game để thấy thay đổi.");
            } else {
                showDialog("Lỗi", "Copy Resources thất bại. Thử lại.");
            }

        } catch (Exception e) {
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
            File tmpDir = new File(getCacheDir(), "mod_tmp");
            if (tmpDir.exists()) deleteDir(tmpDir);
            tmpDir.mkdirs();

            unzipFromUri(zipUri, tmpDir);

            File resourcesDir = detectResourcesDir(tmpDir);
            if (resourcesDir == null) {
                showDialog("Lỗi", "Không tìm thấy thư mục Resources trong ZIP.\n\nZIP phải có cấu trúc:\n• Resources/...\n• files/Resources/...\n• com.garena.game.kgvn/files/Resources/...");
                deleteDir(tmpDir);
                return;
            }

            boolean copied = runShell("cp -rT \"" + resourcesDir.getAbsolutePath() + "\" \"" + RESOURCES_PATH + "\"");

            deleteDir(tmpDir);

            if (copied) {
                showDialog("Thành công ✅", "Cài mod thành công! Khởi động lại game để thấy thay đổi.");
            } else {
                showDialog("Lỗi", "Cài mod thất bại. Hãy chạy Fix Resources trước rồi thử lại.");
            }

        } catch (Exception e) {
            showDialog("Lỗi", "Đã xảy ra lỗi: " + e.getMessage());
        } finally {
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                showProgress(false);
            });
        }
    }

    private File detectResourcesDir(File tmpDir) {
        File direct = new File(tmpDir, "Resources");
        if (direct.exists()) return direct;
        File fromFiles = new File(tmpDir, "files/Resources");
        if (fromFiles.exists()) return fromFiles;
        File fromPackage = new File(tmpDir, "com.garena.game.kgvn/files/Resources");
        if (fromPackage.exists()) return fromPackage;
        return null;
    }

    // ─── Tính năng 3: Xóa Mod ────────────────────────────────────

    private void removeMod() {
        try {
            boolean backupExists = fileExists(BACKUP_PATH);
            if (!backupExists) {
                showDialog("Thông báo", "Không tìm thấy Resources gốc (backup). Fix Resources chưa được chạy.");
                return;
            }

            boolean deleted = runShell("rm -rf \"" + RESOURCES_PATH + "\"");
            if (!deleted) {
                showDialog("Lỗi", "Không thể xóa Resources hiện tại.");
                return;
            }

            boolean restored = runShell("mv \"" + BACKUP_PATH + "\" \"" + RESOURCES_PATH + "\"");
            if (restored) {
                showDialog("Thành công ✅", "Đã xóa mod và khôi phục Resources gốc!");
            } else {
                showDialog("Lỗi", "Khôi phục Resources gốc thất bại.");
            }

        } catch (Exception e) {
            showDialog("Lỗi", "Đã xảy ra lỗi: " + e.getMessage());
        } finally {
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                showProgress(false);
            });
        }
    }

    // ─── Helper: Download ─────────────────────────────────────────

    private void downloadFile(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);

        int status = conn.getResponseCode();
        if (status == HttpURLConnection.HTTP_MOVED_TEMP || status == HttpURLConnection.HTTP_MOVED_PERM
                || status == 307 || status == 308) {
            String newUrl = conn.getHeaderField("Location");
            conn = (HttpURLConnection) new URL(newUrl).openConnection();
        }

        try (InputStream in = conn.getInputStream();
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
        }
    }

    // ─── Helper: Unzip ────────────────────────────────────────────

    private void unzip(File zipFile, File destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(zipFile))) {
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
                tvShizukuStatus.setText("Shizuku: ✅ Sẵn sàng");
                tvShizukuStatus.setTextColor(0xFF00CC66);
            } else {
                tvShizukuStatus.setText("Shizuku: ❌ Chưa kết nối");
                tvShizukuStatus.setTextColor(0xFFE94560);
            }
        });
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
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
            btnInstallMod.setEnabled(enabled);
            btnRemoveMod.setEnabled(enabled);
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
