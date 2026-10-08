package app.flint.prototype.updates

import app.flint.prototype.BuildConfig

import androidx.core.content.FileProvider

/** Separate component identity from Qt's FileProvider and its files-path roots. */
class FlintUpdateProvider : FileProvider()
