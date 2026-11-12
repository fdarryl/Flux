package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.MenuItem
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutSettingsActivityBinding
import io.nekohasekai.sagernet.widget.ListListener

class SettingsActivity : ThemedActivity() {

    lateinit var binding: LayoutSettingsActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutSettingsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val toolbar = binding.root.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.settings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings, SettingsPreferenceFragment())
                .commit()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
