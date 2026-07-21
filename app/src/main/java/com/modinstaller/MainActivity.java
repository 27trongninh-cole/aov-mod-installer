package com.modinstaller;

import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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
    private static final int STORAGE_PERMISSION_CODE = 101;
    private boolean isLegacyMode = false; // Android <= 10: dùng File API thường
    private static final String CONFIG_URL = "https://raw.githubusercontent.com/27trongninh-cole/aov-mod-installer/main/config.json";
    private static final String DATA_PATH = "/storage/emulated/0/Android/data/com.garena.game.kgvn/files";
    private static final String RESOURCES_PATH = DATA_PATH + "/Resources";
    private static final String BACKUP_PATH = DATA_PATH + "/Resources_ninfinity_backup";
    private static final String PREF_NAME = "mod_ninstaller";
    private static final String PREF_HASH = "resources_hash";
    private static final String PREF_GAME_VERSION = "game_version";
    private static final String MARKER_FIXED = "4fei6x96e66696e697479";
    private static final String MARKER_MODDED = "4e696e66696e697m4o7d9";

    private TextView tvShizukuStatus;
    private TextView tvShizukuLabel;
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
    private String gameVersion = "";
    private File rishFile = null;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
        (requestCode, grantResult) -> {
            if (requestCode == SHIZUKU_PERMISSION_CODE) {
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    updateShizukuStatus(true);
                    executor.execute(this::initRishOrDirect);
                } else {
                    updateShizukuStatus(false);
                    showToast("Shizuku từ chối quyền. Vui lòng thử lại.");
                }
            }
        };

    private final ActivityResultLauncher<Intent> filePickerLauncher =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) {
                    setButtonsEnabled(false);
                    showProgress(true);
                    executor.execute(() -> installMod(uri));
                }
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        tvShizukuLabel = findViewById(R.id.tv_shizuku_label);
        btnFixResources = findViewById(R.id.btn_fix_resources);
        btnInstallMod = findViewById(R.id.btn_install_mod);
        btnRemoveMod = findViewById(R.id.btn_remove_mod);
        progressBar = findViewById(R.id.progress_bar);
        tvGameVersion = findViewById(R.id.tv_game_version);
        tvResourcesStatus = findViewById(R.id.tv_resources_status);

        Shizuku.addRequestPermissionResultListener(permissionResultListener);

        btnFixResources.setOnClickListener(v -> {
            if (!checkShizuku()) return;
            AlertDialog d1 = new AlertDialog.Builder(this)
                .setTitle("Fix Resources")
                .setMessage("App sẽ thay thế thư mục Resources. Tiếp tục?")
                .setPositiveButton("Tiếp tục", (d, w) -> {
                    setButtonsEnabled(false);
                    showProgress(true);
                    executor.execute(this::fixResources);
                })
                .setNegativeButton("Hủy", null)
                .create();
            styleDialog(d1);
            d1.show();
        });

        btnInstallMod.setOnClickListener(v -> {
            if (!checkShizuku()) return;
            filePickerLauncher.launch(createModFilePickerIntent());
        });

        btnRemoveMod.setOnClickListener(v -> {
            if (!checkShizuku()) return;
            AlertDialog d2 = new AlertDialog.Builder(this)
                .setTitle("Xóa tất cả Mod")
                .setMessage("App sẽ khôi phục Resources gốc. Tiếp tục?")
                .setPositiveButton("Tiếp tục", (d, w) -> {
                    setButtonsEnabled(false);
                    showProgress(true);
                    executor.execute(this::removeMod);
                })
                .setNegativeButton("Hủy", null)
                .create();
            styleDialog(d2);
            d2.show();
        });

        checkShizukuAndInit();

        // Load gameVersion đã lưu → hiện tạm ngay (không query trạng thái để tránh race
        // với checkMaintenanceMode/updateResourcesStatus chạy sau khi fetch config xong)
        gameVersion = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .getString(PREF_GAME_VERSION, "");
        if (!gameVersion.isEmpty() && tvGameVersion != null) {
            tvGameVersion.setText(gameVersion);
        }

        // Công cụ tạo mod
        findViewById(R.id.btn_tool_map).setOnClickListener(v -> {
            Intent intent = new Intent(this, WebViewActivity.class);
            intent.putExtra(WebViewActivity.EXTRA_URL, "https://mapdes.onrender.com");
            intent.putExtra(WebViewActivity.EXTRA_TITLE, "Map Texture Tool");
            startActivity(intent);
        });

        // BNK Studio — khóa mặc định, mở khóa bằng cách bấm 7 lần liên tiếp
        setupBnkStudioButton();

        // Nút thông tin (!)
        findViewById(R.id.btn_info_fix).setOnClickListener(v ->
            showDialog("🔧 Fix Resources",
                "Tải Resources mới nhất từ server của Ninfinity về máy.\n\n" +
                "• Bắt buộc phải chạy trước khi cài Mod\n" +
                "• File Resources sẽ được lưu cache, các lần sau không cần tải lại (trừ khi có cập nhật)\n" +
                "• Thư mục Resources gốc của game sẽ được đổi tên thành Resources_ninfinity_backup để bảo toàn")
        );

        findViewById(R.id.btn_info_mod).setOnClickListener(v ->
            showDialog("📦 Cài file Mod",
                "Cài mod vào game từ file .zip.\n\n" +
                "• Cần chạy Fix Resources trước\n" +
                "• File .zip hỗ trợ 3 cấu trúc:\n" +
                "  — Resources/...\n" +
                "  — files/Resources/...\n" +
                "  — com.garena.game.kgvn/files/Resources/...\n" +
                "• Khởi động lại game sau khi cài để thấy thay đổi")
        );

        findViewById(R.id.btn_info_remove).setOnClickListener(v ->
            showDialog("🗑️ Xóa tất cả Mod",
                "Xóa toàn bộ mod và khôi phục Resources gốc.\n\n" +
                "• Resources gốc được khôi phục từ cache — không cần tải lại từ server\n" +
                "• Sau khi xóa mod, có thể cài mod mới ngay mà không cần Fix Resources lại\n" +
                "• Khởi động lại game sau khi xóa để thấy thay đổi")
        );
    }

    // ─── Progress Dialog ─────────────────────────────────────────

    private android.widget.ImageView ivProgressSpinner;
    private android.animation.ObjectAnimator spinAnimator;

    private android.graphics.drawable.Drawable createGearDrawable() {
        int size = 96;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);

        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFFe94560);
        paint.setStyle(android.graphics.Paint.Style.FILL);

        float cx = size / 2f, cy = size / 2f;
        float outerR = size * 0.42f;
        float innerR = size * 0.28f;
        float toothLen = size * 0.12f;

        // Vẽ 8 răng bánh răng
        int teeth = 8;
        for (int i = 0; i < teeth; i++) {
            double angle = Math.toRadians(360.0 / teeth * i);
            float x1 = cx + (float) Math.cos(angle) * outerR;
            float y1 = cy + (float) Math.sin(angle) * outerR;
            float x2 = cx + (float) Math.cos(angle) * (outerR + toothLen);
            float y2 = cy + (float) Math.sin(angle) * (outerR + toothLen);
            canvas.drawLine(x1, y1, x2, y2, strokePaint(paint, size * 0.14f));
        }

        // Vòng ngoài
        canvas.drawCircle(cx, cy, outerR, paint);
        // Lỗ giữa (trong suốt)
        android.graphics.Paint holePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        holePaint.setColor(0xFF16213e);
        holePaint.setStyle(android.graphics.Paint.Style.FILL);
        canvas.drawCircle(cx, cy, innerR, holePaint);

        return new android.graphics.drawable.BitmapDrawable(getResources(), bmp);
    }

    private android.graphics.Paint strokePaint(android.graphics.Paint base, float width) {
        android.graphics.Paint p = new android.graphics.Paint(base);
        p.setStrokeWidth(width);
        p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        return p;
    }

    private void showProgressDialog(String title) {
        mainHandler.post(() -> {
            // Container card bo tròn
            android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.setPadding(56, 48, 56, 40);
            layout.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

            // Nền bo tròn tối đồng bộ app
            android.graphics.drawable.GradientDrawable bgShape = new android.graphics.drawable.GradientDrawable();
            bgShape.setColor(0xFF16213e);
            bgShape.setCornerRadius(28f);
            bgShape.setStroke(2, 0xFF0f3460);
            layout.setBackground(bgShape);

            // Icon xoay (dùng ký tự ⚙ hoặc wrench)
            ivProgressSpinner = new android.widget.ImageView(this);
            android.widget.LinearLayout.LayoutParams spinnerLp =
                new android.widget.LinearLayout.LayoutParams(64, 64);
            spinnerLp.bottomMargin = 20;
            ivProgressSpinner.setLayoutParams(spinnerLp);
            ivProgressSpinner.setImageDrawable(createGearDrawable());
            ivProgressSpinner.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            layout.addView(ivProgressSpinner);

            // Bắt đầu animation xoay liên tục
            spinAnimator = android.animation.ObjectAnimator.ofFloat(ivProgressSpinner, "rotation", 0f, 360f);
            spinAnimator.setDuration(1400);
            spinAnimator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
            spinAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
            spinAnimator.start();

            tvProgressMsg = new TextView(this);
            tvProgressMsg.setText(title);
            tvProgressMsg.setTextSize(14);
            tvProgressMsg.setTextColor(0xFFFFFFFF);
            tvProgressMsg.setGravity(android.view.Gravity.CENTER);
            tvProgressMsg.setPadding(0, 0, 0, 18);
            layout.addView(tvProgressMsg);

            // Progress bar bo tròn với track nền tối
            android.widget.FrameLayout progressContainer = new android.widget.FrameLayout(this);
            android.widget.LinearLayout.LayoutParams containerLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 20);
            progressContainer.setLayoutParams(containerLp);

            android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
            track.setColor(0xFF0f3460);
            track.setCornerRadius(10f);
            progressContainer.setBackground(track);

            progressBarDialog = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            progressBarDialog.setMax(100);
            progressBarDialog.setProgress(0);
            progressBarDialog.setIndeterminate(false);

            // Gradient đỏ cho progress bar
            android.graphics.drawable.GradientDrawable progressShape = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFe94560, 0xFFff6b8a});
            progressShape.setCornerRadius(10f);
            android.graphics.drawable.ClipDrawable clipDrawable = new android.graphics.drawable.ClipDrawable(
                progressShape, android.view.Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);

            android.graphics.drawable.LayerDrawable layerDrawable = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT),
                    clipDrawable
                });
            layerDrawable.setId(1, android.R.id.progress); // layer 1 = clipDrawable (fix: trước đây trỏ nhầm layer 0)
            progressBarDialog.setProgressDrawable(layerDrawable);

            progressContainer.addView(progressBarDialog, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            layout.addView(progressContainer);

            tvProgressPercent = new TextView(this);
            tvProgressPercent.setText("0%");
            tvProgressPercent.setTextSize(12);
            tvProgressPercent.setTextColor(0xFF888899);
            tvProgressPercent.setGravity(android.view.Gravity.END);
            tvProgressPercent.setPadding(0, 10, 0, 0);
            layout.addView(tvProgressPercent);

            progressDialog = new AlertDialog.Builder(this)
                .setView(layout)
                .setCancelable(false)
                .create();

            if (progressDialog.getWindow() != null) {
                progressDialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            }

            progressDialog.show();
        });
    }

    private void updateProgressDialog(String msg, int percent) {
        mainHandler.post(() -> {
            if (progressDialog != null && progressDialog.isShowing()) {
                if (tvProgressMsg != null) tvProgressMsg.setText(msg);
                if (progressBarDialog != null) {
                    // Animate mượt từ giá trị hiện tại đến percent mới
                    android.animation.ObjectAnimator anim = android.animation.ObjectAnimator.ofInt(
                        progressBarDialog, "progress", progressBarDialog.getProgress(), percent);
                    anim.setDuration(300);
                    anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
                    anim.start();
                }
                if (tvProgressPercent != null) tvProgressPercent.setText(percent + "%");
            }
        });
    }

    private void dismissProgressDialog() {
        mainHandler.post(() -> {
            if (spinAnimator != null) {
                spinAnimator.cancel();
                spinAnimator = null;
            }
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
                progressDialog = null;
            }
        });
    }

    // ─── Shizuku ────────────────────────────────────────────────

    private void checkShizukuAndInit() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            // Android 10 trở xuống: dùng File API thường
            isLegacyMode = true;
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                updateShizukuStatus(true);
                executor.execute(this::initRishOrDirect);
            } else {
                requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, STORAGE_PERMISSION_CODE);
            }
        } else {
            // Android 11+: cần Shizuku
            isLegacyMode = false;
            if (!Shizuku.pingBinder()) {
                updateShizukuStatus(false);
                showToast("Shizuku chưa chạy. Hãy mở Shizuku và bấm Start.");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                updateShizukuStatus(true);
                executor.execute(this::initRishOrDirect);
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                updateShizukuStatus(true);
                executor.execute(this::initRishOrDirect);
            } else {
                updateShizukuStatus(false);
                showToast("Cần quyền truy cập storage để sử dụng app!");
            }
        }
    }

    private void initRishOrDirect() {
        if (isLegacyMode) {
            fetchConfig();
        } else {
            initRish();
        }
    }

    // Tạo Intent mở file picker, gợi ý mở tại Download/ModNinstaller/ (nơi WebView tải mod về)
    private Intent createModFilePickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
            "application/zip", "application/x-zip-compressed"});

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // Xây dựng URI trỏ tới Download/ModNinstaller/ trên storage chính
                java.io.File downloadDir = new java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    WebViewActivity.DOWNLOAD_SUBFOLDER);

                String docId = "primary:Download/" + WebViewActivity.DOWNLOAD_SUBFOLDER;
                Uri initialUri = android.provider.DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", docId);
                intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri);
            } catch (Exception e) {
                // Nếu lỗi, bỏ qua — file picker vẫn mở bình thường ở vị trí mặc định
            }
        }
        return intent;
    }

    // ─── BNK Studio: khóa mặc định, mở khóa bằng 7 lần bấm liên tiếp ────

    private static final String PREF_BNK_UNLOCKED = "bnk_studio_unlocked";
    private int bnkTapCount = 0;
    private long bnkFirstTapTime = 0;
    private static final long BNK_TAP_RESET_MS = 3000; // quá 3s không bấm tiếp thì reset đếm

    private void setupBnkStudioButton() {
        View btnBnk = findViewById(R.id.btn_tool_bnk);
        TextView tvIcon = findViewById(R.id.tv_bnk_icon);
        TextView tvTitle = findViewById(R.id.tv_bnk_title);
        TextView tvSubtitle = findViewById(R.id.tv_bnk_subtitle);
        TextView tvArrow = findViewById(R.id.tv_bnk_arrow);

        boolean unlocked = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .getBoolean(PREF_BNK_UNLOCKED, false);

        if (unlocked) {
            applyBnkUnlockedUI(tvIcon, tvTitle, tvSubtitle, tvArrow);
        }

        btnBnk.setOnClickListener(v -> {
            boolean currentlyUnlocked = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .getBoolean(PREF_BNK_UNLOCKED, false);

            if (currentlyUnlocked) {
                // Đã mở khóa → mở thẳng WebView
                Intent intent = new Intent(this, WebViewActivity.class);
                intent.putExtra(WebViewActivity.EXTRA_URL, "https://bnkenin.netlify.app/");
                intent.putExtra(WebViewActivity.EXTRA_TITLE, "BNK Studio");
                startActivity(intent);
                return;
            }

            // Chưa mở khóa → đếm số lần bấm liên tiếp trong khoảng thời gian ngắn
            long now = System.currentTimeMillis();
            if (now - bnkFirstTapTime > BNK_TAP_RESET_MS) {
                bnkTapCount = 0;
                bnkFirstTapTime = now;
            }
            bnkTapCount++;

            if (bnkTapCount >= 7) {
                bnkTapCount = 0;
                AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("👀 Bị phát hiện rồi!")
                    .setMessage("Đúng là không qua mắt được bạn, nhưng sử dụng tính năng chưa ra mắt "
                        + "có thể kèm theo rủi ro khóa tài khoản. Tiếp tục?")
                    .setPositiveButton("Tiếp tục", (dlg, w) -> {
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putBoolean(PREF_BNK_UNLOCKED, true).apply();
                        applyBnkUnlockedUI(tvIcon, tvTitle, tvSubtitle, tvArrow);
                        showToast("Đã mở khóa BNK Studio!");
                    })
                    .setNegativeButton("Hủy", null)
                    .create();
                styleDialog(d);
                d.show();
            }
        });
    }

    private void applyBnkUnlockedUI(TextView tvIcon, TextView tvTitle, TextView tvSubtitle, TextView tvArrow) {
        tvIcon.setText("🗺️");
        tvTitle.setText("BNK Studio");
        tvTitle.setTextColor(0xFFffffff);
        tvSubtitle.setText("Tạo mod nhạc/giọng tướng");
        tvSubtitle.setTextColor(0xFF888899);
        tvArrow.setText("›");
    }

    private boolean checkShizuku() {
        if (isLegacyMode) return true; // Android <= 10 không cần Shizuku
        if (!Shizuku.pingBinder()) {
            mainHandler.post(() -> {
                AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("Shizuku chưa chạy")
                    .setMessage("Cần mở Shizuku và bấm Start trước khi sử dụng tính năng này.")
                    .setPositiveButton("Mở Shizuku", (dlg, w) -> {
                        try {
                            startActivity(getPackageManager()
                                .getLaunchIntentForPackage("moe.shizuku.privileged.api"));
                        } catch (Exception e) {
                            showToast("Không tìm thấy app Shizuku. Hãy cài Shizuku trước!");
                        }
                    })
                    .setNegativeButton("Hủy", null)
                    .create();
                styleDialog(d);
                d.show();
            });
            return false;
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mainHandler.post(() -> {
                AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("Chưa có quyền Shizuku")
                    .setMessage("App cần được Shizuku cấp quyền để hoạt động.")
                    .setPositiveButton("Cấp quyền", (dlg, w) ->
                        Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE))
                    .setNegativeButton("Hủy", null)
                    .create();
                styleDialog(d);
                d.show();
            });
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
        if (isLegacyMode) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                p.waitFor();
                return sb.toString().trim();
            } catch (Exception e) { return ""; }
        }
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
        if (isLegacyMode) {
            // Android <= 10: chạy shell thường không cần rish
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                new BufferedReader(new InputStreamReader(p.getInputStream()))
                    .lines().forEach(l -> {});
                return p.waitFor() == 0;
            } catch (Exception e) { return false; }
        }
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
        if (isLegacyMode) {
            return new File(path).exists();
        }
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

    // ─── Maintenance Mode ───────────────────────────────────────

    private void checkMaintenanceMode() {
        executor.execute(() -> {
            if (gameVersion.isEmpty()) return;

            String rawOutput = runShellOutput(
                "ls \"" + RESOURCES_PATH + "\" 2>/dev/null");

            // Lọc bỏ các dòng debug/lỗi, chỉ lấy dòng trông giống version (chứa dấu chấm)
            String actualVersion = "";
            for (String l : rawOutput.split("\n")) {
                String trimmed = l.trim();
                if (trimmed.isEmpty()) continue;
                // Dòng version phải có dạng số.số.số (vd: 1.63.1)
                if (trimmed.matches("\\d+\\.\\d+.*")) {
                    actualVersion = trimmed;
                    break;
                }
            }

            boolean isMaintenance = !actualVersion.isEmpty()
                && !actualVersion.equals(gameVersion);

            final String finalVersion = actualVersion;
            mainHandler.post(() -> setMaintenanceUI(isMaintenance, finalVersion));
            updateResourcesStatus(); // gọi duy nhất 1 lần ở đây sau khi version đã ổn định
        });
    }

    private void setMaintenanceUI(boolean maintenance, String actualVersion) {
        setButtonsEnabled(!maintenance);
        if (maintenance) {
            if (tvResourcesStatus != null) {
                tvResourcesStatus.setText("🚧 Bảo trì");
                tvResourcesStatus.setTextColor(0xFFFFAA00);
            }
            showDialog("🚧 Đang bảo trì",
                "Game đã cập nhật lên phiên bản " + actualVersion
                + " nhưng Resources trên server vẫn đang ở bản " + gameVersion
                + ".\n\nVui lòng quay lại sau khi Ninfinity cập nhật Resources mới!");
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
            gameVersion = json.optString("game_version", "");
            String gameVersionDisplay = gameVersion.isEmpty() ? "N/A" : gameVersion;

            // Lưu gameVersion để dùng ngay lần sau khi mở app
            if (!gameVersion.isEmpty()) {
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_GAME_VERSION, gameVersion).apply();
            }

            mainHandler.post(() -> {
                if (tvGameVersion != null) tvGameVersion.setText(gameVersionDisplay);
            });

            checkMaintenanceMode();
        } catch (Exception e) {
            resourcesUrl = null;
            resourcesHash = null;
            mainHandler.post(() -> {
                if (tvGameVersion != null) tvGameVersion.setText("Không tải được");
            });
        }
    }

    private void updateResourcesStatus() {
        executor.execute(() -> {
            String currentGameVersion = gameVersion; // snapshot để tránh race condition
            if (currentGameVersion.isEmpty()) {
                mainHandler.post(() -> {
                    if (tvResourcesStatus != null) {
                        tvResourcesStatus.setText("❓ Chưa rõ");
                        tvResourcesStatus.setTextColor(0xFF888888);
                    }
                });
                return;
            }

            String configPath = RESOURCES_PATH + "/" + currentGameVersion + "/Config";
            String moddedPath = configPath + "/" + MARKER_MODDED;
            String fixedPath = configPath + "/" + MARKER_FIXED;

            // Gộp thành 1 lệnh duy nhất: check modded trước, fallback fixed, tránh gọi rish nhiều lần
            String combinedCmd =
                "if [ -e \"" + moddedPath + "\" ]; then " +
                "  echo MODDED; cat \"" + moddedPath + "\" 2>/dev/null; " +
                "elif [ -e \"" + fixedPath + "\" ]; then " +
                "  echo FIXED; " +
                "else " +
                "  echo NONE; " +
                "fi";

            String output = runShellOutput(combinedCmd);
            String[] lines = output.split("\n", 2);
            String state = lines.length > 0 ? lines[0].trim() : "NONE";
            String moddedName = lines.length > 1 ? lines[1].trim() : "";

            String status;
            int color;
            if ("MODDED".equals(state)) {
                status = "🎨 Đã mod: " + (moddedName.isEmpty() ? "(không rõ tên)" : moddedName);
                color = 0xFFE94560;
            } else if ("FIXED".equals(state)) {
                status = "✅ Đã Fix";
                color = 0xFF00CC66;
            } else {
                status = "⚠️ Chưa Fix";
                color = 0xFFFFAA00;
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

            // Tự tạo file marker "fixed"
            if (copied) {
                String configPath = RESOURCES_PATH + "/" + gameVersion + "/Config";
                runShell("mkdir -p \"" + configPath + "\" && rm -f \"" + configPath + "/" + MARKER_MODDED + "\" && touch \"" + configPath + "/" + MARKER_FIXED + "\"");
            }

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
                updateResourcesStatus();
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

            // Tự tạo file marker "fixed" và xóa "modded" sau khi khôi phục
            if (restored) {
                String configPath = RESOURCES_PATH + "/" + gameVersion + "/Config";
                runShell("mkdir -p \"" + configPath + "\" && rm -f \"" + configPath + "/" + MARKER_MODDED + "\" && touch \"" + configPath + "/" + MARKER_FIXED + "\"");
            }

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
            String label = isLegacyMode ? "Storage" : "Shizuku";
            if (granted) {
                tvShizukuStatus.setText("●");
                tvShizukuStatus.setTextColor(0xFF00CC66);
                if (tvShizukuLabel != null) {
                    tvShizukuLabel.setText(label + ": Sẵn sàng");
                    tvShizukuLabel.setTextColor(0xFF00CC66);
                }
            } else {
                tvShizukuStatus.setText("●");
                tvShizukuStatus.setTextColor(0xFFE94560);
                if (tvShizukuLabel != null) {
                    tvShizukuLabel.setText(label + ": Chưa kết nối");
                    tvShizukuLabel.setTextColor(0xFFE94560);
                }
            }
        });
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void showDialog(String title, String msg) {
        mainHandler.post(() -> {
            AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .create();
            styleDialog(dialog);
            dialog.show();
        });
    }

    // Style AlertDialog đồng bộ theme tối của app (nền #16213e, viền #0f3460, chữ trắng, nút đỏ)
    private void styleDialog(AlertDialog dialog) {
        if (dialog.getWindow() != null) {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFF16213e);
            bg.setCornerRadius(24f);
            bg.setStroke(2, 0xFF0f3460);
            dialog.getWindow().setBackgroundDrawable(bg);
        }
        dialog.setOnShowListener(d -> {
            int titleId = getResources().getIdentifier("alertTitle", "id", "android");
            TextView titleView = dialog.findViewById(titleId);
            if (titleView != null) titleView.setTextColor(0xFFe94560);

            TextView messageView = dialog.findViewById(android.R.id.message);
            if (messageView != null) messageView.setTextColor(0xFFcccccc);

            android.widget.Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (pos != null) pos.setTextColor(0xFFe94560);

            android.widget.Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (neg != null) neg.setTextColor(0xFF888888);
        });
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
