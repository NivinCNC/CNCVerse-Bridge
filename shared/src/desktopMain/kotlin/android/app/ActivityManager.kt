package android.app

/** ActivityManager — StreamPlay reads device RAM to recommend concurrency. */
class ActivityManager {
    class MemoryInfo {
        @JvmField var availMem: Long = 4L * 1024 * 1024 * 1024
        @JvmField var totalMem: Long = 8L * 1024 * 1024 * 1024
        @JvmField var threshold: Long = 256L * 1024 * 1024
        @JvmField var lowMemory: Boolean = false
    }

    fun getMemoryInfo(outInfo: MemoryInfo?) {
        outInfo?.let {
            it.availMem = Runtime.getRuntime().maxMemory() / 2
            it.totalMem = Runtime.getRuntime().maxMemory()
        }
    }

    fun getRunningAppProcesses(): List<RunningAppProcessInfo> = emptyList()

    class RunningAppProcessInfo {
        @JvmField var pid: Int = 0
        @JvmField var processName: String? = null
    }

    companion object {
        const val RUNNING_PROCESS = 1
    }
}
