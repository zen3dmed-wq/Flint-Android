"""Bound Binder payloads, bind reliably, and retain early service errors."""
from pathlib import Path
import shutil


def apply(root: Path, assets: Path):
    android = root / 'client/android/src/org/amnezia/vpn'
    shutil.copy2(assets / 'FlintVpnConfigTransport.kt', android / 'FlintVpnConfigTransport.kt')

    def edit(name, old, new):
        p = android / name
        text = p.read_text(encoding='utf-8')
        assert text.count(old) == 1, (name, old[:100], text.count(old))
        p.write_text(text.replace(old, new, 1), encoding='utf-8')

    a = 'AmneziaActivity.kt'
    # Compression occurs on a worker, after consent, with cancellation checked
    # again before any service is started. No profile is sent through Binder raw.
    edit(a, 'import kotlinx.coroutines.launch',
         'import kotlinx.coroutines.launch\nimport kotlinx.coroutines.withContext')
    edit(a, '                    startVpn(vpnConfig)', '                    startVpn(vpnConfig, attempt)')
    edit(a, '    private fun startVpn(vpnConfig: String) {', '''    private fun startVpn(vpnConfig: String, attempt: Long) {
        mainScope.launch {
            if (!FlintVpnDiagnostics.isCurrent(attempt)) return@launch
            val packed = try {
                withContext(Dispatchers.Default) { FlintVpnConfigTransport.encode(vpnConfig) }
            } catch (e: Exception) {
                if (e is java.util.concurrent.CancellationException) throw e
                if (FlintVpnDiagnostics.isCurrent(attempt)) {
                    FlintVpnDiagnostics.error("config transport encoding failed")
                    QtAndroidController.onServiceError()
                }
                return@launch
            }
            if (!FlintVpnDiagnostics.isCurrent(attempt)) return@launch
            startPreparedVpn(vpnConfig, packed)
        }
    }

    private fun startPreparedVpn(vpnConfig: String, packed: ByteArray) {''')
    edit(a, '                    connectToVpn(vpnConfig)', '                    connectToVpn(packed)')
    edit(a, '            startVpnService(vpnConfig, proto)\n            doBindService()',
         '            if (startVpnService(packed, proto)) doBindService()')
    edit(a, '    private fun connectToVpn(vpnConfig: String) {',
         '    private fun connectToVpn(packed: ByteArray) {')
    edit(a, '                putString(MSG_VPN_CONFIG, vpnConfig)',
         '                putByteArray(FlintVpnConfigTransport.EXTRA, packed)')
    start = '''    private fun startVpnService(vpnConfig: String, proto: VpnProto) {
        Log.d(TAG, "Start VPN service: $proto")
        Intent(this, proto.serviceClass).apply {
            putExtra(MSG_VPN_CONFIG, vpnConfig)
        }.also {
            try {
                ContextCompat.startForegroundService(this, it)
            } catch (e: SecurityException) {
                FlintVpnDiagnostics.error("foreground start service: ${e.message}")
                Log.e(TAG, "Failed to start ${proto.serviceClass.simpleName}: $e")
                QtAndroidController.onServiceError()
            }
        }
    }'''
    edit(a, start, '''    private fun startVpnService(packed: ByteArray, proto: VpnProto): Boolean {
        Log.d(TAG, "Start VPN service: $proto")
        return try {
            val intent = Intent(this, proto.serviceClass).apply {
                putExtra(FlintVpnConfigTransport.EXTRA, packed)
            }
            checkNotNull(ContextCompat.startForegroundService(this, intent))
            true
        } catch (_: Exception) {
            FlintVpnDiagnostics.error("foreground start service failed")
            QtAndroidController.onServiceError()
            false
        }
    }''')
    edit(a, '''            Intent(this, proto.serviceClass).also {
                bindService(it, serviceConnection, BIND_ABOVE_CLIENT and BIND_AUTO_CREATE)
            }
            isInBoundState = true''', '''            if (isInBoundState) return
            isInBoundState = try {
                bindService(Intent(this, proto.serviceClass), serviceConnection,
                    BIND_ABOVE_CLIENT or BIND_AUTO_CREATE)
            } catch (_: Exception) { false }
            if (!isInBoundState) {
                FlintVpnDiagnostics.error("service bind failed")
                QtAndroidController.onServiceError()
            }''')
    # IpcMessenger catches RemoteException internally; surface a fixed code.
    edit(a, '''                doBindService()
            }
        )''', '''                doBindService()
            },
            onRemoteException = {
                FlintVpnDiagnostics.error("service config transport failed")
                QtAndroidController.onServiceError()
            }
        )''')
    # A no-flags bind may have delivered the service connection only after an
    # early engine failure. Replaying that error prevents an unexplained timeout.
    s = 'AmneziaVpnService.kt'
    edit(s, '    private var serverIndex: Int = -1',
         '    private var serverIndex: Int = -1\n    private var lastConnectionError: String? = null')
    edit(s, '                        clientMessengers[msg.replyTo] = messenger', '''                        clientMessengers[msg.replyTo] = messenger
                        lastConnectionError?.let { error ->
                            messenger.send {
                                ServiceEvent.ERROR.packToMessage { putString(MSG_ERROR, error) }
                            }
                        }''')
    edit(s, '                        connect(msg.data.getString(MSG_VPN_CONFIG))',
         '                        connectEnvelope(msg.data)')
    edit(s, '            connect(intent?.getStringExtra(MSG_VPN_CONFIG))',
         '            connectEnvelope(intent?.extras)')
    edit(s, '    private fun connect(vpnConfig: String? = null) {', '''    private fun connectEnvelope(extras: android.os.Bundle?) {
        if (extras?.containsKey(FlintVpnConfigTransport.EXTRA) == true) {
            val config = try {
                FlintVpnConfigTransport.decode(requireNotNull(extras.getByteArray(FlintVpnConfigTransport.EXTRA)))
            } catch (_: Exception) {
                onError("VPN config transport decoding failed")
                protocolState.value = DISCONNECTED
                return // Explicit invalid input must never connect a previous profile.
            }
            connect(config)
        } else {
            // Quick Settings, Always-on and upstream permission callbacks.
            connect(extras?.getString(MSG_VPN_CONFIG))
        }
    }

    @MainThread
    private fun connect(vpnConfig: String? = null) {
        lastConnectionError = null''')
    edit(s, '    private fun onError(msg: String) {',
         '    private fun onError(msg: String) {\n        lastConnectionError = msg')
