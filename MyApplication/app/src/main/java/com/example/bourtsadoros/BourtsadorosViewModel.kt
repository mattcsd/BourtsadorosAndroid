package com.example.bourtsadoros

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bourtsadoros.audio.SoundTouchProcessor
import com.example.bourtsadoros.audio.WavLoader
import com.example.bourtsadoros.model.Chord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collectLatest
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BourtsadorosViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    val chords = listOf(
        Chord("Do", Color(0xFFE91E63), R.raw.chord_do),
        Chord("La", Color(0xFF2196F3), R.raw.chord_la),
        Chord("Sol", Color(0xFF4CAF50), R.raw.chord_sol),
        Chord("Re", Color(0xFFCDDC39), R.raw.chord_re)
    )

    private val _sequence = MutableStateFlow<List<Int>>(emptyList())
    val sequence: StateFlow<List<Int>> = _sequence.asStateFlow()

    private val _bpm = MutableStateFlow(120)
    val bpm: StateFlow<Int> = _bpm.asStateFlow()

    private val _loopCount = MutableStateFlow(1)
    val loopCount: StateFlow<Int> = _loopCount.asStateFlow()

    private val _infiniteLoop = MutableStateFlow(false)
    val infiniteLoop: StateFlow<Boolean> = _infiniteLoop.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPlayingIndex = MutableStateFlow<Int?>(null)
    val currentPlayingIndex: StateFlow<Int?> = _currentPlayingIndex.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val wavCache = mutableMapOf<Int, FloatArray>()
    private var sampleRate = 44100
    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private var progressJob: Job? = null

    // Loop data for gap‑free playback
    private data class LoopData(
        val pcmBytes: ByteArray,
        val noteFrameOffsets: IntArray,
        val totalFrames: Int
    )

    private var currentLoopData: LoopData? = null
    private var nextLoopData: LoopData? = null
    private var loopStartFrame: Int = 0

    init {
        try {
            var firstRate = 44100
            for (chord in chords) {
                val wav = WavLoader.load(app.resources, chord.rawResId)
                wavCache[chord.rawResId] = wav.data
                if (chord == chords.first()) firstRate = wav.sampleRate
                Log.d("Bourtsadoros", "Loaded ${chord.name}, samples=${wav.data.size}, rate=${wav.sampleRate}")
            }
            sampleRate = firstRate
        } catch (e: Exception) {
            Log.e("Bourtsadoros", "WAV loading failed", e)
        }

        // Automatically restart playback when BPM changes while playing
        viewModelScope.launch {
            _bpm.drop(1).collectLatest {
                if (_isPlaying.value) {
                    restartPlayback()
                }
            }
        }
    }

    fun addChord(chordIndex: Int) { _sequence.value = _sequence.value + chordIndex }
    fun removeChordAt(position: Int) {
        _sequence.value = _sequence.value.toMutableList().apply {
            if (position in indices) removeAt(position)
        }
    }
    fun clearSequence() { _sequence.value = emptyList() }
    fun setBpm(newBpm: Int) { _bpm.value = newBpm.coerceIn(50, 200) }
    fun setLoopCount(count: Int) { _loopCount.value = count.coerceIn(1, 99) }
    fun toggleInfiniteLoop() {
        val wasInfinite = _infiniteLoop.value
        _infiniteLoop.value = !wasInfinite
        if (wasInfinite && _isPlaying.value) stopPlayback()
    }
    fun togglePlay() { if (_isPlaying.value) stopPlayback() else startPlayback() }

    private fun restartPlayback() {
        // Stop and restart after a tiny delay to avoid rapid changes
        if (!_isPlaying.value) return
        stopPlayback()
        viewModelScope.launch {
            delay(80)
            if (!_isPlaying.value) startPlayback() // only restart if still not playing (will start)
        }
    }

    private fun startPlayback() {
        val seq = _sequence.value
        if (seq.isEmpty()) {
            Log.d("Bourtsadoros", "Play pressed but sequence empty")
            return
        }
        Log.d("Bourtsadoros", "Start playback, sequence=${seq.size}, bpm=${_bpm.value}")

        _isPlaying.value = true

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
        audioTrack = track
        track.play()

        // Pre‑build first two loops
        currentLoopData = buildLoopData()
        nextLoopData = buildLoopData()
        loopStartFrame = 0

        // Launch playback loop on Default dispatcher (blocking writes)
        playbackJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val infinite = _infiniteLoop.value
                var loopsRemaining = if (infinite) Int.MAX_VALUE else _loopCount.value

                while (loopsRemaining > 0 && _isPlaying.value) {
                    val data = currentLoopData ?: break

                    // Pre‑build the loop after next asynchronously
                    val futureBuild = launch {
                        nextLoopData = buildLoopData()
                    }

                    // Write current loop to AudioTrack (blocking)
                    track.write(data.pcmBytes, 0, data.pcmBytes.size)
                    loopStartFrame += data.totalFrames

                    loopsRemaining--
                    if (loopsRemaining <= 0 || !_isPlaying.value) break

                    futureBuild.join()    // ensure next loop is ready
                    // Swap buffers
                    val temp = currentLoopData
                    currentLoopData = nextLoopData
                    nextLoopData = temp
                }
            } catch (e: Exception) {
                Log.e("Bourtsadoros", "Playback error", e)
            } finally {
                // Safely clean up AudioTrack
                try {
                    track.stop()
                } catch (_: Exception) {}
                try {
                    track.release()
                } catch (_: Exception) {}
                audioTrack = null
                _currentPlayingIndex.value = null
                _progress.value = 0f
                _isPlaying.value = false
            }
        }

        // Progress updater
        progressJob = viewModelScope.launch {
            while (isActive && _isPlaying.value) {
                updateProgress()
                delay(50)
            }
        }
    }

    private fun buildLoopData(): LoopData? {
        val seq = _sequence.value
        if (seq.isEmpty()) return null

        val tempoRatio = _bpm.value / 120f
        val processor = SoundTouchProcessor()
        processor.setSampleRate(sampleRate)
        processor.setChannels(1)
        processor.setTempo(tempoRatio)

        val allProcessed = ArrayList<Float>()
        val offsets = IntArray(seq.size)
        var frameCount = 0

        for ((i, chordIndex) in seq.withIndex()) {
            val chord = chords.getOrNull(chordIndex) ?: continue
            val rawPcm = wavCache[chord.rawResId] ?: continue
            offsets[i] = frameCount
            processor.putSamples(rawPcm)
        }

        processor.flush()   // <-- ADD THIS LINE

        while (true) {
            val chunk = processor.receiveSamples(4096)
            if (chunk.isEmpty()) break
            allProcessed.addAll(chunk.toList())
            frameCount += chunk.size
        }

        processor.destroy()

        if (allProcessed.isEmpty()) return null
        val pcmBytes = convertFloatTo16Bit(allProcessed.toFloatArray())
        val durationMs = (pcmBytes.size / 2).toLong() * 1000L / sampleRate
        Log.d("Bourtsadoros", "Loop duration: ${durationMs}ms, tempoRatio=$tempoRatio")
        return LoopData(pcmBytes, offsets, pcmBytes.size / 2)
    }

    private fun convertFloatTo16Bit(input: FloatArray): ByteArray {
        val buffer = ByteArray(input.size * 2)
        val view = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in input) {
            val intSample = (sample.coerceIn(-1f, 1f) * 32767).toInt()
            view.putShort(intSample.toShort())
        }
        return buffer
    }

    private fun updateProgress() {
        val track = audioTrack ?: return
        val data = currentLoopData ?: return
        val offsets = data.noteFrameOffsets
        if (offsets.isEmpty()) return

        val currentHead = track.playbackHeadPosition
        // Relative position inside the loop (handle wrap)
        val loopRelativeHead = (currentHead - loopStartFrame).let {
            if (it < 0) it + Int.MAX_VALUE else it
        }.toInt()

        var index = -1
        for (i in offsets.indices) {
            if (loopRelativeHead >= offsets[i]) {
                index = i
            } else break
        }
        if (index >= 0 && index < offsets.size) {
            _currentPlayingIndex.value = index
            val noteStart = offsets[index]
            val noteEnd = if (index + 1 < offsets.size) offsets[index + 1] else data.totalFrames
            val noteProgress = ((loopRelativeHead - noteStart).toFloat() / (noteEnd - noteStart)).coerceIn(0f, 1f)
            _progress.value = noteProgress
        }
    }

    private fun stopPlayback() {
        // Just cancel the coroutines; the finally block will clean up AudioTrack
        playbackJob?.cancel()
        progressJob?.cancel()
        playbackJob = null
        progressJob = null
        // Do not touch audioTrack here – let the coroutine finally handle it
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        progressJob?.cancel()
        audioTrack?.release()
        audioTrack = null
    }
}