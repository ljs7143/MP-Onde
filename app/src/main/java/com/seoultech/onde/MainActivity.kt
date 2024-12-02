package com.seoultech.onde

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import kotlin.math.sqrt


class MainActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private lateinit var requestMultiplePermissionsLauncher: ActivityResultLauncher<Array<String>>

    private val requiredPermissions = mutableListOf<String>().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }.toTypedArray()

    //센서
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var shakeThreshold: Float = 12.0f // 흔들기 감지 민감도
    private var lastShakeTime: Long = 0

    companion object {
        const val REQUEST_ENABLE_BT = 1
        val SERVICE_UUID: UUID = UUID.fromString("fee84403-9ba8-4797-b69b-7330b6f1a464")
    }

    // BLE 스캔 상태를 추적하기 위한 변수
    private var isScanning = false
    private var isAdvertising = false

    // BLE 스캔 중지 핸들러
    private val scanHandler = Handler(Looper.getMainLooper())

    // 스캔된 사용자 ID 해시를 저장할 집합 (중복 방지)
    private val scannedUserHashes = mutableSetOf<String>()

    // 사용자 정보를 저장할 리스트
    private val scannedUsers = mutableListOf<User>()

    // 사용자와 버튼 매핑을 위한 Map 추가
    private val userButtonMap = mutableMapOf<String, View>()

    // activatedButtons를 Map으로 변경
    private val activatedButtons = mutableMapOf<String, View>()

    // RSSI 영역 기준
    private val RSSI_RANGE_LOW = -90
    private val RSSI_RANGE_MID = -60


    //위젯
    private lateinit var topAppBar: MaterialToolbar
    private lateinit var startScanButton: Button
    private lateinit var userButtons: List<View>

    // 생성
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: run {
            Toast.makeText(this, "가속도 센서를 사용할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
        // UI 요소 초기화 및 이벤트 리스너 설정
        initViews()

        adjustTopMarginForStatusBar()
        startConnectionCheck() // 연결 상태 확인 시작

        userButtons = listOf(
            findViewById(R.id.user1), findViewById(R.id.user2), findViewById(R.id.user3),
            findViewById(R.id.user4), findViewById(R.id.user5), findViewById(R.id.user6),
            findViewById(R.id.user7), findViewById(R.id.user8), findViewById(R.id.user9),
            findViewById(R.id.user10), findViewById(R.id.user11), findViewById(R.id.user12),
            findViewById(R.id.user13), findViewById(R.id.user14), findViewById(R.id.user15),
            findViewById(R.id.user16), findViewById(R.id.user17), findViewById(R.id.user18),
            findViewById(R.id.user19), findViewById(R.id.user20)
        )

        // Hide all buttons initially
        userButtons.forEach { it.visibility = View.GONE }

        // 상단 앱바 설정
        setSupportActionBar(topAppBar)
        topAppBar.setNavigationOnClickListener {
            isAdvertising = !isAdvertising
            checkPermissionsAndToggleAdvertise(isAdvertising)
            updateAdvertiseButtonIcon()
        }



        // Firebase 초기화 및 사용자 등록
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        // 사용자 인증 상태 확인
        if (auth.currentUser == null) {
            // 사용자가 로그인하지 않은 경우, LoginActivity로 이동
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            return // 현재 액티비티를 종료하여 아래 코드가 실행되지 않도록 함
        }

        // 권한 요청 초기화
        requestMultiplePermissionsLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val deniedPermissions = permissions.filter { !it.value }.map { it.key }
            if (deniedPermissions.isEmpty()) {
                // 모든 권한이 허용됨
                // 필요에 따라 추가 작업 수행
            } else {
                // 거부된 권한이 있음
                val message = "다음 권한을 허용해야 합니다:\n${
                    deniedPermissions.joinToString("\n") { permission ->
                        when (permission) {
                            Manifest.permission.BLUETOOTH_SCAN -> "블루투스 스캔"
                            Manifest.permission.BLUETOOTH_CONNECT -> "블루투스 연결"
                            Manifest.permission.BLUETOOTH_ADVERTISE -> "블루투스 광고"
                            Manifest.permission.ACCESS_FINE_LOCATION -> "정밀 위치 정보"
                            Manifest.permission.ACCESS_COARSE_LOCATION -> "대략적 위치 정보"
                            else -> permission
                        }
                    }
                }"
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                showPermissionSettingsDialog()
            }
        }
    }

    private fun initViews() {
        topAppBar = findViewById(R.id.topAppBar)
        startScanButton = findViewById(R.id.startScanButton)

        startScanButton.setOnClickListener {
            checkPermissionsAndStartScan()
        }
        updateAdvertiseButtonIcon()
    }

    private fun adjustTopMarginForStatusBar() {
        val rootLayout: View = findViewById(R.id.mainActivity) // 최상위 레이아웃 ID
        rootLayout.setOnApplyWindowInsetsListener { view, insets ->
            val statusBarHeight = insets.systemWindowInsetTop // 상태바 높이

            // LayoutParams를 가져와 topMargin 추가
            val layoutParams = view.layoutParams as ViewGroup.MarginLayoutParams
            layoutParams.topMargin = statusBarHeight
            view.layoutParams = layoutParams

            insets // 변경된 insets 반환
        }
    }

    private fun updateAdvertiseButtonIcon() {
        val iconRes = if (isAdvertising) {
            R.drawable.ic_bluetooth_disabled
        } else {
            R.drawable.ic_bluetooth
        }
        topAppBar.navigationIcon = getDrawable(iconRes)
    }

    private fun checkPermissionsAndToggleAdvertise(shouldAdvertise: Boolean) {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            if (bluetoothAdapter?.isEnabled == true) {
                if (shouldAdvertise) {
                    startAdvertising()
                } else {
                    stopAdvertising()
                }
            } else {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
        }
    }

    private fun checkPermissionsAndStartScan() {
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(missingPermissions.toTypedArray())
        } else {
            if (bluetoothAdapter?.isEnabled == true) {
                startScanning()
            } else {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            }
        }
    }

    private fun startAdvertising() {
        val bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

        if (bluetoothLeAdvertiser == null) {
            Toast.makeText(this, "BLE Advertiser를 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
            return
        }

        // BLE 광고 지원 여부 확인
        if (bluetoothAdapter?.isMultipleAdvertisementSupported == false) {
            Log.e("Advertise", "BLE 광고를 지원하지 않는 기기입니다.")
            Toast.makeText(this, "BLE 광고를 지원하지 않는 기기입니다.", Toast.LENGTH_LONG).show()
            return
        }

        // 권한 체크
        val hasAdvertisePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasAdvertisePermission) {
            Toast.makeText(this, "BLUETOOTH_ADVERTISE 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val serviceUuid = ParcelUuid(SERVICE_UUID)

        // 사용자 ID 해시 생성
        val userId = auth.currentUser?.uid ?: "unknown"
        val serviceDataString = HashUtils.generateUserIdHash(userId)
        val serviceData = Base64.decode(serviceDataString, Base64.NO_WRAP)
        Log.d("Advertise", "광고에 사용될 userIdHash: $serviceDataString") // 로그 추가


        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(serviceUuid)
            .build()

        val scanResponseData = AdvertiseData.Builder()
            .addServiceData(serviceUuid, serviceData)
            .build()

        try {
            bluetoothLeAdvertiser.startAdvertising(
                settings,
                advertiseData,
                scanResponseData,
                advertiseCallback
            )
            isAdvertising = true
        } catch (e: Exception) {
            Log.e("Advertise", "광고 시작 중 예외 발생: ${e.message}")
            Toast.makeText(this, "광고 시작 중 예외 발생: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAdvertising() {
        val bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

        // 권한 체크
        val hasAdvertisePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android S 미만에서는 해당 권한이 필요하지 않습니다.
        }

        if (!hasAdvertisePermission) {
            Toast.makeText(this, "광고를 중지하려면 BLUETOOTH_ADVERTISE 권한이 필요합니다.", Toast.LENGTH_LONG)
                .show()
            return
        }

        try {
            bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            isAdvertising = false
            Toast.makeText(this, "BLE 광고 중지", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.e("Advertise", "권한 오류로 인해 광고를 중지할 수 없습니다: ${e.message}")
            Toast.makeText(this, "권한 오류로 인해 광고를 중지할 수 없습니다.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("Advertise", "광고 중지 중 예외 발생: ${e.message}")
            Toast.makeText(this, "광고 중지 중 예외 발생: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }


    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.d("Advertise", "BLE 광고 시작 성공")
            Toast.makeText(this@MainActivity, "BLE 광고 시작 성공", Toast.LENGTH_SHORT).show()
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e("Advertise", "BLE 광고 시작 실패: $errorCode")
            Toast.makeText(this@MainActivity, "BLE 광고 시작 실패: $errorCode", Toast.LENGTH_LONG).show()
            isAdvertising = false
        }
    }

    private fun startScanning() {
        if (isScanning) {
            Toast.makeText(this, "이미 스캔 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        // 스캔 결과 리스트 초기화
        scannedUserHashes.clear()
        scannedUsers.clear()
        userButtonMap.clear()
        activatedButtons.clear()
        userButtons.forEach {
            it.visibility = View.GONE
            it.setOnClickListener(null) // 리스너 제거
        }

        val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

        if (bluetoothLeScanner == null) {
            Toast.makeText(this, "BLE Scanner를 사용할 수 없습니다.", Toast.LENGTH_LONG).show()
            return
        }

        // 권한 체크
        val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasScanPermission) {
            Toast.makeText(
                this,
                "BLUETOOTH_SCAN 또는 ACCESS_FINE_LOCATION 권한이 필요합니다.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 앱 UUID를 이용한 앱 사용자 필터링
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val filters = listOf(scanFilter)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bluetoothLeScanner.startScan(filters, settings, scanCallback)
            isScanning = true
            Toast.makeText(this, "BLE 스캔을 시작합니다.", Toast.LENGTH_SHORT).show()

            // 12초 후 스캔 중지
            scanHandler.postDelayed({
                stopScanning()
            }, 12000)
        } catch (e: Exception) {
            Log.e("Scanner", "스캔 시작 중 예외 발생: ${e.message}")
            Toast.makeText(this, "스캔 시작 중 예외 발생: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopScanning() {
        val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

// 권한 체크
        val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android S 미만에서는 해당 권한이 필요하지 않습니다.
        }

        if (!hasScanPermission) {
            Toast.makeText(this, "스캔을 중지하려면 BLUETOOTH_SCAN 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
            Toast.makeText(this, "BLE 스캔 중지", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Log.e("Scanner", "권한 오류로 인해 스캔을 중지할 수 없습니다: ${e.message}")
            Toast.makeText(this, "권한 오류로 인해 스캔을 중지할 수 없습니다.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("Scanner", "스캔 중지 중 예외 발생: ${e.message}")
            Toast.makeText(this, "스캔 중지 중 예외 발생: ${e.message}", Toast.LENGTH_LONG).show()
        }
// 스캔 종료 후 메시지 표시
        Toast.makeText(this, "스캔이 완료되었습니다.", Toast.LENGTH_SHORT).show()
    }


    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            Log.d("Scanner", "스캔 결과 수신: ${result.device.address}, RSSI: ${result.rssi}")

            val serviceUuid = ParcelUuid(SERVICE_UUID)
            val serviceData = result.scanRecord?.getServiceData(serviceUuid)
            val rssi = result.rssi
            val currentTime = System.currentTimeMillis()

            if (serviceData != null) {
                val userIdHashString = Base64.encodeToString(serviceData, Base64.NO_WRAP)

                if (scannedUserHashes.add(userIdHashString)) {
                    // New user
                    val user = User(userIdHashString, "Unknown", "", "", rssi, currentTime)
                    scannedUsers.add(user)
                    updateButtonsBasedOnRssi(user)
                    fetchUserInfo(userIdHashString, rssi)
                } else {
                    // Existing user; update RSSI and timestamp
                    updateUserRssi(userIdHashString, rssi)
                }
            } else {
                Log.e("Scanner", "Unable to retrieve serviceData.")
            }
        }
    }

    // updateButtonsBasedOnRssi 버튼 그룹을 세기에 따라 할당해주는 코드
    private fun updateButtonsBasedOnRssi(user: User) {
        val rssi = user.rssi
        val userIdHash = user.userIdHash
        runOnUiThread {
            // Select button group based on RSSI
            val buttonGroup = when {
                rssi <= RSSI_RANGE_LOW -> userButtons.subList(14, 20)
                rssi in (RSSI_RANGE_LOW + 1)..RSSI_RANGE_MID -> userButtons.subList(6, 14)
                else -> userButtons.subList(0, 6)
            }

            val currentButton = userButtonMap[userIdHash]

            if (currentButton != null) {
                if (currentButton in buttonGroup) {
                    // Button is in the correct group; do nothing
                } else {
                    // Reassign button to correct group
                    currentButton.visibility = View.GONE
                    currentButton.setOnClickListener(null)
                    activatedButtons.remove(userIdHash)
                    userButtonMap.remove(userIdHash)

                    assignButtonToUser(buttonGroup, user)
                }
            } else {
                // Assign new button
                assignButtonToUser(buttonGroup, user)
            }
        }
    }


    // removeInactiveDevices 5초 이상 비활성화이면 삭제
    private fun removeInactiveDevices() {
        val currentTime = System.currentTimeMillis()
        val inactiveUsers = scannedUsers.filter { user ->
            currentTime - user.lastSeenTimestamp > 5000 // Inactive for over 5 seconds
        }

        runOnUiThread {
            inactiveUsers.forEach { user ->
                val userIdHash = user.userIdHash
                // Hide and remove the button
                val button = userButtonMap[userIdHash]
                if (button != null) {
                    button.visibility = View.GONE
                    button.setOnClickListener(null)
                    activatedButtons.remove(userIdHash)
                    userButtonMap.remove(userIdHash)
                }
                // Remove user from lists
                scannedUsers.remove(user)
                scannedUserHashes.remove(userIdHash)
            }
        }
    }

    private fun assignButtonToUser(buttonGroup: List<View>, user: User) {
        val userIdHash = user.userIdHash

        // Check if the user already has an assigned button
        val existingButton = userButtonMap[userIdHash]
        if (existingButton != null) {
            // Hide and remove the existing button
            existingButton.visibility = View.GONE
            existingButton.setOnClickListener(null)
            activatedButtons.remove(userIdHash)
            userButtonMap.remove(userIdHash)
        }

        // Find available buttons not already assigned
        val availableButtons = buttonGroup.filter { it !in activatedButtons.values }

        if (availableButtons.isNotEmpty()) {
            val buttonToActivate = availableButtons.first()
            buttonToActivate.visibility = View.VISIBLE
            buttonToActivate.setOnClickListener {
                navigateToProfileActivity(userIdHash)
            }
            activatedButtons[userIdHash] = buttonToActivate
            userButtonMap[userIdHash] = buttonToActivate
        } else {
            Log.w("UpdateButtons", "No available buttons to activate!")
        }
    }


    // 주기적으로 연결 상태를 확인
    private val connectionCheckHandler = Handler(Looper.getMainLooper())

    private fun startConnectionCheck() {
        connectionCheckHandler.postDelayed(object : Runnable {
            override fun run() {
                removeInactiveDevices()
                connectionCheckHandler.postDelayed(this, 2000) // 2초마다 호출
            }
        }, 2000)
    }

    private fun stopConnectionCheck() {
        connectionCheckHandler.removeCallbacksAndMessages(null)
    }


    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        try {
            stopAdvertising()
            stopScanning()
            stopConnectionCheck() // 연결 상태 확인 중지
        } catch (e: Exception) {
            Log.e("MainActivity", "BLE 중지 중 예외 발생: ${e.message}")
        }
    }

    private fun showPermissionSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("권한 필요")
            .setMessage("앱을 사용하려면 모든 권한을 허용해야 합니다. 설정에서 권한을 허용해주세요.")
            .setPositiveButton("설정으로 이동") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun fetchUserInfo(userIdHash: String, rssi: Int) {
        val currentTime = System.currentTimeMillis()

        db.collection("users")
            .whereEqualTo("userIdHash", userIdHash)
            .get()
            .addOnSuccessListener { documents ->
                if (!documents.isEmpty) {
                    val document = documents.first()
                    val nickname = document.getString("nickname") ?: "Unknown"
                    val smallTalk = document.getString("smallTalk") ?: ""
                    val ootd = document.getString("ootd") ?: ""

                    // Update existing user in scannedUsers
                    val index = scannedUsers.indexOfFirst { it.userIdHash == userIdHash }
                    if (index != -1) {
                        val user = scannedUsers[index]
                        scannedUsers[index] = user.copy(
                            nickname = nickname,
                            smallTalk = smallTalk,
                            ootd = ootd,
                            rssi = rssi,
                            lastSeenTimestamp = currentTime
                        )
                        updateButtonsBasedOnRssi(scannedUsers[index])
                    } else {
                        // Should not happen, but handle just in case
                        val user = User(userIdHash, nickname, ootd, smallTalk, rssi, currentTime)
                        scannedUsers.add(user)
                        updateButtonsBasedOnRssi(user)
                    }
                } else {
                    Log.d("Scanner", "No user info found for: $userIdHash")
                }
            }
            .addOnFailureListener { exception ->
                Log.e("Scanner", "Failed to fetch user info: ${exception.message}")
            }
    }


    private fun updateUserRssi(userIdHash: String, rssi: Int) {
        val index = scannedUsers.indexOfFirst { it.userIdHash == userIdHash }
        if (index != -1) {
            val user = scannedUsers[index]
            if (user.rssi != rssi) {
                scannedUsers[index] = user.copy(
                    rssi = rssi,
                    lastSeenTimestamp = System.currentTimeMillis()
                )
                updateButtonsBasedOnRssi(scannedUsers[index])
            }
        }
    }


    // 탑 바와 관련된 menu, layout 불러오기
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_edit_profile -> {
                val intent = Intent(this, ProfileEditActivity::class.java)
                startActivity(intent)
                return true
            }

            R.id.menu_logout -> {
                auth.signOut()
                val intent = Intent(this, LoginActivity::class.java)
                startActivity(intent)
                finish()
                return true
            }

            R.id.action_question -> {
                // AI 추천 메뉴 아이템 클릭 시 AIRecommendationBottomSheetFragment를 다이얼로그 형식으로 띄우기
                showAIRecommendationFragment()
                return true
            }

            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun showAIRecommendationFragment() {
        // AIRecommendationBottomSheetFragment 인스턴스를 생성하고 다이얼로그로 띄우기
        val fragment = AIRecommendationBottomSheetFragment.newInstance()
        fragment.show(supportFragmentManager, fragment.tag)
    }

    private fun navigateToProfileActivity(userIdHash: String) {
        val intent = Intent(this, ProfileActivity::class.java)
        intent.putExtra("userIdHash", userIdHash)
        startActivity(intent)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val acceleration = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
            val currentTime = System.currentTimeMillis()

            if (acceleration > shakeThreshold && currentTime - lastShakeTime > 1000) {
                lastShakeTime = currentTime
                Toast.makeText(this, "흔들기 감지됨! BLE 스캔 시작", Toast.LENGTH_SHORT).show()
                checkPermissionsAndStartScan() // 기존 스캔 메서드 호출
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 센서 정확도 변경 시 처리할 내용 (필요 없으면 비워두기)
    }

}





