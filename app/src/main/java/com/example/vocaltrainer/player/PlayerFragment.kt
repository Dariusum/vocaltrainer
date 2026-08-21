package com.example.vocaltrainer.player

import android.Manifest
import android.content.Intent
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
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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

    // Activity-weit statt Fragment-weit gescoped: MainActivity erzeugt bei jedem
    // Tab-Wechsel (auch erneutem Tippen auf "Wiedergabe" selbst) eine komplett neue
    // PlayerFragment-Instanz über replace(). Mit einem Fragment-gescopeten ViewModel
    // würde das den laufenden Ladevorgang/die KI-Trennung (die inzwischen 40-60s dauert)
    // beim Tab-Wechsel abbrechen — beobachtet als JobCancellationException mitten in der
    // Trennung. Activity-Scope lässt das ViewModel (und die Wiedergabe) Tab-Wechsel
    // überleben, passend zur ohnehin geplanten Hintergrund-Wiedergabe.
    private val viewModel: PlayerViewModel by activityViewModels {
        PlayerViewModel.Factory(
            requireActivity().application,
            vocaltrainerApp.recordingRepository,
            vocaltrainerApp.recentTracksStore
        )
    }

    private lateinit var recentTrackAdapter: RecentTrackAdapter

    // OpenDocument statt GetContent: nur OpenDocument-URIs erlauben eine dauerhafte
    // Berechtigung via takePersistableUriPermission — nötig, damit Playlists und die
    // Schnellauswahl einen Titel auch nach einem App-Neustart noch öffnen können.
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            requireContext().contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.pickFile(it)
        }
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

        binding.btnPickFile.setOnClickListener { filePicker.launch(arrayOf("audio/*")) }
        binding.btnPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        binding.btnRestart.setOnClickListener { viewModel.restart() }
        binding.btnPrevious.setOnClickListener { viewModel.playPrevious() }
        binding.btnNext.setOnClickListener { viewModel.playNext() }

        recentTrackAdapter = RecentTrackAdapter { position ->
            viewModel.playFromQueue(viewModel.recentTracks.value, position)
        }
        binding.recyclerRecentTracks.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerRecentTracks.adapter = recentTrackAdapter

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
                binding.btnPrevious.isEnabled = !recording && viewModel.hasPrevious.value
                binding.btnNext.isEnabled = !recording && viewModel.hasNext.value
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.elapsedRecordingMs.collect { ms ->
                binding.tvRecordingStatus.text =
                    "${getString(R.string.recording_in_progress)} ${formatDuration(ms)}"
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recentTracks.collect { tracks ->
                recentTrackAdapter.submitList(tracks)
                val visibility = if (tracks.isEmpty()) View.GONE else View.VISIBLE
                binding.tvRecentTracksLabel.visibility = visibility
                binding.recyclerRecentTracks.visibility = visibility
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.hasPrevious.collect { has -> binding.btnPrevious.isEnabled = has && !viewModel.isRecording.value }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.hasNext.collect { has -> binding.btnNext.isEnabled = has && !viewModel.isRecording.value }
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
                binding.btnPickFile.isEnabled = true
                binding.btnPlayPause.isEnabled = false
                binding.btnRestart.isEnabled = false
                binding.btnPrevious.isEnabled = false
                binding.btnNext.isEnabled = false
                binding.sliderVocalReduction.isEnabled = false
                binding.btnRecord.isEnabled = false
                binding.tvMonoWarning.visibility = View.GONE
                binding.waveformView.setPeaks(FloatArray(0))
            }
            is TrackUiState.Loading -> {
                binding.progressLoading.visibility = View.VISIBLE
                binding.tvTrackName.text = getString(R.string.decode_in_progress)
                binding.btnPickFile.isEnabled = false
                binding.btnPlayPause.isEnabled = false
                binding.btnRestart.isEnabled = false
                binding.btnPrevious.isEnabled = false
                binding.btnNext.isEnabled = false
                binding.sliderVocalReduction.isEnabled = false
                binding.btnRecord.isEnabled = false
            }
            is TrackUiState.Loaded -> {
                binding.progressLoading.visibility = View.GONE
                binding.tvTrackName.text = state.fileName
                binding.waveformView.setPeaks(state.peaks)
                binding.btnPickFile.isEnabled = true
                binding.btnPlayPause.isEnabled = true
                binding.btnRestart.isEnabled = true
                binding.btnPrevious.isEnabled = viewModel.hasPrevious.value
                binding.btnNext.isEnabled = viewModel.hasNext.value
                binding.btnRecord.isEnabled = true
                val isStereo = state.pcm.channelCount == 2
                binding.sliderVocalReduction.isEnabled = isStereo
                binding.tvMonoWarning.visibility = if (isStereo) View.GONE else View.VISIBLE
            }
            is TrackUiState.Error -> {
                binding.progressLoading.visibility = View.GONE
                binding.tvTrackName.text = getString(R.string.decode_error)
                binding.btnPickFile.isEnabled = true
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderPlaybackState(state: PlaybackState) {
        val playing = state == PlaybackState.PLAYING
        binding.btnPlayPause.text = getString(if (playing) R.string.action_pause else R.string.action_play)
        binding.btnPlayPause.setIconResource(
            if (playing) R.drawable.ic_action_pause else R.drawable.ic_action_play
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
