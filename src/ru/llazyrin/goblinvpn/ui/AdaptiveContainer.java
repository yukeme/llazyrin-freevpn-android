package ru.llazyrin.goblinvpn.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/**
 * Adaptive container that adheres to an 8dp grid system.
 * - On compact phones and standard screens (< 512dp width): adapts to full available width.
 * - On tablets and landscape devices (>= 512dp width): clamps max width to 512dp (64 * 8dp)
 *   and cleanly centers itself in the parent container.
 */
public class AdaptiveContainer extends LinearLayout {

    private int maxWidthPx = 0;

    public AdaptiveContainer(Context context) {
        super(context);
        init(context);
    }

    public AdaptiveContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public AdaptiveContainer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        if (context != null && context.getResources() != null) {
            float density = context.getResources().getDisplayMetrics().density;
            // 512dp = 64 * 8dp grid units
            maxWidthPx = (int) (512 * density + 0.5f);
        }
    }

    public void setMaxWidthDp(int maxDp) {
        float density = getContext().getResources().getDisplayMetrics().density;
        maxWidthPx = (int) (maxDp * density + 0.5f);
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);

        if (maxWidthPx > 0 && widthSize > maxWidthPx) {
            int newMode = widthMode == MeasureSpec.UNSPECIFIED ? MeasureSpec.UNSPECIFIED : MeasureSpec.EXACTLY;
            widthMeasureSpec = MeasureSpec.makeMeasureSpec(maxWidthPx, newMode);
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
