package com.example.vocaltrainer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.vocaltrainer.databinding.ActivityMainBinding
import com.example.vocaltrainer.player.PlayerFragment
import com.example.vocaltrainer.recordings.RecordingsListFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showFragment(PlayerFragment())
            binding.bottomNav.selectedItemId = R.id.navPlayer
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navPlayer -> { showFragment(PlayerFragment()); true }
                R.id.navRecordings -> { showFragment(RecordingsListFragment()); true }
                R.id.navHelp -> { showFragment(InfoFragment()); true }
                else -> false
            }
        }
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
    }
}
