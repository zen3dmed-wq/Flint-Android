package app.flint.prototype.data

import android.content.Context
import android.util.AtomicFile
import app.flint.prototype.imports.ImportException
import app.flint.prototype.imports.ServerProfile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile

/** The application manifest disables backups; this file never goes to shared storage. */
class ProfileStore(context: Context) {
    private val directory = context.applicationContext.filesDir
    private val file = AtomicFile(File(directory, "flint-profiles-v1.json"))
    private val lockFile = File(directory, "flint-profiles-v1.lock")

    fun load(): List<ServerProfile> = locked { read() }

    /** Existing subscriptions remain present. Importing an identical ID updates that entry. */
    fun merge(profiles: List<ServerProfile>): List<ServerProfile> = locked {
        val merged = ProfileCollection.merge(read(), profiles)
        val bytes = ProfileCollection.encode(merged)
        val stream = try { file.startWrite() }
            catch (_: Exception) { throw ImportException("Не удалось сохранить серверы. Проверьте свободное место") }
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (_: Exception) {
            file.failWrite(stream)
            throw ImportException("Не удалось сохранить серверы. Предыдущий список сохранён")
        }
        merged
    }

    private fun read(): List<ServerProfile> {
        val stream = try { file.openRead() }
            catch (_: FileNotFoundException) {
                if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
                throw ImportException("Нет доступа к сохранённому списку серверов")
            }
        try {
            return stream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > ProfileCollection.MAX_BYTES) throw ImportException("Сохранённый список серверов слишком большой")
                    output.write(buffer, 0, count)
                }
                ProfileCollection.decode(output.toByteArray())
            }
        } catch (error: ImportException) { throw error }
        catch (_: Exception) { throw ImportException("Не удалось прочитать сохранённые серверы. Данные не изменены") }
    }

    private fun <T> locked(operation: () -> T): T = synchronized(processLock) {
        try {
            RandomAccessFile(lockFile, "rw").use { guard ->
                guard.channel.lock().use { operation() }
            }
        } catch (error: ImportException) { throw error }
        catch (_: Exception) { throw ImportException("Не удалось открыть хранилище серверов") }
    }

    companion object {
        // AtomicFile alone does not serialize competing writers or different instances.
        private val processLock = Any()
    }
}
