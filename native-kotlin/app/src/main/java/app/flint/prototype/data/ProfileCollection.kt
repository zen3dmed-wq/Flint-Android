package app.flint.prototype.data

import app.flint.prototype.imports.ImportException
import app.flint.prototype.imports.ServerProfile
import org.json.JSONArray
import org.json.JSONObject

/** Pure serialization and merge logic; callers must keep the output in private storage. */
internal object ProfileCollection {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val MAX_PROFILES = 2000

    fun merge(existing: List<ServerProfile>, incoming: List<ServerProfile>): List<ServerProfile> {
        val byId = linkedMapOf<String, ServerProfile>()
        (existing + incoming).forEach { profile ->
            validate(profile)
            byId[profile.id] = profile
        }
        if (byId.size > MAX_PROFILES) throw ImportException("Слишком много сохранённых серверов")
        return byId.values.toList()
    }

    fun encode(profiles: List<ServerProfile>): ByteArray {
        val entries = JSONArray()
        merge(emptyList(), profiles).forEach { profile ->
            entries.put(JSONObject().put("id", profile.id).put("name", profile.name)
                .put("host", profile.host).put("port", profile.port).put("protocol", profile.protocol)
                .put("outbound", JSONObject(profile.outboundJson))
                .apply { profile.originalConfigJson?.let { put("originalConfig", JSONObject(it)) } })
        }
        val bytes = JSONObject().put("version", 1).put("profiles", entries).toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw ImportException("Список серверов слишком большой для сохранения")
        return bytes
    }

    fun decode(bytes: ByteArray): List<ServerProfile> {
        if (bytes.size > MAX_BYTES) throw ImportException("Сохранённый список серверов слишком большой")
        try {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            if (root.getInt("version") != 1) throw ImportException("Неподдерживаемая версия сохранённого списка серверов")
            val entries = root.getJSONArray("profiles")
            if (entries.length() > MAX_PROFILES) throw ImportException("Слишком много сохранённых серверов")
            val profiles = (0 until entries.length()).map { index ->
                val obj = entries.getJSONObject(index)
                ServerProfile(obj.getString("id"), obj.getString("name"), obj.getString("host"), obj.getInt("port"),
                    obj.getString("protocol"), obj.getJSONObject("outbound").toString(), obj.optJSONObject("originalConfig")?.toString())
            }
            return merge(emptyList(), profiles)
        } catch (error: ImportException) { throw error }
        catch (_: Exception) { throw ImportException("Не удалось прочитать сохранённые серверы. Данные не изменены") }
    }

    private fun validate(profile: ServerProfile) {
        if (!profile.id.matches(Regex("[a-fA-F0-9]{64}")) || profile.name.isBlank() || profile.host.isBlank() || profile.port !in 1..65535)
            throw ImportException("Некорректный профиль сервера")
        try {
            if (JSONObject(profile.outboundJson).getString("protocol") != profile.protocol)
                throw ImportException("Протокол профиля не соответствует конфигурации")
            profile.originalConfigJson?.let(::JSONObject)
        } catch (error: ImportException) { throw error }
        catch (_: Exception) { throw ImportException("Повреждена конфигурация профиля сервера") }
    }
}
