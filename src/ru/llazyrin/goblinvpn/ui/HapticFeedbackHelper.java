package ru.llazyrin.goblinvpn.ui;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;

/**
 * Modern, multi-tier custom haptic feedback helper for Goblin VPN.
 * Provides distinct, tactile, pleasant haptic patterns tailored for each action type:
 * - {@link #performPowerButton(View)}: Deep, satisfying, tactile mechanical thud with 0ms touch delay and debounce protection.
 * - {@link #performButtonClick(View)}: Sharp, crisp, subtle modern tick for tabs, server card, settings rows.
 * - {@link #performTick(View)}: Ultra-light, crisp micro-tick for chips, search clear, list interactions.
 * - {@link #performSuccess(View)}: Pleasant double-impulse confirmation when VPN connects or settings are applied.
 * - {@link #performToggle(View, boolean)}: Differentiated ON/OFF tactile clicks for switches.
 *
 * Utilizes rich composition primitives on Android 12+ (API 31+),
 * predefined effects on Android 10+ (API 29+), one-shot/waveform patterns on Android 8+ (API 26+),
 * and legacy vibrator/view haptics on Android 7.0+ (API 24-25).
 * Completely thread-safe, null-safe, and exception-safe.
 */
@SuppressWarnings("deprecation")
public final class HapticFeedbackHelper {

    private static volatile Vibrator sVibrator = null;
    private static volatile Boolean sHasVibrator = null;

    // Debounce thresholds (in milliseconds) to prevent vibration stutter or duplicate triggers
    private static volatile long sLastPowerHapticMs = 0;
    private static final long POWER_HAPTIC_DEBOUNCE_MS = 400;

    private static volatile long sLastButtonClickMs = 0;
    private static final long BUTTON_CLICK_DEBOUNCE_MS = 50;

    private static volatile long sLastTickMs = 0;
    private static final long TICK_DEBOUNCE_MS = 35;

    private static volatile long sLastSuccessMs = 0;
    private static final long SUCCESS_DEBOUNCE_MS = 500;

    private static volatile long sLastToggleMs = 0;
    private static final long TOGGLE_DEBOUNCE_MS = 50;

    private HapticFeedbackHelper() {
        // Utility class
    }

    /**
     * Pre-warms and caches the system Vibrator service during app startup,
     * ensuring zero latency on the first user interaction.
     */
    public static void init(Context context) {
        if (context != null && sVibrator == null) {
            getVibrator(context);
        }
    }

