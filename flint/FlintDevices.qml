import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Popup {
    id: panel
    objectName: "flintDevicesPanel"
    property var profile: ({})
    property var subscriptions: []
    property var devices: []
    property var sessions: []
    property var pending: ({})
    property var confirmTarget: ({})
    property int generation: 0
    property string subscriptionId: ""
    property bool canManage: false
    property string notice: ""
    property string error: ""
    readonly property color ink: "#F8FBFF"
    readonly property color muted: "#B7C9DA"
    width: Math.min(parent.width - 24, 500)
    height: Math.max(180, Math.min(parent.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - 24, 720))
    x: (parent.width - width) / 2
    y: PageController.safeAreaTopMargin + 12
    padding: 18; modal: true; focus: true
    background: Rectangle { radius: 23; color: "#FD081827"; border.color: "#46637A" }

    function request(kind, method, path, target) {
        var id = "devices:" + generation + ":" + kind
        if (pending[id]) return
        var next = Object.assign({}, pending)
        next[id] = {kind:kind, subscriptionId:subscriptionId, target:target || {}}
        pending = next
        FlintController.accountRequest(id, method, path, {}, "")
    }
    function reload() {
        generation++; pending = {}; canManage = false; devices = []; profile = {}; error = ""; notice = ""
        if (!FlintController.loggedIn) return
        request("me", "GET", "/me")
        request("sessions", "GET", "/me/sessions")
        request("subscriptions", "GET", "/subscriptions")
    }
    function loadDevices() {
        canManage = false; devices = []; error = ""; notice = ""
        if (!subscriptionId || !profile.id) return
        request("list-" + subscriptionId, "GET", "/subscriptions/" + encodeURIComponent(subscriptionId) + "/devices")
    }
    function askRevoke(device) {
        if (!canManage || device.revoked || !device.id) return
        confirmTarget = {subscriptionId:subscriptionId, id:device.id, name:device.name || device.model || "Устройство", isCurrent:device.isCurrent === true}
        confirmation.open()
    }
    function revokeConfirmed() {
        if (!canManage || confirmTarget.subscriptionId !== subscriptionId || !confirmTarget.id) return
        request("revoke", "DELETE", "/subscriptions/" + encodeURIComponent(subscriptionId) + "/devices/" + encodeURIComponent(confirmTarget.id), confirmTarget)
        confirmation.close()
    }
    function dateText(value) {
        if (!value) return "Нет данных"
        var date = new Date(value)
        return isNaN(date.getTime()) ? "Нет данных" : Qt.formatDateTime(date, "dd.MM.yyyy HH:mm")
    }
    onOpened: reload()
    onClosed: { generation++; pending = {}; confirmation.close(); confirmTarget = {} }
    Connections {
        target: FlintController
        function onAuthChanged() {
            if (!FlintController.loggedIn) { panel.devices=[]; panel.sessions=[]; panel.subscriptions=[]; panel.profile={}; panel.canManage=false; panel.close() }
        }
        function onApiBaseChanged() { panel.devices=[]; panel.sessions=[]; panel.subscriptions=[]; panel.profile={}; panel.canManage=false; panel.close() }
        function onAccountResponse(id, status, data, failure) {
            var operation = panel.pending[id]
            if (!operation) return
            var next = Object.assign({}, panel.pending); delete next[id]; panel.pending = next
            var kind = operation.kind
            if ((kind.indexOf("list-") === 0 || kind === "revoke") && operation.subscriptionId !== panel.subscriptionId) return
            if (failure) {
                if (kind.indexOf("list-") === 0) {
                    panel.canManage = false; panel.devices = []
                    panel.notice = status === 403 ? "Управлять устройствами может только владелец основной подписки." :
                        "Список VPN-устройств и отключение доступа ещё не подключены на сервере. Ниже доступны сеансы входа в аккаунт."
                } else panel.error = failure
                return
            }
            if (kind === "me") { panel.profile = data; panel.loadDevices() }
            if (kind === "sessions") panel.sessions = data.items || []
            if (kind === "subscriptions") {
                panel.subscriptions = (data.items || []).map(function(s) { return {id:s.id, name:s.plan ? s.plan.name : "Подписка", status:s.status} })
                if (!panel.subscriptions.some(function(s) { return s.id === panel.subscriptionId }))
                    panel.subscriptionId = panel.subscriptions.length ? panel.subscriptions[0].id : ""
                panel.loadDevices()
            }
            if (kind.indexOf("list-") === 0) {
                panel.canManage = !!panel.profile.id && data.ownerUserId === panel.profile.id && data.subscriptionId === panel.subscriptionId && data.canManageDevices === true
                panel.devices = panel.canManage ? (data.items || []) : []
                panel.notice = panel.canManage ? "Только вы, как владелец подписки, можете отключать устройства." : "Управление доступно только владельцу основной подписки."
            }
            if (kind === "revoke") {
                if (status !== 204) { panel.error = "Сервер ещё не подтвердил отключение VPN. Обновите список."; return }
                if (operation.target.isCurrent) ConnectionController.closeConnection()
                panel.loadDevices()
                panel.notice = "Доступ устройства отключён сервером."
            }
        }
    }
    contentItem: ColumnLayout {
        spacing: 12
        RowLayout {
            Layout.fillWidth: true
            Text { Layout.fillWidth: true; text: "Устройства"; color: panel.ink; font.pixelSize: 23; font.bold: true }
            FlintButton { text: "×"; implicitWidth: 40; font.pixelSize: 24; subtle: true; onClicked: panel.close() }
        }
        FlintChoice {
            Layout.fillWidth: true; visible: panel.subscriptions.length > 1
            model: panel.subscriptions; textRole: "name"
            onActivated: { panel.subscriptionId = panel.subscriptions[currentIndex].id; panel.loadDevices() }
        }
        Text { Layout.fillWidth: true; visible: !!panel.error; text: panel.error; color: "#FFAAAA"; wrapMode: Text.Wrap }
        Text { Layout.fillWidth: true; visible: !!panel.notice; text: panel.notice; color: panel.muted; wrapMode: Text.Wrap }
        ScrollView {
            id: scroll; Layout.fillWidth: true; Layout.fillHeight: true; contentWidth: availableWidth; clip: true
            ColumnLayout {
                width: scroll.availableWidth; spacing: 12
                Text { visible: panel.canManage; text: "УСТРОЙСТВА ПОДПИСКИ"; color: panel.muted; font.pixelSize: 11; font.letterSpacing: 1 }
                Text { Layout.fillWidth: true; visible: panel.canManage && panel.devices.length === 0; text: "Устройства пока не добавлены"; color: panel.ink; wrapMode: Text.Wrap }
                Repeater {
                    model: panel.devices
                    Rectangle {
                        required property var modelData
                        Layout.fillWidth: true; implicitHeight: deviceBody.implicitHeight + 28
                        radius: 16; color: "#142E40"; border.color: "#2B4A5E"
                        ColumnLayout {
                            id: deviceBody; anchors.fill: parent; anchors.margins: 14; spacing: 7
                            Text { Layout.fillWidth: true; text: (modelData.name || modelData.model || "Устройство") + (modelData.isCurrent ? " · это устройство" : ""); textFormat: Text.PlainText; color: panel.ink; font.bold: true; wrapMode: Text.Wrap }
                            Text { Layout.fillWidth: true; text: modelData.revoked ? "Доступ отключён" : modelData.online === true ? "VPN подключён" : modelData.online === false ? "VPN не подключён" : "Статус VPN неизвестен"; color: "#57E4B0"; wrapMode: Text.Wrap }
                            Text { Layout.fillWidth: true; text: "Последняя активность: " + panel.dateText(modelData.lastSeenAt); color: panel.muted; wrapMode: Text.Wrap; font.pixelSize: 12 }
                            FlintButton { text: "Отключить доступ"; visible: panel.canManage && !modelData.revoked; enabled: !panel.pending["devices:" + panel.generation + ":revoke"]; onClicked: panel.askRevoke(modelData) }
                        }
                    }
                }
                Text { Layout.topMargin: 12; text: "СЕАНСЫ ВХОДА В АККАУНТ"; color: panel.muted; font.pixelSize: 11; font.letterSpacing: 1 }
                Text { Layout.fillWidth: true; text: "Сеанс входа не означает, что VPN сейчас подключён. Устройства с общим QR-ключом здесь не отображаются."; color: panel.muted; font.pixelSize: 12; wrapMode: Text.Wrap }
                Repeater {
                    model: panel.sessions
                    Rectangle {
                        required property var modelData
                        Layout.fillWidth: true; implicitHeight: sessionBody.implicitHeight + 28
                        radius: 16; color: "#102635"; border.color: "#2B4A5E"
                        ColumnLayout {
                            id: sessionBody; anchors.fill: parent; anchors.margins: 14; spacing: 7
                            Text { Layout.fillWidth: true; text: (modelData.model || modelData.platform || "Устройство") + (modelData.isCurrent ? " · это устройство" : ""); textFormat: Text.PlainText; color: panel.ink; font.bold: true; wrapMode: Text.Wrap }
                            Text { Layout.fillWidth: true; text: (modelData.platform || "") + " " + (modelData.osVersion || "") + " · Flint " + (modelData.appVersion || "—"); color: panel.muted; wrapMode: Text.Wrap; font.pixelSize: 12 }
                            Text { Layout.fillWidth: true; text: "Вход / обновление сеанса: " + panel.dateText(modelData.lastActiveAt); color: panel.muted; wrapMode: Text.Wrap; font.pixelSize: 12 }
                        }
                    }
                }
            }
        }
        FlintButton { Layout.fillWidth: true; text: "Обновить список"; onClicked: panel.reload() }
    }
    Popup {
        id: confirmation; parent: panel.parent; width: Math.min(parent.width - 40, 400)
        x: (parent.width-width)/2; y: Math.max(PageController.safeAreaTopMargin, (parent.height-height)/2)
        padding: 20; modal: true; focus: true
        background: Rectangle { radius: 20; color: "#102635"; border.color: "#57E4B0" }
        contentItem: ColumnLayout {
            spacing: 16
            Text { Layout.fillWidth: true; text: "Отключить «" + (panel.confirmTarget.name || "Устройство") + "»?"; textFormat: Text.PlainText; color: panel.ink; font.pixelSize: 20; wrapMode: Text.Wrap }
            Text { Layout.fillWidth: true; text: "Устройство потеряет доступ к VPN по этой подписке. Остальные устройства продолжат работать."; color: panel.muted; wrapMode: Text.Wrap }
            RowLayout {
                FlintButton { text: "Отмена"; onClicked: confirmation.close() }
                FlintButton { text: "Отключить"; primary: true; onClicked: panel.revokeConfirmed() }
            }
        }
    }
}
