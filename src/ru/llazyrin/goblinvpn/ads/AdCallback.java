package ru.llazyrin.goblinvpn.ads;

/**
 * Clean lifecycle callback for rewarded ad events.
 * Compatible with Yandex Mobile Ads SDK RewardedAdEventListener.
 */
public interface AdCallback {
    /**
     * Called when the rewarded video has finished playing and the user earned the reward
     * (e.g. VIP Turbo Boost activated or extended).
     */
    void onAdCompleted();

    /**
     * Called when the user dismissed or skipped the ad before completion.
     */
    void onAdSkipped();

    /**
     * Called when the ad failed to load, failed to render, or an error occurred.
     *
     * @param reason Human-readable explanation or error message
     */
    void onAdFailed(String reason);
}
