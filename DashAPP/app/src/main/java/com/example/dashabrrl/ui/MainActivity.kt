package com.example.dashabrrl.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.dashabrrl.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
  private lateinit var binding: ActivityMainBinding

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)

    fun launch(mode: String) {
      val url = binding.etMpdUrl.text.toString().trim()
      if (url.isEmpty()) {
        android.widget.Toast.makeText(this, "Please enter the MPD URL", android.widget.Toast.LENGTH_SHORT).show()
        return
      }
      val hasScheme = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
      if (!hasScheme) {
        android.widget.Toast.makeText(this, "URL must start with http:// or https://", android.widget.Toast.LENGTH_SHORT).show()
        return
      }
      val i = Intent(this, PlayerActivity::class.java)
      i.putExtra(PlayerActivity.EXTRA_MODE, mode)
      i.putExtra(PlayerActivity.EXTRA_MPD_URL, url)
      startActivity(i)
    }

    binding.btnAdaptive.setOnClickListener { launch(PlayerActivity.MODE_ADAPTIVE) }
    binding.btnFixed.setOnClickListener { launch(PlayerActivity.MODE_FIXED) }
    binding.btnRl.setOnClickListener { launch(PlayerActivity.MODE_RL) }
  }
}
