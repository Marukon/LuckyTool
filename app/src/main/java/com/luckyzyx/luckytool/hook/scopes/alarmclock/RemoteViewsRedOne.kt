package com.luckyzyx.luckytool.hook.scopes.alarmclock

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.XmlResourceParser
import android.widget.RemoteViews
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.toClassOrNull
import com.highcapable.yukihookapi.hook.log.YLog
import org.lsposed.lsparanoid.Obfuscate
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap

/**
 * 红一（红色 1）处理器
 *
 * 策略：优先"就地开关"——解析当前 layout，对其中带 id 的自定义 view 追加 RemoteViews 反射动作，
 * 由宿主在 apply 时反射调用目标 view 上的同名方法；只有探测不到可用方法时才回退到推导出的 layout。
 */
@Obfuscate
object RemoteViewsRedOne {

    private const val TAG = "AlarmClockWidget"

    /** 候选方法名：精确匹配优先，其次退化为小写包含匹配 */
    private val CANDIDATE_METHODS = listOf(
        "setOnePlusRedOneSwitch",
        "setOnePlusRedOne",
        "setRedOne",
        "setRedOneEnabled",
        "setRedOneEnable",
        "setRedOneSwitch",
        "setRedOneStyle",
        "setShowRedOne",
        "setRedMode"
    )

    /** 明确排除的无关方法，不允许被当成命中 */
    private val EXCLUDED_METHODS = listOf("setText", "setClockStyle", "setVisibility")

    /**
     * 宿主 apply 反射动作时（RemoteViews.getMethod）会强制校验 @RemotableViewMethod，
     * 未标注的方法会直接抛 ActionException 打断整次刷新，所以必须提前挡掉。
     */
    private val REMOTE_VIEW_METHOD: Class<*>? by lazy {
        runCatching {
            "android.view.RemotableViewMethod".toClassOrNull()
        }.getOrNull()
    }

    /**
     * 探测缓存：键 = layoutId，值 = 该布局探测出的动作列表
     *
     * 空列表同样会被缓存，表示"这个 layoutId 已解析过且没有可用方法"，用于避免重复解析 XML / 反射探测。
     */
    private val probeCache = ConcurrentHashMap<Int, List<RedOneAction>>()

    /**
     * 一条就地开关动作
     * @param viewId 目标 view 的 android:id
     * @param methodName 目标方法名
     * @param booleanArg 方法唯一参数是否为 boolean（否则按 int 处理）
     */
    private data class RedOneAction(
        val viewId: Int,
        val methodName: String,
        val booleanArg: Boolean
    )

    /**
     * 处理一次 RemoteViews 返回
     * @param context Context
     * @param remoteViews 原方法返回的 RemoteViews（就地开关会直接改写它）
     * @param redMode "1" 强制红一，"2" 强制关闭，其它一律不处理
     * @return 需要替换的 RemoteViews；null 表示不替换（已就地处理或无法处理）
     */
    @SuppressLint("DiscouragedApi", "DEPRECATION")
    fun handle(context: Context, remoteViews: RemoteViews, redMode: String): RemoteViews? {
        val want = when (redMode) {
            "1" -> true
            "2" -> false
            else -> return null
        }
        val layoutName = runCatching {
            context.resources.getResourceEntryName(remoteViews.layoutId)
        }.getOrNull() ?: return null
        //当前布局已经是目标形态，无需处理
        if (layoutName.contains("red") == want) return null
        val applied = applyInPlace(context, remoteViews, want)
        if (applied.isNotEmpty()) {
            YLog.debug(
                "$TAG [$layoutName] 就地开关 want=$want -> " + applied.joinToString(),
                tag = TAG
            )
            return null
        }
        val target = deriveLayoutName(layoutName, want)
        if (target == null) {
            YLog.debug("$TAG [$layoutName] 就地开关未命中，且无法推导目标布局", tag = TAG)
            return null
        }
        val resId = context.resources.getIdentifier(target, "layout", context.packageName)
        if (resId == 0) {
            YLog.debug("$TAG [$layoutName] 就地开关未命中，推导布局 $target 不存在", tag = TAG)
            return null
        }
        YLog.debug("$TAG [$layoutName] 回退替换布局 -> $target", tag = TAG)
        return RemoteViews(context.packageName, resId)
    }

    /**
     * 推导目标 layout 名（原 39 对静态映射的等价规则）
     * @param layoutName 当前 layout 名
     * @param want true 需要红色布局，false 需要普通布局
     * @return 推导出的 layout 名；推导不出返回 null
     */
    fun deriveLayoutName(layoutName: String, want: Boolean): String? {
        if (!want) {
            if (!layoutName.contains("_red_")) return null
            return layoutName.replace("_red_", "_").takeIf { it != layoutName }
        }
        if (layoutName.contains("red") || !layoutName.contains("widget")) return null
        return layoutName.replaceFirst("widget", "red_widget").takeIf { it != layoutName }
    }

