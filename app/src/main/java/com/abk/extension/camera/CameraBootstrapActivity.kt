package com.abk.extension.camera

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class CameraBootstrapActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
