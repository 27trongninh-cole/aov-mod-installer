package com.modinstaller;

import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.StyleSpan;
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
    // "latest" release trên GitHub luôn có đúng 1 bản (workflow xoá bản "latest" cũ
    // trước khi tạo bản mới) → link /releases/latest/download/<tên file> luôn trỏ
    // đúng vào asset của lần build gần nhất, không cần biết tag/version cụ thể.
    private static final String LATEST_VERSION_URL = "https://github.com/27trongninh-cole/aov-mod-installer/releases/latest/download/version.json";
    private static final String LATEST_APK_URL = "https://github.com/27trongninh-cole/aov-mod-installer/releases/latest/download/Mod_Ninstaller.apk";
    private static final String PREF_UPDATE_SKIP_COUNT = "update_skip_count";
    private static final String PREF_UPDATE_SKIP_VERSION_CODE = "update_skip_version_code";
    private static final int MAX_UPDATE_SKIPS = 2;
    private static final String PREF_ANNOUNCEMENT_DISMISSED_HASH = "announcement_dismissed_hash";
    private static final String DATA_PATH = "/storage/emulated/0/Android/data/com.garena.game.kgvn/files";
    private static final String RESOURCES_PATH = DATA_PATH + "/Resources";
    // Tên thư mục backup cũ (hardcode) — giữ lại làm fallback để nhận diện backup
    // đã tạo bởi các bản app cũ hơn (trước khi đổi sang tên ngẫu nhiên), tránh
    // "mất dấu" backup có sẵn của người dùng khi update app.
    private static final String LEGACY_BACKUP_FOLDER_NAME = "Resources_ninfinity_backup";
    // Prefix cho tên backup ngẫu nhiên mới — cố định để dễ dò/dọn dẹp orphan sau
    // này, nhưng không lộ rõ ràng như tên cũ.
    private static final String BACKUP_FOLDER_PREFIX = "Resources_nf_";
    private static final String PREF_NAME = "mod_ninstaller";
    private static final String PREF_HASH = "resources_hash";
    private static final String PREF_GAME_VERSION = "game_version";
    // Lưu tên thư mục backup ĐANG active (do app tự sinh ngẫu nhiên hoặc là tên
    // legacy) — mọi thao tác đọc/ghi backup phải qua getBackupPath(), không dùng
    // hằng số path cố định nữa.
    private static final String PREF_BACKUP_FOLDER_NAME = "backup_folder_name";
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
        // checkAnnouncement() không gọi ở đây nữa — đã chuyển sang onResume() để
        // chạy lại mỗi lần app quay lại foreground, không chỉ lúc cold-start.

        // Nút hướng dẫn (product tour / spotlight) — luôn có thể mở lại bất cứ lúc nào
        View btnHelpTour = findViewById(R.id.btn_help_tour);
        if (btnHelpTour != null) {
            btnHelpTour.setOnClickListener(v -> startAppTour());
        }

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
            intent.putExtra(WebViewActivity.EXTRA_URL, "https://mapinity.onrender.com");
            intent.putExtra(WebViewActivity.EXTRA_TITLE, "Mapinity");
            startActivity(intent);
        });

        // BNK Studio — khóa mặc định, mở khóa bằng cách bấm 7 lần liên tiếp
        setupBnkStudioButton();

        // Nút thông tin (!)
        findViewById(R.id.btn_info_fix).setOnClickListener(v ->
            showDialog("🔧 Fix Resources",
                "Tải Resources mới nhất từ server của Ninfinity về máy.\n\n" +
                "• Không bắt buộc, có thể bỏ qua\n" +
                "• File Resources sẽ được lưu cache, các lần sau không cần tải lại (trừ khi có cập nhật)\n" +
                "• Thư mục Resources gốc sẽ được bảo toàn, xoá tất cả Mod sẽ trực tiếp hoàn trả thư mục này")
        );

        findViewById(R.id.btn_info_mod).setOnClickListener(v ->
            showDialog("📦 Cài file Mod",
                "Cài mod vào game từ file .zip.\n\n" +
                "• Nếu cài mod lỗi, cần chạy lại Fix Resources rồi thử lại\n" +
                "• File .zip hỗ trợ 3 cấu trúc:\n" +
                "  — Resources/...\n" +
                "  — files/Resources/...\n" +
                "  — com.garena.game.kgvn/files/Resources/...\n" +
                "• Khởi động lại game sau khi cài để thấy thay đổi")
        );

        findViewById(R.id.btn_info_remove).setOnClickListener(v ->
            showDialog("🗑️ Xóa tất cả Mod",
                "Xóa toàn bộ mod và khôi phục Resources trước khi cài bằng ứng dụng.\n\n" +
                "• Sau khi xóa mod, có thể cần Fix Resources lại để cài mod mới\n" +
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

        // Cùng lý do: 2 lỗi dưới đây trước kia đều chỉ chạy 1 lần ở onCreate.
        // 1) checkAnnouncement(): nếu người dùng mở lại app đang chạy sẵn (không
        //    cold-start), onCreate() không chạy lại → sửa announcement.txt trên
        //    repo xong app không bao giờ biết để hiện thông báo mới.
        checkAnnouncement();

        // 2) checkMaintenanceMode(): nếu lần chạy shell đầu tiên (lúc cold-start)
        //    vô tình xảy ra ngay khi Shizuku/rish chưa kịp sẵn sàng hoàn toàn, lệnh
        //    ls/cat có thể fail trong im lặng → activeVersionFolder bị "đóng băng"
        //    ở giá trị null (hiện "Chưa Fix" sai) suốt cả phiên, dù Resources thực
        //    tế vẫn đúng — đây chính là lý do "lúc hiện Đã Fix, lúc hiện Chưa Fix"
        //    dù Resources không hề đổi. Re-check mỗi lần resume để tự phục hồi.
        if (isShizukuReadyNow()) {
            checkMaintenanceMode();
        }

        // Check cập nhật — cũng chạy lại mỗi lần resume, không chỉ lúc cold-start,
        // để nếu người dùng bấm "Để sau" rồi vẫn ở trong app lâu, hoặc quay lại app
        // sau khi có bản mới, đều được nhắc đúng lúc.
        checkForUpdate();
    }

    // Kiểm tra Shizuku có đang thực sự sẵn sàng ngay lúc này hay không (không tự
    // request quyền, không hiện dialog) — dùng chung cho refreshShizukuStatus() và
    // để quyết định có nên chạy lại checkMaintenanceMode() lúc resume hay không.
    private boolean isShizukuReadyNow() {
        if (isLegacyMode) {
            return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
        }
        return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }

    // Kiểm tra lại trạng thái Shizuku hiện tại (không tự động request quyền, không
    // hiện dialog) — chỉ dùng để cập nhật badge hiển thị mỗi khi app resume.
    private void refreshShizukuStatus() {
        updateShizukuStatus(isShizukuReadyNow());
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
    // Như trên, nhưng nhận thẳng thư mục Resources làm tham số (dùng cho file
    // Mod, vì locateResourcesRoot() trả về CHÍNH thư mục Resources đó rồi,
    // không có thêm 1 lớp "Resources/" bọc ngoài như gói tải từ server).
    private String[] findVersionTxtInResourcesFolder(File resourcesDir) {
        if (resourcesDir == null || !resourcesDir.isDirectory()) return null;
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

    // Mod không có version.txt bên trong → chỉ còn cách lấy tên thư mục version
    // (thư mục con đầu tiên có tên dạng số.số..., vd "1.63.1") để so sánh trực
    // tiếp tên thư mục với bản Resources đã Fix.
    private String findVersionFolderName(File resourcesDir) {
        if (resourcesDir == null || !resourcesDir.isDirectory()) return null;
        File[] children = resourcesDir.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.isDirectory() && child.getName().matches("\\d+\\.\\d+.*")) {
                return child.getName();
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
        tvMessage.setText(parseBoldMarkup(content));
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

    // ─── Kiểm tra cập nhật ─────────────────────────────────────────

    private void checkForUpdate() {
        executor.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_VERSION_URL).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                int remoteVersionCode = json.getInt("versionCode");
                String remoteVersionName = json.optString("versionName", "");
                int localVersionCode = BuildConfig.VERSION_CODE;

                SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);

                if (remoteVersionCode <= localVersionCode) {
                    // Đang ở bản mới nhất (hoặc mới hơn) rồi → dọn bộ đếm "đã bỏ qua"
                    // cũ đi, để nếu có bản cập nhật MỚI KHÁC xuất hiện sau này thì
                    // người dùng lại được bỏ qua đủ 2 lần từ đầu, không bị cộng dồn
                    // oan từ 1 bản cập nhật đã cũ mà họ chưa từng thấy.
                    prefs.edit()
                        .remove(PREF_UPDATE_SKIP_COUNT)
                        .remove(PREF_UPDATE_SKIP_VERSION_CODE)
                        .apply();
                    return;
                }

                // Có bản mới hơn bản đang cài. Bộ đếm "đã bỏ qua" chỉ tính cho ĐÚNG
                // bản remoteVersionCode này — nếu server đã lên bản mới hơn nữa kể
                // từ lần cuối người dùng bỏ qua, coi như nhắc nhở mới, đếm lại từ 0.
                int savedSkipVersionCode = prefs.getInt(PREF_UPDATE_SKIP_VERSION_CODE, -1);
                int skipCount = (savedSkipVersionCode == remoteVersionCode)
                    ? prefs.getInt(PREF_UPDATE_SKIP_COUNT, 0) : 0;

                boolean forceUpdate = skipCount >= MAX_UPDATE_SKIPS;
                mainHandler.post(() -> showUpdateDialog(remoteVersionName, remoteVersionCode, forceUpdate));
            } catch (Exception ignored) {
                // Không có mạng / chưa có version.json trên release → bỏ qua lặng lẽ
            }
        });
    }

    private void showUpdateDialog(String versionName, int remoteVersionCode, boolean forceUpdate) {
        String versionDisplay = versionName.isEmpty() ? "mới nhất" : versionName;

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(forceUpdate ? "🚨 Bắt buộc cập nhật" : "🔔 Có bản cập nhật mới")
            .setMessage(forceUpdate
                ? "Bạn đã bỏ qua cập nhật " + MAX_UPDATE_SKIPS + " lần rồi. Vui lòng cập nhật lên phiên bản "
                    + versionDisplay + " để tiếp tục sử dụng app."
                : "Đã có phiên bản mới (" + versionDisplay + "). Cập nhật ngay để trải nghiệm đầy đủ và ổn định nhất.")
            .setCancelable(false)
            .setPositiveButton("Cập nhật ngay", (d, w) -> downloadAndInstallUpdate());

        if (!forceUpdate) {
            builder.setNegativeButton("Để sau", (d, w) -> {
                SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
                int savedSkipVersionCode = prefs.getInt(PREF_UPDATE_SKIP_VERSION_CODE, -1);
                int currentSkipCount = (savedSkipVersionCode == remoteVersionCode)
                    ? prefs.getInt(PREF_UPDATE_SKIP_COUNT, 0) : 0;
                prefs.edit()
                    .putInt(PREF_UPDATE_SKIP_VERSION_CODE, remoteVersionCode)
                    .putInt(PREF_UPDATE_SKIP_COUNT, currentSkipCount + 1)
                    .apply();
            });
        }

        AlertDialog dialog = builder.create();
        styleDialog(dialog);
        dialog.show();
    }

    // Tải APK bản mới NGAY TRONG APP (dùng lại đúng bảng tiến trình bánh răng như
    // Fix Resources), thay vì mở trình duyệt trỏ ra ngoài repo — người dùng không
    // cần (và không nên) biết địa chỉ repo GitHub của app.
    private void downloadAndInstallUpdate() {
        executor.execute(() -> {
            File apkFile = new File(getCacheDir(), "update.apk");
            try {
                // Nếu đã tải sẵn từ lần trước (vd lần trước còn thiếu quyền cài đặt,
                // giờ quay lại bấm tiếp) thì dùng luôn, khỏi tải lại tốn dữ liệu.
                if (!apkFile.exists() || apkFile.length() == 0) {
                    mainHandler.post(() -> showProgressDialog("Đang tải bản cập nhật..."));
                    updateProgressDialog("Đang tải bản cập nhật...", 0);
                    downloadFileWithProgress(LATEST_APK_URL, apkFile);
                    updateProgressDialog("Tải xong!", 100);
                    dismissProgressDialog();
                }
                mainHandler.post(() -> promptInstallApk(apkFile));
            } catch (Exception e) {
                if (apkFile.exists()) apkFile.delete();
                dismissProgressDialog();
                showDialog("Lỗi", "Tải bản cập nhật thất bại: " + e.getMessage() + "\n\nVui lòng thử lại.");
            }
        });
    }

    private void promptInstallApk(File apkFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            // Chưa cấp quyền "Cài đặt ứng dụng không rõ nguồn" cho app này — đưa
            // thẳng tới đúng màn hình cấp quyền hệ thống cho ĐÚNG app này, người
            // dùng chỉ cần bật công tắc rồi quay lại bấm "Cập nhật ngay" lần nữa
            // (file APK đã tải sẵn ở trên sẽ được dùng lại, không tải lại từ đầu).
            showDialog("Cần cấp quyền cài đặt",
                "Bật quyền \"Cài đặt ứng dụng không rõ nguồn\" cho Mod Ninstaller ở màn hình tiếp theo, sau đó quay lại bấm /Cập nhật ngay/ lần nữa.");
            Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
            startActivity(settingsIntent);
            return;
        }
        installApk(apkFile);
    }

    private void installApk(File apkFile) {
        Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(installIntent);
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

    // Kiểm tra đồng bộ (dùng trên executor thread) Resources hiện tại đã được
    // Fix hay chưa — dùng để phân biệt lỗi cài Mod do CHƯA Fix Resources với
    // lỗi copy thật sự (permission, thiếu dung lượng, v.v).
    private boolean isCurrentResourcesFixed() {
        String folder = activeVersionFolder;
        if (folder == null || folder.isEmpty()) return false;
        String configPath = RESOURCES_PATH + "/" + folder + "/Config";
        return fileExists(configPath + "/" + MARKER_FIXED);
    }

    private void updateResourcesStatus() {
        executor.execute(() -> {
            String folder = activeVersionFolder; // snapshot để tránh race condition
            if (folder == null || folder.isEmpty()) {
                // Không xác định được thư mục version đang hoạt động — thường là do
                // chưa có quyền đọc version.txt (hoặc chưa từng Fix Resources lần nào)
                // chứ không hẳn là "trạng thái không xác định". Coi như Chưa Fix để
                // người dùng biết cần bấm Fix Resources, thay vì mơ hồ.
                mainHandler.post(() -> {
                    if (tvResourcesStatus != null) {
                        tvResourcesStatus.setText("⚠️ Chưa Fix");
                        tvResourcesStatus.setTextColor(0xFFFFAA00);
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

    // ─── Backup Resources gốc: quản lý tên thư mục (TH2) ──────────

    // Trả về đường dẫn ĐẦY ĐỦ tới thư mục backup Resources gốc đang thực sự tồn
    // tại trên máy, hoặc null nếu không có backup nào. Ưu tiên đọc tên đã lưu
    // trong pref (do chính app này tạo, tên ngẫu nhiên); nếu pref rỗng (app mới
    // cài, hoặc backup được tạo bởi bản cũ trước khi có tên ngẫu nhiên) thì
    // fallback kiểm tra tên legacy cố định để không "mất dấu" backup có sẵn.
    private String resolveActiveBackupPath() {
        String savedName = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .getString(PREF_BACKUP_FOLDER_NAME, "");
        if (!savedName.isEmpty()) {
            String path = DATA_PATH + "/" + savedName;
            // Đã có tên lưu trong pref (app đã ở cơ chế backup ngẫu nhiên) → tin
            // tưởng tuyệt đối vào pref, KHÔNG fallback sang tên legacy nữa — nếu
            // path này không còn tồn tại, coi như không có backup, tránh dùng
            // nhầm 1 thư mục legacy không rõ nguồn gốc/không khớp thực tế.
            return fileExists(path) ? path : null;
        }
        // Chưa từng lưu tên trong pref → có thể là backup từ bản app cũ, kiểm
        // tra tên legacy cố định.
        String legacyPath = DATA_PATH + "/" + LEGACY_BACKUP_FOLDER_NAME;
        return fileExists(legacyPath) ? legacyPath : null;
    }

    // Sinh tên thư mục backup ngẫu nhiên (prefix cố định + 8 ký tự hex ngẫu nhiên).
    private String generateRandomBackupFolderName() {
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        byte[] buf = new byte[4];
        rnd.nextBytes(buf);
        StringBuilder sb = new StringBuilder();
        for (byte b : buf) sb.append(String.format("%02x", b));
        return BACKUP_FOLDER_PREFIX + sb;
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

            String existingBackupPath = resolveActiveBackupPath();
            boolean backupExists = existingBackupPath != null;

            if (!backupExists) {
                // TH1: nếu không có Resources gốc trên máy (chưa Fix lần nào, hoặc
                // người dùng/game vừa xoá sạch) thì không có gì để đổi tên — bỏ qua
                // bước này, đi thẳng tới giải nén + copy bên dưới. Không coi đây là lỗi.
                boolean hasOriginalResources = fileExists(RESOURCES_PATH);
                if (hasOriginalResources) {
                    updateProgressDialog("Đổi tên Resources gốc...", 20);
                    // TH2: sinh tên backup ngẫu nhiên thay vì tên cố định, tránh xung
                    // đột nếu 1 thư mục cùng tên đã tồn tại vì lý do bất thường nào đó.
                    // Lưu ngay vào pref để mọi thao tác sau (kể cả Xóa Mod) dùng đúng
                    // tên này.
                    String newBackupName = generateRandomBackupFolderName();
                    String newBackupPath = DATA_PATH + "/" + newBackupName;
                    boolean renamed = runShell("mv \"" + RESOURCES_PATH + "\" \"" + newBackupPath + "\"");
                    if (renamed) {
                        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                            .putString(PREF_BACKUP_FOLDER_NAME, newBackupName)
                            .apply();
                    }
                    if (!renamed) {
                        dismissProgressDialog();
                        showDialog("Lỗi", "Không thể đổi tên thư mục Resources.");
                        return;
                    }
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

            // Xác định đúng thư mục gốc chứa nội dung Resources thật bên trong
            // gói vừa giải nén — KHÔNG giả định cứng "extractTmpDir/. chính là
            // nội dung của Resources". Lý do: nếu lần đóng gói resources_backup.zip
            // nào đó trên server bị lồng thừa 1 lớp (Resources/Resources/...)
            // hoặc thiếu hẳn lớp "Resources" bọc ngoài, copy thẳng theo cách cũ
            // sẽ tạo ra .../files/Resources/Resources/... trên máy người dùng —
            // đúng lỗi đã gặp ở luồng cài mod, dùng lại chung logic dò/xác thực
            // (locateResourcesRoot + fallback locateVersionFolderParent) để tự
            // sửa các trường hợp đóng gói sai này.
            File resourcesContentRoot = locateResourcesRoot(extractTmpDir);
            if (resourcesContentRoot == null) {
                resourcesContentRoot = locateVersionFolderParent(extractTmpDir);
            }
            if (resourcesContentRoot == null) {
                dismissProgressDialog();
                deleteRecursive(extractTmpDir);
                showDialog("Lỗi", "Gói Resources trên server có cấu trúc không hợp lệ "
                    + "(không tìm thấy thư mục Resources hoặc thư mục version nào bên trong) — "
                    + "đây là lỗi đóng gói phía server, vui lòng báo Ninfinity.");
                return;
            }

            // Đọc version.txt chính xác từ gói Resources VỪA giải nén — dùng làm
            // chuẩn đối chiếu chính xác cho các lần check version/bảo trì về sau,
            // và cũng dùng chính tên thư mục này (không phải gameVersion từ config.json)
            // để tạo marker "đã Fix", tránh lệch nếu 2 giá trị này không khớp nhau.
            // Dùng resourcesContentRoot đã xác thực ở trên (không dùng
            // findVersionTxtInExtractedPackage() nữa vì hàm đó giả định cứng
            // "extractTmpDir/Resources", sẽ trả về null sai nếu gói bị lồng
            // thừa/thiếu lớp Resources — cùng loại lỗi vừa sửa ở trên).
            String[] versionInfo = findVersionTxtInResourcesFolder(resourcesContentRoot);
            if (versionInfo != null) {
                resourcesVersionFolder = versionInfo[0];
                resourcesVersionTxt = versionInfo[1];
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_RESOURCES_VERSION_FOLDER, resourcesVersionFolder)
                    .putString(PREF_RESOURCES_VERSION_TXT, resourcesVersionTxt)
                    .apply();
            }

            updateProgressDialog("Đang copy vào game...", 75);
            // rish chỉ làm nhiệm vụ copy file đã giải nén sẵn vào Android/data/.
            // Copy đúng NỘI DUNG của resourcesContentRoot (đã xác thực) thẳng
            // vào RESOURCES_PATH — không copy nguyên extractTmpDir/. vào
            // DATA_PATH/ như trước, để không phụ thuộc cấu trúc lồng nhau
            // trong zip gốc.
            boolean copied = runShell("mkdir -p \"" + RESOURCES_PATH + "\" && cp -r \"" + resourcesContentRoot.getAbsolutePath() + "/.\" \"" + RESOURCES_PATH + "/\"");

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

        // Fallback: mod chỉ nén thẳng thư mục version (vd "1.63.1/..."), không
        // có thư mục "Resources/" bọc ngoài — dò tìm thư mục version rồi lấy
        // cha của nó làm sourceDir.
        if (sourceDir == null) {
            sourceDir = locateVersionFolderParent(extractTmpDir);
        }

        if (sourceDir == null) {
            dismissProgressDialog();
            deleteRecursive(extractTmpDir);
            tmpZip.delete();
            showDialog("Lỗi", "Không tìm thấy thư mục Resources trong file mod.\n\n"
                + "ZIP phải chứa thư mục Resources, hoặc trực tiếp thư mục version "
                + "(vd 1.63.1/...), ở đâu đó bên trong (có thể lồng trong nhiều thư "
                + "mục cha), ví dụ:\n"
                + "• Resources/...\n"
                + "• files/Resources/...\n"
                + "• com.garena.game.kgvn/files/Resources/...\n"
                + "• TenMod/com.garena.game.kgvn/files/Resources/...\n"
                + "• 1.63.1/... (thư mục version trực tiếp)");
            mainHandler.post(() -> {
                setButtonsEnabled(true);
                showProgress(false);
            });
            return;
        }

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

        // ─── Kiểm tra version mod trước khi cài ────────────────────────
        // Ưu tiên đối chiếu version LUÔN, không phụ thuộc app có tự biết Resources
        // đã được Fix hay chưa — vì lệch version thì chắc chắn lỗi không vào được
        // game dù copy "thành công", trong khi "app chưa ghi nhận đã Fix" chỉ là
        // app không biết (người dùng có thể đã Fix bằng cách khác ngoài app này).
        if (isModVersionMismatch(sourceDir)) {
            dismissProgressDialog();
            mainHandler.post(() -> showModVersionWarningDialog(sourceDir, extractTmpDir, tmpZip));
            return;
        }

        proceedModInstallCopy(sourceDir, extractTmpDir, tmpZip);
    }

    // Dò trực tiếp trên máy (không phụ thuộc lịch sử Fix Resources qua app) để
    // lấy version thực tế đang có trong Resources — dùng làm CHUẨN đối chiếu
    // với mod, vì Resources có thể đã được Fix bằng cách khác mà app không biết.
    // Trả về {tên thư mục, nội dung version.txt (rỗng nếu máy không có file này)}.
    private String[] findLiveDeviceVersionInfo() {
        String rawOutput = runShellOutput("ls \"" + RESOURCES_PATH + "\" 2>/dev/null");
        for (String l : rawOutput.split("\n")) {
            String folderName = l.trim();
            if (folderName.isEmpty() || !folderName.matches("\\d+\\.\\d+.*")) continue;
            String content = runShellOutput(
                "cat \"" + RESOURCES_PATH + "/" + folderName + "/" + VERSION_FILE_NAME + "\" 2>/dev/null").trim();
            return new String[]{folderName, content};
        }
        return null;
    }

    // So sánh version của mod (thư mục sourceDir = Resources tìm được trong mod)
    // với version THẬT đang có trên máy (không phải lịch sử Fix của app).
    // Ưu tiên đối chiếu version.txt nếu cả 2 bên đều có; nếu không thì fallback
    // so tên thư mục version trực tiếp.
    private boolean isModVersionMismatch(File sourceDir) {
        String[] deviceVersionInfo = findLiveDeviceVersionInfo();
        if (deviceVersionInfo == null) {
            // Máy chưa có thư mục version nào trong Resources (chưa Fix lần nào,
            // kể cả bằng cách khác) → không đủ căn cứ đối chiếu, bỏ qua cảnh báo.
            return false;
        }
        String deviceFolder = deviceVersionInfo[0];
        String deviceVersionTxt = deviceVersionInfo[1];

        String[] modVersionInfo = findVersionTxtInResourcesFolder(sourceDir);
        if (modVersionInfo != null && !deviceVersionTxt.isEmpty()) {
            // Cả 2 bên đều có version.txt → đối chiếu CHÍNH XÁC nội dung
            return !modVersionInfo[1].equals(deviceVersionTxt);
        }

        // Thiếu version.txt ở 1 trong 2 bên → fallback so tên thư mục version
        String modVersionFolder = modVersionInfo != null ? modVersionInfo[0] : findVersionFolderName(sourceDir);
        return modVersionFolder != null && !modVersionFolder.equals(deviceFolder);
    }

    // Bảng cảnh báo đầu tiên khi phát hiện mod thuộc phiên bản khác — Dừng cài
    // (huỷ, dọn dẹp file tạm) hoặc Tiếp tục cài (chuyển sang bảng cảnh báo mạnh hơn).
    private void showModVersionWarningDialog(File sourceDir, File extractTmpDir, File tmpZip) {
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("⚠️ Cảnh báo phiên bản")
            .setMessage(parseBoldMarkup(
                "File Mod này có vẻ thuộc về /phiên bản game cũ hơn/, hãy sử dụng một File Mod mới hơn để đảm bảo trải nghiệm."))
            .setCancelable(false)
            .setNegativeButton("Dừng cài", (d, w) -> cancelModInstall(extractTmpDir, tmpZip))
            .setPositiveButton("Tiếp tục cài", (d, w) -> showModVersionForceWarningDialog(sourceDir, extractTmpDir, tmpZip))
            .create();
        styleDialog(dialog);
        dialog.show();
    }

    // Bảng cảnh báo thứ 2, mạnh hơn — hiện sau khi người dùng chọn "Tiếp tục cài"
    // ở bảng đầu. Dừng (huỷ) hoặc Chấp nhận (tiếp tục copy mod vào game).
    private void showModVersionForceWarningDialog(File sourceDir, File extractTmpDir, File tmpZip) {
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("🚨 Xác nhận rủi ro")
            .setMessage(parseBoldMarkup(
                "Tiếp tục cài File Mod này có thể khiến /không truy cập được game/ hoặc /lỗi mạng bất định/. Ứng dụng này sẽ không chịu trách nhiệm."))
            .setCancelable(false)
            .setNegativeButton("Dừng", (d, w) -> cancelModInstall(extractTmpDir, tmpZip))
            .setPositiveButton("Chấp nhận", (d, w) -> {
                showProgressDialog("Đang cài mod...");
                executor.execute(() -> proceedModInstallCopy(sourceDir, extractTmpDir, tmpZip));
            })
            .create();
        styleDialog(dialog);
        dialog.show();
    }

    // Huỷ cài mod giữa chừng (do người dùng bấm Dừng ở 1 trong 2 bảng cảnh báo
    // version) — dọn file tạm và mở lại nút bấm cho người dùng.
    private void cancelModInstall(File extractTmpDir, File tmpZip) {
        executor.execute(() -> {
            deleteRecursive(extractTmpDir);
            tmpZip.delete();
        });
        setButtonsEnabled(true);
        showProgress(false);
    }

    // Phần copy mod thật sự vào game — tách riêng khỏi finishInstallModFromExtractedDir
    // để có thể gọi lại sau khi người dùng xác nhận "Chấp nhận" ở bảng cảnh báo version
    // (không phải lúc nào cũng chạy ngay, có thể bị tạm dừng chờ người dùng quyết định).
    private void proceedModInstallCopy(File sourceDir, File extractTmpDir, File tmpZip) {
        String targetPath = RESOURCES_PATH;

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
        } else if (!isCurrentResourcesFixed()) {
            // Lý do phổ biến nhất khiến copy thất bại/không xác minh được là do
            // Resources chưa từng được Fix (thư mục Config/marker chưa tồn tại) —
            // báo đúng nguyên nhân thay vì bảng debug dài khó hiểu với người dùng thường.
            showDialog("Cài Mod thất bại", "Cài Mod thất bại, hãy thử /Fix Resources/ rồi thử lại.\n\n"
                + "Lưu ý: Fix Resources sẽ xoá toàn bộ Mod trước đó, bạn có thể cài lại "
                + "Mod trước đó bằng cách nhấn /Xóa Mod/ ở ứng dụng này.");
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
    //
    // QUAN TRỌNG: không dừng ngay khi vừa thấy tên khớp — một số file mod bị
    // lồng thừa 1 lớp "Resources/Resources/..." (do công cụ nén của người
    // dùng tự bọc thêm thư mục cùng tên khi tạo zip). Nếu chấp nhận ngay lớp
    // ngoài, bước copy sau đó sẽ tạo ra .../files/Resources/Resources/... trên
    // máy người dùng. Vì vậy phải XÁC THỰC: thư mục "Resources" tìm được có
    // chứa ít nhất 1 thư mục con khớp định dạng version (vd "1.63.1") hay
    // không — nếu không, phải tiếp tục đào sâu hơn (kể cả xuống thư mục con
    // cũng tên "Resources") để tìm đúng lớp thật.
    private File locateResourcesRoot(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;

        for (File child : children) {
            if (child.isDirectory() && child.getName().equalsIgnoreCase("Resources")) {
                if (containsVersionFolder(child)) {
                    return child;
                }
                // Tên khớp nhưng nội dung bên trong không giống cấu trúc
                // Resources thật (không có thư mục version nào) → rất có thể
                // đây là lớp "Resources" bọc thừa bên ngoài. Đào tiếp bên
                // trong CHÍNH thư mục này trước, ưu tiên tìm lớp "Resources"
                // thật nằm sâu hơn.
                File nested = locateResourcesRoot(child);
                if (nested != null) return nested;
                // Không tìm được lớp nào hợp lệ hơn bên trong → đành chấp
                // nhận lớp ngoài này làm phương án cuối, còn hơn báo lỗi
                // "không tìm thấy Resources" hoàn toàn.
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

    // Một số file mod chỉ nén thẳng thư mục VERSION (vd "1.63.1/...") mà không
    // bọc trong thư mục "Resources/" bên ngoài — vì tên thư mục version đổi
    // theo từng bản game nên không thể match cứng "Resources" như trên. Quét
    // đệ quy tìm thư mục con có tên khớp định dạng version game (3 nhóm số
    // cách nhau dấu chấm, vd "1.63.1", "1.63.1.10"...), rồi trả về THƯ MỤC CHA
    // của nó — vì thư mục cha đó đóng đúng vai trò "Resources" (chứa các thư
    // mục version bên trong), khớp với cách các hàm phía sau
    // (findVersionTxtInResourcesFolder, isModVersionMismatch...) đang xử lý.
    private static final java.util.regex.Pattern VERSION_FOLDER_PATTERN =
        java.util.regex.Pattern.compile("\\d+\\.\\d+\\.\\d+.*");

    // Kiểm tra thư mục truyền vào có chứa TRỰC TIẾP ít nhất 1 thư mục con khớp
    // định dạng version game hay không — dùng để xác thực 1 thư mục "Resources"
    // tìm được có đúng là lớp chứa nội dung thật, hay chỉ là lớp bọc thừa.
    private boolean containsVersionFolder(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (child.isDirectory() && VERSION_FOLDER_PATTERN.matcher(child.getName()).matches()) {
                return true;
            }
        }
        return false;
    }

    private File locateVersionFolderParent(File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] children = dir.listFiles();
        if (children == null) return null;

        for (File child : children) {
            if (child.isDirectory() && VERSION_FOLDER_PATTERN.matcher(child.getName()).matches()) {
                return dir;
            }
        }
        // Không có thư mục version ở cấp này → đệ quy xuống các thư mục con
        for (File child : children) {
            if (child.isDirectory()) {
                File found = locateVersionFolderParent(child);
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
            String activeBackupPath = resolveActiveBackupPath();
            if (activeBackupPath == null) {
                showDialog("Lỗi", "Không tìm thấy thư mục backup Resources gốc. Hãy chạy Fix Resources trước.");
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
            boolean restored = runShell("mv \"" + activeBackupPath + "\" \"" + RESOURCES_PATH + "\"");

            updateProgressDialog("Hoàn tất!", 100);
            dismissProgressDialog();

            if (restored) {
                // Backup vừa được mv về lại Resources gốc → không còn tồn tại nữa,
                // xoá luôn tên đã lưu trong pref để tránh lần sau resolveActiveBackupPath()
                // trỏ vào 1 path không còn tồn tại.
                getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                    .remove(PREF_BACKUP_FOLDER_NAME)
                    .apply();
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

    // Chuyển text có đánh dấu /.../ thành CharSequence với phần nằm giữa 2 dấu /
    // được in đậm (dấu / bị loại bỏ khỏi kết quả hiển thị). Dùng chung cho
    // showDialog() và bảng thông báo announcement.
    private CharSequence parseBoldMarkup(String text) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf('/', i);
            if (start == -1) {
                builder.append(text.substring(i));
                break;
            }
            int end = text.indexOf('/', start + 1);
            if (end == -1) {
                builder.append(text.substring(i));
                break;
            }
            builder.append(text, i, start);
            int boldStart = builder.length();
            builder.append(text, start + 1, end);
            builder.setSpan(new StyleSpan(Typeface.BOLD), boldStart, builder.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = end + 1;
        }
        return builder;
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    private void showDialog(String title, String msg) {
        mainHandler.post(() -> {
            AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(parseBoldMarkup(msg))
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

    // ─── Product tour (spotlight/coach-mark) ──────────────────────

    private void startAppTour() {
        new TourManager(this)
            .addStep(findViewById(R.id.shizuku_badge),
                "Trạng thái Shizuku",
                "Chấm xanh nghĩa là Shizuku đang chạy và đã cấp quyền — cần thiết để app thao tác được với file game.")
            .addStep(tvResourcesStatus,
                "Trạng thái Resources",
                "Cho biết Resources trên máy đã được Fix hay chưa, dựa trên đúng phiên bản game hiện tại.")
            .addStep(findViewById(R.id.card_fix_resources),
                "🔧 Fix Resources",
                "Bấm vào đây đầu tiên làm mới Resources và đảm bảo cài Mod thành công, không bắt buộc.")
            .addStep(findViewById(R.id.card_install_mod),
                "📦 Cài file Mod",
                "Dùng nút này để chọn file .zip Mod muốn cài vào game. Nếu cài thất bại, bạn phải Fix Resources trước rồi thử lại.")
            .addStep(findViewById(R.id.card_remove_mod),
                "🗑️ Xóa tất cả Mod",
                "Xoá tất cả Mod đã cài từ ứng dụng này. Nếu bạn đã Fix Resources trước đó, sẽ hoàn trả lại Resources cũ trước khi Fix.")
            .addStep(findViewById(R.id.tv_tool_section_label),
                "🧰 Công cụ tạo Mod",
                "Các công cụ tạo Mod cùng tác giả, đang cập nhật thêm...")
            .start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        executor.shutdown();
    }
}
