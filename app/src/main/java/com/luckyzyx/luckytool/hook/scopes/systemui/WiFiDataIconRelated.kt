package com.luckyzyx.luckytool.hook.scopes.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.VariousClass
import com.highcapable.kavaref.extension.classOf
import com.highcapable.yukihookapi.extension.kavaref.hook
import com.highcapable.yukihookapi.extension.kavaref.intercept
import com.highcapable.yukihookapi.extension.kavaref.toClass
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.luckyzyx.luckytool.hook.utils.FlowUtils
import com.luckyzyx.luckytool.hook.utils.sysui.AbsSettingsValueProxyUtils
import com.luckyzyx.luckytool.hook.utils.sysui.WifiStandardIconDrawable
import com.luckyzyx.luckytool.hook.utils.sysui.WifiUtils
import com.luckyzyx.luckytool.utils.ModulePrefs
import com.luckyzyx.luckytool.utils.getOSVersionCode
import org.lsposed.lsparanoid.Obfuscate
import java.lang.ref.WeakReference

@Obfuscate
object WiFiDataIconRelated : YukiBaseHooker() {
    override fun onHook() {
        when (getOSVersionCode) {
            in 34..Int.MAX_VALUE -> loadHooker(WiFiDataIcon)
            else -> loadHooker(WiFiDataIconV14)
        }
    }

    @Obfuscate
    object WiFiDataIcon : YukiBaseHooker() {

        private const val WIFI_LEFT_ID_NAME = "wifi_left"
        private const val TECHNICAL_ICON_SWITCH = "wifi_use_technical_standard_icons_switch_on"
        private const val ICON_WIDTH_DP = 22f
        private const val ICON_HEIGHT_DP = 17f
        private const val STANDARD_GATE_CACHE_MS = 3000L

        @Volatile
        private var wifiStandard = ScanResult.WIFI_STANDARD_UNKNOWN

        @Volatile
        private var callbackRegistered = false

        @Volatile
        private var wifiLeftId = 0

        @Volatile
        private var wifiLeftIdResolved = false

        // 双 WiFi / PassPoint / 系统技术标准图标开关的判定要走反射和设置查询，按时间窗口缓存结果
        @Volatile
        private var gateCheckedAt = 0L

        @Volatile
        private var gateAllowed = false

        // 标准号是异步拿到的，拿到后补画一次，避免宿主只贴一次图时错过
        private var lastWifiLeftView: WeakReference<ImageView>? = null

        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

        override fun onHook() {
            val removeInout = preferences(ModulePrefs).getBoolean("remove_wifi_data_inout", false)
            val forceStandard =
                preferences(ModulePrefs).getBoolean("force_display_wifi_standard", false)

            //Source OplusWifiViewModel
            if (false) "com.oplus.systemui.statusbar.pipeline.wifi.ui.viewmodel.OplusWifiViewModel".toClass()
                .resolve().apply {
                    firstMethod {
                        name = "getWifiActivityResId"
                        returnType = "kotlinx.coroutines.flow.StateFlow"
                    }.hook {
                        after {
                            if (!removeInout) return@after
                            if (result == null) return@after

                            val originalValue =
                                FlowUtils(hostClassLoader!!).getValue<Int>(result!!) ?: -1
                            if (originalValue <= 0) return@after

                            result = FlowUtils(hostClassLoader!!).let {
                                val mutableStateFlow = it.MutableStateFlow(-1)
                                    ?: return@after
                                it.asStateFlow(mutableStateFlow) ?: return@after
                            }
                        }
                    }
                }

            if (!forceStandard) return

            //Source ImageView
            // 旧实现把 OplusWifiViewModel.getWifiLeftResId 的 StateFlow<Int> 换成 StateFlow<Drawable>，
            // 下游按 Int 取资源 id 时 as Number 抛 ClassCastException 打死 SystemUI。
            // 现在只在宿主贴图时换图：命中宿主 wifi_left 就把图标换成本地绘制的数字，不碰任何流类型
            val imageViewClass = "android.widget.ImageView".toClass().resolve()

            //宿主直接贴资源 id（C17 的 OplusWifiSignalExImpl 给的就是资源 id）
            imageViewClass.firstMethod {
                name = "setImageResource"; parameters(Int::class)
            }.intercept {
                val view = instance as? ImageView ?: return@intercept proceed()
                val drawable = digitDrawableFor(view) ?: return@intercept proceed()
                //跳过宿主原图直接贴自绘数字，贴图会再进 setImageDrawable，那里按类型短路
                view.setImageDrawable(drawable)
                null
            }

            //宿主自己取 Drawable 再贴图
            imageViewClass.firstMethod {
                name = "setImageDrawable"; parameters(Drawable::class)
            }.intercept {
                val view = instance as? ImageView ?: return@intercept proceed()
                val incoming = arg<Drawable>(0)
                if (incoming is WifiStandardIconDrawable) return@intercept proceed()
                //宿主清空图标（未连接等）时保持原样
                if (incoming == null) return@intercept proceed()
                val drawable = digitDrawableFor(view) ?: return@intercept proceed()
                proceed(arrayOf<Any?>(drawable))
            }
        }

