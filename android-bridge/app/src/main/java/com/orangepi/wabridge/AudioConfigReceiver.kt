package com.orangepi.wabridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Ajusta a taxa de amostragem da gravação sem rebuildar.
 *
 *   adb shell am broadcast -a com.orangepi.wabridge.SET_SAMPLE_RATE --ei sample_rate 16000
 *
 * O servidor externo re-encoda com ffmpeg a 16000 Hz; o app grava 48000 Hz.
 * O padrão continua 48000 (DEFAULT_SAMPLE_RATE) — este receiver só existe
 * para A/B testar o formato.
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
