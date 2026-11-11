package com.spizganed.quickbuds.bluetooth

/** The words and text of the connect diagnostic log lines. No Android types, so the JVM tests cover it. */
object ConnectDiagnostics {

    /** The [android.bluetooth.BluetoothDevice] bond state as a word. */
    fun bondStateWord(state: Int): String = when (state) {
        10 -> "none"
        11 -> "bonding"
        12 -> "bonded"
        else -> "unknown"
    }

    /** [uuids] as one short comma-separated list, or "none". */
    fun formatUuids(uuids: List<String>): String =
        if (uuids.isEmpty()) "none" else uuids.joinToString(", ") { it.lowercase() }

    /**
     * Whether a failed connect asks for a fresh UUID list. It asks on the first failed try of a
     * connect cycle only, and not while an earlier answer still waits.
     */
    fun shouldFetchFreshUuids(reconnectAttempts: Int, refreshPending: Boolean): Boolean =
        reconnectAttempts == 0 && !refreshPending

    /**
     * Whether another device or app holds the earbuds. Every socket try failed fast with `read ret: -1`.
     * A down link times out after about 14 s instead, and a busy channel fails fast with another text
     * (reports #22, #34, #90). Each pair is the error text and the time of that try in ms.
     */
    fun heldByOther(tries: List<Pair<String?, Long>>): Boolean =
        tries.size >= 2 && tries.all { (text, ms) -> text?.contains("read ret: -1") == true && ms < FAST_FAIL_MS }

    private const val FAST_FAIL_MS = 1500L

    /**
     * `android.bluetooth.BluetoothSocketException` constant names, by error code.
     * Codes 0..20 are API 34 (android-14.0.0_r1). Code 21 is in later Android 14 builds
     * (android-14.0.0_r75) and in main.
     */
    private val SOCKET_ERRORS = mapOf(
        0 to "UNSPECIFIED",
        1 to "L2CAP_UNKNOWN",
        2 to "L2CAP_ACL_FAILURE",
        3 to "L2CAP_CLIENT_SECURITY_FAILURE",
        4 to "L2CAP_INSUFFICIENT_AUTHENTICATION",
        5 to "L2CAP_INSUFFICIENT_AUTHORIZATION",
        6 to "L2CAP_INSUFFICIENT_ENCRYPT_KEY_SIZE",
        7 to "L2CAP_INSUFFICIENT_ENCRYPTION",
        8 to "L2CAP_INVALID_SOURCE_CID",
        9 to "L2CAP_SOURCE_CID_ALREADY_ALLOCATED",
        10 to "L2CAP_UNACCEPTABLE_PARAMETERS",
        11 to "L2CAP_INVALID_PARAMETERS",
        12 to "L2CAP_NO_RESOURCES",
        13 to "L2CAP_NO_PSM_AVAILABLE",
        14 to "L2CAP_TIMEOUT",
        15 to "BLUETOOTH_OFF_FAILURE",
        16 to "SOCKET_MANAGER_FAILURE",
        17 to "SOCKET_CLOSED",
        18 to "SOCKET_CONNECTION_FAILURE",
        19 to "NULL_DEVICE",
        20 to "RPC_FAILURE",
        21 to "UNIX_FILE_SOCKET_CREATION_FAILURE"
    )

    fun socketErrorName(code: Int): String? = SOCKET_ERRORS[code]

    /** The socket error as a log line: the number, then the SDK constant name when the SDK has one. */
    fun socketErrorLine(code: Int): String =
        socketErrorName(code)?.let { "Socket error code: $code ($it)" } ?: "Socket error code: $code"
}
