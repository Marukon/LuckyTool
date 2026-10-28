package com.luckyzyx.luckytool.hook.scopes.alarmclock

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews
import androidx.core.graphics.toColorInt
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.classOf
import com.highcapable.yukihookapi.extension.kavaref.intercept
import com.highcapable.yukihookapi.extension.kavaref.toClass
import com.highcapable.yukihookapi.extension.kavaref.toClassOrNull
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.luckyzyx.luckytool.utils.DexkitUtils.checkDataList
import com.luckyzyx.luckytool.utils.ModulePrefs
import com.luckyzyx.luckytool.utils.getOSVersionCode
import com.luckyzyx.luckytool.utils.safeOfNull
import org.lsposed.lsparanoid.Obfuscate
import org.luckypray.dexkit.DexKitBridge

@Obfuscate
class AlarmClockWidget(val dexKitBridge: DexKitBridge) : YukiBaseHooker() {

    companion object {
        private lateinit var redMode: String

        private val BaseClockWidget = "com.coloros.widget.smallweather.BaseClockWidget"
        private val OnePlusWidget = "com.coloros.widget.smallweather.OnePlusWidget"
//        val OppoWeather = "com.coloros.widget.smallweather.OppoWeather"
//        val OppoWeatherSingle = "com.coloros.widget.smallweather.OppoWeatherSingle"
//        val OppoWeatherVertical = "com.coloros.widget.smallweather.OppoWeatherVertical"
//        val OppoWeatherMultiVertical = "com.coloros.widget.smallweather.OppoWeatherMultiVertical"
//        val RealmeWeather = "com.coloros.widget.smallweather.RealmeWeather"
//        val OxygenWeatherSingle = "com.coloros.widget.smallweather.OxygenWeatherSingle"

        fun setCharRedOne(format: CharSequence): CharSequence {
            val sp = SpannableStringBuilder(format)
            val length = if (format.contains(":")) format.toString().substringBefore(":").length
            else if (format.contains("\u2236")) format.toString().substringBefore("\u2236").length
            else format.length
            for (i in 0 until length) {
                if (format[i].toString() == "1") {
                    val colorRes = "#c41442".toColorInt()
                    sp.setSpan(ForegroundColorSpan(colorRes), i, i + 1, 34)
                }
            }
            return sp
        }

        /**
         * 新版时钟V14+处理RemoteViews
         *
         * 优先就地往返回的 RemoteViews 里追加反射动作（不换 layout），
         * 探测不到可用方法时才回退到推导出的 layout。
         * @receiver Class<*>
         */
        fun YukiBaseHooker.hookBaseClock(clazz: Class<Any>) {
            clazz.resolve().apply {
                method {
                    emptyParameters()
                    returnType = RemoteViews::class
                }.forEach {
                    it.intercept {
                        //先跑原逻辑，避免任何探测异常影响小组件刷新
                        val raw = proceed()
                        val remoteViews = raw as? RemoteViews ?: return@intercept raw
                        //跟随系统：零动作
                        if (redMode == "0") return@intercept raw
                        val context = safeOfNull {
                            (field { type = Context::class }.firstOrNull()
                                ?: field { type = Context::class; superclass() }.firstOrNull())
                                ?.of(instance)?.get<Context>()
                        } ?: return@intercept raw
                        //就地开关命中时返回 null（remoteViews 已被直接改写），只有兜底替换才返回新实例
                        safeOfNull {
                            RemoteViewsRedOne.handle(context, remoteViews, redMode)
                        } ?: return@intercept raw
                    }
                }
            }
        }
    }


    override fun onHook() {
        //ColorOS 17 (40 -> c17.0.0) 起系统自带红一开关，本模块不再接管，不注册任何 hook
        if (getOSVersionCode >= 40) return

        redMode = preferences(ModulePrefs).getString("alarmclock_widget_redone_mode", "0")
        dataChannel.wait<String>("alarmclock_widget_redone_mode") { redMode = it }

        val onePlusWidget = OnePlusWidget.toClassOrNull()?.resolve() ?: return
        when {
            onePlusWidget.firstMethodOrNull {
                parameters(String::class, String::class)
                returnType = CharSequence::class
            } != null -> loadHooker(AlarmClock12)

            onePlusWidget.firstMethodOrNull {
                returnType = RemoteViews::class
            } != null -> loadHooker(AlarmClock130(dexKitBridge))

            else -> {
                val baseClockWidget = BaseClockWidget.toClassOrNull()
                if (baseClockWidget != null) loadHooker(BaseAlarmClock14)
                else loadHooker(BaseAlarmClock15(dexKitBridge))
            }
        }
    }

