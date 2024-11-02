package com.kmou.capstone_sensor

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset

class MainActivity : AppCompatActivity() {

    // UI 요소 변수 선언
    private var heartRateTextView: TextView? = null
    private var stepCountTextView: TextView? = null

    // Health Connect 권한 문자열 배열
    private val permissionsArray = arrayOf(
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.WRITE_HEART_RATE",
        "android.permission.health.READ_STEPS",
        "android.permission.health.WRITE_STEPS"
    )

    private lateinit var healthConnectClient: HealthConnectClient

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allPermissionsGranted = permissions.all { it.value }
        if (allPermissionsGranted) {
            Log.d("HealthConnect", "모든 권한이 허용되었습니다.")
            lifecycleScope.launch {
                insertAndReadData()
            }
        } else {
            Log.e("HealthConnect", "권한이 거부되었습니다.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        // UI 요소 초기화
        heartRateTextView = findViewById(R.id.heartRateTextView)
        stepCountTextView = findViewById(R.id.stepCountTextView)

        // HealthConnectClient 초기화
        healthConnectClient = HealthConnectClient.getOrCreate(this)

        // 권한이 필요한 경우 PermissionsRationaleActivity 호출
        if (isHealthConnectAvailable()) {
            checkPermissionsAndRequest()
        } else {
            showHealthConnectNotInstalledDialog()
        }
    }

    private fun isHealthConnectAvailable(): Boolean {
        return try {
            packageManager.getPackageInfo("com.google.android.apps.healthdata", 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun checkPermissionsAndRequest() {
        lifecycleScope.launch {
            val grantedPermissions = healthConnectClient.permissionController.getGrantedPermissions()
            if (grantedPermissions.containsAll(permissionsArray.toSet())) {
                Log.d("HealthConnect", "필요한 모든 권한이 이미 허용되었습니다.")
                insertAndReadData()
            } else {
                // PermissionsRationaleActivity를 호출하여 권한 안내
                val intent = Intent(this@MainActivity, PermissionsRationaleActivity::class.java)
                startActivityForResult(intent, REQUEST_PERMISSION_RATIONALE)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_PERMISSION_RATIONALE) {
            if (resultCode == RESULT_OK) {
                // 사용자가 권한 허용을 누른 경우 권한 요청 진행
                requestPermissionsLauncher.launch(permissionsArray)
            } else {
                // 사용자가 권한 거부한 경우 처리
                Log.e("HealthConnect", "사용자가 권한 요청을 취소했습니다.")
            }
        }
    }

    private fun showHealthConnectNotInstalledDialog() {
        AlertDialog.Builder(this)
            .setTitle("Health Connect 필요")
            .setMessage("이 앱을 사용하려면 Google의 Health Connect 앱이 필요합니다. Google Play 스토어에서 설치해 주세요.")
            .setPositiveButton("확인") { dialog, _ ->
                dialog.dismiss()
                finish() // 앱 종료 또는 다른 처리
            }
            .setCancelable(false)
            .show()
    }

    private suspend fun insertAndReadData() {
        insertHeartRecord()
        readHeartByTimeRange()
        readStepsByTimeRange()
    }

    private suspend fun insertHeartRecord() {
        try {
            val startTime = Instant.now()
            val endTime = Instant.now().plusSeconds(10)
            val zoneOffset = ZoneOffset.ofHours(9)

            val heartRecord = HeartRateRecord(
                startTime = startTime,
                endTime = endTime,
                startZoneOffset = zoneOffset,
                endZoneOffset = zoneOffset,
                samples = listOf(
                    HeartRateRecord.Sample(
                        time = startTime,
                        beatsPerMinute = 120
                    )
                )
            )
            healthConnectClient.insertRecords(listOf(heartRecord))
            Log.d("HealthConnect", "심박수 기록이 성공적으로 삽입되었습니다.")
        } catch (e: Exception) {
            Log.e("HealthConnect", "심박수 기록 삽입 실패: ${e.message}")
        }
    }

    private suspend fun readHeartByTimeRange() {
        try {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(Instant.now().minusSeconds(60), Instant.now())
                )
            )

            for (heartRecord in response.records) {
                val bpm = heartRecord.samples.first().beatsPerMinute
                runOnUiThread {
                    heartRateTextView?.text = getString(R.string.heartrate, bpm)
                }
            }
        } catch (e: Exception) {
            Log.e("HealthConnect", "심박수 기록 읽기 실패: ${e.message}")
        }
    }

    private suspend fun readStepsByTimeRange() {
        try {
            val res = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(Instant.now().minusSeconds(60), Instant.now())
                )
            )
            val totalSteps = res.records.sumOf { it.count }
            runOnUiThread {
                stepCountTextView?.text = getString(R.string.step_count, totalSteps)
            }
        } catch (e: Exception) {
            Log.e("HealthConnect", "걸음수 기록 읽기 실패: ${e.message}")
        }
    }

    companion object {
        private const val REQUEST_PERMISSION_RATIONALE = 1001
    }
}