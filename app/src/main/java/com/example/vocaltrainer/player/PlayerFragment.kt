package com.example.vocaltrainer.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.vocaltrainer.R
import com.example.vocaltrainer.VocaltrainerApp
import com.example.vocaltrainer.audio.PlaybackState
import com.example.vocaltrainer.databinding.FragmentPlayerBinding
import com.example.vocaltrainer.recordings.RemixFragment
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!

    private val vocaltrainerApp by lazy { requireActivity().application as VocaltrainerApp }

    private val viewModel: PlayerViewModel by viewModels {
        PlayerViewModel.Factory(requireActivity().application, vocaltrainerApp.recordingRepository)
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.pickFile(it) }
    }

    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            Toast.makeText(requireContext(), R.string.record_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPickFile.setOnClickListener { filePicker.launch("audio/*") }
        binding.btnPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        binding.btnRestart.setOnClickListener { viewModel.restart() }

        binding.sliderVocalReduction.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setVocalReduction(value / 100f)
        }

        binding.switchLoop.setOnCheckedChangeListener { _, checked -> viewModel.setLoopEnabled(checked) }

        binding.btnRecord.setOnClickListener {
            if (viewModel.isRecording.value) {
                viewModel.stopRecording()
            } else {
                requestRecordPermissionAndStart()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.trackState.collect { state -> render(state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playbackState.collect { state -> renderPlaybackState(state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.positionFrames.collect { frame ->
                val state = viewModel.trackState.value
                if (state is TrackUiState.Loaded && state.pcm.frameCount > 0) {
                    binding.waveformView.setProgress(frame.toFloat() / state.pcm.frameCount)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isRecording.collect { recording ->
                binding.btnRecord.text = getString(
                    if (recording) R.string.action_stop_recording else R.string.action_record
                )
                binding.tvRecordingStatus.visibility = if (recording) View.VISIBLE else View.GONE
                binding.btnPickFile.isEnabled = !recording
                binding.btnRestart.isEnabled = !recording && viewModel.trackState.value is TrackUiState.Loaded
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.elapsedRecordingMs.collect { ms ->
                binding.tvRecordingStatus.text =
                    "${getString(R.string.recording_in_progress)} ${formatDuration(ms)}"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event -> handleEvent(event) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.vocalReduction.collect { value ->
                val target = value * 100f
                if (binding.sliderVocalReduction.value != target) {
                    binding.sliderVocalReduction.value = target
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isSeparatingVocals.collect { separating ->
                if (viewModel.trackState.value is TrackUiState.Loading) {
                    binding.tvTrackName.text = getString(
                        if (separating) R.string.vocal_separation_in_progress else R.string.decode_in_progress
                    )
                }
            }
        }
    }

    private fun render(state: TrackUiState) {
        when (state) {
            is TrackUiState.Idle -> {
                binding.progressLoading.visibility = View.GONE
                binding.tvTrackName.text = getString(R.string.player_no_track)
                binding.btnPlayPause.isEnabled = false
                binding.btnRestart.isEnabled = false
                binding.sliderVocalReduction.isEnabled = false
                binding.btnRecord.isEnabled = false
                binding.tvMonoWarning.visibility = View.GONE
                binding.waveformView.setPeaks(FloatArray(0))
            }
            is TrackUiState.Loading -> {
                binding.progressLoading.visibility = View.VISIBLE
                binding.tvTrackName.text = getString(R.string.decode_in_progress)
                binding.btnPlayPause.isEnabled = false
                binding.btnRestart.isEnabled = false
                binding.sliderVocalReduction.isEnabled = false
                binding.btnRecord.isEnabled = false
            }
            is TrackUiState.Loaded -> {
                binding.progressLoading.visibility = View.GONE
                binding.tvTrackName.text = state.fileName
                binding.waveformView.setPeaks(state.peaks)
                binding.btnPlayPause.isEnabled = true
                binding.btnRestart.isEnabled = true
                binding.btnRecord.isEnabled = true
                val isStereo = state.pcm.channelCount == 2
                binding.sliderVocalReduction.isEnabled = isStereo
                binding.tvMonoWarning.visibility = if (isStereo) View.GONE else View.VISIBLE
            }
            is TrackUiState.Error -> {
                binding.progressLoading.visibility = View.GONE
                binding.tvTrackName.text = getString(R.string.decode_error)
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderPlaybackState(state: PlaybackState) {
        binding.btnPlayPause.text = getString(
            if (state == PlaybackState.PLAYING) R.string.action_pause else R.string.action_play
        )
    }

    private fun requestRecordPermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startRecording()
        } else {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun handleEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.PendingSave -> showSaveDialog(event.tempFile, event.suggestedTitle)
            is PlayerEvent.NavigateToRemix -> navigateToRemix(event.projectId)
            is PlayerEvent.Error -> Toast.makeText(requireContext(), event.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun showSaveDialog(tempFile: File, suggestedTitle: String) {
        val input = EditText(requireContext())
        input.setText(suggestedTitle)
        input.hint = getString(R.string.save_recording_hint)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.save_recording_title)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val title = input.text.toString().ifBlank { suggestedTitle }
                viewModel.saveRecording(tempFile, title)
            }
            .setNegativeButton(R.string.action_discard) { _, _ -> viewModel.discardRecording(tempFile) }
            .setCancelable(false)
            .show()
    }

    private fun navigateToRemix(projectId: String) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, RemixFragment.newInstance(projectId))
            .addToBackStack(null)
            .commit()
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
