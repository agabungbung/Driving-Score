package com.hdkia.drivingscore.debug

import ai.pleos.playground.navi.INaviService
import ai.pleos.playground.navi.INaviServiceCallback
import ai.pleos.playground.navi.INaviServiceCallbackV2
import ai.pleos.playground.navi.ResponseParcel
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 내비 원본 JSON 기록 (debug 전용, 2026-11-09).
 *
 * NaviHelper는 내비 앱이 보내는 JSON을 `ignoreUnknownKeys = true`로 읽어, SDK 데이터 클래스에 없는 필드를 버린다.
 * 이 화면은 같은 내비 서비스(`INaviService`)에 콜백을 직접 걸어 **버려지기 전의 원본**을 파일로 남긴다.
 * 기록: files/navi-raw-<시각>.jsonl (한 줄 = {t 단조 ms, wall, cb, a, b}). 위치가 들어가므로 저장소에 올리지 않는다.
 *
 * 실행: `adb shell am start --user 10 -n com.hdkia.drivingscore/.debug.NaviRawProbeActivity`
 * 경로 걸기: 위 명령에 `--ed lat 37.5547 --ed lon 126.9707 --es name 서울역`, 취소: `--ez cancel true`
 *
 * 순서: NaviHelper 초기화(경로 요청용) → 원본 콜백 등록(V1·V2). 같은 패키지로 등록하므로 NaviHelper의 콜백을
 * 대신할 수 있다 — 이 화면에서는 NaviHelper의 이벤트를 쓰지 않는다.
 */
class NaviRawProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var out: File
    private lateinit var status: TextView
    private var helper: NaviHelper? = null
    private var service: INaviService? = null
    private var count = 0
    private val counts = sortedMapOf<String, Int>()

    private val callback = object : INaviServiceCallback.Stub() {
        override fun onResponse(a: String?, b: String?) = record("v1", a, b)
    }
    private val callbackV2 = object : INaviServiceCallbackV2.Stub() {
        override fun onResponse(p: ResponseParcel?) =
            record("v2", p?.type, JSONObject().put("requestId", p?.requestId).put("targetId", p?.targetId)
                .put("targetSdk", p?.targetSdk).put("data", p?.data).toString())
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = INaviService.Stub.asInterface(binder)
            service = s
            note("service connected: $name")
            runCatching { s.registerCallback(callback, packageName) }.onFailure { note("registerCallback 실패: $it") }
            runCatching { s.registerCallbackV2(callbackV2, packageName) }.onFailure { note("registerCallbackV2 실패: $it") }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            note("service disconnected")
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 22f; setPadding(40, 40, 40, 40) }
        setContentView(status)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        out = File(filesDir, "navi-raw-$stamp.jsonl")
        note("기록 파일: ${out.name}")

        val h = NaviHelper(applicationContext)
        helper = h
        h.initialize(
            {
                note("NaviHelper 연결")
                handleIntent(intent)
                bindRaw()
            },
            { e -> note("NaviHelper 실패: $e"); bindRaw() },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun bindRaw() {
        val intent = Intent(NAVI_ACTION).setComponent(ComponentName(NAVI_PACKAGE, NAVI_SERVICE))
        val ok = runCatching { bindService(intent, connection, Context.BIND_AUTO_CREATE) }
        note("bindService: ${ok.getOrNull() ?: ok.exceptionOrNull()}")
        // 조회 요청도 원본으로 본다 (응답이 원본 콜백으로 올 수 있다)
        main.postDelayed({ helper?.run { getCurrentLocationInfo(); getRouteStateInfo(); getTBTInfo(); getDestinationInfo() } }, 3_000)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val h = helper ?: return
        if (intent.getBooleanExtra("cancel", false)) {
            note("경로 취소 요청"); h.cancelRoute(); return
        }
        if (intent.hasExtra("lat") && intent.hasExtra("lon")) {
            val lat = intent.getDoubleExtra("lat", 0.0)
            val lon = intent.getDoubleExtra("lon", 0.0)
            val name = intent.getStringExtra("name") ?: "목적지"
            note("경로 요청: $name ($lat, $lon)")
            h.requestRoute(RouteInfo(lat, lon, null, null, name, "", "", "", RouteOption.RECOMMENDED, "", false))
        }
    }

    private fun record(cb: String, a: String?, b: String?) {
        val line = JSONObject()
            .put("t", SystemClock.elapsedRealtime())
            .put("wall", System.currentTimeMillis())
            .put("cb", cb).put("a", a).put("b", b)
            .toString()
        synchronized(this) {
            out.appendText(line + "\n")
            count++
            val key = "$cb:${a ?: "-"}".take(60)
            counts[key] = (counts[key] ?: 0) + 1
        }
        main.post { status.text = "원본 ${count}건\n" + counts.entries.joinToString("\n") { "${it.key} ${it.value}" } }
    }

    private fun note(msg: String) {
        Log.i(TAG, msg)
        record("note", msg, null)
    }

    override fun onDestroy() {
        runCatching { service?.unregisterCallback(callback) }
        runCatching { service?.unregisterCallbackV2(callbackV2) }
        runCatching { unbindService(connection) }
        helper?.release()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "NaviRawProbe"
        // NaviHelper 상수와 같다 (SDK constants.a: PACKAGE_NAME · NAVI_COMPONENT_NAME · NAVI_ACTION)
        const val NAVI_PACKAGE = "ai.pleos.playground.service.navi"
        const val NAVI_SERVICE = "ai.pleos.playground.service.navi.NaviService"
        const val NAVI_ACTION = "ai.pleos.playground.intent.action.NAVI_START"
    }
}
