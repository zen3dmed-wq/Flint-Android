package org.amnezia.vpn

fun main() {
    for (key in listOf(19,20,21,22)) check(FlintRemoteKeys.normalizedKeyCode(key)==key)
    for (key in listOf(23,66,96,109,160)) check(FlintRemoteKeys.normalizedKeyCode(key)==66)
    check(FlintRemoteKeys.normalizedKeyCode(97)==4)
    for (key in listOf(3,4,24,25,29)) check(FlintRemoteKeys.normalizedKeyCode(key)==null)
    check(FlintRemoteKeys.isTelevision(true,false,true))
    check(FlintRemoteKeys.isTelevision(false,true,true))
    check(FlintRemoteKeys.isTelevision(false,false,false))
    check(!FlintRemoteKeys.isTelevision(false,false,true))
    println("Remote arrows, OK aliases, Back and TV detection: passed")
}
