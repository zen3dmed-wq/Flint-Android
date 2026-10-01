import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "FlintFocus.js" as FlintFocus
import "DeviceRows.js" as DeviceRows

Popup {
    id: panel
    objectName: "flintDevicesPanel"
    property var profile: ({})
    property var subscriptions: []
    property var devices: []
    property var sessions: []
    property var pending: ({})
    property var confirmTarget: ({})
    property var sessionTarget: ({})
    property string sessionNotice: ""
    readonly property bool sessionBusy: Object.keys(pending).some(function(k) { return pending[k].kind.indexOf("session-revoke-") === 0 })
    property int generation: 0
    property string subscriptionId: ""
    property bool canManage: false
    property bool deviceUnsupported: false
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
        generation++; pending = {}; canManage = false; deviceUnsupported = false; devices = []; sessions = []; profile = {}; error = ""; notice = ""; sessionNotice = ""
        sessionConfirmation.close(); sessionTarget = {}
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
    function askSessionRevoke(session) {
        if (!session || session.isCurrent || !session.id || sessionBusy) return
        error = ""; sessionNotice = ""
        sessionTarget = session
        sessionConfirmation.open()
    }
    function revokeSessionConfirmed() {
        var target = sessionTarget
        if (sessionBusy || !target.id || target.isCurrent) return
        var present = sessions.some(function(group) { return group.items.some(function(item) { return item.id === target.id && !item.isCurrent }) })
        if (!present) return
        request("session-revoke-" + target.id, "DELETE", "/me/sessions/" + encodeURIComponent(target.id), target)
        sessionConfirmation.close()
    }
    onOpened: reload()
    onClosed: { generation++; pending = {}; confirmation.close(); sessionConfirmation.close(); confirmTarget = {}; sessionTarget = {} }
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
                    panel.deviceUnsupported = status === 404 || status === 501
                    panel.notice = status === 403 ? "Управлять устройствами может только владелец основной подписки." :
                        (status === 404 || status === 501 ? "Вход и подписка работают. Отключение VPN на отдельном устройстве пока недоступно на сервере Flint. Ниже можно управлять входами в свой аккаунт." : "Не удалось загрузить устройства: " + failure + ". Повторите обновление списка.")
                } else panel.error = failure
                return
            }
            if (kind === "me") { panel.profile = data; panel.loadDevices() }
            if (kind === "sessions") panel.sessions = DeviceRows.groupSessions(data.items || [])
            if (kind.indexOf("session-revoke-") === 0) {
                if (status !== 204) { panel.error = "Сервер ещё не подтвердил завершение входа. Обновите список."; return }
                panel.error = ""
                panel.sessionNotice = "Вход завершён. Ранее выданный VPN-ключ этим действием не отзывается."
                panel.request("sessions", "GET", "/me/sessions")
            }
            if (kind === "subscriptions") {
                panel.subscriptions = (data.items || []).map(function(s) { return {id:s.id, name:s.plan ? s.plan.name : "Подписка", status:s.status} })
                if (!panel.subscriptions.some(function(s) { return s.id === panel.subscriptionId }))
                    panel.subscriptionId = panel.subscriptions.some(function(s) { return s.id === FlintController.selectedSubscriptionId }) ? FlintController.selectedSubscriptionId : (panel.subscriptions.length ? panel.subscriptions[0].id : "")
                panel.loadDevices()
            }
            if (kind.indexOf("list-") === 0) {
                panel.deviceUnsupported = false
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
        property bool flintFocusScope: true
        spacing: 12
        RowLayout {
            Layout.fillWidth: true
            Text { Layout.fillWidth: true; text: panel.deviceUnsupported ? "Входы в аккаунт" : "Устройства"; color: panel.ink; font.pixelSize: 23; font.bold: true; wrapMode: Text.Wrap }
            FlintButton { text: "×"; implicitWidth: 40; font.pixelSize: 24; subtle: true; onClicked: panel.close() }
        }
        FlintChoice {
            Layout.fillWidth: true; visible: panel.subscriptions.length > 1 && !panel.deviceUnsupported
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
                Text { Layout.fillWidth: true; text: "Входы собраны по системе устройства. Откройте группу, чтобы завершить ненужный вход. Это список входов в аккаунт, а не активных VPN-подключений."; color: panel.muted; font.pixelSize: 12; wrapMode: Text.Wrap }
                Text { Layout.fillWidth: true; visible: !!panel.sessionNotice; text: panel.sessionNotice; color: "#57E4B0"; wrapMode: Text.Wrap }
                Repeater {
                    model: panel.sessions
                    Rectangle {
                        id: groupCard
                        objectName: "loginSessionGroup"
                        required property var modelData
                        property bool expanded: false
                        Layout.fillWidth: true; implicitHeight: sessionBody.implicitHeight + 28
                        radius: 16; color: "#102635"; border.color: "#2B4A5E"
                        ColumnLayout {
                            id: sessionBody; anchors.fill: parent; anchors.margins: 14; spacing: 7
                            Text { Layout.fillWidth: true; text: "Входы: " + groupCard.modelData.model; textFormat: Text.PlainText; color: panel.ink; font.bold: true; wrapMode: Text.Wrap }
                            Text { Layout.fillWidth: true; text: "Сеансов: " + groupCard.modelData.items.length + (groupCard.modelData.isCurrent ? " · здесь текущий вход" : ""); color: panel.muted; wrapMode: Text.Wrap; font.pixelSize: 12 }
                            Text { Layout.fillWidth: true; visible: !groupCard.modelData.verifiedIdentity && groupCard.modelData.items.length > 1; text: "Здесь могут быть входы с разных устройств."; color: panel.muted; wrapMode: Text.Wrap; font.pixelSize: 12 }
                            FlintButton { text: groupCard.expanded ? "Свернуть входы" : "Показать входы"; onClicked: groupCard.expanded = !groupCard.expanded }
                            Repeater {
                                model: groupCard.expanded ? groupCard.modelData.items : []
                                ColumnLayout {
                                    required property var modelData
                                    Layout.fillWidth: true; Layout.topMargin: 10; spacing: 7
                                    Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: "#345468" }
                                    Text { Layout.fillWidth: true; text: modelData.model || modelData.platform || "Устройство"; textFormat: Text.PlainText; color: panel.ink; wrapMode: Text.Wrap }
                                    Text { Layout.fillWidth: true; text: "Flint " + (modelData.appVersion || "—") + (modelData.isCurrent ? " · текущий вход" : ""); color: panel.ink; wrapMode: Text.Wrap }
                                    Text { Layout.fillWidth: true; text: "Последняя активность: " + panel.dateText(modelData.lastActiveAt); color: panel.muted; wrapMode: Text.Wrap; font.pixelSize: 12 }
                                    FlintButton { text: "Завершить вход"; visible: !modelData.isCurrent; enabled: !panel.sessionBusy; onClicked: panel.askSessionRevoke(modelData) }
                                }
                            }
                        }
                    }
                }
            }
        }
        FlintButton { Layout.fillWidth: true; text: "Обновить список"; onClicked: panel.reload() }
    }
    Popup {
        id: sessionConfirmation; parent: panel.parent; width: Math.min(parent.width - 40, 400)
        x: (parent.width-width)/2; y: Math.max(PageController.safeAreaTopMargin, (parent.height-height)/2)
        padding: 20; modal: true; focus: true
        background: Rectangle { radius: 20; color: "#102635"; border.color: "#57E4B0" }
        contentItem: ColumnLayout {
        property bool flintFocusScope: true
            spacing: 16
            Text { Layout.fillWidth: true; text: "Завершить этот вход?"; color: panel.ink; font.pixelSize: 20; wrapMode: Text.Wrap }
            Text { Layout.fillWidth: true; text: "Flint " + (panel.sessionTarget.appVersion || "—") + " · " + panel.dateText(panel.sessionTarget.lastActiveAt); color: panel.ink; wrapMode: Text.Wrap }
            Text { Layout.fillWidth: true; text: "Для доступа к аккаунту с этого входа потребуется войти снова. Уже выданный VPN-ключ продолжит работать. Текущий вход и остальные сеансы сохранятся."; color: panel.muted; wrapMode: Text.Wrap }
            RowLayout {
                FlintButton { text: "Отмена"; onClicked: sessionConfirmation.close() }
                FlintButton { text: "Завершить"; primary: true; onClicked: panel.revokeSessionConfirmed() }
            }
        }
    }
    Popup {
        id: confirmation; parent: panel.parent; width: Math.min(parent.width - 40, 400)
        x: (parent.width-width)/2; y: Math.max(PageController.safeAreaTopMargin, (parent.height-height)/2)
        padding: 20; modal: true; focus: true
        background: Rectangle { radius: 20; color: "#102635"; border.color: "#57E4B0" }
        contentItem: ColumnLayout {
        property bool flintFocusScope: true
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
