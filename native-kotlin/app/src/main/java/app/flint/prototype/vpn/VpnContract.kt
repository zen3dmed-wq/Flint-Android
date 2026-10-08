package app.flint.prototype.vpn

/** Messenger messages work across the separate :vpn process. */
object VpnContract {
    const val ACTION_CONNECT = "app.flint.prototype.vpn.CONNECT"
    const val ACTION_DISCONNECT = "app.flint.prototype.vpn.DISCONNECT"
    const val ACTION_TOGGLE = "app.flint.prototype.vpn.TOGGLE"
    const val EXTRA_CONFIG_FILE = "config_file"
    const val CONFIG_DIRECTORY = "vpn-configs"
    const val MAX_CONFIG_BYTES = 8 * 1024 * 1024
    const val REGISTER = 1
    const val UNREGISTER = 2
    const val REQUEST_STATUS = 3
    const val DISCONNECT = 4
    const val STATUS = 100
    const val STATE = "state"
    const val MESSAGE = "message"
    const val SERVER_NAME = "serverName"
    const val SERVER_ID = "serverId"
    const val RU_DIRECT = "ruDirect"
    const val GENERATION = "generation"
    const val TIMESTAMP = "timestamp"
}
