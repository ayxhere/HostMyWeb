package com.imayx.host.node

/**
 * Thin JNI layer over the embedded Node.js runtime (digidem libnode.so, placed in
 * jniLibs/arm64-v8a by CI). The backend (AyX Group API, SQLite build) runs inside
 * the app process — no Termux, no external Node binary needed.
 *
 * [available] is false when the native libraries aren't bundled (e.g. a local
 * build without the CI libnode.so step) — callers must check it and degrade
 * gracefully instead of calling [nativeStart].
 *
 * R8 is currently disabled for this app; if it is ever enabled, keep:
 * `-keepclasseswithmembernames class com.imayx.host.node.NodeBridge { native <methods>; }`
 */
object NodeBridge {
    val available: Boolean = try {
        System.loadLibrary("node")        // digidem libnode.so (from CI)
        System.loadLibrary("node_bridge") // our JNI glue (CMake)
        true
    } catch (e: UnsatisfiedLinkError) {
        android.util.Log.w("AYX_NODE", "embedded node not bundled: ${e.message}")
        false
    }

    external fun nativeSetenv(key: String, value: String)

    /** Blocks for the life of the node process — always call on a background thread. */
    external fun nativeStart(args: Array<String>, workdir: String): Int
}
