import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

ColumnLayout {
    id: chat
    property bool active: false
    property var categories: []
    property var tickets: []
    property var ticket: ({})
    property var messages: []
    property string busy: ""
    property string error: ""
    property double retryAt: 0
    property string lastRead: ""
    property int generation: 0
    property string requestId: ""
    readonly property bool foreground: active && Qt.application.state === Qt.ApplicationActive
    readonly property string ticketPath: "/support/tickets/" + encodeURIComponent(ticket.id || "")
    spacing: 10
    function request(kind, method, path, body, key) {
        if (!active || busy || Date.now() < retryAt) return
        busy = kind; error = ""
        requestId = "support-chat:" + generation + ":" + kind
        FlintController.accountRequest(requestId, method, path, body || {}, key || "")
    }
    function start() {
        generation++; busy = ""; requestId = ""
        var draft = FlintController.clientDraft("support")
        composer.text = draft.body ? draft.body.text || "" : draft.text || ""
        request("categories", "GET", "/support/categories")
    }
    function openTicket(id) {
        if (!busy) request("ticket", "GET", "/support/tickets/" + encodeURIComponent(id))
    }
    function merge(data, append) {
        var list = append ? messages.slice() : [], seen = {}
        list.forEach(function(m) { seen[String(m.id)] = true })
        ;(data.messages || []).forEach(function(m) { if (!seen[String(m.id)]) { list.push(m); seen[String(m.id)] = true } })
        ticket = data; messages = list
        Qt.callLater(function() {
            if (!foreground || !messages.length) return
            var last = String(messages[messages.length - 1].id)
            if (last !== lastRead && !busy) request("read", "POST", ticketPath + "/read", {upToMessageId: last})
        })
    }
    function send() {
        var text = composer.text.trim(); if (!text) return
        var draft = FlintController.clientDraft("support")
        if (draft.key && draft.body) {
            if (draft.body.text !== text || String(draft.ticketId || "") !== String(ticket.id || "")) {
                error = "Повторите сохранённое сообщение для проверки доставки."
                composer.text = draft.body.text; return
            }
        } else {
            var body = {text: text}, kind = "send", path = ticketPath + "/messages"
            if (!ticket.id) {
                if (category.currentIndex < 0 || !categories.length) { error = "Дождитесь загрузки категорий"; return }
                body.category = categories[category.currentIndex].id
                body.subject = text.split(/[\r\n]/)[0].slice(0,100)
                kind = "create"; path = "/support/tickets"
            }
            draft = {key: FlintController.newRequestKey(), body: body, ticketId: ticket.id || "", kind: kind, path: path}
            FlintController.saveClientDraft("support", draft)
        }
        request(draft.kind, "POST", draft.path, draft.body, draft.key)
    }
    onActiveChanged: { if (active) start(); else { generation++; busy = ""; requestId = "" } }
    Connections {
        target: FlintController
        function onAccountResponse(id, status, data, failure) {
            if (id !== chat.requestId || !chat.active) return
            var kind = chat.busy; chat.busy = ""; chat.requestId = ""
            if (failure) {
                chat.error = failure
                if (status === 429) chat.retryAt = Date.now() + (data.retryAfterSeconds || 60) * 1000
                if ((kind === "create" || kind === "send") && (status === 400 || status === 409 || status === 422)) FlintController.saveClientDraft("support", {})
                return
            }
            if (kind === "categories") { chat.categories = data.items || []; chat.request("list", "GET", "/support/tickets") }
            else if (kind === "list") {
                chat.tickets = data.items || []
                var draft = FlintController.clientDraft("support")
                if (draft.ticketId) chat.openTicket(draft.ticketId)
            }
            else if (kind === "ticket" || kind === "delta") chat.merge(data, kind === "delta")
            else if (kind === "create") { FlintController.saveClientDraft("support", {}); composer.clear(); chat.merge(data, false) }
            else if (kind === "send") { FlintController.saveClientDraft("support", {}); composer.clear(); chat.request("delta", "GET", chat.ticketPath) }
            else if (kind === "read") chat.lastRead = chat.messages.length ? String(chat.messages[chat.messages.length - 1].id) : ""
            else if (kind === "close" || kind === "rating") { chat.ticket = data; chat.error = kind === "close" ? "Обращение закрыто" : "Спасибо за оценку" }
        }
    }
    Timer {
        interval: 12000; repeat: true; running: chat.foreground && !!chat.ticket.id && chat.ticket.status !== "closed"
        onTriggered: chat.request("delta", "GET", chat.ticketPath + (chat.messages.length ? "?afterMessageId=" + encodeURIComponent(chat.messages[chat.messages.length - 1].id) : ""))
    }
    RowLayout {
        Layout.fillWidth: true
        FlintChoice { Layout.fillWidth: true; model: chat.tickets; textRole: "subject"; enabled: !chat.busy; onActivated: chat.openTicket(chat.tickets[currentIndex].id) }
        FlintButton { text: "Новое"; enabled: !chat.busy; onClicked: { chat.ticket = ({}); chat.messages = []; chat.lastRead = "" } }
        FlintButton { text: "↻"; enabled: !chat.busy; onClicked: chat.request("list", "GET", "/support/tickets") }
    }
    FlintChoice { id: category; visible: !chat.ticket.id; Layout.fillWidth: true; model: chat.categories; textRole: "title" }
    ScrollView {
        id: history; Layout.fillWidth: true; Layout.fillHeight: true; clip: true; contentWidth: availableWidth
        ColumnLayout {
            width: history.availableWidth; spacing: 12
            Repeater {
                model: chat.messages
                Rectangle {
                    required property var modelData
                    Layout.fillWidth: true; implicitHeight: messageText.implicitHeight + 28; radius: 12
                    color: modelData.author === "user" ? "#193E50" : "#142E40"
                    Text { id: messageText; anchors.fill: parent; anchors.margins: 14; text: (modelData.author === "user" ? "Вы" : (modelData.authorName || "Поддержка")) + "\n" + modelData.text; textFormat: Text.PlainText; color: "#F8FBFF"; wrapMode: Text.Wrap }
                }
            }
        }
    }
    FlintButton { text: "Закрыть обращение"; visible: !!chat.ticket.id && chat.ticket.status !== "closed"; enabled: !chat.busy; onClicked: closeConfirm.open() }
    Popup {
        id: closeConfirm; modal: true; anchors.centerIn: Overlay.overlay; width: Math.min(360, chat.width); padding: 20
        background: Rectangle { color: "#102635"; radius: 16 }
        contentItem: ColumnLayout { Text { text: "Закрыть это обращение?"; color: "#F8FBFF" }
            FlintButton { text: "Закрыть обращение"; onClicked: { closeConfirm.close(); chat.request("close", "POST", chat.ticketPath + "/close") } }
            FlintButton { text: "Отмена"; onClicked: closeConfirm.close() } }
    }
    ColumnLayout {
        visible: chat.ticket.canRate === true; Layout.fillWidth: true
        CheckBox { id: resolved; text: "Проблема решена"; checked: true; palette.windowText: "#F8FBFF" }
        FlintChoice { id: score; model: [{title:"1"},{title:"2"},{title:"3"},{title:"4"},{title:"5"}]; textRole: "title"; currentIndex: 4 }
        FlintField { id: comment; Layout.fillWidth: true; maximumLength: 1000; placeholderText: "Комментарий (необязательно)" }
        FlintButton { text: "Отправить оценку"; enabled: !chat.busy; onClicked: chat.request("rating", "POST", chat.ticketPath + "/rating", {resolved: resolved.checked, score: score.currentIndex + 1, comment: comment.text.trim()}) }
    }
    Text { Layout.fillWidth: true; visible: text.length > 0; text: chat.error; color: "#F5C77A"; wrapMode: Text.Wrap; textFormat: Text.PlainText }
    TextArea { id: composer; Layout.fillWidth: true; Layout.preferredHeight: 90; enabled: chat.ticket.status !== "closed"; color: "#F8FBFF"; placeholderText: "Напишите сообщение"; wrapMode: TextEdit.Wrap; onTextChanged: { if (text.length > 4000) text = text.slice(0,4000) } background: Rectangle { radius: 12; color: "#102635"; border.color: "#46637A" } }
    FlintButton { Layout.fillWidth: true; primary: true; text: chat.busy ? "Загрузка…" : "Отправить"; enabled: !chat.busy && chat.ticket.status !== "closed"; onClicked: chat.send() }
}
