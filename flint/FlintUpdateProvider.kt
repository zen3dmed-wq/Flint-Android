package org.amnezia.vpn

import androidx.core.content.FileProvider

/** Separate component identity from Qt's FileProvider and its files-path roots. */
class FlintUpdateProvider : FileProvider()
