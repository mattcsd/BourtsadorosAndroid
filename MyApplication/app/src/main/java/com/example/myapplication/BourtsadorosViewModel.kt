package com.example.bourtsadoros

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bourtsadoros.model.Chord
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BourtsadorosViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    val chords = listOf(
        Chord("Am", Color(0xFFE91E63), R.raw.chord_am),
        Chord("Dm", Color(0xFF2196F3), R.raw.chord_dm),
        Chord("G", Color(0xFF4CAF50), R.raw.chord_g)
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

    private val _fadeEnabled = MutableStateFlow(true)
    val fadeEnabled: StateFlow<Boolean> = _fadeEnabled.asStateFlow()

    private val _fadeDurationMs = MutableStateFlow(30L)
    val fadeDurationMs: StateFlow<Long> = _fadeDurationMs.asStateFlow()

    private var soundPool: SoundPool? = null
    private val soundIds = mutableMapOf<Int, Int>()
    private val sampleDurationsMs = mutableMapOf<Int, Long>()
    private var playbackJob: Job? = null
    private var progressJob: Job? = null
    private var fadeJobs = mutableListOf<Job>()

    init {
        try {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            soundPool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(audioAttributes)
                .build()
            soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
                if (status != 0) Log.e("Bourtsadoros", "Failed to load sample $sampleId")
            }
            for (chord in chords) {
                try {
                    val soundId = soundPool?.load(app, chord.rawResId, 1) ?: 0
                    soundIds[chord.rawResId] = soundId
                    sampleDurationsMs[chord.rawResId] = getRawResourceDuration(chord.rawResId)
                } catch (e: Exception) {
                    Log.e("Bourtsadoros", "Error loading ${chord.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.e("Bourtsadoros", "SoundPool init failed", e)
            soundPool = null
        }
    }

    private fun getRawResourceDuration(rawResId: Int): Long {
        return try {
            val uri = Uri.parse("android.resource://${app.packageName}/$rawResId")
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(app, uri)
            val durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            mmr.release()
            durationStr?.toLongOrNull() ?: 500L
        } catch (e: Exception) {
            500L
        }
    }

    fun addChord(chordIndex: Int) {
        _sequence.value = _sequence.value + chordIndex
    }

    fun removeChordAt(position: Int) {
        _sequence.value = _sequence.value.toMutableList().apply {
            if (position in indices) removeAt(position)
        }
    }

    fun clearSequence() {
        _sequence.value = emptyList()
    }

    fun setBpm(newBpm: Int) {
        _bpm.value = newBpm.coerceIn(50, 200)
    }

    fun setLoopCount(count: Int) {
        _loopCount.value = count.coerceIn(1, 99)
    }

    fun toggleInfiniteLoop() {
        _infiniteLoop.value = !_infiniteLoop.value
        if (_infiniteLoop.value) {
            // if playing, it will just continue looping indefinitely; no action needed
        }
    }

    fun toggleFadeEnabled() {
        _fadeEnabled.value = !_fadeEnabled.value
    }

    fun setFadeDurationMs(duration: Long) {
        _fadeDurationMs.value = duration.coerceIn(20, 200)
    }

    fun togglePlay() {
        if (_isPlaying.value) stopPlayback() else startPlayback()
    }

    private fun startPlayback() {
        val seq = _sequence.value
        if (seq.isEmpty()) return
        _isPlaying.value = true
        playbackJob = viewModelScope.launch {
            val noteDurationMs = 60_000L / _bpm.value
            val fadeDur = if (_fadeEnabled.value) _fadeDurationMs.value else 0L
            val infinite = _infiniteLoop.value
            val loops = if (infinite) Int.MAX_VALUE else _loopCount.value

            for (loop in 0 until loops) {
                if (!_isPlaying.value) break
                val currentSequence = _sequence.value
                for ((stepIndex, chordIndex) in currentSequence.withIndex()) {
                    if (!_isPlaying.value) break

                    _currentPlayingIndex.value = stepIndex
                    _progress.value = 0f

                    val chord = chords.getOrNull(chordIndex) ?: continue
                    val soundId = soundIds[chord.rawResId] ?: continue
                    if (soundId == 0) continue

                    val sampleDur = sampleDurationsMs[chord.rawResId] ?: 500L
                    val isFirstNote = (loop == 0 && stepIndex == 0)
                    val isLastNote = if (infinite) false else (loop == loops - 1 && stepIndex == currentSequence.size - 1)

                    val streamId = soundPool?.play(soundId, 1f, 1f, 0, 0, 1f) ?: 0

                    fadeJobs.clear()
                    if (fadeDur > 0 && streamId != 0) {
                        if (!isFirstNote) fadeJobs.add(fadeIn(streamId, fadeDur))
                        if (!isLastNote) fadeJobs.add(fadeOut(streamId, noteDurationMs - fadeDur, fadeDur))
                    }

                    progressJob = launchProgressAnimation(noteDurationMs)

                    delay(noteDurationMs)

                    progressJob?.cancel()
                    _progress.value = 0f
                }
            }
            _currentPlayingIndex.value = null
            _progress.value = 0f
            _isPlaying.value = false
        }
    }

    private fun launchProgressAnimation(durationMs: Long): Job {
        return viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val p = (elapsed.toFloat() / durationMs).coerceIn(0f, 1f)
                _progress.value = p
                if (p >= 1f) break
                delay(16)
            }
            _progress.value = 1f
        }
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        progressJob?.cancel()
        fadeJobs.forEach { it.cancel() }
        fadeJobs.clear()
        playbackJob = null
        progressJob = null
        _isPlaying.value = false
        _currentPlayingIndex.value = null
        _progress.value = 0f
    }

    private fun fadeIn(streamId: Int, duration: Long): Job = viewModelScope.launch {
        val steps = 20
        val stepTime = duration / steps
        for (i in 0..steps) {
            val vol = i.toFloat() / steps
            soundPool?.setVolume(streamId, vol, vol)
            delay(stepTime)
        }
    }

    private fun fadeOut(streamId: Int, startDelayMs: Long, duration: Long): Job = viewModelScope.launch {
        delay(startDelayMs)
        val steps = 20
        val stepTime = duration / steps
        for (i in steps downTo 0) {
            val vol = i.toFloat() / steps
            soundPool?.setVolume(streamId, vol, vol)
            delay(stepTime)
        }
        soundPool?.setVolume(streamId, 0f, 0f)
    }

    override fun onCleared() {
        super.onCleared()
        fadeJobs.forEach { it.cancel() }
        soundPool?.release()
        playbackJob?.cancel()
        progressJob?.cancel()
    }
}