    /**
     * 就地追加反射动作
     * @return 真正写入成功的动作
     */
    @SuppressLint("DiscouragedApi", "DEPRECATION")
    private fun applyInPlace(
        context: Context, remoteViews: RemoteViews, want: Boolean
    ): List<RedOneAction> {
        val actions = probe(context, remoteViews.layoutId)
        if (actions.isEmpty()) return emptyList()
        val applied = mutableListOf<RedOneAction>()
        for (action in actions) {
            val ok = runCatching {
                if (action.booleanArg) {
                    remoteViews.setBoolean(action.viewId, action.methodName, want)
                } else {
                    remoteViews.setInt(action.viewId, action.methodName, if (want) 1 else 0)
                }
            }.isSuccess
            if (ok) applied += action
        }
        return applied
    }

    /**
     * 取某个 layoutId 的探测结果（带缓存）
     */
    private fun probe(context: Context, layoutId: Int): List<RedOneAction> {
        probeCache[layoutId]?.let { return it }
        val probed = runCatching { probeLayout(context, layoutId) }.getOrDefault(emptyList())
        return probeCache.putIfAbsent(layoutId, probed) ?: probed
    }

    /**
     * 解析 layout，收集可用的就地开关动作
     */
    @SuppressLint("DiscouragedApi")
    private fun probeLayout(context: Context, layoutId: Int): List<RedOneAction> {
        val actions = mutableListOf<RedOneAction>()
        val parser = context.resources.getLayout(layoutId)
        try {
            var type = parser.eventType
            while (type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    val tag = parser.name
                    if (tag != null && isCustomView(tag)) {
                        val viewId = readAndroidId(parser)
                        if (viewId != 0) collectActions(context, tag, viewId, actions)
                    }
                }
                type = parser.next()
            }
        } finally {
            runCatching { parser.close() }
        }
        return actions
    }

    /**
     * 是否是应用自定义 view 标签（带包名且不是框架/三方库前缀）
     */
    private fun isCustomView(tag: String): Boolean =
        tag.contains('.') &&
                !tag.startsWith("android.") &&
                !tag.startsWith("androidx.") &&
                !tag.startsWith("java.")

    /**
     * 读取标签上的 android:id（取不到返回 0）
     */
    @SuppressLint("DiscouragedApi")
    private fun readAndroidId(parser: XmlResourceParser): Int {
        for (index in 0 until parser.attributeCount) {
            if (parser.getAttributeNameResource(index) != android.R.attr.id) continue
            val reference = parser.getAttributeResourceValue(index, 0)
            if (reference != 0) return reference
            return parser.getAttributeIntValue(index, 0)
        }
        return 0
    }

    /**
     * 用 KavaRef 的筛选条件探测目标 view 上的红一开关方法，写入 actions
     *
     * 类加载走 toClassOrNull、方法筛选走 resolve().method { }、注解筛选走 annotations()，
     * 之后只通过 MethodResolver.self 读取 name 与 parameterTypes 构造动作。
     */
    private fun collectActions(
        context: Context, className: String, viewId: Int, actions: MutableList<RedOneAction>
    ) {
        //拿不到注解类型就无法确认宿主能否执行该动作，宁可放弃就地开关
        val remoteViewMethod = REMOTE_VIEW_METHOD ?: return
        val methods = runCatching {
            className.toClassOrNull(context.classLoader)?.resolve()?.method {
                //旧实现取 clazz 的全部方法（public，含继承），此处一并搜索父类与接口，否则声明在父类上的 setter 会漏掉
                superclass(true)
                parameterCount = 1
                modifiers(Modifiers.PUBLIC)
                parameters { types ->
                    types.size == 1 &&
                            (types[0] == classOf<Boolean>() ||
                                    types[0] == classOf<Int>())
                }
                //注意：KavaRef 的 annotations(...) 是"注解集合完全相等"（MemberProcessor.kt:526-536 先比 size 再比集合），
                //不是"是否存在目标注解"（包含）语义；因此只有仅带 @RemotableViewMethod 一个运行期注解的方法会被命中，
                //多挂其它运行期注解的方法会漏掉 —— 漏掉只会退回 layout 替换兜底，不会崩。
                annotations(remoteViewMethod)
                name { n ->
                    val lower = n.lowercase()
                    //先排除明确无关的方法，再按候选名精确/包含匹配，最后退化为同时含 red 与 one
                    EXCLUDED_METHODS.none { it.lowercase() == lower } &&
                            (CANDIDATE_METHODS.any { it == n } ||
                                    CANDIDATE_METHODS.any { lower.contains(it.lowercase()) } ||
                                    (lower.contains("red") && lower.contains("one")))
                }
            }
        }.getOrNull() ?: return
        for (resolver in methods) {
            val method = runCatching { resolver.self }.getOrNull() ?: continue
            val paramType = method.parameterTypes[0]
            val booleanArg = paramType == classOf<Boolean>()
            val name = method.name
            if (actions.any { it.viewId == viewId && it.methodName == name }) continue
            actions += RedOneAction(viewId, name, booleanArg)
            YLog.debug(
                "$TAG 探测命中 [$className] -> $name(" + paramType.simpleName + ") #$viewId",
                tag = TAG
            )
        }
    }
}
