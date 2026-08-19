package com.example.vocaltrainer.recordings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.vocaltrainer.R
import com.example.vocaltrainer.VocaltrainerApp
import com.example.vocaltrainer.audio.MixGains
import com.example.vocaltrainer.audio.PlaybackState
import com.example.vocaltrainer.databinding.FragmentRemixBinding
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch

class RemixFragment : Fragment() {

    private var _binding: FragmentRemixBinding? = null
    private val binding get() = _binding!!

    private val projectId: String by lazy { requireArguments().getString(ARG_PROJECT_ID, "") }

    private val vocaltrainerApp by lazy { requireActivity().application as VocaltrainerApp }

    private val viewModel: RemixViewModel by viewModels {
        RemixViewModel.Factory(requireActivity().application, projectId, vocaltrainerApp.recordingRepository)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri ->
        uri?.let {
            val out = requireContext().contentResolver.openOutputStream(it)
            if (out != null) {
                viewModel.export(out)
            } else {
                Toast.makeText(requireContext(), R.string.export_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRemixBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnPlayPause.setOnClickListener { viewModel.togglePlayPause() }
        binding.btnExport.setOnClickListener { exportLauncher.launch("${sanitizedTitle()}-mix.wav") }

        val listener = Slider.OnChangeListener { _, _, fromUser -> if (fromUser) emitGains() }
        binding.sliderMaster.addOnChangeListener(listener)
        binding.sliderUserVocal.addOnChangeListener(listener)
        binding.sliderOriginalVocal.addOnChangeListener(listener)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state -> render(state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.gains.collect { gains -> applyGainsToSliders(gains) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playbackState.collect { state ->
                binding.btnPlayPause.text = getString(
                    if (state == PlaybackState.PLAYING) R.string.action_pause else R.string.action_play
                )
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isExporting.collect { exporting ->
                binding.btnExport.isEnabled = !exporting
                binding.btnExport.text = getString(
                    if (exporting) R.string.export_in_progress else R.string.action_export
                )
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event -> handleEvent(event) }
        }
    }

    private fun render(state: RemixUiState) {
        when (state) {
            is RemixUiState.Loading -> {
                binding.progressLoading.visibility = View.VISIBLE
                binding.btnPlayPause.isEnabled = false
                binding.btnExport.isEnabled = false
            }
            is RemixUiState.Ready -> {
                binding.progressLoading.visibility = View.GONE
                binding.tvTitle.text = state.title
                binding.btnPlayPause.isEnabled = true
                binding.btnExport.isEnabled = true
            }
            is RemixUiState.Error -> {
                binding.progressLoading.visibility = View.GONE
                Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyGainsToSliders(gains: MixGains) {
        binding.sliderMaster.value = gains.master * 100f
        binding.sliderUserVocal.value = gains.userVocal * 100f
        binding.sliderOriginalVocal.value = gains.originalVocal * 100f
    }

    private fun emitGains() {
        viewModel.setGains(
            MixGains(
                master = binding.sliderMaster.value / 100f,
                userVocal = binding.sliderUserVocal.value / 100f,
                originalVocal = binding.sliderOriginalVocal.value / 100f
            )
        )
    }

    private fun handleEvent(event: RemixEvent) {
        when (event) {
            is RemixEvent.ExportSuccess ->
                Toast.makeText(requireContext(), R.string.export_success, Toast.LENGTH_SHORT).show()
            is RemixEvent.ExportError ->
                Toast.makeText(requireContext(), event.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun sanitizedTitle(): String {
        val state = viewModel.uiState.value
        val title = if (state is RemixUiState.Ready) state.title else "vocaltrainer"
        return title.replace(Regex("[^A-Za-z0-9_\\- ]"), "_").ifBlank { "vocaltrainer" }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_PROJECT_ID = "project_id"

        fun newInstance(projectId: String): RemixFragment = RemixFragment().apply {
            arguments = Bundle().apply { putString(ARG_PROJECT_ID, projectId) }
        }
    }
}
