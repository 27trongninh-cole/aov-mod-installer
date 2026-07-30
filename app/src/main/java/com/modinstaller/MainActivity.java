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

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
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
    private static final String ANNOUNCEMENT_URL = "https://raw.githubusercontent.com/27trongninh-cole/aov-mod-installer/main/announcement.txt";
    private static final String PREF_ANNOUNCEMENT_DISMISSED_HASH = "announcement_dismissed_hash";
    private static final String DATA_PATH = "/storage/emulated/0/Android/data/com.garena.game.kgvn/files";
    private static final String RESOURCES_PATH = DATA_PATH + "/Resources";
    private static final String BACKUP_PATH = DATA_PATH + "/Resources_ninfinity_backup";
    private static final String PREF_NAME = "mod_ninstaller";
    private static final String PREF_HASH = "resources_hash";
    private static final String PREF_GAME_VERSION = "game_version";
    private static final String MARKER_FIXED = "4fei6x96e66696e697479";
    private static final String MARKER_MODDED = "4e696e66696e697m4o7d9";
    private static final String VERSION_FILE_NAME = "version.txt";
    private static final String PREF_RESOURCES_VERSION_TXT = "resources_version_txt";
    private static final String PREF_RESOURCES_VERSION_FOLDER = "resources_version_folder";

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
    // Nội dung version.txt lấy từ gói Resources đã tải về (vd "1.63.1.10|1716331"),
    // dùng để đối chiếu CHÍNH XÁC với version.txt trong thư mục Resources trên máy
    // — thay vì chỉ dựa vào tên thư mục (dễ sai khi có nhiều thư mục version tồn tại song song).
    private String resourcesVersionTxt = "";
    // Tên thư mục version bên trong gói Resources đã tải (vd "1.63.1"), lấy trực tiếp
    // từ cấu trúc file giải nén — không suy ra từ config.json để tránh lệch nhau.
    private String resourcesVersionFolder = "";
    // Thư mục version trên máy hiện khớp với resourcesVersionTxt (null nếu chưa xác định/không khớp)
    private volatile String activeVersionFolder = null;
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
        checkAnnouncement();

        // Debug ẩn: long-press vào dòng "Phiên bản game" để chạy chuỗi lệnh
        // test cp/rish, giúp chẩn đoán lỗi permission mà không cần Termux.
        if (tvGameVersion != null) {
            tvGameVersion.setOnLongClickListener(v -> {
                if (!checkShizuku()) return true;
                setButtonsEnabled(false);
                showProgress(true);
                executor.execute(this::runDebugCpTest);
                return true;
            });
        }

        // Load gameVersion đã lưu → hiện tạm ngay (không query trạng thái để tránh race
        // với checkMaintenanceMode/updateResourcesStatus chạy sau khi fetch config xong)
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        gameVersion = prefs.getString(PREF_GAME_VERSION, "");
        resourcesVersionTxt = prefs.getString(PREF_RESOURCES_VERSION_TXT, "");
        resourcesVersionFolder = prefs.getString(PREF_RESOURCES_VERSION_FOLDER, "");
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

    @Override
    protected void onResume() {
        super.onResume();
        // BUG CŨ: checkShizukuAndInit() chỉ chạy 1 lần ở onCreate, nên nếu người dùng
        // rời app để mở Shizuku và bấm Stop, hoặc Shizuku tự thu hồi quyền của app (ví dụ
        // sau khi Shizuku service bị hệ thống kill và khởi động lại), badge trạng thái vẫn
        // hiển thị "Sẵn sàng" cũ cho tới khi mở lại app từ đầu (onCreate mới chạy lại).
        // → Re-check lại mỗi lần app quay lại foreground để badge luôn phản ánh đúng
        // trạng thái thực tế ngay lúc này.
        refreshShizukuStatus();
    }

    // Kiểm tra lại trạng thái Shizuku hiện tại (không tự động request quyền, không
    // hiện dialog) — chỉ dùng để cập nhật badge hiển thị mỗi khi app resume.
    private void refreshShizukuStatus() {
        if (isLegacyMode) {
            boolean granted = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
            updateShizukuStatus(granted);
            return;
        }
        boolean ready = Shizuku.pingBinder()
            && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        updateShizukuStatus(ready);
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

            // Đọc output trong thread riêng để readLine() không tự treo luồng
            // gọi chính nếu process không đóng stdout đúng cách — timeout ở
            // waitFor() bên dưới mới có tác dụng thực sự khi đó.
            StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                } catch (IOException ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "Exception: rish timeout (không phản hồi sau 20s)";
            }
            reader.join(2000); // chờ thêm chút để đọc nốt output còn sót
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

            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    while (br.readLine() != null) { /* drain output */ }
                } catch (IOException ignored) {
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean finished = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            reader.join(2000);
            return p.exitValue() == 0;
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

    // Quét Resources/<version>/version.txt trong gói VỪA GIẢI NÉN (tải từ server)
    // để lấy nội dung version.txt chính xác (vd "1.63.1.10|1716331") cùng tên thư
    // mục version tương ứng — dùng làm CHUẨN đối chiếu về sau, thay vì đoán qua
    // tên thư mục liệt kê được trên máy (có thể có nhiều thư mục version cũ/mới
    // tồn tại song song cùng lúc, gây báo nhầm bảo trì).
    private String[] findVersionTxtInExtractedPackage(File extractRoot) {
        File resourcesDir = new File(extractRoot, "Resources");
        if (!resourcesDir.isDirectory()) return null;
        File[] children = resourcesDir.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            File versionFile = new File(child, VERSION_FILE_NAME);
            if (versionFile.exists()) {
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(versionFile.toPath());
                    String content = new String(bytes, "UTF-8").trim();
                    if (!content.isEmpty()) {
                        return new String[]{child.getName(), content};
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    // Lấy phần hiển thị ngắn gọn từ nội dung version.txt (vd "1.63.1.10|1716331" -> "1.63.1.10")
    private String parseShortVersion(String versionTxtContent) {
        if (versionTxtContent == null) return "";
        int pipeIndex = versionTxtContent.indexOf('|');
        return pipeIndex >= 0 ? versionTxtContent.substring(0, pipeIndex) : versionTxtContent;
    }

    // ─── Maintenance Mode ───────────────────────────────────────

    private void checkMaintenanceMode() {
        executor.execute(() -> {
            // Chưa từng Fix Resources thành công lần nào nên chưa có version.txt
            // chuẩn để đối chiếu → không đủ căn cứ để báo bảo trì.
            if (resourcesVersionTxt.isEmpty()) {
                activeVersionFolder = !resourcesVersionFolder.isEmpty() ? resourcesVersionFolder : null;
                mainHandler.post(() -> setMaintenanceUI(false, ""));
                updateResourcesStatus();
                return;
            }

            // Liệt kê TẤT CẢ thư mục version có trong Resources trên máy — có thể có
            // nhiều thư mục tồn tại song song (vd game vừa cập nhật lên bản mới nhưng
            // thư mục version cũ chưa kịp bị dọn dẹp). Đối chiếu version.txt BÊN TRONG
            // từng thư mục (không phải tên thư mục) để tìm đúng bản khớp với Resources
            // đã Fix — tránh báo nhầm bảo trì khi vẫn còn 1 thư mục đúng nằm cạnh thư
            // mục khác.
            String rawOutput = runShellOutput("ls \"" + RESOURCES_PATH + "\" 2>/dev/null");
            String matchedFolder = null;
            for (String l : rawOutput.split("\n")) {
                String folderName = l.trim();
                if (folderName.isEmpty() || !folderName.matches("\\d+\\.\\d+.*")) continue;
                String content = runShellOutput(
                    "cat \"" + RESOURCES_PATH + "/" + folderName + "/" + VERSION_FILE_NAME + "\" 2>/dev/null").trim();
                if (!content.isEmpty() && content.equals(resourcesVersionTxt)) {
                    matchedFolder = folderName;
                    break;
                }
            }

            activeVersionFolder = matchedFolder;
            boolean isMaintenance = (matchedFolder == null);
            String expectedDisplay = parseShortVersion(resourcesVersionTxt);

            mainHandler.post(() -> setMaintenanceUI(isMaintenance, expectedDisplay));
            updateResourcesStatus();
        });
    }

    private void setMaintenanceUI(boolean maintenance, String expectedVersion) {
        setButtonsEnabled(!maintenance);
        if (maintenance) {
            if (tvResourcesStatus != null) {
                tvResourcesStatus.setText("🚧 Bảo trì");
                tvResourcesStatus.setTextColor(0xFFFFAA00);
            }
            String detail = expectedVersion.isEmpty()
                ? "Không tìm thấy phiên bản Resources phù hợp trong dữ liệu game hiện tại."
                : "Dữ liệu game hiện tại không khớp với bản Resources đã Fix (" + expectedVersion + ").";
            showDialog("🚧 Đang bảo trì",
                detail + "\n\nVui lòng quay lại sau khi Ninfinity cập nhật Resources mới!");
        }
    }

    // ─── Thông báo (announcement) ─────────────────────────────────

    private void checkAnnouncement() {
        executor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(ANNOUNCEMENT_URL).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                reader.close();
                String content = sb.toString().trim();
                if (content.isEmpty()) return; // chưa có thông báo nào → bỏ qua

                String contentHash = md5OfString(content);
                String dismissedHash = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .getString(PREF_ANNOUNCEMENT_DISMISSED_HASH, "");

                // Nếu người dùng đã tick "Không hiển thị lại" cho ĐÚNG nội dung này rồi
                // thì bỏ qua. Nếu nội dung file đổi (hash đổi theo) → tự động hiện lại.
                if (contentHash.equals(dismissedHash)) return;

                mainHandler.post(() -> showAnnouncementDialog(content, contentHash));
            } catch (Exception ignored) {
                // Không có mạng / chưa có file thông báo trên repo → im lặng bỏ qua
            }
        });
    }

    private String md5OfString(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void showAnnouncementDialog(String content, String contentHash) {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);

        TextView tvMessage = new TextView(this);
        tvMessage.setText(content);
        tvMessage.setTextSize(15);
        tvMessage.setTextColor(0xFFffffff);
        container.addView(tvMessage);

        android.widget.CheckBox cbDontShow = new android.widget.CheckBox(this);
        cbDontShow.setText("Không hiển thị lại");
        cbDontShow.setTextColor(0xFFaaaaaa);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (16 * getResources().getDisplayMetrics().density);
        cbDontShow.setLayoutParams(lp);
        container.addView(cbDontShow);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("📢 Thông báo")
            .setView(container)
            .setPositiveButton("Đóng", (d, w) -> {
                if (cbDontShow.isChecked()) {
                    getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_ANNOUNCEMENT_DISMISSED_HASH, contentHash)
                        .apply();
                }
            })
            .setCancelable(true)
            .create();
        styleDialog(dialog);
        dialog.show();
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
            String folder = activeVersionFolder; // snapshot để tránh race condition
            if (folder == null || folder.isEmpty()) {
                mainHandler.post(() -> {
                    if (tvResourcesStatus != null) {
                        tvResourcesStatus.setText("❓ Chưa rõ");
                        tvResourcesStatus.setTextColor(0xFF888888);
                    }
                });
                return;
            }

            String configPath = RESOURCES_PATH + "/" + folder + "/Config";
            String fixedPath = configPath + "/" + MARKER_FIXED;

            boolean isFixed = fileExists(fixedPath);

            String status;
            int color;
            if (isFixed) {
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
            // Giải nén bằng Zip4j vào thư mục app control được (không cần rish
            // cho bước này) — rish chỉ dùng ở bước copy cuối vào Android/data/.
            File extractTmpDir = new File(getExternalCacheDir(), "fix_resources_extract_tmp");
            deleteRecursive(extractTmpDir);
            extractTmpDir.mkdirs();

            updateProgressDialog("Đang giải nén...", 50);
            ZipExtractResult result = extractZipWithZip4j(backupZip, extractTmpDir, null);

            if (!result.success) {
                dismissProgressDialog();
                deleteRecursive(extractTmpDir);
                String msg = result.needsPassword
                    ? "File Resources trên server bị đặt mật khẩu — đây là lỗi cấu hình, vui lòng báo Ninfinity."
                    : "Giải nén Resources thất bại: " + result.errorMessage;
                showDialog("Lỗi", msg);
                return;
            }

            fixPermissionsRecursively(extractTmpDir);

            // Đọc version.txt chính xác từ gói Resources VỪA giải nén — dùng làm
            // chuẩn đối chiếu chính xác cho các lần check version/bảo trì về sau,
            // và cũng dùng chính tên thư mục này (không phải gameVersion từ config.json)
            // để tạo marker "đã Fix", tránh lệch nếu 2 giá trị này không khớp nhau.
            String[] versionInfo = findVersionTxtInExtractedPackage(extractTmpDir);
            if (versionInfo != null) {
                resourcesVersionFolder = versionInfo[0];
                resourcesVersionTxt = versionInfo[1];
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_RESOURCES_VERSION_FOLDER, resourcesVersionFolder)
                    .putString(PREF_RESOURCES_VERSION_TXT, resourcesVersionTxt)
                    .apply();
            }

            updateProgressDialog("Đang copy vào game...", 75);
            // rish chỉ làm nhiệm vụ copy file đã giải nén sẵn vào Android/data/
            boolean copied = runShell("mkdir -p \"" + DATA_PATH + "\" && cp -r \"" + extractTmpDir.getAbsolutePath() + "/.\" \"" + DATA_PATH + "/\"");

            updateProgressDialog("Dọn dẹp...", 90);
            deleteRecursive(extractTmpDir);

            // Tự tạo file marker "fixed" — dùng tên thư mục lấy được từ chính gói
            // Resources (versionInfo), fallback về gameVersion nếu vì lý do gì đó
            // không đọc được version.txt trong gói.
            if (copied) {
                String versionFolderForMarker = !resourcesVersionFolder.isEmpty()
                    ? resourcesVersionFolder : gameVersion;
                String configPath = RESOURCES_PATH + "/" + versionFolderForMarker + "/Config";
                runShell("mkdir -p \"" + configPath + "\" && rm -f \"" + configPath + "/" + MARKER_MODDED + "\" && touch \"" + configPath + "/" + MARKER_FIXED + "\"");
            }

            updateProgressDialog("Hoàn tất!", 100);
            dismissProgressDialog();

            if (copied) {
                // Gọi lại checkMaintenanceMode() thay vì chỉ updateResourcesStatus() để
                // activeVersionFolder được xác định lại ngay với resourcesVersionTxt vừa
                // cập nhật ở trên (đặc biệt quan trọng cho lần Fix Resources đầu tiên).
                checkMaintenanceMode();
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
            updateProgressDialog("Đang copy file mod...", 15);

            // Copy zip ra external cache — Zip4j đọc/ghi trực tiếp bằng Java,
            // không cần rish cho bước giải nén nữa.
            File tmpZip = new File(getExternalCacheDir(), "mod_tmp.zip");
            try (InputStream is = getContentResolver().openInputStream(zipUri);
                 OutputStream os = new FileOutputStream(tmpZip)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) os.write(buf, 0, len);
            }

            updateProgressDialog("Đang giải nén tạm để dò cấu trúc...", 35);
            File extractTmpDir = new File(getExternalCacheDir(), "mod_extract_tmp");
            deleteRecursive(extractTmpDir);
            extractTmpDir.mkdirs();

            ZipExtractResult result = extractZipWithZip4j(tmpZip, extractTmpDir, null);

            if (result.needsPassword) {
                dismissProgressDialog();
                mainHandler.post(() -> promptZipPasswordV2(tmpZip, extractTmpDir));
                return;
            }

            if (!result.success) {
                dismissProgressDialog();
                deleteRecursive(extractTmpDir);
                tmpZip.delete();
                showDialog("Lỗi", "Giải nén file mod thất bại.\n\nChi tiết: " + result.errorMessage);
                mainHandler.post(() -> {
                    setButtonsEnabled(true);
                    showProgress(false);
                });
                return;
            }

            fixPermissionsRecursively(extractTmpDir);
            finishInstallModFromExtractedDir(extractTmpDir, tmpZip);

        } catch (Exception e) {
            dismissProgressDialog();
            showDialog("Lỗi", "Đã xảy ra lỗi: " + e.getMessage());
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                showProgress(false);
            });
        }
    }

    // Kết quả giải nén Zip4j: phân biệt rõ 3 trạng thái để UI xử lý đúng.
    private static class ZipExtractResult {
        boolean success;
        boolean needsPassword;
        String errorMessage;
    }

    // Giải nén bằng Zip4j — thư viện Java thuần hỗ trợ đầy đủ ZipCrypto/AES
    // password, không phụ thuộc binary unzip hệ thống (vốn có bản rút gọn
    // trên nhiều ROM Android, gây lỗi "invalid option -- P" đã gặp phải).
    private ZipExtractResult extractZipWithZip4j(File zipFile, File destDir, String password) {
        ZipExtractResult result = new ZipExtractResult();
        try {
            ZipFile zf = new ZipFile(zipFile);
            if (password != null) {
                zf.setPassword(password.toCharArray());
            }

            if (zf.isEncrypted() && password == null) {
                result.needsPassword = true;
                return result;
            }

            zf.extractAll(destDir.getAbsolutePath());
            result.success = true;
            return result;

        } catch (ZipException e) {
            // Zip4j báo lỗi cụ thể khi cần password hoặc password sai
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("password") || msg.contains("wrong password")
                    || e.getType() == ZipException.Type.WRONG_PASSWORD) {
                result.needsPassword = true;
            } else {
                result.success = false;
                result.errorMessage = e.getMessage() != null ? e.getMessage() : "Lỗi không xác định khi giải nén";
            }
            return result;
        } catch (Exception e) {
            result.success = false;
            result.errorMessage = e.getMessage() != null ? e.getMessage() : "Lỗi không xác định";
            return result;
        }
    }

    private void promptZipPasswordV2(File tmpZip, File extractTmpDir) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Nhập mật khẩu giải nén");
        input.setTextColor(0xFFffffff);
        input.setHintTextColor(0xFF888888);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("🔒 File mod có mật khẩu")
            .setMessage("File ZIP này được bảo vệ bằng mật khẩu. Vui lòng nhập để tiếp tục cài mod.")
            .setView(input)
            .setPositiveButton("Xác nhận", (d, w) -> {
                String password = input.getText().toString();
                if (password.isEmpty()) {
                    showToast("Vui lòng nhập mật khẩu!");
                    deleteRecursive(extractTmpDir);
                    tmpZip.delete();
                    setButtonsEnabled(true);
                    showProgress(false);
                    return;
                }
                setButtonsEnabled(false);
                showProgress(true);
                executor.execute(() -> installModWithPasswordV2(tmpZip, extractTmpDir, password));
            })
            .setNegativeButton("Hủy", (d, w) -> {
                deleteRecursive(extractTmpDir);
                tmpZip.delete();
                setButtonsEnabled(true);
                showProgress(false);
            })
            .setCancelable(false)
            .create();
        styleDialog(dialog);
        dialog.show();
    }

    private void installModWithPasswordV2(File tmpZip, File extractTmpDir, String password) {
        try {
            showProgressDialog("Đang giải nén với mật khẩu...");
            updateProgressDialog("Đang dò cấu trúc...", 30);

            deleteRecursive(extractTmpDir);
            extractTmpDir.mkdirs();

            ZipExtractResult result = extractZipWithZip4j(tmpZip, extractTmpDir, password);

            if (!result.success) {
                dismissProgressDialog();
                deleteRecursive(extractTmpDir);
                tmpZip.delete();
                if (result.needsPassword) {
                    showDialog("Sai mật khẩu", "Mật khẩu không đúng. Vui lòng thử lại bằng cách bấm Cài file Mod lần nữa.");
                } else {
                    showDialog("Lỗi", "Cài mod thất bại: " + result.errorMessage);
                }
                mainHandler.post(() -> {
                    setButtonsEnabled(true);
                    showProgress(false);
                });
                return;
            }

            fixPermissionsRecursively(extractTmpDir);
            finishInstallModFromExtractedDir(extractTmpDir, tmpZip);

        } catch (Exception e) {
            dismissProgressDialog();
            showDialog("Lỗi", "Đã xảy ra lỗi: " + e.getMessage());
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                showProgress(false);
            });
        }
    }

    // Sau khi đã giải nén ra thư mục tạm (dù có mật khẩu hay không), quét
    // cây thư mục thật để tìm đúng vị trí Resources/ hoặc
    // com.garena.game.kgvn/, bất kể bị bọc bởi bao nhiêu lớp thư mục cha
    // (vd TenMod/com.garena.game.kgvn/files/Resources/...).
    private void finishInstallModFromExtractedDir(File extractTmpDir, File tmpZip) {
        updateProgressDialog("Đang xác định vị trí mod...", 55);

        File sourceDir = locateResourcesRoot(extractTmpDir);
        String targetPath;

        if (sourceDir == null) {
            dismissProgressDialog();
            deleteRecursive(extractTmpDir);
            tmpZip.delete();
            showDialog("Lỗi", "Không tìm thấy thư mục Resources trong file mod.\n\n"
                + "ZIP phải chứa thư mục Resources ở đâu đó bên trong (có thể lồng "
                + "trong nhiều thư mục cha), ví dụ:\n"
                + "• Resources/...\n"
                + "• files/Resources/...\n"
                + "• com.garena.game.kgvn/files/Resources/...\n"
                + "• TenMod/com.garena.game.kgvn/files/Resources/...");
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                showProgress(false);
            });
            return;
        }

        // sourceDir chính là thư mục Resources tìm được → copy thẳng đè lên RESOURCES_PATH
        targetPath = RESOURCES_PATH;

        // Kiểm tra thư mục tìm được có thực sự chứa file không (phòng
        // trường hợp giải nén lỗi giữa chừng khiến thư mục gần như rỗng
        // nhưng vẫn "tồn tại" về mặt kỹ thuật).
        File[] sourceContents = sourceDir.listFiles();
        if (sourceContents == null || sourceContents.length == 0) {
            dismissProgressDialog();
            deleteRecursive(extractTmpDir);
            tmpZip.delete();
            showDialog("Lỗi", "Thư mục Resources trong file mod trống rỗng hoặc giải nén không đầy đủ. Thử tải lại file mod hoặc kiểm tra file ZIP.");
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                showProgress(false);
            });
            return;
        }

        updateProgressDialog("Đang cài mod vào game...", 80);

        // Debug: xem permission/owner THẬT của các file do Zip4j (Java) tạo ra
        // trong sourceDir — so sánh với permission khi rish tự tạo file bằng
        // mkdir/echo (trong debug test), vì đây là điểm khác biệt duy nhất
        // chưa kiểm chứng giữa test giả lập (luôn pass) và tình huống thật
        // (luôn fail).
        String lsBeforeCp = runShellOutput("ls -laR \"" + sourceDir.getAbsolutePath() + "\" 2>&1 | tail -c 2500");

        // Ghi log cp ra file riêng (thay vì chỉ dựa vào output buffer của
        // runShellOutput, có thể bị giới hạn) — đọc lại toàn bộ log thật sau
        // đó để không bỏ sót lỗi cụ thể nào.
        String cpLogPath = getExternalCacheDir().getAbsolutePath() + "/cp_debug_log.txt";
        String cmd = "mkdir -p \"" + targetPath + "\" && cp -rv \"" + sourceDir.getAbsolutePath()
            + "/.\" \"" + targetPath + "/\" > \"" + cpLogPath + "\" 2>&1; echo EXIT_CODE_IS_$?_HERE";
        String exitCheck = runShellOutput(cmd);
        boolean copied = exitCheck.contains("EXIT_CODE_IS_0_HERE");

        String cpOutput = runShellOutput("cat \"" + cpLogPath + "\" | tail -n 40");
        runShell("rm -f \"" + cpLogPath + "\"");

        // Xác minh THỰC TẾ sau khi copy: đếm số file trong sourceDir (nguồn)
        // và kiểm tra 1 file mẫu có thực sự xuất hiện trong đích hay không —
        // vì lệnh cp có thể "thành công" (exit 0) nhưng copy nhầm chỗ hoặc
        // rish chạy với quyền không đủ để ghi đè thực sự vào Android/data/.
        int sourceFileCount = countFilesRecursive(sourceDir);
        String sampleRelativePath = getFirstFileRelativePath(sourceDir);
        String verifyOutput = "";
        boolean verifiedInTarget = false;
        if (sampleRelativePath != null) {
            String checkPath = targetPath + "/" + sampleRelativePath;
            verifyOutput = runShellOutput("[ -e \"" + checkPath + "\" ] && echo VERIFIED_EXISTS || echo VERIFIED_MISSING");
            verifiedInTarget = verifyOutput.contains("VERIFIED_EXISTS");
        }

        // Đếm thực tế số file đã vào đích để so sánh với nguồn — biết chính
        // xác copy dở dang ở đâu (vd 200/517 file) thay vì chỉ biết có/không.
        String targetCountOutput = runShellOutput("find \"" + targetPath + "\" -type f 2>/dev/null | wc -l");

        long freeSpaceMB = new File(DATA_PATH).getUsableSpace() / (1024 * 1024);

        deleteRecursive(extractTmpDir);
        tmpZip.delete();

        updateProgressDialog("Hoàn tất!", 100);
        dismissProgressDialog();

        // Debug info chi tiết — giúp xác định chính xác lỗi nằm ở khâu nào
        // (giải nén / xác định thư mục / copy vào game) thay vì chỉ báo
        // chung chung "thành công" trong khi thực tế có thể không phải vậy.
        StringBuilder debugInfo = new StringBuilder();
        debugInfo.append("📂 Nguồn (đã giải nén): ").append(sourceDir.getAbsolutePath()).append("\n");
        debugInfo.append("📊 Số file trong nguồn: ").append(sourceFileCount).append("\n");
        debugInfo.append("📊 Số file THỰC TẾ ở đích (toàn bộ Resources): ").append(targetCountOutput.trim()).append("\n");
        debugInfo.append("💾 Dung lượng trống còn lại: ").append(freeSpaceMB).append(" MB\n");
        debugInfo.append("🎯 Đích: ").append(targetPath).append("\n");
        debugInfo.append("🔎 ls -laR NGUỒN (permission thật do Zip4j tạo, trước khi cp):\n").append(lsBeforeCp.isEmpty() ? "(trống)" : lsBeforeCp).append("\n\n");
        debugInfo.append("⚙️ Lệnh cp exit code 0: ").append(copied ? "CÓ" : "KHÔNG").append("\n");
        debugInfo.append("📝 Log cp (40 dòng cuối):\n").append(cpOutput.isEmpty() ? "(trống)" : cpOutput).append("\n");
        if (sampleRelativePath != null) {
            debugInfo.append("🔍 File mẫu kiểm tra: ").append(sampleRelativePath).append("\n");
            debugInfo.append("✔️ File mẫu có ở đích sau copy: ").append(verifiedInTarget ? "CÓ (xác nhận)" : "KHÔNG THẤY (đáng ngờ!)").append("\n");
        }

        boolean actuallySuccess = copied && verifiedInTarget;

        if (actuallySuccess) {
            updateResourcesStatus();
            showScrollableDialog("Thành công ✅", "Cài mod thành công! Khởi động lại game để thấy thay đổi.\n\n─── Debug info ───\n" + debugInfo);
        } else {
            showScrollableDialog("⚠️ Nghi ngờ thất bại", "Lệnh copy chạy xong nhưng KHÔNG xác minh được file đã thực sự vào game.\n\n─── Debug info ───\n" + debugInfo
                + "\n\nHãy chụp màn hình bảng này gửi để debug thêm.");
        }

        mainHandler.post(() -> {
            setButtonsEnabled(true);
            showProgress(false);
        });
    }

    // Đếm tổng số file (không tính thư mục) trong 1 cây thư mục, dùng để
    // debug xem giải nén có đầy đủ hay bị thiếu sót.
    private int countFilesRecursive(File dir) {
        int count = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            if (child.isDirectory()) count += countFilesRecursive(child);
            else count++;
        }
        return count;
    }

    // Lấy đường dẫn tương đối của 1 file bất kỳ trong cây thư mục (dùng làm
    // mẫu để verify sau khi copy — không cần duyệt toàn bộ, chỉ cần 1 file
    // đại diện để xác nhận copy có thực sự chạm tới đích hay không).
    private String getFirstFileRelativePath(File dir) {
        return getFirstFileRelativePathInternal(dir, "");
    }

    private String getFirstFileRelativePathInternal(File dir, String prefix) {
        File[] children = dir.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isFile()) {
                return prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
            }
        }
        for (File child : children) {
            if (child.isDirectory()) {
                String newPrefix = prefix.isEmpty() ? child.getName() : prefix + "/" + child.getName();
                String found = getFirstFileRelativePathInternal(child, newPrefix);
                if (found != null) return found;
            }
        }
        return null;
    }

    // Duyệt đệ quy cây thư mục đã giải nén, tìm thư mục tên "Resources"
    // (không phân biệt hoa/thường) ở bất kỳ độ sâu nào. Trả về chính thư
    // mục đó (không phải thư mục cha).
    private File locateResourcesRoot(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;

        for (File child : children) {
            if (child.isDirectory() && child.getName().equalsIgnoreCase("Resources")) {
                return child;
            }
        }
        // Không tìm thấy ở cấp này → đệ quy xuống các thư mục con
        for (File child : children) {
            if (child.isDirectory()) {
                File found = locateResourcesRoot(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ─── Debug: test rish/cp trực tiếp trong app (thay thế Termux đã hỏng) ────

    private void runDebugCpTest() {
        StringBuilder log = new StringBuilder();

        // Bước 1: rish có phản hồi cơ bản không?
        String echoTest = runShellOutput("echo TEST123");
        log.append("1️⃣ echo TEST123 → ").append(echoTest.isEmpty() ? "❌ RỖNG (rish không phản hồi!)" : "✅ " + echoTest).append("\n\n");

        if (echoTest.isEmpty() || echoTest.startsWith("Exception:")) {
            log.append("⚠️ rish của app không phản hồi lệnh cơ bản nhất.\n");
            log.append("→ Kiểm tra: Shizuku có thực sự đang chạy, app có bị thu hồi quyền ngầm không.\n");
            dismissAndShowDebug(log.toString());
            return;
        }

        // Bước 2: id — xem UID thực sự đang chạy dưới quyền gì
        String idTest = runShellOutput("id");
        log.append("2️⃣ id → ").append(idTest.isEmpty() ? "❌ RỖNG" : idTest).append("\n\n");

        // Bước 3: tạo thư mục test + file trong Android/data/com.garena.game.kgvn/files/
        String testBase = DATA_PATH + "/debug_cp_test";
        String srcDir = testBase + "/src/sub";
        String dstDir = testBase + "/dst";

        String mkdirResult = runShellOutput(
            "rm -rf \"" + testBase + "\" && mkdir -p \"" + srcDir + "\" && echo hello > \"" + srcDir + "/a.txt\" && echo MKDIR_OK");
        log.append("3️⃣ Tạo thư mục + file test → ").append(mkdirResult.contains("MKDIR_OK") ? "✅ OK" : "❌ " + mkdirResult).append("\n\n");

        // Bước 4: kiểm tra file test đã thực sự tồn tại
        String checkSrc = runShellOutput("[ -f \"" + srcDir + "/a.txt\" ] && echo EXISTS || echo MISSING");
        log.append("4️⃣ File test tồn tại sau khi tạo → ").append(checkSrc.contains("EXISTS") ? "✅ CÓ" : "❌ KHÔNG (" + checkSrc + ")").append("\n\n");

        // Bước 5: thử cp -r nguồn/. đích/ (cú pháp hiện đang dùng trong app)
        String cpResult = runShellOutput(
            "mkdir -p \"" + dstDir + "\" && cp -r \"" + testBase + "/src/.\" \"" + dstDir + "/\" 2>&1; echo EXIT_CODE_IS_$?_HERE");
        boolean cpOk = cpResult.contains("EXIT_CODE_IS_0_HERE");
        log.append("5️⃣ cp -r src/. dst/ → ").append(cpOk ? "✅ exit 0" : "❌ " + cpResult).append("\n\n");

        // Bước 6: xác minh file đã thực sự copy sang đích
        String checkDst = runShellOutput("[ -f \"" + dstDir + "/sub/a.txt\" ] && echo EXISTS || echo MISSING");
        log.append("6️⃣ File có ở đích sau cp → ").append(checkDst.contains("EXISTS") ? "✅ CÓ (cp hoạt động đúng!)" : "❌ KHÔNG (" + checkDst + ")").append("\n\n");

        // Bước 7: thử ghi ĐÈ lên file đã tồn tại sẵn (mô phỏng tình huống mod)
        String overwriteTest = runShellOutput(
            "echo world > \"" + dstDir + "/sub/a.txt\" 2>&1; echo EXIT_CODE_IS_$?_HERE");
        boolean overwriteOk = overwriteTest.contains("EXIT_CODE_IS_0_HERE");
        log.append("7️⃣ Ghi đè file đã tồn tại → ").append(overwriteOk ? "✅ exit 0" : "❌ " + overwriteTest).append("\n\n");

        // Bước 8: thử cp -r đè lên thư mục đích đã có sẵn file (giống hệt tình huống cài mod thật)
        String cpOverwriteResult = runShellOutput(
            "cp -r \"" + testBase + "/src/.\" \"" + dstDir + "/\" 2>&1; echo EXIT_CODE_IS_$?_HERE");
        boolean cpOverwriteOk = cpOverwriteResult.contains("EXIT_CODE_IS_0_HERE");
        log.append("8️⃣ cp -r ĐÈ LẦN 2 lên thư mục đã có file → ").append(cpOverwriteOk ? "✅ exit 0" : "❌ " + cpOverwriteResult).append("\n\n");

        // Bước 9: test ĐÚNG TÊN FILE thật đang gây lỗi (4e696e66696e697m4o7d9)
        // để cô lập xem có phải chính cái tên này có vấn đề, không liên quan
        // gì đến kích thước, thứ tự copy, hay cấu trúc thư mục.
        String markerTestDir = testBase + "/marker_test/Config";
        String markerFileName = MARKER_MODDED; // "4e696e66696e697m4o7d9"
        String directCreate = runShellOutput(
            "mkdir -p \"" + markerTestDir + "\" && echo 'Mod map cùng Ninfinity' > \"" + markerTestDir + "/" + markerFileName + "\" 2>&1; echo EXIT_CODE_IS_$?_HERE");
        boolean directCreateOk = directCreate.contains("EXIT_CODE_IS_0_HERE");
        log.append("9️⃣ Tạo TRỰC TIẾP file '").append(markerFileName).append("' bằng echo → ")
           .append(directCreateOk ? "✅ exit 0" : "❌ " + directCreate).append("\n\n");

        // Bước 10: xóa rồi thử tạo lại đúng file đó bằng cp -r (giống hệt cách
        // installMod thực sự làm — copy từ 1 thư mục nguồn khác sang)
        String cpSrcDir = testBase + "/marker_cp_src/Config";
        runShell("rm -f \"" + markerTestDir + "/" + markerFileName + "\"");
        String cpMarkerTest = runShellOutput(
            "mkdir -p \"" + cpSrcDir + "\" && echo 'Mod map cùng Ninfinity' > \"" + cpSrcDir + "/" + markerFileName + "\" && "
            + "cp -r \"" + testBase + "/marker_cp_src/.\" \"" + testBase + "/marker_test/\" 2>&1; echo EXIT_CODE_IS_$?_HERE");
        boolean cpMarkerOk = cpMarkerTest.contains("EXIT_CODE_IS_0_HERE");
        log.append("🔟 Copy file '").append(markerFileName).append("' bằng cp -r (giống installMod thật) → ")
           .append(cpMarkerOk ? "✅ exit 0" : "❌ " + cpMarkerTest).append("\n\n");

        // Bước 11: tái hiện CHÍNH XÁC cấu trúc 2 file của AOV_MapMod_lv1_1.62.1.zip
        // — cùng thứ tự (assetbundle/scene/... trước, Config/marker sau), copy
        // bằng 1 lệnh cp -r DUY NHẤT chứa cả 2 file (giống hệt cách installMod
        // thật gọi), thay vì tạo từng file riêng lẻ như bước 9-10.
        String realSrcDir = testBase + "/real_replica_src";
        String realDstDir = testBase + "/real_replica_dst";
        String buildReplica =
            "mkdir -p \"" + realSrcDir + "/1.62.1/assetbundle/scene\" && "
            + "mkdir -p \"" + realSrcDir + "/1.62.1/Config\" && "
            + "dd if=/dev/zero of=\"" + realSrcDir + "/1.62.1/assetbundle/scene/scene_artist_5v5_v4_lv1_raw.assetbundle\" bs=1024 count=3524 2>&1 && "
            + "echo 'Mod map cùng Ninfinity' > \"" + realSrcDir + "/1.62.1/Config/" + markerFileName + "\" && "
            + "echo BUILD_OK";
        String buildResult = runShellOutput(buildReplica);
        log.append("1️⃣1️⃣a. Tạo cấu trúc giả lập y hệt (thư mục + file lớn 3.6MB + marker) → ")
           .append(buildResult.contains("BUILD_OK") ? "✅ OK" : "❌ " + buildResult).append("\n\n");

        String replicaCopy = runShellOutput(
            "mkdir -p \"" + realDstDir + "\" && cp -r \"" + realSrcDir + "/.\" \"" + realDstDir + "/\" 2>&1; echo EXIT_CODE_IS_$?_HERE");
        boolean replicaCopyOk = replicaCopy.contains("EXIT_CODE_IS_0_HERE");
        log.append("1️⃣1️⃣b. cp -r TOÀN BỘ cấu trúc (1 lệnh, giống installMod thật) → ")
           .append(replicaCopyOk ? "✅ exit 0" : "❌ " + replicaCopy).append("\n\n");

        String checkMarkerInDst = runShellOutput(
            "[ -f \"" + realDstDir + "/1.62.1/Config/" + markerFileName + "\" ] && echo EXISTS || echo MISSING");
        log.append("1️⃣1️⃣c. File marker có ở đích sau copy → ")
           .append(checkMarkerInDst.contains("EXISTS") ? "✅ CÓ" : "❌ KHÔNG (" + checkMarkerInDst + ")").append("\n\n");

        // Dọn dẹp
        runShell("rm -rf \"" + testBase + "\"");

        dismissAndShowDebug(log.toString());
    }

    private void dismissAndShowDebug(String log) {
        mainHandler.post(() -> {
            setButtonsEnabled(true);
            showProgress(false);
            showScrollableDialog("🔧 Debug CP Test", log);
        });
    }

    // zip4j giữ nguyên permission gốc lưu trong file zip (khác java.util.zip
    // trước đây luôn tạo file mới với permission mặc định của app). Nếu zip
    // được nén với permission chặt, tiến trình shell chạy qua rish/Shizuku
    // (UID khác app) có thể không đọc được -> reset về quyền đọc/ghi/execute
    // đầy đủ ngay sau khi giải nén, trước khi cp.
    private void fixPermissionsRecursively(File root) {
        if (root == null || !root.exists()) return;
        root.setReadable(true, false);
        root.setExecutable(true, false);
        root.setWritable(true, false);
        if (root.isDirectory()) {
            File[] children = root.listFiles();
            if (children != null) {
                for (File c : children) fixPermissionsRecursively(c);
            }
        }
    }

    private void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }

    // ─── Tính năng 3: Xóa Mod ────────────────────────────────────

    private void removeMod() {
        try {
            boolean backupExists = fileExists(BACKUP_PATH);
            if (!backupExists) {
                showDialog("Lỗi", "Không tìm thấy Resources_ninfinity_backup. Hãy chạy Fix Resources trước.");
                mainHandler.post(() -> {
                    setButtonsEnabled(true);
                    showProgress(false);
                });
                return;
            }

            showProgressDialog("Đang xóa mod...");
            updateProgressDialog("Đang xóa Resources hiện tại...", 30);
            boolean deleted = runShell("rm -rf \"" + RESOURCES_PATH + "\"");
            if (!deleted) {
                dismissProgressDialog();
                showDialog("Lỗi", "Không thể xóa Resources hiện tại.");
                mainHandler.post(() -> {
                    setButtonsEnabled(true);
                    showProgress(false);
                });
                return;
            }

            // Khôi phục nguyên trạng ban đầu: đổi tên backup về lại tên gốc,
            // KHÔNG unzip lại — người dùng cần bấm Fix Resources lần nữa
            // nếu muốn tiếp tục mod sau khi đã xóa.
            updateProgressDialog("Đang khôi phục Resources gốc...", 70);
            boolean restored = runShell("mv \"" + BACKUP_PATH + "\" \"" + RESOURCES_PATH + "\"");

            updateProgressDialog("Hoàn tất!", 100);
            dismissProgressDialog();

            if (restored) {
                updateResourcesStatus();
                showDialog("Thành công ✅", "Đã xóa mod và khôi phục Resources gốc!\n\nLưu ý: cần bấm Fix Resources lại trước khi cài mod mới.");
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

    // Dialog cho nội dung dài (debug info nhiều dòng) — bọc trong ScrollView
    // tường minh + text có thể chọn/copy được, dễ đọc và chụp màn hình hơn
    // so với AlertDialog message thường.
    private void showScrollableDialog(String title, String msg) {
        mainHandler.post(() -> {
            android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
            int pad = (int) (20 * getResources().getDisplayMetrics().density);
            scrollView.setPadding(pad, pad, pad, pad);

            TextView textView = new TextView(this);
            textView.setText(msg);
            textView.setTextColor(0xFFcccccc);
            textView.setTextSize(13);
            textView.setTextIsSelectable(true);
            textView.setLineSpacing(4, 1.1f);

            scrollView.addView(textView);

            AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scrollView)
                .setPositiveButton("Đóng", null)
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
        // Đã có progress dialog riêng (bánh răng xoay + % + thông báo) lo đủ rồi —
        // ProgressBar tròn mặc định này bị thừa và gây hiện tượng 2 vòng loading
        // chồng lên nhau. Giữ nguyên method + toàn bộ chỗ gọi cũ (không phải sửa
        // từng nơi rải rác khắp file), chỉ cho nó luôn ẩn đi để không còn hiện nữa.
        mainHandler.post(() -> progressBar.setVisibility(View.GONE));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        executor.shutdown();
    }
}
