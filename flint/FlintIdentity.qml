import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "FlintFocus.js" as FlintFocus

Popup {
    id: panel
        Shortcut { sequence: "Back"; enabled: panel.activeFocus; onActivated: panel.close() }
    objectName: "flintIdentityPanel"
    property var profile: ({})
    property var pending: ({})
    property int generation: 0
    property string error: ""
    property string message: ""
    property string linkId: ""
    property string botUrl: ""
    property double expiresAt: 0
    signal profileUpdated(var value)
    width: Math.min(parent.width - 24, 520)
    height: Math.min(parent.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - 24, 680)
    x: (parent.width - width) / 2
    y: PageController.safeAreaTopMargin + 12
    padding: 18; modal: true; focus: true
    background: Rectangle { radius: 22; color: "#081827"; border.color: "#46637A" }
    function request(action, method, path, body) {
        if (pending[action]) return
        var next = Object.assign({}, pending); next[action] = true; pending = next
        FlintController.accountRequest("identity:" + generation + ":" + action, method, path, body || {}, "")
    }
    function reload() { request("profile", "GET", "/me") }
    function addEmail(email, password) {
        error = ""; message = ""
        email = email.trim().toLowerCase()
        if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || password.length < 8 || password.length > 128) {
            error = "Укажите почту и пароль от 8 до 128 символов."; return
        }
        request("email", "POST", "/me/email-login", {email: email, password: password})
        emailPassword.text = ""
    }
    function startLink() {
        error = ""; message = ""; linkId = ""; botUrl = ""
        request("start", "POST", "/me/telegram/bot/start")
    }
    function openTelegram() {
        var match = /^https:\/\/t\.me\/([A-Za-z0-9_]+)\?start=([^&#]+)$/.exec(botUrl)
        if (!match) { error = "Сервер вернул некорректную ссылку Telegram."; return }
        if (!Qt.openUrlExternally("tg://resolve?domain=" + match[1] + "&start=" + match[2]) && !Qt.openUrlExternally(botUrl))
            error = "Не удалось открыть Telegram. Отсканируйте QR-код с телефона."
    }
    function poll() {
        if (!linkId) return
        if (Date.now() >= expiresAt) { linkId = ""; botUrl = ""; error = "Время подтверждения истекло. Начните привязку заново."; return }
        request("complete", "POST", "/me/telegram/bot/complete", {loginId: linkId})
    }
    onOpened: { Qt.callLater(function() { FlintFocus.firstButton(panel.contentItem) }); generation++; pending = {}; error = ""; message = ""; profile = {}; reload() }
    onClosed: { generation++; pending = {}; linkId = ""; botUrl = ""; emailPassword.text = "" }
    Connections {
        target: FlintController
        function onAuthChanged() { if (!FlintController.loggedIn) { panel.profile = {}; panel.close() } }
        function onApiBaseChanged() { panel.profile = {}; panel.close() }
        function onAccountResponse(id, status, data, failure) {
            var prefix = "identity:" + panel.generation + ":"
            if (id.indexOf(prefix) !== 0) return
            var action = id.slice(prefix.length)
            if (!panel.pending[action]) return
            var next = Object.assign({}, panel.pending); delete next[action]; panel.pending = next
            if (failure || (status !== 200 && !(action === "complete" && status === 202))) {
                if (action === "start" || action === "complete") { panel.linkId = ""; panel.botUrl = "" }
                var code = data.code || data.error || ""
                panel.error = code === "email_taken" ? "Эта почта уже принадлежит аккаунту Flint. Войдите по ней и привяжите Telegram из раздела «Способы входа»." :
                    code === "already_linked" ? "У обоих аккаунтов уже есть история или другая привязка. Для объединения обратитесь в поддержку: подписки сохранены." :
                    (failure || "Сервер не подтвердил изменение аккаунта.")
                if (status === 409) panel.reload()
                return
            }
            if (action === "start") {
                var expiry = Date.parse(data.expiresAt)
                if (!data.loginId || !/^https:\/\/t\.me\//.test(data.botUrl || "") || !isFinite(expiry) || expiry <= Date.now()) {
                    panel.error = "Сервер не вернул действующее подтверждение Telegram."; return
                }
                panel.linkId = data.loginId; panel.botUrl = data.botUrl
                panel.expiresAt = Math.min(expiry, Date.now() + 300000)
                panel.message = "Подтвердите привязку в боте. Покупать подписку повторно не нужно."
                if (!SettingsController.isOnTv()) panel.openTelegram()
                return
            }
            if (action === "complete" && status === 202) return
            panel.profile = data; panel.profileUpdated(data)
            if (action === "email" || action === "complete") {
                panel.linkId = ""; panel.botUrl = ""
                panel.message = action === "email" ? "Почта добавлена. Вход по почте и Telegram открывает один аккаунт и его подписки." : "Telegram привязан. Подписки, покупки и устройства остаются в одном аккаунте."
                FlintController.refresh()
            }
        }
    }
    Timer { interval: 2000; repeat: true; running: panel.opened && !!panel.linkId && Qt.application.state === Qt.ApplicationActive; onTriggered: panel.poll() }
    contentItem: ColumnLayout {
        property bool flintFocusScope: true
        spacing: 12
        RowLayout {
            Layout.fillWidth: true
            Text { Layout.fillWidth: true; text: "Способы входа"; color: "#F8FBFF"; font.pixelSize: 23; font.bold: true }
            FlintButton { text: "×"; implicitWidth: 42; onClicked: panel.close() }
        }
        ScrollView {
            id: scroll; Layout.fillWidth: true; Layout.fillHeight: true; contentWidth: availableWidth; clip: true
            ColumnLayout {
                width: scroll.availableWidth; spacing: 14
                Text { Layout.fillWidth: true; wrapMode: Text.Wrap; text: "Для подписки достаточно почты или Telegram. Второй способ входа необязателен и открывает тот же аккаунт."; color: "#B7C9DA" }
                Text { Layout.fillWidth: true; wrapMode: Text.Wrap; text: panel.error; visible: !!text; color: "#FFAAAA" }
                Text { Layout.fillWidth: true; wrapMode: Text.Wrap; text: panel.message; visible: !!text; color: "#4AE6A3" }
                Text { text: "ЭЛЕКТРОННАЯ ПОЧТА"; color: "#B7C9DA"; font.pixelSize: 11 }
                Text { Layout.fillWidth: true; wrapMode: Text.WrapAnywhere; textFormat: Text.PlainText; text: panel.profile.email || "Пока не добавлена"; color: "#F8FBFF"; font.pixelSize: 18 }
                ColumnLayout {
                    visible: !!panel.profile.id && !panel.profile.email; Layout.fillWidth: true
                    FlintField { id: emailField; Layout.fillWidth: true; placeholderText: "Ваша почта"; inputMethodHints: Qt.ImhEmailCharactersOnly | Qt.ImhNoAutoUppercase }
                    FlintField { id: emailPassword; Layout.fillWidth: true; placeholderText: "Новый пароль для входа по почте"; echoMode: TextInput.Password }
                    FlintButton { Layout.fillWidth: true; text: "Добавить почту к этому аккаунту"; enabled: !panel.pending.email; onClicked: panel.addEmail(emailField.text, emailPassword.text) }
                }
                Rectangle { Layout.fillWidth: true; height: 1; color: "#2B4A5E" }
                Text { text: "TELEGRAM"; color: "#B7C9DA"; font.pixelSize: 11 }
                Text { Layout.fillWidth: true; wrapMode: Text.Wrap; textFormat: Text.PlainText; text: panel.profile.telegram ? (panel.profile.telegram.username ? "@" + panel.profile.telegram.username : (panel.profile.telegram.firstName || "Привязан")) : "Пока не привязан"; color: "#F8FBFF"; font.pixelSize: 18 }
                FlintButton { Layout.fillWidth: true; visible: !!panel.profile.id && !panel.profile.telegram; text: panel.linkId ? "Начать привязку заново" : "Привязать мой Telegram"; enabled: !panel.pending.start && !panel.pending.complete; onClicked: panel.startLink() }
                Rectangle {
                    visible: !!panel.botUrl; Layout.alignment: Qt.AlignHCenter; Layout.preferredWidth: 220; Layout.preferredHeight: 220; color: "white"; radius: 10
                    Image { anchors.fill: parent; anchors.margins: 12; fillMode: Image.PreserveAspectFit; smooth: false; source: panel.botUrl ? MtProxyConfigModel.generateQrCode(panel.botUrl) : "" }
                }
                FlintButton { visible: !!panel.botUrl; Layout.fillWidth: true; text: "Открыть Telegram"; onClicked: panel.openTelegram() }
                FlintButton { text: "Обновить аккаунт"; enabled: !panel.pending.profile; onClicked: panel.reload() }
            }
        }
    }
}
