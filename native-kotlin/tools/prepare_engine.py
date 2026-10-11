"""Copy the small Qt-free engine layer from an already patched Flint recipe.

No input source is edited. Generated copies keep upstream package names for JNI.
Run again from the same recipe to regenerate deterministic source files.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil


def patch_xray(text: str) -> str:
    old = '''        // Inject SOCKS5 auth before starting xray. Re-uses existing credentials if present.
        ensureInboundAuth(xrayJsonConfig)
        val xrayConfig = parseConfig(config, xrayJsonConfig)

        var xrayJsonConfigString = xrayJsonConfig.toString()
        config.getString("hostName").let { hostName ->
            val ipAddress = parseInetAddress(hostName).ip
            if (hostName != ipAddress) {
                xrayJsonConfigString = xrayJsonConfigString.replace(hostName, ipAddress)
            }
        }

        start(xrayConfig, xrayJsonConfigString, vpnBuilder, protect)'''
    new = '''        // Resolve only the endpoint before TUN creation. A global string
        // replacement also corrupts TLS SNI and HTTP Host when they match the host.
        val endpointHost = config.getString("hostName")
        val endpointIp = parseInetAddress(endpointHost).ip
        val outbounds = xrayJsonConfig.getJSONArray("outbounds")
        for (i in 0 until outbounds.length()) {
            val settings = outbounds.optJSONObject(i)?.optJSONObject("settings") ?: continue
            for (key in listOf("vnext", "servers")) {
                val servers = settings.optJSONArray(key) ?: continue
                for (j in 0 until servers.length()) {
                    val server = servers.optJSONObject(j) ?: continue
                    if (server.optString("address") == endpointHost) server.put("address", endpointIp)
                }
            }
        }
        config.put("hostName", endpointIp)
        ensureInboundAuth(xrayJsonConfig)
        val xrayConfig = parseConfig(config, xrayJsonConfig)
        try {
            start(xrayConfig, xrayJsonConfig.toString(), vpnBuilder, protect)
        } catch (error: Throwable) {
            // Native tun2socks closes its detached fd when startup fails. Stop
            // both native components to cover every later failure as well.
            try { LibXray.stopXray() } catch (_: Throwable) {}
            try { LibXray.stopTun2Socks() } catch (_: Throwable) {}
            throw error
        }'''
    assert text.count(old) == 1, "Unexpected Xray start source; review upstream change"
    text = text.replace(old, new)
    old = '                excludeRoute(InetNetwork(it, 32))'
    new = '                excludeRoute(InetNetwork(parseInetAddress(it)))'
    assert text.count(old) == 1
    text = text.replace(old, new)
    # Keep the listener local even if an imported JSON had a wildcard listener.
    old = '        inbound.put("port", acquireFreeLocalPort())'
    assert text.count(old) == 1
    text = text.replace(old, old + '\n        inbound.put("listen", "127.0.0.1")')
    return text


SAFE_LOG = '''package org.amnezia.vpn.util

/** Do not expose imported endpoints or credentials through native engine logs.
 * The prototype reports safe lifecycle stages through its service contract. */
@Suppress("UNUSED_PARAMETER")
object Log {
    @Volatile var lastIssue: String = "none"
        private set
    fun clear() { lastIssue = "none" }
    fun v(tag: String, message: Any?) {}
    fun d(tag: String, message: Any?) {}
    fun i(tag: String, message: Any?) {}
    fun w(tag: String, message: Any?) { classify(message) }
    fun e(tag: String, message: Any?) { classify(message) }
    private fun classify(message: Any?) {
        val text = message?.toString()?.lowercase().orEmpty()
        // Persist only fixed categories; never the original endpoint, key or error text.
        val code = when {
            "certificate" in text -> "TLS_CERTIFICATE"
            "reality" in text && ("failed" in text || "invalid" in text) -> "REALITY_HANDSHAKE"
            "invalid user" in text || "authentication" in text -> "SERVER_AUTH"
            "failed to lookup" in text || "dns" in text && "failed" in text -> "DNS_LOOKUP"
            "connection refused" in text -> "PORT_REFUSED"
            "network is unreachable" in text || "no route to host" in text -> "NETWORK_ROUTE"
            "timeout" in text || "timed out" in text -> "SERVER_TIMEOUT"
            "failed to find an available destination" in text -> "SERVER_UNREACHABLE"
            else -> return
        }
        lastIssue = code
    }
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--upstream', required=True, type=Path, help='Patched amnezia-client-5.0.3.0 source root')
    parser.add_argument('--branding', type=Path, help='Flint-Android source root (reserved for caller compatibility)')
    args = parser.parse_args()
    project = Path(__file__).resolve().parent.parent
    source = args.upstream.resolve() / 'client/android'
    destination = project / 'engine/src/main/java'
    files = []
    for name in ['Protocol.kt', 'ProtocolConfig.kt', 'ProtocolState.kt', 'Statistics.kt', 'Status.kt', 'Exceptions.kt', 'FlintRussianApps.kt']:
        files.append((source / 'protocolApi/src/main/kotlin' / name, Path('org/amnezia/vpn/protocol') / name))
    for name in ['InetNetwork.kt', 'IpAddress.kt', 'IpRange.kt', 'IpRangeSet.kt', 'NetworkUtils.kt']:
        files.append((source / 'utils/src/main/kotlin/net' / name, Path('org/amnezia/vpn/util/net') / name))
    for name in ['Xray.kt', 'XrayConfig.kt']:
        files.append((source / 'xray/src/main/kotlin' / name, Path('org/amnezia/vpn/protocol/xray') / name))
    records = []
    for src, relative in files:
        text = src.read_text(encoding='utf-8')
        original_hash = hashlib.sha256(src.read_bytes()).hexdigest()
        if src.name == 'Protocol.kt':
            assert 'flintRussianAppsDirect' in text and 'VPN_SESSION_NAME = "Flint"' in text, 'Use a Flint-patched source recipe'
        if src.name == 'Xray.kt':
            text = patch_xray(text)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding='utf-8', newline='\n')
        records.append({'source': str(src.relative_to(args.upstream.resolve())).replace('\\', '/'),
                        'target': relative.as_posix(), 'originalSha256': original_hash,
                        'sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
    log = destination / 'org/amnezia/vpn/util/Log.kt'
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text(SAFE_LOG, encoding='utf-8', newline='\n')
    license_file = args.upstream / 'LICENSE'
    if license_file.is_file():
        shutil.copyfile(license_file, project / 'engine/LICENSE-upstream.txt')
    (project / 'engine/SOURCE-PROVENANCE.json').write_text(json.dumps({
        'upstream': 'amnezia-client 5.0.3.0 with Flint 8.10.25 patches',
        'adaptations': ['endpoint-only DNS replacement preserving SNI', 'IPv6 endpoint host route',
                        'localhost SOCKS binding', 'native failure cleanup', 'safe native logging'],
        'files': records}, indent=2) + '\n', encoding='utf-8')
    print(f'Prepared {len(records)} Qt-free Kotlin engine files')


if __name__ == '__main__':
    main()
