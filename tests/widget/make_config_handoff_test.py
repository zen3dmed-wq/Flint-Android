"""Run the real patched Activity/service methods with inert Android boundaries."""
from pathlib import Path
import sys

android = Path(sys.argv[1]) / 'client/android/src/org/amnezia/vpn'
out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)

def method(file, signature):
    source = (android / file).read_text(encoding='utf-8')
    start = source.index('    ' + signature)
    end = source.index('{', start) + 1; depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}'); end += 1
    return source[start:end]

activity = '\n'.join(method('AmneziaActivity.kt', n) for n in [
    'private fun startVpn(', 'private fun startPreparedVpn(', 'private fun connectToVpn(',
    'private fun startVpnService(', 'private fun doBindService('])
service = method('AmneziaVpnService.kt', 'private fun connectEnvelope(')
(out/'Bundle.kt').write_text('''package android.os
class Bundle {
    private val data = mutableMapOf<String, Any>()
    fun containsKey(key: String) = data.containsKey(key)
    fun putString(key: String, value: String) { data[key] = value }
    fun putByteArray(key: String, value: ByteArray) { data[key] = value }
    fun getString(key: String) = data[key] as? String
    fun getByteArray(key: String) = data[key] as? ByteArray
}
''', encoding='utf-8')
code = '''package org.amnezia.vpn
import android.os.Bundle
import java.io.File
const val TAG = "TEST"
const val MSG_VPN_CONFIG = "VPN_CONFIG"
const val DISCONNECTED = 0
const val BIND_ABOVE_CLIENT = 8
const val BIND_AUTO_CREATE = 1
object Log { fun d(t: String, v: String) {}; fun v(t: String, v: String) {} }
object QtAndroidController { var errors=0; fun onServiceError() { errors++ } }
object Dispatchers { const val Default = 0 }
var afterWork: () -> Unit = {}
fun <T> withContext(d: Int, block: () -> T): T { val result=block(); afterWork(); return result }
class Scope { fun launch(block: () -> Unit) = block() }
class VpnProto(val serviceClass: String)
class Intent(val owner: Any, val cls: String) {
    val extras = Bundle()
    fun putExtra(key: String, value: ByteArray) { extras.putByteArray(key, value) }
}
object ContextCompat {
    var fail=false; var count=0; var started: Intent?=null
    fun startForegroundService(owner: Any, intent: Intent): Any? {
        if (fail) error("TEST failed start")
        started=intent; count++; return intent
    }
}
enum class Action { CONNECT;
    fun packToMessage(block: Bundle.() -> Unit) = Bundle().apply(block)
}
class Messenger { var sent: Bundle?=null; fun send(message: () -> Bundle) { sent=message() } }
class State(var value: Int)
class ActivityFixture {
    val mainScope=Scope(); val serviceConnection=Any(); val vpnServiceMessenger=Messenger()
    var isServiceConnected=false; var isInBoundState=false; var isWaitingStatus=false
    var vpnProto: VpnProto?=null; var binds=0; var bindFlags=0; var allowBind=true
    fun getVpnProto(config: String): VpnProto? = VpnProto("Xray")
    fun doUnbindService() { isServiceConnected=false; isInBoundState=false }
    fun bindService(intent: Intent, connection: Any, flags: Int): Boolean { binds++; bindFlags=flags; return allowBind }
    fun run(config: String, attempt: Long = FlintVpnDiagnostics.currentAttempt()) = startVpn(config, attempt)
'''+activity+'''
}
class ServiceFixture {
    var connected: String?=null; var errors=0; var calls=0
    val protocolState=State(9)
    fun connect(config: String?) { calls++; connected=config ?: "CACHED" }
    fun onError(error: String) { errors++ }
    fun run(extras: Bundle?) = connectEnvelope(extras)
'''+service+'''
}
fun main(args: Array<String>) {
    val config=File(args.single()).readText()
    FlintVpnDiagnostics.reset(); FlintVpnDiagnostics.begin()
    val cold=ActivityFixture(); cold.run(config)
    check(ContextCompat.count==1 && cold.binds==1 && cold.isInBoundState)
    check(cold.bindFlags==9)
    val start=ContextCompat.started!!.extras
    check(!start.containsKey(MSG_VPN_CONFIG))
    check(start.getByteArray(FlintVpnConfigTransport.EXTRA)!!.size < 100_000)
    val service=ServiceFixture(); service.run(start); check(service.connected==config)
    service.run(start); check(service.connected==config) // Intent redelivery.
    cold.isServiceConnected=true; cold.run(config)
    check(ContextCompat.count==1) // Reconnect goes through Messenger.
    val message=cold.vpnServiceMessenger.sent!!
    check(!message.containsKey(MSG_VPN_CONFIG)); service.run(message); check(service.connected==config)
    cold.vpnProto=VpnProto("AWG"); cold.run(config); check(ContextCompat.count==2 && cold.binds==2)
    val failed=ActivityFixture(); ContextCompat.fail=true; failed.run(config)
    check(!failed.isInBoundState && failed.binds==0); ContextCompat.fail=false
    val bindFailure=ActivityFixture(); bindFailure.allowBind=false; bindFailure.run(config)
    check(!bindFailure.isInBoundState && FlintVpnDiagnostics.code()=="SERVICE_BIND_FAILED")
    val count=ContextCompat.count
    afterWork={ FlintVpnDiagnostics.cancel() }; ActivityFixture().run(config)
    check(ContextCompat.count==count); afterWork={}
    val old=FlintVpnDiagnostics.begin(); FlintVpnDiagnostics.cancel(); FlintVpnDiagnostics.begin()
    ActivityFixture().run(config, old); check(ContextCompat.count==count)
    val invalid=Bundle().apply { putByteArray(FlintVpnConfigTransport.EXTRA, byteArrayOf(1,2)) }
    val calls=service.calls; service.run(invalid)
    check(service.errors==1 && service.calls==calls && service.protocolState.value==DISCONNECTED)
    val wrongType=Bundle().apply { putString(FlintVpnConfigTransport.EXTRA,"INVALID") }
    service.run(wrongType); check(service.errors==2 && service.calls==calls)
    service.run(null); check(service.connected=="CACHED") // Always-on/tile.
    service.run(Bundle().apply { putString(MSG_VPN_CONFIG,"LEGACY") }); check(service.connected=="LEGACY")
    println("Actual patched handoff: cold start, reconnect, protocol switch, redelivery, start/bind failure, cancellation, invalid input and legacy paths passed")
}
'''
(out/'HandoffTest.kt').write_text(code, encoding='utf-8')
print('Generated tests from actual patched Android methods')
