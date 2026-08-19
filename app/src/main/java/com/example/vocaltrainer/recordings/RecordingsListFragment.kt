package com.example.vocaltrainer.recordings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vocaltrainer.R
import com.example.vocaltrainer.VocaltrainerApp
import com.example.vocaltrainer.data.RecordingProject
import com.example.vocaltrainer.databinding.FragmentRecordingsListBinding
import kotlinx.coroutines.launch

class RecordingsListFragment : Fragment() {

    private var _binding: FragmentRecordingsListBinding? = null
    private val binding get() = _binding!!

    private val vocaltrainerApp by lazy { requireActivity().application as VocaltrainerApp }

    private val viewModel: RecordingsListViewModel by viewModels {
        RecordingsListViewModel.Factory(requireActivity().application, vocaltrainerApp.recordingRepository)
    }

    private lateinit var adapter: RecordingAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecordingsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RecordingAdapter(
            onClick = { project -> openRemix(project) },
            onLongClick = { project -> confirmDelete(project) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.projects.collect { projects ->
                adapter.submitList(projects)
                binding.tvEmpty.visibility = if (projects.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun openRemix(project: RecordingProject) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, RemixFragment.newInstance(project.id))
            .addToBackStack(null)
            .commit()
    }

    private fun confirmDelete(project: RecordingProject) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_delete_recording_title)
            .setMessage(getString(R.string.confirm_delete_recording_message, project.title))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.deleteProject(project.id) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
