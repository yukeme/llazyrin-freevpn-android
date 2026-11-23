package ru.llazyrin.goblinvpn.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * Atmospheric deep-space starfield & obsidian-emerald nebula background view.
 * Renders multi-layer parallax flying stars, soft mossy-emerald radial glows,
 * and occasional shooting stars at 60fps with zero allocations inside onDraw().
 */
public class StarfieldView extends View {

    // Base cosmic obsidian color
    private static final int COLOR_BG_OBSIDIAN = 0xFF080C09;

    // Star palette (soft white & subtle emerald tints)
    private static final int COLOR_STAR_DIM = 0xFFC5D8C2;
    private static final int COLOR_STAR_SOFT_WHITE = 0xFFE8F8E5;
    private static final int COLOR_STAR_EMERALD = 0xFF8CE07A;
    private static final int COLOR_STAR_PALE_EMERALD = 0xFFB8ECB0;
    private static final int COLOR_METEOR_CORE = 0xFFF3FFF0;
    private static final int COLOR_METEOR_TAIL = 0xFF8CE07A;

    // 3 parallax depth layers: 42 distant + 30 medium + 16 near = 88 stars
    private static final int COUNT_LAYER_FAR = 42;
    private static final int COUNT_LAYER_MID = 30;
    private static final int COUNT_LAYER_NEAR = 16;
    private static final int STAR_COUNT = COUNT_LAYER_FAR + COUNT_LAYER_MID + COUNT_LAYER_NEAR;

    // Shooting star rendering segments (tapered trail, zero allocations)
    private static final int METEOR_SEGMENTS = 10;

    // Flat primitive star arrays (zero GC pressure during animation)
    private final float[] starX = new float[STAR_COUNT];
    private final float[] starY = new float[STAR_COUNT];
    private final float[] starRadius = new float[STAR_COUNT];
    private final float[] starSpeedX = new float[STAR_COUNT];
    private final float[] starSpeedY = new float[STAR_COUNT];
    private final float[] starBaseAlpha = new float[STAR_COUNT];
    private final float[] starTwinklePhase = new float[STAR_COUNT];
    private final float[] starTwinkleSpeed = new float[STAR_COUNT];
    private final int[] starLayer = new int[STAR_COUNT];
    private final int[] starColor = new int[STAR_COUNT];

    // Pre-allocated paints (reused every frame)
    private final Paint topNebulaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topActiveNebulaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bottomNebulaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meteorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meteorGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Random random = new Random();

    // Cached viewport metrics
    private int viewWidth = 0;
    private int viewHeight = 0;
    private float density = 1f;
    private boolean starsInitialized = false;

    // Nebula geometry (precomputed in onSizeChanged)
    private float topGlowCx;
    private float topGlowCy;
    private float topGlowRadius;
    private float topActiveGlowCy;
    private float topActiveGlowRadius;
    private float bottomGlowCx;
    private float bottomGlowCy;
    private float bottomGlowRadius;

    // Animation & mode state
    private boolean activeMode = false;
    private boolean paused = false;
    private boolean attached = false;
    private float activeBlend = 0f; // smoothly interpolates 0f (idle) <-> 1f (connected)
    private float elapsedSec = 0f;
    private long lastFrameTimeMs = 0L;

    // Shooting star state (single active meteor at a time)
    private boolean meteorActive = false;
    private float meteorX;
    private float meteorY;
    private float meteorVx;
    private float meteorVy;
    private float meteorLength;
    private float meteorAge;
    private float meteorDuration;
    private float nextMeteorCooldownSec;

    public StarfieldView(Context context) {
        super(context);
        init(context);
    }

    public StarfieldView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public StarfieldView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public StarfieldView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        if (density <= 0f) {
            density = 1f;
        }

        if (isInEditMode()) {
            random.setSeed(42L);
        }

        topNebulaPaint.setStyle(Paint.Style.FILL);
        topActiveNebulaPaint.setStyle(Paint.Style.FILL);
        bottomNebulaPaint.setStyle(Paint.Style.FILL);

        starPaint.setStyle(Paint.Style.FILL);
        starPaint.setStrokeCap(Paint.Cap.ROUND);
        starGlowPaint.setStyle(Paint.Style.FILL);

