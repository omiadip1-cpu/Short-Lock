package com.shortslock.app

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Shorts Lock\nPhase 1 foundation (v${BuildConfigLike.VERSION})"
            textSize = 20f
            gravity = Gravity.CENTER
        })
    }
}

private object BuildConfigLike {
    const val VERSION = "0.1.0"
}
