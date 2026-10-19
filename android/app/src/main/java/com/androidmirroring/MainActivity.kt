package com.androidmirroring

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Placeholder UI. The real onboarding flow arrives in M1; for M0 the helper is launched over adb. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setText(R.string.spike_info)
            setPadding(48, 96, 48, 48)
            textSize = 18f
        })
    }
}
