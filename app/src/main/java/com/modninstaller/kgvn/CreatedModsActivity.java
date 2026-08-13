package com.modninstaller.kgvn;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// Màn "Các Mod đã tạo" — liệt kê mod (.zip) đang nằm trong bộ nhớ riêng của
// app (ModManifest.getModsDir()), mỗi dòng gồm tên/thời gian tạo/phiên bản
// game lúc tải, có thể bấm để cài lại hoặc xoá. KHÔNG cần Shizuku để MỞ màn
// này (chỉ đọc/xoá file riêng của app) — chỉ cần Shizuku khi thật sự bấm cài
// 1 mod, lúc đó việc cài được giao lại cho MainActivity xử lý (xem finish()
// bên dưới).
public class CreatedModsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_created_mods);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderList(); // load lại mỗi lần quay lại màn này (sau khi xoá 1 mod chẳng hạn)
    }

    private void renderList() {
        LinearLayout container = findViewById(R.id.container_mods);
        TextView emptyState = findViewById(R.id.tv_empty_state);
        container.removeAllViews();

        List<ModManifest.ModEntry> entries = ModManifest.loadAll(this);
        // Mới tải lên trước — người dùng thường quan tâm mod vừa tạo gần đây nhất.
        Collections.sort(entries, (a, b) -> Long.compare(b.createdTime, a.createdTime));

        if (entries.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            return;
        }
        emptyState.setVisibility(View.GONE);

        int density = (int) getResources().getDisplayMetrics().density;
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault());

        for (ModManifest.ModEntry entry : entries) {
            CardView card = new CardView(this);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = 12 * density;
            card.setLayoutParams(cardParams);
            card.setRadius(14 * density);
            card.setCardElevation(3 * density);
            card.setCardBackgroundColor(0xFF2a2a3e);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(16 * density, 12 * density, 8 * density, 12 * density);

            // Phần bấm để cài — chiếm hết chỗ còn lại, riêng nút Xoá tách khỏi vùng này
            // để không lỡ bấm nhầm khi chỉ định xoá.
            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);
            infoCol.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            infoCol.setClickable(true);
            infoCol.setFocusable(true);
            infoCol.setBackgroundResource(android.R.color.transparent);
            android.util.TypedValue outValue = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            infoCol.setBackgroundResource(outValue.resourceId);

            TextView tvName = new TextView(this);
            tvName.setText(entry.fileName);
            tvName.setTextColor(0xFFFFFFFF);
            tvName.setTextSize(15f);
            tvName.setTypeface(tvName.getTypeface(), android.graphics.Typeface.BOLD);
            infoCol.addView(tvName);

            TextView tvMeta = new TextView(this);
            String versionLabel = (entry.gameVersion == null || entry.gameVersion.isEmpty())
                ? "Không rõ" : entry.gameVersion;
            tvMeta.setText("Tạo lúc: " + fmt.format(new java.util.Date(entry.createdTime))
                + "  •  Phiên bản game: " + versionLabel);
            tvMeta.setTextColor(0xFF888888);
            tvMeta.setTextSize(11f);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            metaParams.topMargin = 2 * density;
            tvMeta.setLayoutParams(metaParams);
            infoCol.addView(tvMeta);

            infoCol.setOnClickListener(v -> confirmInstall(entry));
            row.addView(infoCol);

            TextView btnDelete = new TextView(this);
            btnDelete.setText("🗑️");
            btnDelete.setTextSize(18f);
            btnDelete.setGravity(Gravity.CENTER);
            btnDelete.setPadding(12 * density, 12 * density, 12 * density, 12 * density);
            btnDelete.setClickable(true);
            btnDelete.setFocusable(true);
            android.util.TypedValue outValue2 = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue2, true);
            btnDelete.setBackgroundResource(outValue2.resourceId);
            btnDelete.setOnClickListener(v -> confirmDelete(entry));
            row.addView(btnDelete);

            card.addView(row);
            container.addView(card);
        }
    }

    private void confirmDelete(ModManifest.ModEntry entry) {
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Xoá mod")
            .setMessage("Xoá \"" + entry.fileName + "\" khỏi máy? Không thể hoàn tác.")
            .setPositiveButton("Xoá", (d, w) -> {
                ModManifest.deleteEntry(this, entry.fileName);
                renderList();
            })
            .setNegativeButton("Huỷ", null)
            .create();
        dialog.show();
    }

    // Không tự cài ở đây — CreatedModsActivity không có sẵn executor/kết nối
    // Shizuku (chỉ MainActivity có). Ghi lại lựa chọn của người dùng vào cùng
    // Hiện dialog xác nhận NGAY TẠI ĐÂY (không finish() trước rồi mới hiện dialog
    // ở MainActivity) — trước đây finish() chạy trước, quay lại MainActivity rồi
    // mới hiện dialog qua onResume(), nên có 1 khoảng người dùng thấy màn hình
    // chính/công cụ tạo mod trần trụi (không dialog) trước khi dialog kịp hiện —
    // gây cảm giác giật/nhảy màn hình. Giờ chỉ finish() SAU KHI người dùng đã bấm
    // xác nhận — cảnh báo lệch phiên bản (nếu có) vẫn do MainActivity xử lý sau
    // khi quay lại (chấp nhận được vì đó là trường hợp hiếm/cảnh báo phụ thêm,
    // không phải luồng xác nhận thường ngày mà người dùng hay gặp).
    private void confirmInstall(ModManifest.ModEntry entry) {
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Cài mod")
            .setMessage("Mod này sẽ được cài vào game. Tiếp tục?")
            .setPositiveButton("Tiếp tục", (d, w) -> {
                SharedPreferences prefs = getSharedPreferences(WebViewActivity.PREFS_NAME, MODE_PRIVATE);
                prefs.edit()
                    .putString(WebViewActivity.KEY_PENDING_MOD_PATH, entry.file(this).getAbsolutePath())
                    .putString(WebViewActivity.KEY_PENDING_MOD_VERSION, entry.gameVersion)
                    .putString(WebViewActivity.KEY_PENDING_MOD_SOURCE, WebViewActivity.SOURCE_LIST)
                    .putBoolean(WebViewActivity.KEY_PENDING_MOD_ALREADY_CONFIRMED, true)
                    .apply();
                finish();
            })
            .setNegativeButton("Huỷ", null)
            .create();
        dialog.show();
    }
}
