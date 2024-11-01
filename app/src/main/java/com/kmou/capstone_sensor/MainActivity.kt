package com.kmou.capstone_sensor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt


class MainActivity : AppCompatActivity(), SensorEventListener {
    // UI 요소 변수 선언
    private var timeTextView: TextView? = null
    private var noiseTextView: TextView? = null
    private var lightTextView: TextView? = null
    private var heartRateTextView: TextView? = null
    private var stepCountTextView: TextView? = null
    private var startStopButton: Button? = null
    private var lapButton: Button? = null
    private var lapListView: ListView? = null

    // 타이머 관련 변수
    private val handler = Handler(Looper.getMainLooper())  // 타이머 업데이트를 위한 Handler
    private var startTime: Long = 0  // 타이머 시작 시간
    private var pauseTime: Long = 0  // 타이머 일시정지 시간
    private var isRunning = false  // 타이머가 실행 중인지 여부

    // 랩 타임 관련 변수
    private val lapTimes = ArrayList<String>()  // 랩 타임 목록
    private var lapTimeAdapter: LapTimeAdapter? = null  // 랩 타임을 표시할 어댑터
    private var lapCount = 1  // 랩 타임 카운트

    // 센서 관련 변수
    private var sensorManager: SensorManager? = null  // 센서 관리자
    private var lightSensor: Sensor? = null  // 조도 센서
    private var audioRecord: AudioRecord? = null  // 소리 녹음을 위한 AudioRecord 객체
    private var isRecording = false  // 소리 녹음 중인지 여부
    private val permissions = arrayOf(Manifest.permission.RECORD_AUDIO)  // 오디오 녹음 권한
    private var bufferSize = 0  // 오디오 녹음을 위한 버퍼 크기

    // 현재 소음 수준 저장 변수
    private var currentNoiseLevel = 0.0  // 현재 소음 수준

