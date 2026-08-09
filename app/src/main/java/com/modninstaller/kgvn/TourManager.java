package com.modninstaller.kgvn;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

// Quản lý "product tour" kiểu spotlight/coach-mark: phủ nền tối toàn màn hình
// (SpotlightOverlayView), đục 1 vùng sáng đúng phần tử đang giới thiệu, kèm
// tooltip giải thích + nút "Tiếp →"/"Bỏ qua", đi qua từng bước tuần tự.
public class TourManager {

    private static class Step {
        final View targetView;
        final String title;
        final String description;
        final Runnable onBeforeShow;

        Step(View targetView, String title, String description, Runnable onBeforeShow) {
            this.targetView = targetView;
            this.title = title;
            this.description = description;
            this.onBeforeShow = onBeforeShow;
        }
    }

    private final Activity activity;
    private final List<Step> steps = new ArrayList<>();
    private int currentIndex = 0;

    private ViewGroup rootContainer;
    private SpotlightOverlayView spotlightView;
    private LinearLayout tooltipView;
    private TextView tvStepCount;
    private TextView tvTitle;
    private TextView tvDescription;
    private TextView btnNext;

    private RectF currentRect = null; // rect đang hiển thị, dùng làm điểm bắt đầu khi animate sang bước kế
    private ValueAnimator rectAnimator;
    private ValueAnimator pulseAnimator;

    private Runnable onFinishedListener;

    public TourManager(Activity activity) {
        this.activity = activity;
    }

    public TourManager addStep(View targetView, String title, String description) {
        return addStep(targetView, title, description, null);
    }

    // Overload có thêm onBeforeShow: chạy NGAY TRƯỚC khi đo vị trí target — dùng
    // cho các target có thể đang bị ẩn (visibility GONE) tại thời điểm addStep(),
    // ví dụ nằm trong 1 khu vực cần bật hiện lên trước thì mới đo được toạ độ
    // thật. Không có bước này, view GONE sẽ có width/height = 0, khiến khung
    // spotlight bị lệch/vô hiệu.
    public TourManager addStep(View targetView, String title, String description, Runnable onBeforeShow) {
        if (targetView != null) {
            steps.add(new Step(targetView, title, description, onBeforeShow));
        }
        return this;
    }

    public TourManager setOnFinishedListener(Runnable listener) {
        this.onFinishedListener = listener;
        return this;
    }

    public void start() {
        if (steps.isEmpty()) return;
        currentIndex = 0;
        buildOverlay();
        startPulseAnimation();
        showStep(currentIndex);
    }

    // ─── Dựng overlay + tooltip ────────────────────────────────────

    private void buildOverlay() {
        rootContainer = activity.findViewById(android.R.id.content);

        spotlightView = new SpotlightOverlayView(activity);
        spotlightView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        spotlightView.setAlpha(0f);
        rootContainer.addView(spotlightView);

        tooltipView = buildTooltipView();
        tooltipView.setAlpha(0f);
        rootContainer.addView(tooltipView);

        // Hiệu ứng mờ dần khi tour bắt đầu, thay vì hiện đột ngột
        spotlightView.animate().alpha(1f).setDuration(220).start();
        tooltipView.animate().alpha(1f).setDuration(280).setStartDelay(80).start();
    }

    private LinearLayout buildTooltipView() {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad, pad, pad);
        container.setBackground(makeRoundedBackground(density));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (20 * density);
        lp.rightMargin = (int) (20 * density);
        lp.gravity = Gravity.TOP | Gravity.START;
        container.setLayoutParams(lp);

        tvStepCount = new TextView(activity);
        tvStepCount.setTextColor(0xFF888888);
        tvStepCount.setTextSize(11);
        container.addView(tvStepCount);

        tvTitle = new TextView(activity);
        tvTitle.setTextColor(0xFFe94560);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = (int) (4 * density);
        tvTitle.setLayoutParams(titleLp);
        container.addView(tvTitle);

        tvDescription = new TextView(activity);
        tvDescription.setTextColor(0xFFffffff);
        tvDescription.setTextSize(14);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = (int) (8 * density);
        tvDescription.setLayoutParams(descLp);
        container.addView(tvDescription);

        LinearLayout buttonRow = new LinearLayout(activity);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = (int) (16 * density);
        buttonRow.setLayoutParams(rowLp);

        TextView btnSkip = new TextView(activity);
        btnSkip.setText("Bỏ qua");
        btnSkip.setTextColor(0xFF888888);
        btnSkip.setPadding(pad, pad / 2, pad, pad / 2);
        btnSkip.setClickable(true);
        btnSkip.setFocusable(true);
        btnSkip.setOnClickListener(v -> finish());
        buttonRow.addView(btnSkip);

        btnNext = new TextView(activity);
        btnNext.setTextColor(0xFFe94560);
        btnNext.setTypeface(Typeface.DEFAULT_BOLD);
        btnNext.setPadding(pad, pad / 2, pad, pad / 2);
        btnNext.setClickable(true);
        btnNext.setFocusable(true);
        btnNext.setOnClickListener(v -> next());
        buttonRow.addView(btnNext);

