package org.amnezia.vpn.protocol

/** Native app routing covers DNS, UDP and endpoints which cannot be sniffed. */
internal object FlintRussianApps {
    val packages = setOf("ru.yandex.yandexmaps", "ru.yandex.yandexnavi")

    fun apply(
        enabled: Boolean,
        included: MutableSet<String>,
        excluded: MutableSet<String>,
        vpnPackage: String,
        isInstalled: (String) -> Boolean
    ) {
        if (!enabled) return
        val direct = packages.filter(isInstalled).toSet()
        if (direct.isEmpty()) return
        if (included.isNotEmpty()) {
            // Android forbids combining allowed and disallowed application lists.
            included.removeAll(direct)
            // An empty allowlist means ALL apps, not NO apps. Keep it restrictive.
            if (included.isEmpty()) included.add(vpnPackage)
        } else {
            excluded.addAll(direct)
        }
    }
}