    // Health Connect 권한 관련 변수
    private val permissionsSet = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class)
    )

    private lateinit var healthConnectClient: HealthConnectClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        // 오디오 녹음 권한 요청
        ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)

        // UI 요소 초기화
        timeTextView = findViewById(R.id.timeTextView)
        noiseTextView = findViewById(R.id.noiseTextView)
        lightTextView = findViewById(R.id.lightTextView)
        heartRateTextView = findViewById(R.id.heartRateTextView)
        stepCountTextView = findViewById(R.id.stepCountTextView)

        startStopButton = findViewById(R.id.startStopButton)
        lapButton = findViewById(R.id.lapButton)
        lapListView = findViewById(R.id.lapListView)
        lapTimeAdapter = LapTimeAdapter(this, lapTimes)
        lapListView?.adapter = lapTimeAdapter

        // 타이머 시작/중지 버튼 클릭 리스너 설정
        startStopButton?.setOnClickListener {
            if (!isRunning) {
                startTime = System.currentTimeMillis() - pauseTime
                isRunning = true
                handler.post(timeUpdater)
                startStopButton?.text = getString(R.string.stop_timer)
                lapButton?.text = "랩"

                // HeartRate 데이터를 읽고 집계하는 함수 호출
                lifecycleScope.launch {
                    readHeartByTimeRange(healthConnectClient, Instant.now().minusSeconds(60), Instant.now())
                    aggregateHeartRate(healthConnectClient, Instant.now().minusSeconds(60), Instant.now())
                }
            } else {
                isRunning = false
                handler.removeCallbacks(timeUpdater)
                pauseTime = System.currentTimeMillis() - startTime
                startStopButton?.text = getString(R.string.start_timer)
                lapButton?.text = "재설정"
            }
        }

        // 랩/재설정 버튼 클릭 리스너 설정
        lapButton?.setOnClickListener {
            if (isRunning) {
                recordLapTime()
            } else {
                resetTimer()
            }
        }

        // 센서 관리자와 조도 센서 초기화 및 등록
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        sensorManager?.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)

        setupNoiseMeter()

        // HealthConnectClient SDK 상태 확인 및 초기화
        setupHealthConnectClient()
    }

    // HealthConnectClient를 초기화하고 권한을 요청하는 함수
    private fun setupHealthConnectClient() {
        val providerPackageName = "com.google.android.apps.healthdata"
        val availabilityStatus = HealthConnectClient.getSdkStatus(this, providerPackageName)
        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE) {
            return
        }
        if (availabilityStatus == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            val uriString =
                "market://details?id=$providerPackageName&url=healthconnect%3A%2F%2Fonboarding"
            this.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setPackage("com.android.vending")
                    data = Uri.parse(uriString)
                    putExtra("overlay", true)
                    putExtra("callerId", this@MainActivity.packageName)
                }
            )
            return
        }
        healthConnectClient = HealthConnectClient.getOrCreate(this)

        // 권한 요청 함수 호출
        if (availabilityStatus == HealthConnectClient.SDK_AVAILABLE) {
            requestHealthPermissions()
        }
    }

    // Health Connect의 권한을 요청하는 함수
    private fun requestHealthPermissions() {
        val requestPermissionsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allPermissionsGranted = permissions.all { it.value }
            if (allPermissionsGranted) {
                // 모든 권한이 허용됨, 데이터 작업 수행
                Log.d("HealthConnect", "All permissions granted.")
                // 권한 허용 -> 심박수 데이터 기록
                lifecycleScope.launch {
                    insertHeartRecord(healthConnectClient)
                }
            } else {
                // 권한 거부 처리
                Log.e("HealthConnect", "Permissions were denied.")
            }
        }

        requestPermissionsLauncher.launch(permissionsSet.toTypedArray())
    }

    // 타이머 업데이트 함수
    private val timeUpdater: Runnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                val elapsedTime = System.currentTimeMillis() - startTime
                val formattedTime: String
                if (elapsedTime < 3600000) {
                    val seconds = (elapsedTime / 1000).toInt() % 60
                    val minutes = ((elapsedTime / (1000 * 60)) % 60).toInt()
                    val millis = (elapsedTime % 1000 / 10).toInt()
                    formattedTime = String.format(
                        Locale.getDefault(),
                        "%02d:%02d:%02d",
                        minutes,
                        seconds,
                        millis
                    )
                } else {
                    val seconds = (elapsedTime / 1000).toInt() % 60
                    val minutes = ((elapsedTime / (1000 * 60)) % 60).toInt()
                    val hours = ((elapsedTime / (1000 * 60 * 60)) % 24).toInt()
                    formattedTime = String.format(
                        Locale.getDefault(),
                        "%02d:%02d:%02d",
                        hours,
                        minutes,
                        seconds
                    )
                }
                timeTextView?.text = formattedTime

                // 심박수 및 걸음수 데이터를 가져와서 업데이트
                lifecycleScope.launch {
                    val startTime = Instant.now().minusSeconds(10)
                    val endTime = Instant.now()

                    // 심박수 데이터 가져오기
                    readHeartByTimeRange(healthConnectClient, startTime, endTime)

                    // 걸음수 데이터 가져오기
                    val stepRecords = readStepsByTimeRange(healthConnectClient, startTime, endTime)
                    val totalSteps = stepRecords.sumOf { it.count }

                    // UI 업데이트
                    runOnUiThread {
                        stepCountTextView?.text = getString(R.string.step_count, totalSteps)
                    }
                }

                // 타이머를 10ms마다 업데이트
                handler.postDelayed(this, 10)
            }
        }
    }

    // 랩 타임을 기록하는 함수
    private fun recordLapTime() {
        if (isRunning) {
            val lapTime = System.currentTimeMillis() - startTime
            val seconds = (lapTime / 1000).toInt() % 60
            val minutes = ((lapTime / (1000 * 60)) % 60).toInt()
            val hours = ((lapTime / (1000 * 60 * 60)) % 24).toInt()

            val formattedLapTime: String
            if (lapTime < 3600000) {
                val millis = (lapTime % 1000 / 10).toInt()
                formattedLapTime = String.format(
                    Locale.getDefault(),
                    "랩 %d: %02d:%02d:%02d",
                    lapCount,
                    minutes,
                    seconds,
                    millis
                )
            } else {
                formattedLapTime = String.format(
                    Locale.getDefault(),
                    "랩 %d: %02d:%02d:%02d",
                    lapCount,
                    hours,
                    minutes,
                    seconds
                )
            }

            lapTimes.add(0, formattedLapTime)
            lapCount++
            lapTimeAdapter?.notifyDataSetChanged()
            lapListView?.setSelection(0)
        }
    }

    // 타이머를 초기화하는 함수
    private fun resetTimer() {
        isRunning = false
        handler.removeCallbacks(timeUpdater)
        timeTextView?.text = getString(R.string.default_time)
        lapTimes.clear()
        lapTimeAdapter?.notifyDataSetChanged()
        startStopButton?.text = getString(R.string.start_timer)
        pauseTime = 0
    }

    // 소음 측정을 설정하는 함수
    private fun setupNoiseMeter() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            bufferSize = AudioRecord.getMinBufferSize(
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                44100,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                isRecording = true
                handler.post(noiseUpdater)
            } else {
                Log.e("AudioRecord", "AudioRecord initialization failed")
            }
        } else {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_RECORD_AUDIO_PERMISSION)
        }
    }

    // 조도 센서와 소음 레벨 업데이트
    private val noiseUpdater: Runnable = object : Runnable {
        override fun run() {
            if (isRecording && audioRecord != null) {
                val buffer = ShortArray(bufferSize)
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (readSize > 0) {
                    var sum = 0.0
                    for (value in buffer) {
                        sum += (value * value).toDouble()
                    }
                    var amplitude = sqrt(sum / readSize)
                    val minAmplitude = 0.02
                    amplitude = max(amplitude, minAmplitude)
                    currentNoiseLevel = 20 * log10(amplitude / minAmplitude)
                    val formattedNoiseLevel =
                        String.format(Locale.getDefault(), "%.2f", currentNoiseLevel)
                    noiseTextView?.text = getString(R.string.noise, formattedNoiseLevel)
                }
                handler.postDelayed(this, 1000)
            }
        }
    }

    // 심박수 데이터를 기록하는 함수
    private suspend fun insertHeartRecord(healthConnectClient: HealthConnectClient) {
        try {
            // 데이터를 기록할 시간과 시간대 오프셋 설정
            val startTime = Instant.now() // 데이터를 기록하는 현재 시간
            val endTime = Instant.now().plusSeconds(10) // 예시로 10초 후를 종료 시간으로 설정
            val zoneOffset = ZoneOffset.ofHours(9) // 예시로 한국 시간대 (+9) 설정

            // HeartRateRecord 생성
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

            // insertRecords를 사용하여 Health Connect에 기록
            healthConnectClient.insertRecords(listOf(heartRecord))

            Log.d("HealthConnect", "Heart record inserted successfully.")
        } catch (e: Exception) {
            // 에러 처리
            Log.e("HealthConnect", "Failed to insert heart record: ${e.message}")
        }
    }

    // HeartRate 데이터를 읽는 함수
    private suspend fun readHeartByTimeRange(
        healthConnectClient: HealthConnectClient,
        startTime: Instant,
        endTime: Instant
    ) {
        try {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )

            // HeartRateRecord가 있으면 이를 처리
            for (heartRecord in response.records) {
                val bpm = heartRecord.samples.first().beatsPerMinute
                Log.d("HealthConnect", "Heart rate: $bpm bpm")

                // UI 업데이트
                runOnUiThread {
                    heartRateTextView?.text = getString(R.string.heartrate, bpm)
                }
            }
        } catch (e: Exception) {
            // 에러 처리
            Log.e("HealthConnect", "Failed to read heart record: ${e.message}")
        }
    }

    // HeartRate 데이터를 집계하는 함수
    private suspend fun aggregateHeartRate(
        healthConnectClient: HealthConnectClient,
        startTime: Instant,
        endTime: Instant
    ) {
        try {
            val response = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(HeartRateRecord.BPM_AVG), // AggregateMetric 사용하지 않음
                    timeRangeFilter = TimeRangeFilter.between(startTime, endTime)
                )
            )
            // 데이터가 없을 경우 null 반환 가능
            val heartRateAvg = response[HeartRateRecord.BPM_AVG]
            Log.d("HealthConnect", "Average heart rate: $heartRateAvg bpm")
        } catch (e: Exception) {
            // 에러 처리
            Log.e("HealthConnect", "Failed to aggregate heart rate: ${e.message}")
        }
    }

    suspend fun insertSteps(
        healthConnectClient: HealthConnectClient,
        stepCnt: Long,
        startT: Instant,
        endT: Instant
    ) {
        try {
            val stepsRec = StepsRecord(
                count = stepCnt,
                startTime = startT,
                endTime = endT,
                startZoneOffset = null,
                endZoneOffset = null
            )
            healthConnectClient.insertRecords(listOf(stepsRec))
        } catch (e: Exception) {
            Log.e("HealthConnect", "Insert failed: ${e.message}")
        }
    }

    private suspend fun readStepsByTimeRange(
        healthConnectClient: HealthConnectClient,
        startT: Instant,
        endT: Instant
    ): List<StepsRecord> {
        return try {
            val res = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(startT, endT)
                )
            )
            res.records
        } catch (e: Exception) {
            Log.e("HealthConnect", "Read failed: ${e.message}")
            emptyList()
        }
    }

    private suspend fun aggregateSteps(
        healthConnectClient: HealthConnectClient,
        startT: Instant,
        endT: Instant
    ): Long {
        return try {
            val res = healthConnectClient.aggregate(
                AggregateRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(startT, endT)
                )
            )
            res[StepsRecord.COUNT_TOTAL] ?: 0L
        } catch (e: Exception) {
            Log.e("HealthConnect", "Aggregate failed: ${e.message}")
            0L
        }
    }


    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_LIGHT) {
            val lightLevel = event.values[0]
            lightTextView?.text = getString(R.string.light, lightLevel)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Do nothing
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager?.unregisterListener(this)
        if (isRecording) {
            audioRecord?.stop()
            audioRecord?.release()
            isRecording = false
        }
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 200
    }
}