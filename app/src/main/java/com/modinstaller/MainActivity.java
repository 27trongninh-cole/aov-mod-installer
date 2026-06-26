package com.modinstaller;

import android.content.Intent;
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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import rikka.shizuku.Shizuku;

public class MainActivity extends AppCompatActivity {

    private static final int SHIZUKU_PERMISSION_CODE = 100;

    private TextView tvStatus;
    private TextView tvLog;
    private Button btnPickFile;
    private Button btnInstall;
    private ProgressBar progressBar;

    private Uri selectedZipUri = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Shizuku permission listener
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
        (requestCode, grantResult) -> {
            if (requestCode == SHIZUKU_PERMISSION_CODE) {
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    log("✅ Shizuku đã cấp quyền!");
                    updateStatus("Sẵn sàng cài mod");
                } else {
                    log("❌ Shizuku từ chối quyền. Vui lòng thử lại.");
                }
            }
        };

    // File picker
    private final ActivityResultLauncher<String[]> filePickerLauncher =
        registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                selectedZipUri = uri;
                String fileName = getFileNameFromUri(uri);
                log("📦 Đã chọn: " + fileName);
                updateStatus("Đã chọn file: " + fileName);
                btnInstall.setEnabled(true);
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        tvLog = findViewById(R.id.tv_log);
        btnPickFile = findViewById(R.id.btn_pick_file);
        btnInstall = findViewById(R.id.btn_install);
        progressBar = findViewById(R.id.progress_bar);

        btnInstall.setEnabled(false);

        // Thêm listener Shizuku
        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        // Kiểm tra Shizuku khi mở app
        checkShizuku();

        btnPickFile.setOnClickListener(v -> {
            filePickerLauncher.launch(new String[]{"application/zip", "application/x-zip-compressed"});
        });

        btnInstall.setOnClickListener(v -> {
            if (selectedZipUri != null) {
                startInstall();
            }
        });
    }

    private void checkShizuku() {
        if (!Shizuku.pingBinder()) {
            log("⚠️ Shizuku chưa chạy. Hãy mở Shizuku và bấm Start.");
            updateStatus("Chờ Shizuku...");
            return;
        }

        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("✅ Shizuku sẵn sàng!");
            updateStatus("Sẵn sàng cài mod");
        } else {
            log("🔑 Đang xin quyền Shizuku...");
            Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
        }
    }

    private void startInstall() {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku chưa chạy!", Toast.LENGTH_SHORT).show();
            checkShizuku();
            return;
        }

        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Chưa có quyền Shizuku!", Toast.LENGTH_SHORT).show();
            Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
            return;
        }

        btnInstall.setEnabled(false);
        btnPickFile.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvLog.setText("");

        executor.execute(() -> {
            try {
                log("📂 Đang giải nén...");

                // Giải nén vào thư mục tạm
                File tmpDir = new File(getCacheDir(), "mod_tmp");
                if (tmpDir.exists()) deleteDir(tmpDir);
                tmpDir.mkdirs();

                String packageName = unzipAndDetectPackage(selectedZipUri, tmpDir);

                if (packageName == null) {
                    log("❌ Không tìm thấy thư mục package trong ZIP!");
                    log("💡 ZIP phải chứa thư mục tên package (vd: com.garena.game.kgvn)");
                    resetUI();
                    return;
                }

                log("📱 Package phát hiện: " + packageName);
                log("📋 Đang copy vào Android/data/...");

                // Copy bằng Shizuku shell
                File modFolder = new File(tmpDir, packageName);
                String targetPath = "/sdcard/Android/data/" + packageName;

                // Dùng Shizuku để chạy shell command
                boolean success = copyWithShizuku(modFolder.getAbsolutePath(), targetPath);

                // Dọn dẹp tmp
                deleteDir(tmpDir);

                if (success) {
                    log("✅ Cài mod thành công!");
                    log("🎮 Khởi động lại game để thấy thay đổi.");
                    mainHandler.post(() -> updateStatus("✅ Cài mod thành công!"));
                } else {
                    log("❌ Cài mod thất bại. Kiểm tra log bên trên.");
                    mainHandler.post(() -> updateStatus("❌ Cài mod thất bại"));
                }

            } catch (Exception e) {
                log("❌ Lỗi: " + e.getMessage());
            } finally {
                resetUI();
            }
        });
    }

    private String unzipAndDetectPackage(Uri zipUri, File destDir) throws IOException {
        String detectedPackage = null;

        try (InputStream is = getContentResolver().openInputStream(zipUri);
             ZipInputStream zis = new ZipInputStream(is)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String entryName = entry.getName();

                // Detect package name từ thư mục đầu tiên
                if (detectedPackage == null) {
                    String[] parts = entryName.split("/");
                    if (parts.length > 0 && parts[0].contains(".")) {
                        detectedPackage = parts[0];
                        log("🔍 Phát hiện package: " + detectedPackage);
                    }
                }

                // Giải nén file
                File outFile = new File(destDir, entryName);

                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(outFile)) {
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }

        return detectedPackage;
    }

    private boolean copyWithShizuku(String srcPath, String targetPath) {
    return copyWithShizukuExecute(srcPath, targetPath);
}

    private boolean copyWithShizukuExecute(String srcPath, String targetPath) {
    try {
        String mkdirCmd = "mkdir -p \"" + targetPath + "\"";
        String copyCmd = "cp -rT \"" + srcPath + "\" \"" + targetPath + "\"";

        // Dùng Runtime với shell thường trước
        Process p1 = Runtime.getRuntime().exec(new String[]{"sh", "-c", mkdirCmd});
        p1.waitFor();

        Process p2 = Runtime.getRuntime().exec(new String[]{"sh", "-c", copyCmd});
        byte[] errBytes = p2.getErrorStream().readAllBytes();
        int result = p2.waitFor();

        if (errBytes.length > 0) {
            log("📋 " + new String(errBytes));
        }

        log("📤 Copy result: " + (result == 0 ? "OK" : "Fail(" + result + ")"));
        return result == 0;

    } catch (Exception e) {
        log("❌ Lỗi: " + e.getMessage());
        return false;
    }
}

    private void deleteDir(File dir) {
        if (dir.isDirectory()) {
            for (File child : dir.listFiles()) {
                deleteDir(child);
            }
        }
        dir.delete();
    }

    private String getFileNameFromUri(Uri uri) {
        String path = uri.getPath();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            if (slash >= 0) return path.substring(slash + 1);
        }
        return uri.toString();
    }

    private void log(String message) {
        mainHandler.post(() -> {
            String current = tvLog.getText().toString();
            tvLog.setText(current + "\n" + message);
        });
    }

    private void updateStatus(String status) {
        mainHandler.post(() -> tvStatus.setText(status));
    }

    private void resetUI() {
        mainHandler.post(() -> {
            btnInstall.setEnabled(selectedZipUri != null);
            btnPickFile.setEnabled(true);
            progressBar.setVisibility(View.GONE);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        executor.shutdown();
    }
}
