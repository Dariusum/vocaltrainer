package com.example.vocaltrainer.playlists

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.vocaltrainer.MainActivity
import com.example.vocaltrainer.R
import com.example.vocaltrainer.VocaltrainerApp
import com.example.vocaltrainer.databinding.FragmentPlaylistDetailBinding
import com.example.vocaltrainer.player.PlayerViewModel
import kotlinx.coroutines.launch

class PlaylistDetailFragment : Fragment() {

    private var _binding: FragmentPlaylistDetailBinding? = null
    private val binding get() = _binding!!

    private val playlistId: String by lazy { requireArguments().getString(ARG_PLAYLIST_ID, "") }

    private val vocaltrainerApp by lazy { requireActivity().application as VocaltrainerApp }

    private val viewModel: PlaylistDetailViewModel by viewModels {
        PlaylistDetailViewModel.Factory(
            requireActivity().application,
            playlistId,
            vocaltrainerApp.playlistRepository,
            vocaltrainerApp.stemsCache
        )
    }

    // Activity-weit gescoped, wie in PlayerFragment — damit ein aus der Playlist gestarteter
    // Titel im selben, bereits laufenden Player-ViewModel landet statt in einer zweiten Instanz.
    private val playerViewModel: PlayerViewModel by activityViewModels {
        PlayerViewModel.Factory(
            requireActivity().application,
            vocaltrainerApp.recordingRepository,
            vocaltrainerApp.recentTracksStore,
            vocaltrainerApp.recentPlaylistsStore,
            vocaltrainerApp.playlistRepository,
            vocaltrainerApp.stemsCache
        )
    }

    private lateinit var adapter: PlaylistTrackAdapter

    // OpenDocument statt GetContent, analog zu PlayerFragment: Playlist-Einträge müssen auch
    // nach einem App-Neustart noch über eine persistierte Berechtigung geöffnet werden können.
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            requireContext().contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val name = queryFileName(it) ?: "Track"
            viewModel.addTrack(it, name)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = PlaylistTrackAdapter(
            onClick = { position -> playTrack(position) },
            onLongClick = { position -> showTrackActions(position) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.fabAddTrack.setOnClickListener { filePicker.launch(arrayOf("audio/*")) }
        binding.btnReseparateAll.setOnClickListener { confirmReseparateAll() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playlist.collect { playlist ->
                binding.tvTitle.text = playlist?.title ?: ""
                val tracks = playlist?.tracks ?: emptyList()
                adapter.submitList(tracks)
                binding.tvEmpty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
                binding.btnReseparateAll.isEnabled = tracks.isNotEmpty()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.reseparateState.collect { state ->
                when (state) {
                    is ReseparateState.Idle -> {
                        binding.tvReseparateProgress.visibility = View.GONE
                        binding.btnReseparateAll.isEnabled = !viewModel.playlist.value?.tracks.isNullOrEmpty()
                        binding.fabAddTrack.isEnabled = true
                    }
                    is ReseparateState.InProgress -> {
                        binding.tvReseparateProgress.visibility = View.VISIBLE
                        binding.tvReseparateProgress.text = getString(
                            R.string.reseparate_progress, state.current, state.total, state.currentTitle
                        )
                        binding.btnReseparateAll.isEnabled = false
                        binding.fabAddTrack.isEnabled = false
                    }
                }
            }
        }
    }

    private fun confirmReseparateAll() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_reseparate_playlist_title)
            .setMessage(R.string.confirm_reseparate_playlist_message)
            .setPositiveButton(R.string.action_reseparate_playlist) { _, _ -> viewModel.reseparateAll() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun playTrack(position: Int) {
        val playlist = viewModel.playlist.value ?: return
        playerViewModel.playFromQueue(playlist.tracks, position, playlistId = playlist.id, playlistTitle = playlist.title)
        (requireActivity() as? MainActivity)?.navigateToPlayerTab()
    }

    private fun showTrackActions(position: Int) {
        val entry = viewModel.playlist.value?.tracks?.getOrNull(position) ?: return
        AlertDialog.Builder(requireContext())
            .setTitle(entry.displayName)
            .setItems(arrayOf(getString(R.string.action_rename_track), getString(R.string.action_delete))) { _, which ->
                when (which) {
                    0 -> showRenameDialog(position, entry.displayName)
                    1 -> confirmRemove(position, entry.displayName)
                }
            }
            .show()
    }

    private fun showRenameDialog(position: Int, currentName: String) {
        val input = EditText(requireContext())
        input.setText(currentName)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_rename_track)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) viewModel.renameTrack(position, name)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmRemove(position: Int, displayName: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_remove_track_title)
            .setMessage(getString(R.string.confirm_remove_track_message, displayName))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.removeTrack(position) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun queryFileName(uri: Uri): String? {
        return runCatching {
            requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_PLAYLIST_ID = "playlist_id"

        fun newInstance(playlistId: String): PlaylistDetailFragment = PlaylistDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_PLAYLIST_ID, playlistId) }
        }
    }
}