    private class BaseAlarmClock15(val dexKitBridge: DexKitBridge) : YukiBaseHooker() {
        override fun onHook() {
            //Source BaseClockWidget
            //Source OnePlusWidget / OppoWeather / OppoWeatherSingle / OppoWeatherVertical
            dexKitBridge.findClass {
                matcher {
                    fields {
                        addForType(classOf<Class<*>>())
                        addForType(classOf<Context>())
                        addForType(classOf<Bitmap>())
                        addForType(classOf<Boolean>())
                        addForType(classOf<Int>())
                    }
                    methods {
                        add {
                            paramCount(0)
                            returnType(classOf<Int>())
                        }
                        add {
                            paramTypes(classOf<RemoteViews>(), classOf<Int>(), classOf<String>())
                            usingStrings("setTimeZone")
                        }
                        add {
                            paramTypes(
                                classOf<RemoteViews>(),
                                classOf<Boolean>(),
                                classOf<Boolean>()
                            )
//                            usingStrings(
//                                "com.oplus.widget.smallweather.WEATHER_CLICK",
//                                "com.oplus.widget.smallweather.RESIDENT_CITY_CLICK"
//                            )
                        }
                        add {
                            paramTypes(
                                classOf<RemoteViews>(),
                                classOf<Int>(),
                                classOf<CharSequence>()
                            )
                            usingStrings("setFormat24Hour", "setFormat12Hour")
                        }
                        add {
                            paramTypes(classOf<RemoteViews>())
                            usingStrings("com.oplus.widget.smallweather.REFRESH_CLICK")
                        }
                    }
                }
            }.apply {
                checkDataList("AlarmClock145")
                hookBaseClock(single().name.toClass())
            }
        }
    }

    private object BaseAlarmClock14 : YukiBaseHooker() {
        override fun onHook() {
            //Source BaseClockWidget
            hookBaseClock(BaseClockWidget.toClass())
        }
    }

    private class AlarmClock130(val dexKitBridge: DexKitBridge) : YukiBaseHooker() {
        override fun onHook() {
            //OnePlusWidget setTextViewText -> local_hour_txt -> SpannableStringBuilder -> CharSequence
            dexKitBridge.findClass {
                matcher {
                    fields {
                        addForType(classOf<Boolean>())
                        addForType(classOf<Handler>())
                    }
                    methods {
                        add { returnType(classOf<Boolean>()) }
                        add { returnType(classOf<Handler>()) }
                        add { paramTypes(classOf<Context>()) }
                        add { paramTypes(classOf<Context>(), classOf<String>()) }
                        add {
                            paramTypes(
                                classOf<Context>(),
                                classOf<String>(),
                                classOf<String>()
                            )
                        }
                    }
                }
            }.apply {
                checkDataList("AlarmClock13")
                single().name.toClass().apply {
                    resolve().method {
                        parameters { it[0] == classOf<Context>() && it[1] == classOf<String>() }
                        parameterCount { it in 2..3 }
                    }.forEach {
                        it.intercept {
                            if (redMode == "0") return@intercept proceed()
                            val raw = proceed()
                            val text = raw as? CharSequence ?: return@intercept raw
                            when (redMode) {
                                "1" -> setCharRedOne(text)
                                "2" -> text.toString()
                                else -> raw
                            }
                        }
                    }
                }
            }
        }
    }

    private object AlarmClock12 : YukiBaseHooker() {
        override fun onHook() {
            //Source OnePlusWidget
            "com.coloros.widget.smallweather.OnePlusWidget".toClass().resolve().apply {
                firstMethod {
                    parameters(String::class, String::class)
                    returnType = CharSequence::class
                }.intercept {
                    if (redMode == "0") return@intercept proceed()
                    val raw = proceed()
                    val text = raw as? CharSequence ?: return@intercept raw
                    when (redMode) {
                        "1" -> setCharRedOne(text)
                        "2" -> text.toString()
                        else -> raw
                    }
                }
            }
        }
    }
}
