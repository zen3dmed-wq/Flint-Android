package app.flint.prototype.testing

import app.flint.prototype.account.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/** Strict local implementation of the 2026-10-08 support contract; never sends externally. */
class SupportFixture(private val failFirstCreate: Boolean = false) {
    val bodies = mutableListOf<JSONObject>()
    val keys = mutableListOf<String>()
    private var failed = false
    private var created: JSONObject? = null
    private val dedup = mutableMapOf<String, Pair<String, JSONObject>>()
    private val old = JSONObject("""{"id":"ticket-test","category":"custom-general","subject":"Помогите подключить телевизор","status":"closed","closedBy":"support","unreadCount":0,"canRate":true,"messages":[{"id":"m-old","author":"support","authorName":"Анна","text":"Нажмите «Добавить с помощью QR» на телевизоре","createdAt":"2026-10-08T09:30:00Z"}]}""")
    @Synchronized fun request(method: String, path: String, body: JSONObject?, key: String?): ApiReply {
        if (path == "/support/categories") return ApiReply(200, JSONObject("""{"items":[{"id":"custom-general","title":"Общий вопрос"}]}"""))
        if (path == "/support/tickets" && method == "GET") {
            val items = JSONArray()
            listOfNotNull(created, old).forEach { items.put(JSONObject(it.toString()).apply { remove("messages") }) }
            return ApiReply(200, JSONObject().put("items",items).put("unreadTickets", if ((created?.optInt("unreadCount") ?: 0) > 0) 1 else 0))
        }
        if (path == "/support/tickets" && method == "POST") {
            requireNotNull(body); check(body.string("category") == "custom-general")
            check(body.string("subject").length in 1..100 && '\n' !in body.string("subject"))
            check(body.string("text").length in 1..4000); check(!body.has("platform")); check(!key.isNullOrBlank())
            bodies.add(JSONObject(body.toString())); keys.add(key)
            val record = dedup[key]
            if (record != null) { check(record.first == body.toString()); return ApiReply(201,JSONObject(record.second.toString())) }
            val ticket = JSONObject().put("id","ticket-new").put("category",body.string("category"))
                .put("subject",body.string("subject")).put("status","waiting_for_user").put("unreadCount",1).put("canRate",false)
                .put("messages",JSONArray().put(message("m-1","user",body.string("text")))
                    .put(message("m-2","support","Здравствуйте! Уточните, пожалуйста, устройство.")))
            created = ticket; dedup[key] = body.toString() to JSONObject(ticket.toString())
            if (failFirstCreate && !failed) { failed = true; throw ApiError(0,"network","Тестовый обрыв связи. Повторите отправку.") }
            return ApiReply(201,JSONObject(ticket.toString()))
        }
        val id = path.removePrefix("/support/tickets/").substringBefore('/').substringBefore('?')
        val ticket = listOfNotNull(created,old).firstOrNull { it.string("id") == id } ?: throw ApiError(404,"not_found","Обращение не найдено")
        val messages = ticket.getJSONArray("messages")
        if (method == "GET") {
            val value = JSONObject(ticket.toString())
            if (path.contains("?afterMessageId=")) {
                val cursor = URLDecoder.decode(path.substringAfter("?afterMessageId="),"UTF-8")
                val index = (0 until messages.length()).firstOrNull { messages.getJSONObject(it).string("id") == cursor }
                check(index != null)
                value.put("messages",JSONArray().apply { for(i in index+1 until messages.length()) put(messages.get(i)) })
            }
            return ApiReply(200,value)
        }
        if (path.endsWith("/read")) { check((0 until messages.length()).any { messages.getJSONObject(it).string("id") == body?.string("upToMessageId") }); ticket.put("unreadCount",0); return ApiReply(204,JSONObject()) }
        if (path.endsWith("/messages")) {
            if(ticket.string("status") == "closed") throw ApiError(409,"ticket_closed","Обращение закрыто")
            requireNotNull(body);check(body.length()==1 && body.string("text").isNotBlank()); check(!key.isNullOrBlank())
            bodies.add(JSONObject(body.toString())); keys.add(key)
            dedup[key]?.let { check(it.first==body.toString());return ApiReply(201,JSONObject(it.second.toString())) }
            val msg = message("m-${messages.length()+1}","user",body.string("text")); messages.put(msg)
            dedup[key]=body.toString() to msg
            messages.put(message("m-${messages.length()+1}","support","Спасибо, проверяем подключение."))
            ticket.put("status","waiting_for_user").put("unreadCount",1)
            return ApiReply(201,msg)
        }
        if(path.endsWith("/close")) { ticket.put("status","closed").put("closedBy","user").put("canRate",true);return ApiReply(200,JSONObject(ticket.toString()).apply { remove("messages") }) }
        if(path.endsWith("/rating")) {
            requireNotNull(body);check(body.optInt("score") in 1..5);check(ticket.string("status")=="closed")
            ticket.put("canRate",false).put("rating",body);return ApiReply(200,JSONObject(ticket.toString()))
        }
        error("Unexpected support fixture: $method $path")
    }
    private fun message(id:String,author:String,text:String) = JSONObject().put("id",id).put("author",author)
        .put("authorName",if(author=="support") "Анна" else JSONObject.NULL).put("text",text).put("createdAt","2026-10-08T09:30:00Z")
}
