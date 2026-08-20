package com.klk.hams.ui.count

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.VibratorManager
import com.klk.hams.R

/**
 * Audible + haptic confirmation for a +/- press.
 *
 * Both channels are driven from [PressFeedback], never from the touch itself,
 * so a cue can only mean what it says: a confirming tone/buzz is emitted after
 * the row is stored, and a refusal gets its own unmistakable cue.
 *
 * Sound and haptics are deliberately independent. A worker in a noisy block may
 * not hear the tone; a worker with the handset on an armband over thick sleeves
 * may not feel the buzz. Either one alone carries the outcome.
 */
class FieldFeedback(context: Context) {

    // Sonification usage rides the media stream, so the estate's own volume
    // control applies. Not USAGE_ALARM: this must not override a silenced phone.
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // SoundPool.load is asynchronous. Presses in the first moments after the
    // screen opens are silent rather than crashing; play() no-ops on id 0.
    private val loaded = mutableSetOf<Int>()
    private val plusId = soundPool.load(context, R.raw.press_plus, 1)
    private val minusId = soundPool.load(context, R.raw.press_minus, 1)
    private val refusedId = soundPool.load(context, R.raw.press_refused, 1)

    // Vibrator directly rather than performHapticFeedback, so the cue still
    // fires when the device's touch-vibration system setting is off.
    private val vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    // Full amplitude where the device allows it. The previous 35 ms pulse at
    // DEFAULT_AMPLITUDE sat under the perceptible threshold on low-cost ERM
    // motors, which is what the "no vibration" reports were really describing.
    private val amplitude =
        if (vibrator.hasAmplitudeControl()) 255 else VibrationEffect.DEFAULT_AMPLITUDE

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded += sampleId
        }
    }

    fun play(feedback: PressFeedback) {
        when (feedback) {
            PressFeedback.PlusRecorded -> {
                playSound(plusId)
                vibrate(VibrationEffect.createOneShot(RECORDED_MS, amplitude))
            }
            PressFeedback.MinusRecorded -> {
                playSound(minusId)
                vibrate(VibrationEffect.createOneShot(RECORDED_MS, amplitude))
            }
            PressFeedback.Refused -> {
                playSound(refusedId)
                // Two pulses: a rhythm no recorded press ever produces, so it
                // stays distinguishable without looking at the screen.
                vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, REFUSED_PULSE_MS, REFUSED_GAP_MS, REFUSED_PULSE_MS),
                        -1
                    )
                )
            }
        }
    }

    fun release() {
        soundPool.release()
    }

    private fun playSound(id: Int) {
        if (id in loaded) soundPool.play(id, 1f, 1f, 1, 0, 1f)
    }

    private fun vibrate(effect: VibrationEffect) {
        if (vibrator.hasVibrator()) vibrator.vibrate(effect)
    }

    private companion object {
        const val MAX_STREAMS = 4
        const val RECORDED_MS = 60L
        const val REFUSED_PULSE_MS = 70L
        const val REFUSED_GAP_MS = 60L
    }
}
