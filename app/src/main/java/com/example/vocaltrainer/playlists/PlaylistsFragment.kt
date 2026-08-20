package com.example.vocaltrainer.playlists

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vocaltrainer.R
import com.example.vocaltrainer.VocaltrainerApp
import com.example.vocaltrainer.data.Playlist
import com.example.vocaltrainer.databinding.FragmentPlaylistsBinding
import kotlinx.coroutines.launch

class PlaylistsFragment : Fragment() {

    private var _binding: FragmentPlaylistsBinding? = null
    private val binding get() = _binding!!

    private val vocaltrainerApp by lazy { requireActivity().application as VocaltrainerApp }

    private val viewModel: PlaylistsViewModel by viewModels {
        PlaylistsViewModel.Factory(requireActivity().application, vocaltrainerApp.playlistRepository)
    }

    private lateinit var adapter: PlaylistAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = PlaylistAdapter(
            onClick = { playlist -> openDetail(playlist) },
            onLongClick = { playlist -> confirmDelete(playlist) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.fabNewPlaylist.setOnClickListener { showCreateDialog() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playlists.collect { playlists ->
                adapter.submitList(playlists)
                binding.tvEmpty.visibility = if (playlists.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun openDetail(playlist: Playlist) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, PlaylistDetailFragment.newInstance(playlist.id))
            .addToBackStack(null)
            .commit()
    }

    private fun showCreateDialog() {
        val input = EditText(requireContext())
        input.hint = getString(R.string.new_playlist_hint)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.new_playlist_title)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotEmpty()) viewModel.createPlaylist(title)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmDelete(playlist: Playlist) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_delete_playlist_title)
            .setMessage(getString(R.string.confirm_delete_playlist_message, playlist.title))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.deletePlaylist(playlist.id) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
