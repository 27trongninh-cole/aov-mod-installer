package com.modninstaller.kgvn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.view.View;

// Vẽ nền tối phủ toàn màn hình + đục 1 "lỗ sáng" (spotlight) đúng vị trí phần tử
// đang được giới thiệu trong product tour, kèm 1 vòng viền "thở" (pulse) lan ra
// ngoài liên tục quanh spotlight để thu hút mắt người dùng. Dùng LAYER_TYPE_HARDWARE
// + PorterDuff.CLEAR để đục lỗ xuyên qua lớp màu tối đã vẽ trên chính View này
// (kỹ thuật chuẩn cho spotlight/coach-mark overlay).
public class SpotlightOverlayView extends View {

    private static final int DIM_COLOR = 0xCC000000; // đen mờ 80%
    private static final float MAX_PULSE_EXPAND_DP = 10f; // vòng pulse lan ra tối đa bao xa

    private final Paint clearPaint = new Paint();
    private final Paint pulsePaint = new Paint();
    private RectF spotlightRect = null;
    private float cornerRadius = 16f;
    private float pulseProgress = 0f; // 0..1, lặp lại liên tục

    public SpotlightOverlayView(Context context) {
        super(context);
        init();
    }

    private void init() {
        setWillNotDraw(false);
        clearPaint.setAntiAlias(true);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        // Bắt buộc phải là hardware layer thì PorterDuff.CLEAR mới đục được lỗ
        // xuyên qua màu đã vẽ trước đó trên cùng 1 View.
        setLayerType(View.LAYER_TYPE_HARDWARE, null);

        pulsePaint.setAntiAlias(true);
        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setColor(0xFFe94560);
        pulsePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);

        // Chặn mọi chạm xuyên qua vùng tối xuống UI phía sau — ép người dùng
        // phải dùng nút Tiếp/Bỏ qua trên tooltip thay vì bấm nhầm ra ngoài.
        setClickable(true);
    }

    public void setSpotlightRect(RectF rect, float cornerRadiusPx) {
        this.spotlightRect = rect;
        this.cornerRadius = cornerRadiusPx;
        invalidate();
    }

    public void setPulseProgress(float progress) {
        this.pulseProgress = progress;
        invalidate();
    }

    public void clearSpotlight() {
        this.spotlightRect = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(DIM_COLOR);
        if (spotlightRect == null) return;

        canvas.drawRoundRect(spotlightRect, cornerRadius, cornerRadius, clearPaint);

        // Vòng viền lan ra + mờ dần theo pulseProgress (0 -> 1 lặp lại), tạo hiệu
        // ứng "thở" quanh spotlight để thu hút sự chú ý vào đúng phần tử đang giới thiệu.
        float density = getResources().getDisplayMetrics().density;
        float expand = pulseProgress * MAX_PULSE_EXPAND_DP * density;
        int alpha = (int) (255 * (1f - pulseProgress));
        pulsePaint.setAlpha(alpha);

        RectF pulseRect = new RectF(
            spotlightRect.left - expand,
            spotlightRect.top - expand,
            spotlightRect.right + expand,
            spotlightRect.bottom + expand);
        canvas.drawRoundRect(pulseRect, cornerRadius + expand, cornerRadius + expand, pulsePaint);
    }
}
