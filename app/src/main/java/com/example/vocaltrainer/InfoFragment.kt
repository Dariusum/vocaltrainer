package com.example.vocaltrainer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.example.vocaltrainer.databinding.FragmentInfoBinding
import com.example.vocaltrainer.log.VocaltrainerLogger

class InfoFragment : Fragment() {

    private var _binding: FragmentInfoBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvVersion.text = getString(R.string.github_version, BuildConfig.VERSION_NAME)
        binding.tvViewReleases.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
            startActivity(intent)
        }

        binding.btnViewLog.setOnClickListener { showLogDialog() }
        binding.btnShareLog.setOnClickListener { shareLog() }
        binding.btnClearLog.setOnClickListener {
            VocaltrainerLogger.clear()
            Toast.makeText(requireContext(), R.string.log_cleared, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLogDialog() {
        val textView = TextView(requireContext()).apply {
            text = VocaltrainerLogger.readLog()
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(requireContext()).apply { addView(textView) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.log_dialog_title)
            .setView(scrollView)
            .setPositiveButton(R.string.dialog_ok, null)
            .show()
    }

    private fun shareLog() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Vocaltrainer Log")
            putExtra(Intent.EXTRA_TEXT, VocaltrainerLogger.readLog())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.action_share_log)))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val RELEASES_URL = "https://github.com/Dariusum/vocaltrainer/releases"
    }
}
