package com.orangepi.wabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Adjusts the recording sample rate without rebuilding.
 *
 *   adb shell am broadcast -a com.orangepi.wabridge.SET_SAMPLE_RATE --ei sample_rate 16000
 *
 * The external server re-encodes with ffmpeg at 16000 Hz; the app records at 48000 Hz.
 * The default stays 48000 (DEFAULT_SAMPLE_RATE); this receiver only exists
 * to A/B test the format.
 */
class AudioConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val rate = intent.getIntExtra("sample_rate", Prefs.DEFAULT_SAMPLE_RATE)
        if (rate != 16000 && rate != 48000) {
            Log.w(TAG, "sample_rate ignorado ($rate) — só 16000 ou 48000")
            return
        }
        Prefs.setAudioSampleRate(context, rate)
        Log.i(TAG, "sample_rate=${Prefs.audioSampleRate(context)}")
    }

    companion object {
        private const val TAG = "AudioConfig"
    }
}
