package ir.abrova.trace.example

/**
 * Example-only JNI bridge for raising real POSIX signals in native crash tests.
 * Backed by libexample_crash_helper.so (see src/main/cpp/).
 */
internal object CrashHelper {
    init {
        System.loadLibrary("example_crash_helper")
    }

    external fun nativeSigsegv()
    external fun nativeAbort()
}
