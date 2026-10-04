"""Expose safe native startup diagnostics and cancel late permission callbacks."""
from pathlib import Path
import shutil

def apply(root: Path, assets: Path):
    android = root/'client/android/src/org/amnezia/vpn'
    shutil.copy2(assets/'FlintVpnDiagnostics.kt', android/'FlintVpnDiagnostics.kt')
    p = android/'AmneziaActivity.kt'
    s = p.read_text(encoding='utf-8')
    def replace(old, new):
        nonlocal s
        assert s.count(old) == 1, (p.name, old[:100], s.count(old))
        s = s.replace(old, new, 1)
    replace('                            QtAndroidController.onVpnStateChanged(state.ordinal)',
            '                            FlintVpnDiagnostics.protocolState(state.name)\n                            QtAndroidController.onVpnStateChanged(state.ordinal)')
    replace('                            Log.e(TAG, "From VpnService: $error")',
            '                            FlintVpnDiagnostics.error(error)\n                            Log.e(TAG, "From VpnService: $error")')
    replace('                isServiceConnected = true',
            '                isServiceConnected = true\n                FlintVpnDiagnostics.record(FlintVpnDiagnostics.Stage.SERVICE_BOUND)')
    replace('        getVpnProto(vpnConfig)?.let { proto ->',
            '        FlintVpnDiagnostics.record(FlintVpnDiagnostics.Stage.START_SERVICE)\n        getVpnProto(vpnConfig)?.let { proto ->')
    replace('                Log.e(TAG, "Failed to start ${proto.serviceClass.simpleName}: $e")',
            '                FlintVpnDiagnostics.error("foreground start service: ${e.message}")\n                Log.e(TAG, "Failed to start ${proto.serviceClass.simpleName}: $e")')
    # Dismissing the explanatory notification dialog used to lose onChecked and
    # leave the app waiting forever without ever starting the VPN service.
    replace('            .setTitle(R.string.notificationDialogTitle)',
            '''            .setOnCancelListener {
                Prefs.save(PREFS_NOTIFICATION_PERMISSION_ASKED, true)
                onChecked()
            }
            .setTitle(R.string.notificationDialogTitle)''')
    replace('''        mainScope.launch {
            checkVpnPermission {
                checkNotificationPermission {
                    startVpn(vpnConfig)
                }
            }
        }''', '''        val attempt = FlintVpnDiagnostics.begin()
        mainScope.launch {
            if (!FlintVpnDiagnostics.isCurrent(attempt)) return@launch
            FlintVpnDiagnostics.record(FlintVpnDiagnostics.Stage.VPN_PERMISSION)
            checkVpnPermission vpnGranted@ {
                if (!FlintVpnDiagnostics.isCurrent(attempt)) return@vpnGranted
                FlintVpnDiagnostics.record(FlintVpnDiagnostics.Stage.NOTIFICATION_PERMISSION)
                checkNotificationPermission notificationsDone@ {
                    if (!FlintVpnDiagnostics.isCurrent(attempt)) return@notificationsDone
                    startVpn(vpnConfig)
                }
            }
        }''')
    replace('''        Log.v(TAG, "Stop VPN")
        mainScope.launch {''', '''        Log.v(TAG, "Stop VPN")
        FlintVpnDiagnostics.cancel()
        mainScope.launch {''')
    replace('                    Log.w(TAG, "Vpn permission denied")',
            '                    FlintVpnDiagnostics.error("VPN permission denied")\n                    Log.w(TAG, "Vpn permission denied")')
    methods = '''    @Suppress("unused")
    fun resetFlintVpnDiagnostics() = FlintVpnDiagnostics.reset()

    @Suppress("unused")
    fun getFlintVpnDiagnostics(): String = "android.sdk=${Build.VERSION.SDK_INT}\\n" + FlintVpnDiagnostics.report()

    @Suppress("unused")
    fun getFlintVpnStage(): String = FlintVpnDiagnostics.stage()

    @Suppress("unused")
    fun getFlintVpnErrorCode(): String = FlintVpnDiagnostics.code()

'''
    replace('    fun isFlintVpnPermissionGranted(): Boolean =', methods+'    fun isFlintVpnPermissionGranted(): Boolean =')
    # The existing annotation belongs to isFlintVpnPermissionGranted, not the new
    # first method: keep only one annotation on each declaration.
    s = s.replace('    @Suppress("unused")\n    @Suppress("unused")', '    @Suppress("unused")')
    p.write_text(s, encoding='utf-8')