    /**
     * Retrieves and caches the system Vibrator instance.
     */
    private static Vibrator getVibrator(Context context) {
        if (sVibrator != null) {
            return sVibrator;
        }
        if (context == null) {
            return null;
        }
        synchronized (HapticFeedbackHelper.class) {
            if (sVibrator != null) {
                return sVibrator;
            }
            try {
                Context appContext = context.getApplicationContext();
                if (appContext == null) {
                    appContext = context;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        VibratorManager vm = (VibratorManager) appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                        if (vm != null) {
                            sVibrator = vm.getDefaultVibrator();
                        }
                    } catch (Throwable ignored) {}
                }
                if (sVibrator == null) {
                    sVibrator = (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);
                }
                if (sVibrator != null) {
                    sHasVibrator = sVibrator.hasVibrator();
                } else {
                    sHasVibrator = false;
                }
            } catch (Throwable ignored) {
                sHasVibrator = false;
            }
            return sVibrator;
        }
    }

    private static boolean hasVibrator(Context context) {
        if (sHasVibrator != null) {
            return sHasVibrator;
        }
        Vibrator v = getVibrator(context);
        return v != null && Boolean.TRUE.equals(sHasVibrator);
    }

    // =========================================================================
    // 1. POWER BUTTON HAPTIC
    // =========================================================================

    public static void performPowerButton(View view) {
        performPowerButton(view != null ? view.getContext() : null, view);
    }

    public static void performPowerButton(Context context) {
        performPowerButton(context, null);
    }

    /**
     * Deep, satisfying, tactile mechanical thud for the main power switch.
     * Debounced at 400ms to guarantee zero double vibrations between ACTION_DOWN and onClick.
     */
    public static void performPowerButton(Context context, View view) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastPowerHapticMs < POWER_HAPTIC_DEBOUNCE_MS) {
            return;
        }
        sLastPowerHapticMs = now;

        if (context == null && view != null) {
            context = view.getContext();
        }

        boolean vibrated = false;

        if (hasVibrator(context)) {
            Vibrator vibrator = getVibrator(context);
            if (vibrator != null) {
                // Tier 1: API 31+ Rich primitive mechanical thud (or fallback click)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)) {
                            VibrationEffect effect = VibrationEffect.startComposition()
                                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f)
                                    .compose();
                            vibrator.vibrate(effect);
                            vibrated = true;
                        } else if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                            VibrationEffect effect = VibrationEffect.startComposition()
                                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                                    .compose();
                            vibrator.vibrate(effect);
                            vibrated = true;
                        }
                    } catch (Throwable ignored) {}
                }

                // Tier 2: API 29+ Predefined Heavy Click
                if (!vibrated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK);
                        vibrator.vibrate(effect);
                        vibrated = true;
                    } catch (Throwable ignored) {
                        try {
                            VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
                            vibrator.vibrate(effect);
                            vibrated = true;
                        } catch (Throwable ignored2) {}
                    }
                }

                // Tier 3: API 26+ Amplitude One-Shot (Solid 24ms punch at 230 amplitude)
                if (!vibrated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        VibrationEffect effect = VibrationEffect.createOneShot(24, 230);
                        vibrator.vibrate(effect);
                        vibrated = true;
                    } catch (Throwable ignored) {}
                }

                // Tier 4: API 24-25 Legacy Vibrator (25ms pulse)
                if (!vibrated && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    try {
                        vibrator.vibrate(25);
                        vibrated = true;
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Tier 5: View-level fallback
        if (!vibrated && view != null) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Throwable ignored) {}
        }
    }

    // =========================================================================
    // 2. BUTTON CLICK (Tabs, Server Card, Settings Rows)
    // =========================================================================

    public static void performButtonClick(View view) {
        performButtonClick(view != null ? view.getContext() : null, view);
    }

    public static void performButtonClick(Context context) {
        performButtonClick(context, null);
    }

    /**
     * Sharp, crisp, subtle modern tactile tick for navigation tabs, server card,
     * refresh button, and settings rows.
     */
    public static void performButtonClick(Context context, View view) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastButtonClickMs < BUTTON_CLICK_DEBOUNCE_MS) {
            return;
        }
        sLastButtonClickMs = now;

        if (context == null && view != null) {
            context = view.getContext();
        }

        boolean vibrated = false;

        if (hasVibrator(context)) {
            Vibrator vibrator = getVibrator(context);
            if (vibrator != null) {
                // Tier 1: API 31+ Primitive Click calibrated to crisp subtle 0.65f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                            VibrationEffect effect = VibrationEffect.startComposition()
                                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.65f)
                                    .compose();
                            vibrator.vibrate(effect);
                            vibrated = true;
                        } else if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                            VibrationEffect effect = VibrationEffect.startComposition()
                                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.8f)
                                    .compose();
                            vibrator.vibrate(effect);
                            vibrated = true;
                        }
                    } catch (Throwable ignored) {}
                }

                // Tier 2: API 29+ Predefined Click
                if (!vibrated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK);
                        vibrator.vibrate(effect);
                        vibrated = true;
                    } catch (Throwable ignored) {}
                }

                // Tier 3: API 26+ Amplitude One-Shot: 10ms at 130 amplitude (sharp, snappy cutoff)
                if (!vibrated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        VibrationEffect effect = VibrationEffect.createOneShot(10, 130);
                        vibrator.vibrate(effect);
                        vibrated = true;
                    } catch (Throwable ignored) {}
                }

                // Tier 4: API 24-25 Legacy Vibrator (10ms)
                if (!vibrated && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    try {
                        vibrator.vibrate(10);
                        vibrated = true;
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Tier 5: View-level fallback
        if (!vibrated && view != null) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Throwable ignored) {}
        }
    }

    // =========================================================================
    // 3. TICK (Chips, Search Clear, Subtle Micro-interactions)
    // =========================================================================

    public static void performTick(View view) {
        performTick(view != null ? view.getContext() : null, view);
    }

    public static void performTick(Context context) {
        performTick(context, null);
    }

    /**
     * Ultra-light, crisp micro-tick for chips, search clear, list scrolling.
     */
    public static void performTick(Context context, View view) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastTickMs < TICK_DEBOUNCE_MS) {
            return;
        }
        sLastTickMs = now;

        if (context == null && view != null) {
            context = view.getContext();
        }

        boolean vibrated = false;

        if (hasVibrator(context)) {
            Vibrator vibrator = getVibrator(context);
            if (vibrator != null) {
                // Tier 1: API 31+ Primitive Tick
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                            VibrationEffect effect = VibrationEffect.startComposition()
                                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f)
                                    .compose();
                            vibrator.vibrate(effect);
                            vibrated = true;
                        }
                    } catch (Throwable ignored) {}
                }

                // Tier 2: API 29+ Predefined Tick
                if (!vibrated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
                        vibrator.vibrate(effect);
                        vibrated = true;
                    } catch (Throwable ignored) {}
                }

                // Tier 3: API 26+ Amplitude One-Shot: 6ms at 80 amplitude
                if (!vibrated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        VibrationEffect effect = VibrationEffect.createOneShot(6, 80);
                        vibrator.vibrate(effect);
                        vibrated = true;
                    } catch (Throwable ignored) {}
                }

                // Tier 4: API 24-25 Legacy Vibrator (6ms)
                if (!vibrated && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    try {
                        vibrator.vibrate(6);
                        vibrated = true;
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Tier 5: View-level fallback
        if (!vibrated && view != null) {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Throwable ignored) {}
        }
    }

    // =========================================================================
    // 4. SUCCESS CONFIRMATION (VPN Connected, Settings Applied)
    // =========================================================================

    public static void performSuccess(View view) {
        performSuccess(view != null ? view.getContext() : null, view);
    }

    public static void performSuccess(Context context) {
        performSuccess(context, null);
    }

    /**
     * Pleasant double-impulse confirmation when VPN connects or settings are applied.
     * Debounced at 500ms to eliminate duplicate firing between listeners and broadcasts.
     */
    public static void performSuccess(Context context, View view) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastSuccessMs < SUCCESS_DEBOUNCE_MS) {
            return;
        }
        sLastSuccessMs = now;

        if (context == null && view != null) {
            context = view.getContext();
        }

        boolean vibrated = false;

        if (hasVibrator(context)) {
            Vibrator vibrator = getVibrator(context);
            if (vibrator != null) {
                // Tier 1: API 31+ Primitive Double Impulse Composition (Click + Tick after 60ms)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        if (vibrator.areAllPrimitivesSupported(
                                VibrationEffect.Composition.PRIMITIVE_CLICK,
                                VibrationEffect.Composition.PRIMITIVE_TICK)) {
                            VibrationEffect effect = VibrationEffect.startComposition()
                                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f)
                                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.8f, 60)
                                    .compose();
                            vibrator.vibrate(effect);
                            vibrated = true;
                        }
                    } catch (Throwable ignored) {}
                }

                // Tier 2: API 29+ Predefined Double Click
                if (!vibrated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        VibrationEffect effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK);
                        vibrator.vibrate(effect);
                        vibrated = true;
                    } catch (Throwable ignored) {}
                }

                // Tier 3: API 26+ Waveform double burst
                if (!vibrated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        VibrationEffect effect = VibrationEffect.createWaveform(
                                new long[]{0, 10, 45, 16},
                                new int[]{0, 120, 0, 180},
                                -1
                        );
                        vibrator.vibrate(effect);
                        vibrated = true;
                    } catch (Throwable ignored) {}
                }

                // Tier 4: API 24-25 Legacy Waveform
                if (!vibrated && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    try {
                        vibrator.vibrate(new long[]{0, 10, 45, 16}, -1);
                        vibrated = true;
                    } catch (Throwable ignored) {}
                }
            }
        }

        // Tier 5: View-level fallback
        if (!vibrated && view != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } else {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                }
            } catch (Throwable ignored) {}
        }
    }

    public static void performErrorFeedback(View view) {
        performButtonClick(view);
    }

    // =========================================================================
    // 5. SWITCH TOGGLE
    // =========================================================================

    public static void performToggle(View view, boolean isChecked) {
        performToggle(view != null ? view.getContext() : null, view, isChecked);
    }

    public static void performToggle(Context context, boolean isChecked) {
        performToggle(context, null, isChecked);
    }

    /**
     * Crisp haptic feedback for switches (distinct ON vs OFF tactile response).
     */
    public static void performToggle(Context context, View view, boolean isChecked) {
        long now = SystemClock.uptimeMillis();
        if (now - sLastToggleMs < TOGGLE_DEBOUNCE_MS) {
            return;
        }
        sLastToggleMs = now;

        if (context == null && view != null) {
            context = view.getContext();
        }

        boolean vibrated = false;

        if (hasVibrator(context)) {
            Vibrator vibrator = getVibrator(context);
            if (vibrator != null) {
                if (isChecked) {
                    // Turn ON: affirmative crisp impulse
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                                VibrationEffect effect = VibrationEffect.startComposition()
                                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f)
                                        .compose();
                                vibrator.vibrate(effect);
                                vibrated = true;
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (!vibrated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
                            vibrated = true;
                        } catch (Throwable ignored) {}
                    }
                    if (!vibrated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            vibrator.vibrate(VibrationEffect.createOneShot(14, 160));
                            vibrated = true;
                        } catch (Throwable ignored) {}
                    }
                    if (!vibrated && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        try {
                            vibrator.vibrate(14);
                            vibrated = true;
                        } catch (Throwable ignored) {}
                    }
                } else {
                    // Turn OFF: lighter soft tick
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            if (vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK)) {
                                VibrationEffect effect = VibrationEffect.startComposition()
                                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.55f)
                                        .compose();
                                vibrator.vibrate(effect);
                                vibrated = true;
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (!vibrated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
                            vibrated = true;
                        } catch (Throwable ignored) {}
                    }
                    if (!vibrated && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            vibrator.vibrate(VibrationEffect.createOneShot(8, 95));
                            vibrated = true;
                        } catch (Throwable ignored) {}
                    }
                    if (!vibrated && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        try {
                            vibrator.vibrate(8);
                            vibrated = true;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }

        // Tier 5: View-level fallback
        if (!vibrated && view != null) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    view.performHapticFeedback(isChecked ? HapticFeedbackConstants.TOGGLE_ON : HapticFeedbackConstants.TOGGLE_OFF,
                            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } else {
                    view.performHapticFeedback(isChecked ? HapticFeedbackConstants.KEYBOARD_TAP : HapticFeedbackConstants.CLOCK_TICK,
                            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                }
            } catch (Throwable ignored) {}
        }
    }
}
