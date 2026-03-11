package com.walkietalkie.audio

import android.media.*
import com.walkietalkie.network.UdpMulticastManager

/**
 * Handles microphone capture (transmit) and speaker playback (receive).
 *
 * Audio format: PCM 16-bit, 8 kHz, mono — suitable for voice and low bandwidth.
 */
class AudioStreamer(private val udpManager: UdpMulticastManager) {

    companion object {
        private const val SAMPLE_RATE = 8000
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        /** Must match UdpMulticastManager.MAX_AUDIO_BYTES — reject oversized payloads. */
        private const val MAX_PLAY_BYTES = 2560
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordThread: Thread? = null

    @Volatile private var recording = false

    // ── Playback ─────────────────────────────────────────────────────────────

    fun initPlayback() {
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_OUT)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    /** Write received PCM bytes to the speaker. Thread-safe. */
    fun playAudio(data: ByteArray) {
        if (data.isEmpty() || data.size > MAX_PLAY_BYTES) return
        audioTrack?.write(data, 0, data.size)
    }

    // ── Transmit ─────────────────────────────────────────────────────────────

    fun startTransmitting() {
        if (recording) return

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        val bufSize = maxOf(minBuf * 2, 1280) // at least ~80 ms worth

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE, CHANNEL_IN, ENCODING, bufSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord?.release()
            audioRecord = null
            return
        }

        recording = true
        audioRecord?.startRecording()

        recordThread = Thread {
            // Use 160-sample (20 ms) chunks for low latency
            val chunkSize = 320 // 160 samples * 2 bytes
            val buffer = ByteArray(chunkSize)
            while (recording) {
                val read = audioRecord?.read(buffer, 0, chunkSize) ?: 0
                if (read > 0) {
                    udpManager.sendAudio(if (read == chunkSize) buffer else buffer.copyOf(read))
                }
            }
        }.also { it.isDaemon = true; it.name = "wt-record"; it.start() }
    }

    fun stopTransmitting() {
        recording = false
        recordThread?.join(300)
        recordThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun release() {
        stopTransmitting()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}
