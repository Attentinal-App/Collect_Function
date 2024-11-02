package com.kmou.capstone_sensor

import android.os.Bundle
import android.view.Window
import androidx.appcompat.app.AppCompatActivity
import com.kmou.capstone_sensor.databinding.ActivityPermissionsRationaleBinding

class PermissionsRationaleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionsRationaleBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 대화 상자 스타일을 위해 타이틀 제거
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        binding = ActivityPermissionsRationaleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // "권한 허용" 버튼 클릭 시 권한 요청 진행
        binding.confirmButton.setOnClickListener {
            setResult(RESULT_OK)  // 결과를 OK로 설정
            finish()  // 액티비티 종료
        }

        // "취소" 버튼 클릭 시 액티비티 종료
        binding.cancelButton.setOnClickListener {
            setResult(RESULT_CANCELED)  // 결과를 CANCELED로 설정
            finish()  // 액티비티 종료
        }
    }
}
