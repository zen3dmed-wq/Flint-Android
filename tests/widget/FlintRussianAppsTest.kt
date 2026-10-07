package org.amnezia.vpn.protocol

fun main() {
    val maps = "ru.yandex.yandexmaps"
    val navi = "ru.yandex.yandexnavi"
    val other = "org.example.browser"
    val flint = "app.flint.vpn"
    var checks = 0
    fun verify(enabled: Boolean, allow: Set<String>, deny: Set<String>, installed: Set<String>,
               expectedAllow: Set<String>, expectedDeny: Set<String>) {
        val included = allow.toMutableSet()
        val excluded = deny.toMutableSet()
        var queries = 0
        FlintRussianApps.apply(enabled, included, excluded, flint) { queries++; it in installed }
        check(included == expectedAllow) { "Unexpected allowlist: $included" }
        check(excluded == expectedDeny) { "Unexpected denylist: $excluded" }
        check(included.isEmpty() || excluded.isEmpty()) { "Mixed Android routing modes" }
        if (!enabled) check(queries == 0)
        checks++
    }
    val both = setOf(maps, navi)
    verify(true, emptySet(), emptySet(), both, emptySet(), both)
    verify(true, emptySet(), emptySet(), emptySet(), emptySet(), emptySet())
    verify(true, emptySet(), emptySet(), setOf(maps), emptySet(), setOf(maps))
    verify(true, emptySet(), emptySet(), setOf(navi), emptySet(), setOf(navi))
    verify(true, emptySet(), setOf(other), both, emptySet(), both + other)
    verify(true, emptySet(), both, both, emptySet(), both)
    verify(true, setOf(other, maps, navi), emptySet(), both, setOf(other), emptySet())
    verify(true, both, emptySet(), both, setOf(flint), emptySet())
    verify(true, setOf(maps), emptySet(), both, setOf(flint), emptySet())
    verify(true, setOf(other), emptySet(), both, setOf(other), emptySet())
    verify(false, emptySet(), emptySet(), both, emptySet(), emptySet())
    verify(false, both, emptySet(), both, both, emptySet())
    verify(false, emptySet(), setOf(other), both, emptySet(), setOf(other))
    // Prefix matches must never bypass browsers, clones or unrelated Yandex apps.
    verify(true, emptySet(), emptySet(), setOf("ru.yandex.yandexmaps.clone", "com.yandex.browser"), emptySet(), emptySet())
    println("Russian app routing: $checks cases passed")
}
