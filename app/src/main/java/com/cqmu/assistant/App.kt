package com.cqmu.assistant

import android.app.Application
import com.cqmu.assistant.data.EamsRepository
import com.cqmu.assistant.data.ekta.EktaRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    val repository: EamsRepository by lazy { EamsRepository(this) }
    val ektaRepository: EktaRepository by lazy { EktaRepository(this) }

    override fun onCreate() {
        super.onCreate()
        installCrashCapture()
    }

    /** 未捕获异常经脱敏和限长后写入应用私有目录，供用户主动查看或复制。 */
    private fun installCrashCapture() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val text = buildString {
                    append("时间: ")
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                    append("\n版本: ")
                    runCatching {
                        append(packageManager.getPackageInfo(packageName, 0).versionName)
                    }.onFailure { append("?") }
                    append("\n线程: ").append(thread.name)
                    append("\n系统: Android API ").append(android.os.Build.VERSION.SDK_INT)
                    append("\n\n")
                    append(android.util.Log.getStackTraceString(throwable))
                }
                File(filesDir, "crash_latest.txt").writeText(PrivacySanitizer.crashText(text))
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun crashFile(app: Application): File = File(app.filesDir, "crash_latest.txt")
    }
}
