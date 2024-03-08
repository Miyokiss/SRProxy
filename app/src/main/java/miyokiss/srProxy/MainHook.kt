package miyokiss.srProxy

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.res.XModuleResources
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import com.github.kyuubiran.ezxhelper.init.EzXHelperInit
import com.github.kyuubiran.ezxhelper.utils.findConstructor
import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.findMethodOrNull
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import miyokiss.srProxy.R
import java.util.regex.Pattern


/**
 * 主要的hook类，负责加载和处理自定义服务器地址的功能。
 */
class MainHook : IXposedHookLoadPackage, IXposedHookZygoteInit {
    // 正则表达式，用于匹配需要替换的URL
    private val regex = Pattern.compile("http(s|)://.*?\\.(hoyoverse|mihoyo|bhsr)\\.com")
    // 服务器地址变量
    private lateinit var server: String
    // 是否强制使用自定义URL
    private var forceUrl = false
    // 模块的路径
    private lateinit var modulePath: String
    // 模块的资源
    private lateinit var moduleRes: XModuleResources

    /**
     * Zygote初始化时调用的方法，用于初始化模块路径和资源。
     *
     * @param startupParam 启动参数
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
        moduleRes = XModuleResources.createInstance(modulePath, null)
    }

    /**
     * 加载包时调用的方法，用于hook相关的应用程序方法。
     *
     * @param lpparam 包的加载参数
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 仅处理特定的应用程序包名
        if (lpparam.packageName != "com.miHoYo.hkrpgcb") return
        // 初始化辅助工具
        EzXHelperInit.initHandleLoadPackage(lpparam)
        // SSL hook
        sslHook(lpparam)
        // 初始化自定义服务器地址设置
        hook()
        // 在ComboSDKActivity创建时显示服务器选择对话框
        findMethod("com.mihoyo.combosdk.ComboSDKActivity") { name == "onCreate" }.hookBefore { param ->
            val context = param.thisObject as Activity
            // 从SharedPreferences读取配置
            val sp = context.getSharedPreferences("serverConfig", 0)
            forceUrl = sp.getBoolean("forceUrl", false)
            server = sp.getString("serverip", "") ?: ""
            // 显示对话框
            AlertDialog.Builder(context).apply {
                setCancelable(false)
                setTitle(moduleRes.getString(R.string.SelectServer))
                setMessage(moduleRes.getString(R.string.Tips))
                setView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    // 添加服务器地址输入框
                    addView(EditText(context).apply {
                        hint = "http(s)://server.com:1234"
                        val str = sp.getString("serverip", "") ?: ""
                        setText(str.toCharArray(), 0, str.length)
                        // 监听地址变化并保存
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(p0: CharSequence, p1: Int, p2: Int, p3: Int) {}
                            override fun onTextChanged(p0: CharSequence, p1: Int, p2: Int, p3: Int) {}

                            @SuppressLint("CommitPrefEdits")
                            override fun afterTextChanged(p0: Editable) {
                                sp.edit().run {
                                    putString("serverip", p0.toString())
                                    apply()
                                }
                            }
                        })
                    })
                    // 添加强制使用自定义地址开关
                    addView(Switch(context).apply {
                        text = moduleRes.getString(R.string.ForcedMode)
                        isChecked = sp.getBoolean("forceUrl", false)
                        // 监听开关变化并保存配置
                        setOnClickListener {
                            sp.edit().run {
                                putBoolean("forceUrl", (it as Switch).isChecked)
                                apply()
                            }
                            forceUrl = (it as Switch).isChecked
                        }
                    })
                })
                // 确定按钮：应用自定义服务器地址
                setNegativeButton(moduleRes.getString(R.string.CustomServer)) { _, _ ->
                    val ip = sp.getString("serverip", "") ?: ""
                    if (ip == "") {
                        Toast.makeText(context, moduleRes.getString(R.string.ServerAddressError), Toast.LENGTH_LONG).show()
                        context.finish()
                    } else {
                        server = ip
                        forceUrl = true
                    }
                }
                // 重置按钮：使用官方服务器地址
                setNeutralButton(moduleRes.getString(R.string.OfficialServer)) { _, _ ->
                    forceUrl = false
                    server = ""
                }
            }.show()
        }
    }

    /**
     * SSL相关hook，用于信任所有证书。
     *
     * @param lpparam 包的加载参数
     */
    private fun sslHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook OkHttp的hostname验证和证书固定pinning以允许所有证书
        findMethodOrNull("com.combosdk.lib.third.okhttp3.internal.tls.OkHostnameVerifier") { name == "verify" }?.hookBefore {
            it.result = true
        }
        findMethodOrNull("com.combosdk.lib.third.okhttp3.CertificatePinner") { name == "check" && parameterTypes[0] == String::class.java && parameterTypes[1] == List::class.java }?.hookBefore {
            it.result = null
        }
        // 启用JustTrustMe库以跳过证书验证
        JustTrustMe().hook(lpparam)
    }

    /**
     * Hook相关网络请求地址，以替换为自定义服务器地址。
     */
    private fun hook() {
        // Hook MiHoYoWebview和OkHttp的请求地址
        findMethod("com.miHoYo.sdk.webview.MiHoYoWebview") { name == "load" && parameterTypes[0] == String::class.java && parameterTypes[1] == String::class.java }.hookBefore {
            replaceUrl(it, 1)
        }

        findMethod("okhttp3.HttpUrl") { name == "parse" && parameterTypes[0] == String::class.java }.hookBefore {
            replaceUrl(it, 0)
        }
        findMethod("com.combosdk.lib.third.okhttp3.HttpUrl") { name == "parse" && parameterTypes[0] == String::class.java }.hookBefore {
            replaceUrl(it, 0)
        }

        // Hook Gson的JSON解析，以处理API请求地址
        findMethod("com.google.gson.Gson") { name == "fromJson" && parameterTypes[0] == String::class.java && parameterTypes[1] == java.lang.reflect.Type::class.java }.hookBefore {
            replaceUrl(it, 0)
        }
        // Hook URL类的构造函数，以处理通用的网页请求地址
        findConstructor("java.net.URL") { parameterTypes[0] == String::class.java }.hookBefore {
            replaceUrl(it, 0)
        }
        // Hook okhttp和com.combosdk.lib.third.okhttp3的Request.Builder的url方法，以处理请求地址
        findMethod("com.combosdk.lib.third.okhttp3.Request\$Builder") { name == "url" && parameterTypes[0] == String::class.java }.hookBefore {
            replaceUrl(it, 0)
        }
        findMethod("okhttp3.Request\$Builder") { name == "url" && parameterTypes[0] == String::class.java }.hookBefore {
            replaceUrl(it, 0)
        }
    }

    /**
     * 替换URL中的匹配部分为自定义服务器地址。
     *
     * @param method 方法参数
     * @param argsIndex URL参数在method.args中的索引
     */
    private fun replaceUrl(method: XC_MethodHook.MethodHookParam, args: Int) {
        // 仅在强制使用自定义地址且已初始化服务器地址时处理
        if (!forceUrl || !this::server.isInitialized || server == "") return
        // 日志记录原始URL
        if (BuildConfig.DEBUG) XposedBridge.log("old: " + method.args[args].toString())
        // 匹配并替换URL
        val m = regex.matcher(method.args[args].toString())
        if (m.find()) {
            method.args[args] = m.replaceAll(server)
        }
        // 日志记录替换后的URL
        if (BuildConfig.DEBUG) XposedBridge.log("new: " + method.args[args].toString())
    }
}