package com.example.gemini.data.receiver

import android.app.Activity
import android.os.Bundle

open class TermuxOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            intent?.let {
                TermuxOpenReceiver.handleOpen(this, it)
            }
        } finally {
            finish()
        }
    }
}