        private fun digitDrawableFor(view: ImageView): Drawable? {
            if (view.id == 0 || view.id != resolveWifiLeftId(view.context)) return null
            lastWifiLeftView = WeakReference(view)
            ensureWifiCallback(view.context)
            val digit = digitOf(wifiStandard) ?: return null
            if (!isStandardIconEnabled(view.context)) return null
            return buildDigitDrawable(view.context, digit)
        }

        private fun resolveWifiLeftId(context: Context): Int {
            if (wifiLeftIdResolved) return wifiLeftId
            wifiLeftIdResolved = true
            wifiLeftId = context.resources.getIdentifier(WIFI_LEFT_ID_NAME, "id", packageName)
            if (wifiLeftId == 0) YLog.warn("wifi_left not found in " + packageName + " resources")
            return wifiLeftId
        }

        private fun digitOf(standard: Int): Char? = when (standard) {
            ScanResult.WIFI_STANDARD_11N -> '4'
            ScanResult.WIFI_STANDARD_11AC -> '5'
            ScanResult.WIFI_STANDARD_11AX -> '6'
            ScanResult.WIFI_STANDARD_11BE -> '7'
            else -> null
        }

        private fun buildDigitDrawable(context: Context, digit: Char): Drawable {
            val density = context.resources.displayMetrics.density
            return WifiStandardIconDrawable(
                digit = digit,
                widthPx = (ICON_WIDTH_DP * density).toInt(),
                heightPx = (ICON_HEIGHT_DP * density).toInt()
            )
        }

        private fun isStandardIconEnabled(context: Context): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (now - gateCheckedAt < STANDARD_GATE_CACHE_MS) return gateAllowed
            gateCheckedAt = now
            gateAllowed = runCatching {
                val wifiUtils = WifiUtils(hostClassLoader)
                if (wifiUtils.isDualWifiConnected(context) || wifiUtils.isPassPointAp(context)) {
                    return@runCatching false
                }
                AbsSettingsValueProxyUtils(hostClassLoader!!)
                    .getGlobalIntValue(context, TECHNICAL_ICON_SWITCH, 0) == 1
            }.getOrDefault(false)
            return gateAllowed
        }

        @SuppressLint("MissingPermission")
        private fun ensureWifiCallback(context: Context) {
            if (callbackRegistered) return
            callbackRegistered = true
            runCatching {
                val manager = (context.applicationContext ?: context)
                    .getSystemService(classOf<ConnectivityManager>()) ?: return
                val request = NetworkRequest.Builder().apply {
                    addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }.build()
                manager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network, networkCapabilities: NetworkCapabilities
                    ) {
                        val info = networkCapabilities.transportInfo as? WifiInfo ?: return
                        val isPrimary = info.asResolver().firstMethod {
                            name = "isPrimary"; emptyParameters()
                        }.invoke<Boolean>() ?: false
                        if (!isPrimary) return
                        val standard = info.wifiStandard
                        if (wifiStandard == standard) return
                        wifiStandard = standard
                        repaintWifiLeftIcon(standard)
                    }
                }, mainHandler)
            }.onFailure { YLog.error("register wifi network callback failed", it) }
        }

        private fun repaintWifiLeftIcon(standard: Int) {
            val digit = digitOf(standard) ?: return
            val view = lastWifiLeftView?.get() ?: return
            mainHandler.post {
                if (view.drawable == null) return@post
                if (!isStandardIconEnabled(view.context)) return@post
                view.setImageDrawable(buildDigitDrawable(view.context, digit))
            }
        }
    }

    @Obfuscate
    object WiFiDataIconV14 : YukiBaseHooker() {
        override fun onHook() {
            val removeInout = preferences(ModulePrefs).getBoolean("remove_wifi_data_inout", false)

            //Source OplusStatusBarWifiView
            VariousClass(
                "com.oplusos.systemui.statusbar.OplusStatusBarWifiView",
                "com.oplus.systemui.statusbar.phone.signal.OplusStatusBarWifiViewExImpl"
            ).toClass().resolve().apply {
                firstMethod { name = "initViewState" }.hook {
                    after {
                        if (!removeInout) return@after
                        firstField { name = "mWifiActivity" }.of(instance).get<View>()?.isVisible =
                            false
                    }
                }
                firstMethod { name = "updateState" }.hook {
                    after {
                        if (!removeInout) return@after
                        firstField { name = "mWifiActivity" }.of(instance).get<View>()?.isVisible =
                            false
                    }
                }
            }
        }
    }
}
