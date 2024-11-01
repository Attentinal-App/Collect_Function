package com.kmou.capstone_sensor

import android.Manifest
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class PermissionsRationaleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions_rationale)

        // 사용자에게 권한 설명을 보여주는 텍스트 뷰
        val rationaleTextView = findViewById<TextView>(R.id.rationaleTextView)
        rationaleTextView.setText(R.string.permissions_rationale)

        // 권한 요청 버튼
        val requestButton = findViewById<Button>(R.id.requestButton)
        requestButton.setOnClickListener { v: View? ->
            // 권한 요청
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                ), 100
            )
        }
    }
}