        meteorPaint.setStyle(Paint.Style.STROKE);
        meteorPaint.setStrokeCap(Paint.Cap.ROUND);

        meteorGlowPaint.setStyle(Paint.Style.STROKE);
        meteorGlowPaint.setStrokeCap(Paint.Cap.ROUND);

        nextMeteorCooldownSec = 2.5f + random.nextFloat() * 3.5f;
    }

    /**
     * Enables or disables connected/active warp mode.
     * Smoothly intensifies emerald nebula glow and gently increases star travel speed.
     */
    public void setActiveMode(boolean active) {
        if (this.activeMode != active) {
            this.activeMode = active;
            if (shouldAnimate()) {
                postInvalidateOnAnimation();
            } else {
                invalidate();
            }
        }
    }

    public boolean isActiveMode() {
        return activeMode;
    }

    /**
     * Pauses or resumes the starfield animation loop for battery efficiency.
     */
    public void setPaused(boolean paused) {
        if (this.paused != paused) {
            this.paused = paused;
            if (!paused && shouldAnimate()) {
                lastFrameTimeMs = SystemClock.uptimeMillis();
                postInvalidateOnAnimation();
            }
        }
    }

    public boolean isPaused() {
        return paused;
    }

    private boolean shouldAnimate() {
        return !paused && attached && !isInEditMode() && getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        lastFrameTimeMs = SystemClock.uptimeMillis();
        if (shouldAnimate()) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && shouldAnimate()) {
            lastFrameTimeMs = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && shouldAnimate()) {
            lastFrameTimeMs = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewWidth = w;
        viewHeight = h;

        if (w <= 0 || h <= 0) {
            return;
        }

        buildNebulaShaders(w, h);
        initStars(w, h);
        lastFrameTimeMs = SystemClock.uptimeMillis();
    }

    private void buildNebulaShaders(int w, int h) {
        float maxDim = Math.max(w, h);

        // Top mossy-emerald ambient nebula glow (#3D5E38 / #2A4A28 -> transparent)
        topGlowCx = w * 0.50f;
        topGlowCy = -h * 0.02f;
        topGlowRadius = maxDim * 0.68f;
        RadialGradient topGradient = new RadialGradient(
                topGlowCx,
                topGlowCy,
                topGlowRadius,
                new int[]{
                        0xD83D5E38,
                        0x982A4A28,
                        0x3E1B301A,
                        0x00080C09
                },
                new float[]{0.0f, 0.34f, 0.68f, 1.0f},
                Shader.TileMode.CLAMP
        );
        topNebulaPaint.setShader(topGradient);

        // Connected-state luminous emerald boost shader (faded via paint alpha only)
        topActiveGlowCy = h * 0.04f;
        topActiveGlowRadius = maxDim * 0.64f;
        RadialGradient topActiveGradient = new RadialGradient(
                topGlowCx,
                topActiveGlowCy,
                topActiveGlowRadius,
                new int[]{
                        0xB8568C4D,
                        0x6E3D5E38,
                        0x26244123,
                        0x00080C09
                },
                new float[]{0.0f, 0.38f, 0.72f, 1.0f},
                Shader.TileMode.CLAMP
        );
        topActiveNebulaPaint.setShader(topActiveGradient);

        // Bottom subtle warm-emerald glow behind bottom navigation dock (#2B4D28)
        bottomGlowCx = w * 0.50f;
        bottomGlowCy = h * 1.04f;
        bottomGlowRadius = maxDim * 0.44f;
        RadialGradient bottomGradient = new RadialGradient(
                bottomGlowCx,
                bottomGlowCy,
                bottomGlowRadius,
                new int[]{
                        0xA82B4D28,
                        0x5A213C1F,
                        0x1C142514,
                        0x00080C09
                },
                new float[]{0.0f, 0.40f, 0.74f, 1.0f},
                Shader.TileMode.CLAMP
        );
        bottomNebulaPaint.setShader(bottomGradient);
    }

    private void initStars(int w, int h) {
        int idx = 0;

        // Layer 0: distant tiny dim stars
        for (int i = 0; i < COUNT_LAYER_FAR; i++, idx++) {
            starLayer[idx] = 0;
            starX[idx] = random.nextFloat() * w;
            starY[idx] = random.nextFloat() * h;
            starRadius[idx] = (0.55f + random.nextFloat() * 0.55f) * density;
            starSpeedY[idx] = -(4.0f + random.nextFloat() * 4.5f) * density;
            starSpeedX[idx] = (0.8f + random.nextFloat() * 1.4f) * density;
            starBaseAlpha[idx] = 0.22f + random.nextFloat() * 0.32f;
            starTwinklePhase[idx] = random.nextFloat() * 6.2831853f;
            starTwinkleSpeed[idx] = 0.7f + random.nextFloat() * 1.2f;
            starColor[idx] = (random.nextFloat() < 0.75f) ? COLOR_STAR_DIM : COLOR_STAR_SOFT_WHITE;
        }

        // Layer 1: medium softly twinkling stars
        for (int i = 0; i < COUNT_LAYER_MID; i++, idx++) {
            starLayer[idx] = 1;
            starX[idx] = random.nextFloat() * w;
            starY[idx] = random.nextFloat() * h;
            starRadius[idx] = (0.95f + random.nextFloat() * 0.65f) * density;
            starSpeedY[idx] = -(9.5f + random.nextFloat() * 7.5f) * density;
            starSpeedX[idx] = (1.8f + random.nextFloat() * 2.4f) * density;
            starBaseAlpha[idx] = 0.42f + random.nextFloat() * 0.36f;
            starTwinklePhase[idx] = random.nextFloat() * 6.2831853f;
            starTwinkleSpeed[idx] = 1.3f + random.nextFloat() * 1.9f;
            float tintRoll = random.nextFloat();
            if (tintRoll < 0.55f) {
                starColor[idx] = COLOR_STAR_SOFT_WHITE;
            } else if (tintRoll < 0.85f) {
                starColor[idx] = COLOR_STAR_PALE_EMERALD;
            } else {
                starColor[idx] = COLOR_STAR_EMERALD;
            }
        }

        // Layer 2: closer brighter emerald/white parallax stars
        for (int i = 0; i < COUNT_LAYER_NEAR; i++, idx++) {
            starLayer[idx] = 2;
            starX[idx] = random.nextFloat() * w;
            starY[idx] = random.nextFloat() * h;
            starRadius[idx] = (1.45f + random.nextFloat() * 0.85f) * density;
            starSpeedY[idx] = -(17.5f + random.nextFloat() * 12.5f) * density;
            starSpeedX[idx] = (3.0f + random.nextFloat() * 3.8f) * density;
            starBaseAlpha[idx] = 0.65f + random.nextFloat() * 0.30f;
            starTwinklePhase[idx] = random.nextFloat() * 6.2831853f;
            starTwinkleSpeed[idx] = 1.8f + random.nextFloat() * 2.2f;
            starColor[idx] = (random.nextFloat() < 0.52f) ? COLOR_STAR_SOFT_WHITE : COLOR_STAR_EMERALD;
        }

        starsInitialized = true;
    }

    private void spawnMeteor() {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }

        meteorActive = true;
        meteorAge = 0f;
        meteorDuration = 0.68f + random.nextFloat() * 0.42f;

        // Start in upper half, flying diagonally down-right or down-left across the dark sky
        boolean flyRight = random.nextFloat() < 0.72f;
        if (flyRight) {
            meteorX = viewWidth * (0.04f + random.nextFloat() * 0.55f);
            meteorVx = (260f + random.nextFloat() * 160f) * density;
        } else {
            meteorX = viewWidth * (0.42f + random.nextFloat() * 0.54f);
            meteorVx = -(260f + random.nextFloat() * 160f) * density;
        }

        meteorY = viewHeight * (0.05f + random.nextFloat() * 0.38f);
        meteorVy = (145f + random.nextFloat() * 115f) * density;
        meteorLength = (58f + random.nextFloat() * 44f) * density;
    }

    private void updateSimulation(float dt) {
        elapsedSec += dt;

        // Smoothly interpolate activeMode blend (approx 0.75s transition)
        float targetBlend = activeMode ? 1.0f : 0.0f;
        float blendStep = dt * 1.45f;
        if (activeBlend < targetBlend) {
            activeBlend += blendStep;
            if (activeBlend > targetBlend) {
                activeBlend = targetBlend;
            }
        } else if (activeBlend > targetBlend) {
            activeBlend -= blendStep;
            if (activeBlend < targetBlend) {
                activeBlend = targetBlend;
            }
        }

        // Gentle speed multiplier for subtle connected warp feel
        float speedMul = 1.0f + 1.20f * activeBlend;
        float margin = 6f * density;
        float w = viewWidth;
        float h = viewHeight;

        for (int i = 0; i < STAR_COUNT; i++) {
            starX[i] += starSpeedX[i] * speedMul * dt;
            starY[i] += starSpeedY[i] * speedMul * dt;
            starTwinklePhase[i] += starTwinkleSpeed[i] * dt;

            // Wrap seamlessly when drifting past top or horizontal bounds
            if (starY[i] < -margin) {
                starY[i] = h + margin;
                starX[i] = random.nextFloat() * w;
            }
            if (starX[i] > w + margin) {
                starX[i] = -margin;
            } else if (starX[i] < -margin) {
                starX[i] = w + margin;
            }
        }

        // Update shooting star lifecycle
        if (meteorActive) {
            meteorAge += dt;
            float meteorBoost = 1.0f + 0.25f * activeBlend;
            meteorX += meteorVx * meteorBoost * dt;
            meteorY += meteorVy * meteorBoost * dt;

            if (meteorAge >= meteorDuration
                    || meteorX < -meteorLength * 2f
                    || meteorX > w + meteorLength * 2f
                    || meteorY > h + meteorLength * 2f) {
                meteorActive = false;
                float minDelay = activeMode ? 2.8f : 4.2f;
                float extraDelay = activeMode ? 3.5f : 5.2f;
                nextMeteorCooldownSec = minDelay + random.nextFloat() * extraDelay;
            }
        } else {
            nextMeteorCooldownSec -= dt;
            if (nextMeteorCooldownSec <= 0f) {
                spawnMeteor();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }

        // Compute frame delta safely
        if (shouldAnimate()) {
            long nowMs = SystemClock.uptimeMillis();
            if (lastFrameTimeMs == 0L) {
                lastFrameTimeMs = nowMs;
            }
            float dt = (nowMs - lastFrameTimeMs) * 0.001f;
            lastFrameTimeMs = nowMs;
            if (dt > 0.05f) {
                dt = 0.05f;
            } else if (dt < 0f) {
                dt = 0f;
            }
            if (starsInitialized) {
                updateSimulation(dt);
            }
        }

        // 1. Deep cosmic obsidian base
        canvas.drawColor(COLOR_BG_OBSIDIAN);

        // 2. Soft organic breathing mossy-emerald radial nebulae
        float breathe = (float) Math.sin(elapsedSec * 0.65f);
        int topAlpha = clampAlpha((int) (208 + 18 * breathe + 34 * activeBlend));
        topNebulaPaint.setAlpha(topAlpha);
        canvas.drawCircle(topGlowCx, topGlowCy, topGlowRadius, topNebulaPaint);

        if (activeBlend > 0.005f) {
            int activeAlpha = clampAlpha((int) ((168 + 26 * breathe) * activeBlend));
            topActiveNebulaPaint.setAlpha(activeAlpha);
            canvas.drawCircle(topGlowCx, topActiveGlowCy, topActiveGlowRadius, topActiveNebulaPaint);
        }

        int bottomAlpha = clampAlpha((int) (170 + 14 * (-breathe) + 46 * activeBlend));
        bottomNebulaPaint.setAlpha(bottomAlpha);
        canvas.drawCircle(bottomGlowCx, bottomGlowCy, bottomGlowRadius, bottomNebulaPaint);

        // 3. Multi-layered parallax stars
        if (starsInitialized) {
            drawStars(canvas);
        }

        // 4. Occasional shooting star streak
        if (meteorActive) {
            drawMeteor(canvas);
        }

        // Schedule next 60fps frame only when active and visible
        if (shouldAnimate()) {
            postInvalidateOnAnimation();
        }
    }

    private void drawStars(Canvas canvas) {
        float warpTailFactor = activeBlend * 0.14f;

        for (int i = 0; i < STAR_COUNT; i++) {
            float twinkle = 0.72f + 0.28f * (float) Math.sin(starTwinklePhase[i]);
            float brightnessBoost = 1.0f + 0.18f * activeBlend;
            int alpha = clampAlpha((int) (starBaseAlpha[i] * twinkle * brightnessBoost * 255f));
            if (alpha <= 2) {
                continue;
            }

            float x = starX[i];
            float y = starY[i];
            float r = starRadius[i];
            int layer = starLayer[i];
            int color = starColor[i];

            // Soft emerald/white halo for closer stars (layer 2)
            if (layer == 2) {
                starGlowPaint.setColor(color);
                starGlowPaint.setAlpha((int) (alpha * (0.20f + 0.10f * activeBlend)));
                canvas.drawCircle(x, y, r * (2.6f + 0.45f * activeBlend), starGlowPaint);
            }

            // Subtle velocity stretch when VPN is connected (gentle warp feeling)
            if (warpTailFactor > 0.02f && layer >= 1) {
                float dx = starSpeedX[i] * warpTailFactor;
                float dy = starSpeedY[i] * warpTailFactor;
                starPaint.setColor(color);
                starPaint.setAlpha((int) (alpha * 0.65f));
                starPaint.setStrokeWidth(r * 1.45f);
                canvas.drawLine(x, y, x - dx, y - dy, starPaint);
            }

            // Crisp star core
            starPaint.setColor(color);
            starPaint.setAlpha(alpha);
            canvas.drawCircle(x, y, r, starPaint);
        }
    }

    private void drawMeteor(Canvas canvas) {
        float progress = meteorAge / meteorDuration;
        if (progress <= 0f || progress >= 1f) {
            return;
        }

        // Smooth fade-in and fade-out envelope
        float fade;
        if (progress < 0.18f) {
            fade = progress / 0.18f;
        } else {
            float rem = (1f - progress) / 0.82f;
            fade = rem * rem;
        }
        if (fade <= 0.01f) {
            return;
        }

        float speedMag = (float) Math.hypot(meteorVx, meteorVy);
        if (speedMag <= 0.001f) {
            return;
        }

        float dirX = meteorVx / speedMag;
        float dirY = meteorVy / speedMag;

        // Draw tapered multi-segment fading light trail (zero allocations)
        float segLen = meteorLength / METEOR_SEGMENTS;
        float prevX = meteorX;
        float prevY = meteorY;

        for (int s = 0; s < METEOR_SEGMENTS; s++) {
            float t = (float) s / METEOR_SEGMENTS;
            float inv = 1f - t;
            float segFade = inv * inv * fade;

            float nextX = prevX - dirX * segLen;
            float nextY = prevY - dirY * segLen;

            // Outer emerald aura stroke
            meteorGlowPaint.setColor(COLOR_METEOR_TAIL);
            meteorGlowPaint.setAlpha(clampAlpha((int) (segFade * 95f)));
            meteorGlowPaint.setStrokeWidth((0.8f + 2.8f * inv) * density);
            canvas.drawLine(prevX, prevY, nextX, nextY, meteorGlowPaint);

            // Inner bright white-emerald core streak
            meteorPaint.setColor((s < 3) ? COLOR_METEOR_CORE : COLOR_METEOR_TAIL);
            meteorPaint.setAlpha(clampAlpha((int) (segFade * 235f)));
            meteorPaint.setStrokeWidth((0.4f + 1.3f * inv) * density);
            canvas.drawLine(prevX, prevY, nextX, nextY, meteorPaint);

            prevX = nextX;
            prevY = nextY;
        }

        // Bright meteor head spark
        starGlowPaint.setColor(COLOR_METEOR_TAIL);
        starGlowPaint.setAlpha(clampAlpha((int) (fade * 120f)));
        canvas.drawCircle(meteorX, meteorY, 3.6f * density, starGlowPaint);

        starPaint.setColor(COLOR_METEOR_CORE);
        starPaint.setAlpha(clampAlpha((int) (fade * 255f)));
        canvas.drawCircle(meteorX, meteorY, 1.55f * density, starPaint);
    }

    private static int clampAlpha(int alpha) {
        if (alpha < 0) return 0;
        if (alpha > 255) return 255;
        return alpha;
    }
}