        container.addView(buttonRow);
        return container;
    }

    private GradientDrawable makeRoundedBackground(float density) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF16213e);
        bg.setCornerRadius(14 * density);
        bg.setStroke((int) density, 0xFF0f3460);
        return bg;
    }

    // ─── Điều hướng từng bước ──────────────────────────────────────

    private void next() {
        currentIndex++;
        if (currentIndex >= steps.size()) {
            finish();
        } else {
            showStep(currentIndex);
        }
    }

    private void finish() {
        if (rootContainer == null) return;
        if (rectAnimator != null) rectAnimator.cancel();
        if (pulseAnimator != null) pulseAnimator.cancel();

        // Mờ dần khi đóng thay vì biến mất đột ngột
        spotlightView.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            rootContainer.removeView(spotlightView);
        }).start();
        tooltipView.animate().alpha(0f).setDuration(180).withEndAction(() -> {
            rootContainer.removeView(tooltipView);
            if (onFinishedListener != null) onFinishedListener.run();
        }).start();
    }

    // Vòng lặp hiệu ứng "thở" (pulse) quanh viền spotlight — chạy liên tục
    // suốt tour, không phụ thuộc bước nào, chỉ để thu hút mắt vào vùng sáng.
    private void startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1100);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.RESTART);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(a -> {
            if (spotlightView != null) {
                spotlightView.setPulseProgress((float) a.getAnimatedValue());
            }
        });
        pulseAnimator.start();
    }

    private void showStep(int index) {
        Step step = steps.get(index);
        View target = step.targetView;

        // Chạy hook trước khi đo — vd bật hiện 1 khu vực đang GONE, để target có
        // toạ độ/kích thước thật khi đo bên dưới.
        if (step.onBeforeShow != null) step.onBeforeShow.run();

        // post() để đợi layout ổn định, đảm bảo target đã có toạ độ thật trên màn hình
        target.post(() -> {
            float density = activity.getResources().getDisplayMetrics().density;
            float padding = 8 * density;

            // QUAN TRỌNG: getLocationInWindow() trả toạ độ SO VỚI WINDOW, nhưng
            // spotlightView lại được vẽ tương đối SO VỚI rootContainer (content view).
            // 2 hệ toạ độ này lệch nhau đúng bằng khoảng cách từ rootContainer tới
            // rìa window (thường là chiều cao status bar/notch — khác nhau tuỳ máy).
            // Trước đây dùng thẳng toạ độ window nên bị lệch xuống dưới, và mức độ
            // lệch khác nhau giữa các thiết bị. Fix: trừ đi toạ độ của rootContainer
            // để quy về đúng hệ toạ độ cục bộ mà spotlightView đang vẽ.
            int[] rootLoc = new int[2];
            rootContainer.getLocationInWindow(rootLoc);

            int[] targetLoc = new int[2];
            target.getLocationInWindow(targetLoc);

            float left = (targetLoc[0] - rootLoc[0]) - padding;
            float top = (targetLoc[1] - rootLoc[1]) - padding;

            RectF newRect = new RectF(
                left,
                top,
                left + target.getWidth() + padding * 2,
                top + target.getHeight() + padding * 2);

            animateSpotlightTo(newRect, 16 * density);

            tvStepCount.setText("Bước " + (index + 1) + "/" + steps.size());
            tvTitle.setText(step.title);
            tvDescription.setText(step.description);
            btnNext.setText(index == steps.size() - 1 ? "Xong ✓" : "Tiếp →");

            positionTooltip(newRect, density);
        });
    }

    // Animate spotlight trượt mượt từ vị trí cũ sang vị trí mới thay vì
    // "nhảy" đột ngột giữa các bước.
    private void animateSpotlightTo(RectF newRect, float cornerRadius) {
        if (rectAnimator != null) rectAnimator.cancel();

        RectF startRect = currentRect != null ? currentRect : newRect;
        rectAnimator = ValueAnimator.ofFloat(0f, 1f);
        rectAnimator.setDuration(currentRect == null ? 0 : 260);
        rectAnimator.setInterpolator(new DecelerateInterpolator());
        rectAnimator.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            RectF lerped = new RectF(
                startRect.left + (newRect.left - startRect.left) * f,
                startRect.top + (newRect.top - startRect.top) * f,
                startRect.right + (newRect.right - startRect.right) * f,
                startRect.bottom + (newRect.bottom - startRect.bottom) * f);
            spotlightView.setSpotlightRect(lerped, cornerRadius);
        });
        rectAnimator.start();
        currentRect = newRect;
    }

    // Đặt tooltip ngay dưới spotlight nếu đủ chỗ, ngược lại đặt lên trên
    private void positionTooltip(RectF spotlightRect, float density) {
        tooltipView.post(() -> {
            int screenHeight = rootContainer.getHeight();
            int tooltipHeight = tooltipView.getHeight();

            float spaceBelow = screenHeight - spotlightRect.bottom;
            float spaceAbove = spotlightRect.top;

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) tooltipView.getLayoutParams();
            int newTopMargin;
            if (spaceBelow >= tooltipHeight + 24 * density || spaceBelow >= spaceAbove) {
                newTopMargin = (int) (spotlightRect.bottom + 16 * density);
            } else {
                newTopMargin = (int) Math.max(24 * density, spotlightRect.top - tooltipHeight - 16 * density);
            }

            // Animate tooltip trượt theo thay vì giật cục khi đổi vị trí trên/dưới
            if (lp.topMargin != 0 && lp.topMargin != newTopMargin) {
                ValueAnimator marginAnimator = ValueAnimator.ofInt(lp.topMargin, newTopMargin);
                marginAnimator.setDuration(220);
                marginAnimator.setInterpolator(new DecelerateInterpolator());
                marginAnimator.addUpdateListener(a -> {
                    lp.topMargin = (int) a.getAnimatedValue();
                    tooltipView.setLayoutParams(lp);
                });
                marginAnimator.start();
            } else {
                lp.topMargin = newTopMargin;
                tooltipView.setLayoutParams(lp);
            }
        });
    }
}
