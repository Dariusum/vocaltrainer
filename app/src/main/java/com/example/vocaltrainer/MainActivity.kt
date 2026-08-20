package com.example.vocaltrainer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.vocaltrainer.databinding.ActivityMainBinding
import com.example.vocaltrainer.player.PlayerFragment
import com.example.vocaltrainer.recordings.RecordingsListFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTabId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showFragment(R.id.navPlayer, PlayerFragment())
            binding.bottomNav.selectedItemId = R.id.navPlayer
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navPlayer -> { showFragment(R.id.navPlayer, PlayerFragment()); true }
                R.id.navRecordings -> { showFragment(R.id.navRecordings, RecordingsListFragment()); true }
                R.id.navHelp -> { showFragment(R.id.navHelp, InfoFragment()); true }
                else -> false
            }
        }
    }

    /**
     * Ignoriert erneutes Tippen auf den bereits aktiven Tab — sonst würde
     * replace() auch dafür eine neue Fragment-Instanz erzeugen und die alte
     * unnötig zerstören (bei der Wiedergabe-Fragment sonst harmlos, da das
     * ViewModel jetzt Activity-weit gescoped ist, aber unnötiger UI-Reset).
     */
    private fun showFragment(tabId: Int, fragment: Fragment) {
        if (tabId == currentTabId) return
        currentTabId = tabId
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
