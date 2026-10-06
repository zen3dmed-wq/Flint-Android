import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs

import Style 1.0
import "./"
import "FlintFocus.js" as FlintFocus
import "FlintUsage.js" as Usage
import "../Controls2"

PageType {
    id: root
    // The upstream PageType timer targets legacy FocusControl elements and
    // steals focus from Flint controls after the page has already appeared.
    enableTimer: false
    focus: isTv
    onVisibleChanged: if (visible) Qt.callLater(restoreHomeFocus)
    onActiveFocusChanged: if (activeFocus) Qt.callLater(restoreHomeFocus)

    function restoreHomeFocus() {
        if (!root.isTv || !root.visible || hasOpenPopup()) return
        var target = root.isTv && tvPairButton.visible ? tvPairButton : connectBtn
        if (target.enabled) target.forceActiveFocus(Qt.TabFocusReason)
    }
    function homePopups() { return [diagnosticsPopup, qrSourcePopup, tvPairPopup, accountPopup, countryPopup, assistPopup, familyQrPopup, settingsPopup, updatesPopup, importPopup, apiSetupPopup, sitesPopup, servicePopup, subscriptionsPopup, devicesPopup] }
    function hasOpenPopup() { return homePopups().some(function(p) { return p.opened || p.visible }) }

    readonly property bool isTv: SettingsController.isOnTv()
    property bool tvPairWaiting: false
    property bool tvPairReceivedLogin: false
    property string tvPairMessage: ""
    readonly property bool wideHome: width > height * 1.2
    readonly property bool denseHome: viewport.height < 700 || wideHome
    readonly property real homeGap: wideHome && viewport.height < 400 ? 4 : (denseHome ? 6 : 10)
    readonly property real tileHeight: wideHome && viewport.height < 400 ? 52 : (denseHome ? 56 : 80)
    property real u: Math.max(0.86, Math.min(1.0, width / 412))
    property color ink: "#F8FBFF"
    property color muted: "#B7C9DA"
    property color mint: "#4AE6A3"
    property color card: "#DE0A1C2D"
    property color line: "#46637A"
    property color warning: "#FFC56D"
    FlintAccount { id: servicePopup; parent: root; onAddDeviceRequested: familyQrPopup.open() }
    FlintDevices { id: devicesPopup; parent: root; onAddDeviceRequested: familyQrPopup.open() }
    FlintSites { id: sitesPopup; parent: root; onRoutingEdited: root.requestRoutingApply() }
    FlintSubscriptions { id: subscriptionsPopup; parent: root }
    FlintUpdates { id: updatesPopup; parent: root }
    Connections { target: FlintUpdateController; function onAvailable() { if (!root.hasOpenPopup()) updatesPopup.open() } }
    Timer {
        interval: 60000; repeat: true
        running: FlintController.loggedIn && Qt.application.state === Qt.ApplicationActive
        onTriggered: if (!FlintController.busy && !root.connectionPending) FlintController.refresh()
    }
    property bool connectRequested: false
    property bool awaitingProfile: false
    property bool retryPending: false
    property bool autoConnection: false
    property bool sawConnectionProgress: false
    property int retryWaitTicks: 0
    readonly property bool connectionPending: queuedLocation !== null || connectRequested || FlintController.profilePreparing || ConnectionController.isConnectionInProgress
    // Only VPN connection outcomes affect this state; account/import errors do not.
    property bool connectionFailed: false
    property int lastConnectionError: -1
    property real connectionStartedAt: 0
    property var connectionEvents: []
    property string failureDetails: ""
    property string failureMessage: ""
    property string lastNativeAttempt: ""
    function traceConnection(event) {
        var events = connectionEvents.slice(-23)
        events.push(Math.max(0, Date.now() - connectionStartedAt) + " ms " + event)
        connectionEvents = events
    }
    function resetConnectionDiagnostics() {
        connectionStartedAt = Date.now(); connectionEvents = []; failureDetails = ""; failureMessage = ""; lastNativeAttempt = ""
        FlintController.resetVpnDiagnostics()
        traceConnection("REQUESTED")
    }
    function connectionReport() {
        return "Flint 8.10.19 / 2189\nmode=" + (autoConnection ? "auto" : "manual") +
            "\nruDirect=" + FlintController.ruDirectEnabled + "\ncore.error=" + lastConnectionError +
            "\n" + connectionEvents.join("\n") + "\n" + (lastNativeAttempt || FlintController.vpnDiagnostics())
    }
    function openConnectionDiagnostics() {
        if (!failureDetails) failureDetails = connectionReport()
        diagnosticsPopup.open()
    }
    property bool cancellingConnection: false
    property bool observedConnection: ConnectionController.isConnected || ConnectionController.isConnectionInProgress
    readonly property string connectionVisualState: connectionFailed ? "error"
        : cancellingConnection ? "idle"
        : queuedLocation !== null || connectRequested || retryPending || ConnectionController.isConnectionInProgress ? "connecting"
        : ConnectionController.isConnected ? "connected" : "idle"
    readonly property color connectionStatusColor: connectionVisualState === "connected" ? "#4AE6A3"
        : connectionVisualState === "connecting" ? "#F1C75B"
        : connectionVisualState === "error" ? "#EF626B" : "#82909E"
    property bool telegramRequested: false
    property string telegramError: ""
    property string previousTelegramUrl: ""
    readonly property string backendError: FlintController.lastError
    readonly property bool telegramNeedsRestart: !FlintController.loggedIn &&
        (/login_expired|login_rejected|invalid_code_verifier/i.test(backendError) ||
         (/auth\/telegram\/bot\/complete/i.test(backendError) && /\bGone\b|\b410\b/i.test(backendError)))
    readonly property string displayBackendError: telegramNeedsRestart
        ? "Попытка входа больше недействительна. Начните вход заново и подтвердите новую ссылку в Telegram."
        : backendError
    onBackendErrorChanged: { if (backendError.length > 0) telegramRequested = false }
    property bool qrScanning: false
    property int externalImportCount: 0
    property bool importReady: false
    property bool importBusy: false
    property bool qrImageReading: false
    property string qrImageRequest: ""
    property bool purchaseAfterLogin: false
    property string importError: ""
    readonly property var locationChoices: buildLocationChoices()

    function buildLocationChoices() {
        var result = []
        var countries = FlintController.countries
        for (var i = 0; i < countries.length; ++i) {
            if (countries[i].code === "AUTO")
                result.push({kind: "country", code: countries[i].code, name: countries[i].name})
        }
        var saved = FlintController.savedServers
        for (var j = 0; j < saved.length; ++j)
            result.push({kind: "saved", id: saved[j].id, name: saved[j].name || "Добавленный сервер"})
        for (var k = 0; k < countries.length; ++k) {
            if (countries[k].code !== "AUTO")
                result.push({kind: "country", code: countries[k].code, name: countries[k].name})
        }
        return result
    }

    property var queuedLocation: null
    property bool routingBootstrapping: true
    property bool routingStartupPending: false
    function requestRoutingApply() {
        if (routingBootstrapping || cancellingConnection) return
        routingDebounce.restart()
    }
    function applyRoutingChanges() {
        // A pending location/reconnect already reads the latest routing settings.
        if (queuedLocation !== null || cancellingConnection) return
        if (!ConnectionController.isConnected && !ConnectionController.isConnectionInProgress) return
        routingStartupPending = false
        connectionFailed = false
        queuedLocation = {kind: "routing"}; switchWaitTicks = 0
        connectRequested = false; awaitingProfile = false; retryPending = false
        connectionDeadline.stop(); retryWait.stop()
        FlintController.cancelProfileImport()
        ConnectionController.closeConnection()
        locationSwitch.start()
        PageController.showNotificationMessage("Применяем правила сайтов РФ: переподключаем VPN.")
    }
    Timer { id: routingDebounce; interval: 500; onTriggered: root.applyRoutingChanges() }
    Connections {
        target: FlintController
        function onRuDirectEnabledChanged() { root.requestRoutingApply() }
        function onRoutingChanged() { root.requestRoutingApply() }
    }
    property int switchWaitTicks: 0
    function applyLocation(location, reconnect) {
        if (location.kind === "routing") {
            // Rebuild the current native profile; no subscription fetch or change
            // of the user's server is needed to apply routing settings.
            connectionFailed = false; cancellingConnection = false; lastConnectionError = -1
            resetConnectionDiagnostics(); connectRequested = true
            autoConnection = FlintController.subscriptionActive && FlintController.selectedCountry === "AUTO" && !FlintController.selectedSavedServerId
            startTunnel()
            return
        }
        if (location.kind === "saved") {
            if (ServersUiController.getServerIndexById(location.id) < 0) return
            FlintController.cancelProfileImport()
            ServersUiController.setDefaultServer(location.id)
            ServersUiController.setProcessedServerId(location.id)
        } else FlintController.selectedCountry = location.code
        if (reconnect) beginConnect()
        else if (location.kind !== "saved") FlintController.importSubscription()
    }
    function chooseLocation(location) {
        if (root.connectionPending || queuedLocation) return
        countryPopup.close()
        if (ConnectionController.isConnected) {
            connectionFailed = false; cancellingConnection = false
            queuedLocation = location; switchWaitTicks = 0
            ConnectionController.closeConnection(); locationSwitch.start()
        } else applyLocation(location, false)
    }
    Timer {
        id: locationSwitch; interval: 100; repeat: true
        onTriggered: {
            if (!root.queuedLocation) { stop(); return }
            if (ConnectionController.isConnected || ConnectionController.isConnectionInProgress) {
                if (++root.switchWaitTicks < 150) return
                stop(); root.queuedLocation = null
                root.connectionFailed = true
                PageController.showNotificationMessage("Не удалось завершить старое соединение. Повторите смену сервера."); return
            }
            stop(); var target = root.queuedLocation; root.queuedLocation = null
            root.applyLocation(target, true)
        }
    }
    function openLocations() {
        if (FlintController.subscriptionActive || FlintController.savedServers.length > 0) {
            countryPopup.open()
            FlintController.refreshServerHealth()
            if (FlintController.subscriptionActive) FlintController.importSubscription(false)
        } else accountPopup.open()
    }

    function telegramAppUrl(url) {
        var match = /^https?:\/\/(?:www\.)?(?:t\.me|telegram\.me|telegram\.dog)\/([A-Za-z0-9_]+)\/?(?:\?([^#]*))?(?:#.*)?$/.exec(url)
        return match ? "tg://resolve?domain=" + encodeURIComponent(match[1]) + (match[2] ? "&" + match[2] : "") : url
    }

    function openTelegram() {
        var url = String(FlintController.telegramBotUrl).trim()
        if (!url) return
        telegramRequested = false
        var direct = telegramAppUrl(url)
        var opened = Qt.openUrlExternally(direct)
        if (!opened && direct !== url) opened = Qt.openUrlExternally(url)
        telegramError = opened ? "" : "Не удалось открыть Telegram. Откройте ссылку в браузере или скопируйте её."
    }

    function beginTelegram(restart) {
        if (FlintController.busy || telegramRequested) return
        telegramError = ""
        telegramRequested = true
        if (!restart && !telegramNeedsRestart && FlintController.telegramPending && FlintController.telegramBotUrl.length > 0)
            openTelegram()
        else {
            previousTelegramUrl = FlintController.telegramBotUrl
            FlintController.startTelegramLogin()
        }
    }

    function openClipboardImport() {
        FlintController.cancelProfileImport()
        importError = ""
        importReady = false
        qrScanning = false
        importPopup.open()
        importText.text = ""
        importText.paste()
        if (!importText.text.trim()) importError = "В буфере нет текста. Скопируйте ключ подключения и нажмите «Вставить»."
    }

    function scanQr() {
        FlintController.cancelProfileImport()
        importError = ""
        importReady = false
        if (!SettingsController.isCameraPresent()) {
            importError = "Камера недоступна. Вставьте ключ подключения из буфера."
            importPopup.open()
            return
        }
        qrScanning = true
        importPopup.close()
        ImportController.startDecodingQr()
    }

    function chooseQrSource() { qrSourcePopup.open() }
    function readQrImage(url) {
        if (qrImageReading || importBusy) return
        FlintController.cancelProfileImport()
        qrImageRequest = FlintController.newRequestKey()
        importText.text = ""; importError = ""; importReady = false; qrScanning = false
        qrImageReading = true; importPopup.open()
        FlintController.decodeQrImage(String(url), qrImageRequest)
    }
    function openPurchase() {
        if (!FlintController.loggedIn) { purchaseAfterLogin = true; accountPopup.open(); return }
        servicePopup.section = 1; servicePopup.open()
    }
    FileDialog {
        id: qrImagePicker
        title: "Выберите картинку с QR-кодом"
        fileMode: FileDialog.OpenFile
        nameFilters: ["Изображения (*.png *.jpg *.jpeg *.webp *.heic *.heif *.bmp)", "Все файлы (*)"]
        onAccepted: root.readQrImage(selectedFile)
    }
    Connections {
        target: FlintController
        function onExternalImportPrepared(count, error) {
            root.importBusy=false;root.externalImportCount=count;root.importReady=count>0;root.importError=error
        }
        function onManualImportFinished(count, error) {
            root.importBusy=false;root.importReady=false;root.externalImportCount=0
            if(error){root.importError=error;return}
            importPopup.close();PageController.showNotificationMessage("Добавлено серверов: "+count)
        }
        function onQrImageDecoded(requestId, text, error) {
            if (!root.qrImageReading || requestId !== root.qrImageRequest) return
            root.qrImageReading = false
            if (error) { root.importError = error; return }
            importText.text = text
            root.parseImport()
        }
        function onAuthChanged() {
            if (FlintController.loggedIn && root.purchaseAfterLogin) {
                root.purchaseAfterLogin = false; accountPopup.close(); root.openPurchase()
            }
        }
    }
    Popup {
        id: qrSourcePopup
        Shortcut { sequence: "Back"; enabled: qrSourcePopup.activeFocus; onActivated: qrSourcePopup.close() }
        onOpened: Qt.callLater(function() { FlintFocus.firstButton(qrSourcePopup.contentItem) })
        objectName: "qrSourcePopup"
        parent: root
        width: Math.min(root.width - 32, 360)
        height: Math.min(root.height - 24, sourceColumn.implicitHeight + 36)
        anchors.centerIn: parent
        padding: 18; modal: true; focus: true
        background: Rectangle { color: "#081827"; radius: 22; border.color: root.line }
        contentItem: ColumnLayout {
        property bool flintFocusScope: true
            id: sourceColumn; spacing: 12
            Text { text: "Добавить по QR-коду"; color: root.ink; font.bold: true; font.pixelSize: 20; Layout.fillWidth: true; wrapMode: Text.Wrap }
            FlintButton { Layout.fillWidth: true; text: "Сканировать камерой"; onClicked: { qrSourcePopup.close(); root.scanQr() } }
            FlintButton { objectName: "qrImageSourceButton"; Layout.fillWidth: true; text: "Выбрать изображение"; onClicked: { qrSourcePopup.close(); qrImagePicker.open() } }
            FlintButton { Layout.fillWidth: true; text: "Отмена"; subtle: true; onClicked: qrSourcePopup.close() }
        }
    }

    function parseImport() {
        importError = ""
        importReady = false
        var value = importText.text.trim()
        if (!value) { importError = "Вставьте ключ подключения."; return }
        externalImportCount = 0
        if (/^https:\/\//i.test(value)) { importBusy=true; FlintController.prepareExternalImport(value); return }
        if (ImportController.extractConfigFromData(value)) {
            importReady = true
            importCloaking.checked = false
        } else if (!importError.length) {
            importError = "Ключ не распознан. Нужен VPN-профиль, а не ссылка на Telegram."
        }
    }

    function handleImportError(error) {
        var owned = importBusy || qrScanning || importPopup.opened
        importBusy = false
        qrScanning = false
        importReady = false
        if (owned) {
            importError = typeof error === "string" ? error : "Не удалось импортировать профиль. Проверьте ключ подключения."
            importPopup.open()
        } else {
            PageController.showErrorMessage(error)
        }
    }

    function saveImport() {
        if (!importReady || importBusy) return
        importBusy = true
        importError = ""
        if (externalImportCount > 0) { FlintController.commitExternalImport(); return }
        if (ImportController.isNativeWireGuardConfig && importCloaking.checked)
            ImportController.processNativeWireGuardConfig()
        ImportController.importConfig()
    }

    TextEdit { id: linkClipboard; visible: false }

    Connections {
        target: ImportController
        function onFlintSubscriptionQr(url) {
            if (!root.qrScanning) return
            root.qrScanning = false
            importText.text = url
            importPopup.open()
            root.parseImport()
        }
        function onQrDecodingFinished() {
            if (!root.qrScanning) return
            root.qrScanning = false
            root.importReady = true
            root.importError = ""
            importCloaking.checked = false
            importPopup.open()
        }
        function onImportFinished() {
            if (!root.importBusy) return
            root.importBusy = false
            root.importReady = false
            importText.text = ""
            importPopup.close()
            PageController.showNotificationMessage("Сервер добавлен в список «Локация».")
        }
    }

    function keepHomeFocusVisible(item) {
        if (!isTv) return
        var point = item.mapToItem(main, 0, 0)
        if(point.y < viewport.contentY) viewport.contentY = Math.max(0, point.y - 12)
        else if(point.y + item.height > viewport.contentY + viewport.height) viewport.contentY = Math.min(viewport.contentHeight - viewport.height, point.y + item.height - viewport.height + 12)
    }
    function moveHomeFocus(step) {
        if (!root.isTv) return false
        var controls = [homeSettingsButton, importQrButton, importClipboardButton, connectBtn, homePurchaseButton, tvPairButton, homeSubscriptionButton, locationTile, directTile, familyTile, supportTile]
        controls = controls.filter(function(item) { return item.visible && item.enabled })
        var index = controls.findIndex(function(item) { return item.activeFocus })
        if (!controls.length) return false
        var next = controls[index < 0 ? 0 : (index + step + controls.length) % controls.length]
        next.forceActiveFocus(); keepHomeFocusVisible(next); return true
    }
    Keys.onPressed: function(event) {
        if(event.key === Qt.Key_Down || event.key === Qt.Key_Right) event.accepted = moveHomeFocus(1)
        else if(event.key === Qt.Key_Up || event.key === Qt.Key_Left) event.accepted = moveHomeFocus(-1)
    }
    function startTvPairing() {
        if (FlintController.loggedIn) { beginConnect(); return }
        tvPairWaiting = true; tvPairReceivedLogin = false; tvPairMessage = ""
        telegramRequested = false
        tvPairPopup.open()
        FlintController.startTelegramLogin()
    }
    function finishTvPairing() {
        if(!tvPairWaiting || !tvPairReceivedLogin || !FlintController.loggedIn || !FlintController.subscriptionActive || !FlintController.subscriptionUrl) return
        tvPairWaiting = false; tvPairTimer.stop(); tvPairPopup.close()
        FlintController.selectedCountry = "AUTO"
        connectionFailed = false; cancellingConnection = false; lastConnectionError = -1
        resetConnectionDiagnostics()
        connectRequested = true; autoConnection = true; awaitingProfile = true
        FlintController.importSubscription()
    }
    Timer {
        id: tvPairTimer; interval: 45000
        onTriggered: { root.tvPairWaiting = false; root.tvPairMessage = "Вход выполнен, но активная подписка пока не получена. Проверьте тариф или повторите обновление." }
    }
    Connections {
        target: FlintController
        function onAuthChanged() {
            if(root.tvPairWaiting && FlintController.loggedIn) {
                root.tvPairReceivedLogin = true; tvPairTimer.restart()
            }
        }
        function onSubscriptionChanged() { root.finishTvPairing() }
    }
    Popup {
        id: tvPairPopup
        Shortcut { sequence: "Back"; enabled: tvPairPopup.activeFocus; onActivated: tvPairPopup.close() }
 objectName: "tvPairPopup"; parent: root
        onOpened: Qt.callLater(function() { FlintFocus.firstButton(tvPairPopup.contentItem) })
        width: Math.min(root.width - 32, 560); height: Math.min(root.height - 24, 690)
        anchors.centerIn: parent; padding: 22; modal: true; focus: true
        background: Rectangle { radius: 24; color: "#081827"; border.color: root.line }
        onClosed: { root.tvPairWaiting = false; tvPairTimer.stop() }
        contentItem: ColumnLayout {
        property bool flintFocusScope: true
            spacing: 14
            Text { text: "Подключить телевизор"; color: root.ink; font.pixelSize: 25; font.bold: true; Layout.fillWidth: true; wrapMode: Text.Wrap }
            Text { text: "Сканируйте QR камерой телефона и подтвердите вход в Telegram. После этого телевизор подключится по вашей подписке."; color: root.muted; Layout.fillWidth: true; wrapMode: Text.Wrap }
            Rectangle {
                Layout.fillWidth: true; Layout.fillHeight: true; Layout.minimumHeight: 90
                color: "white"; radius: 18
                Image {
                    anchors.fill: parent; anchors.margins: 18; fillMode: Image.PreserveAspectFit
                    source: root.tvPairWaiting && !root.tvPairReceivedLogin && FlintController.telegramPending && FlintController.telegramBotUrl ? MtProxyConfigModel.generateQrCode(FlintController.telegramBotUrl) : ""
                }
                Text { anchors.centerIn: parent; text: root.tvPairReceivedLogin ? "Получаем подписку…" : "Готовим QR…"; color: "#142E40"; visible: root.tvPairReceivedLogin || !FlintController.telegramPending }
            }
            Text { Layout.fillWidth: true; wrapMode: Text.Wrap; color: root.warning; text: root.tvPairMessage || FlintController.lastError }
            Text { Layout.fillWidth: true; wrapMode: Text.Wrap; color: root.muted; text: "При первом подключении разрешите VPN в системном окне на телевизоре."; font.pixelSize: 12 }
            RowLayout {
                Layout.fillWidth: true
                FlintButton { Layout.fillWidth: true; text: "Новый QR"; onClicked: root.startTvPairing() }
                FlintButton { Layout.fillWidth: true; text: "Закрыть"; onClicked: tvPairPopup.close() }
            }
        }
    }

    Popup {
        id: diagnosticsPopup
        objectName: "connectionDiagnosticsPopup"
        parent: root; anchors.centerIn: parent
        width: Math.min(root.width - 28, 510); height: Math.min(root.height - 32, 540)
        modal: true; focus: true; padding: 20
        onOpened: Qt.callLater(function() { FlintFocus.firstButton(diagnosticsPopup.contentItem) })
        Shortcut { sequence: "Back"; enabled: diagnosticsPopup.activeFocus; onActivated: diagnosticsPopup.close() }
        background: Rectangle { radius: 24; color: "#081827"; border.color: root.line }
        contentItem: ColumnLayout {
            property bool flintFocusScope: true
            spacing: 14
            Text { text: "Диагностика подключения"; color: root.ink; font.pixelSize: 22; font.bold: true; wrapMode: Text.Wrap; Layout.fillWidth: true }
            Text { text: root.failureMessage || "Последние этапы запуска VPN. В отчёте нет ключей подписки и данных аккаунта."; color: root.muted; wrapMode: Text.Wrap; Layout.fillWidth: true }
            ScrollView {
                Layout.fillWidth: true; Layout.fillHeight: true; clip: true
                TextArea {
                    text: root.failureDetails; readOnly: true; selectByMouse: true
                    wrapMode: TextEdit.Wrap; color: root.ink; font.pixelSize: 12
                    background: Rectangle { color: "#142C3C"; radius: 12 }
                }
            }
            FlintButton {
                text: "Скопировать диагностику"; Layout.fillWidth: true
                onClicked: { linkClipboard.text = root.failureDetails; linkClipboard.selectAll(); linkClipboard.copy(); PageController.showNotificationMessage("Диагностика скопирована") }
            }
            FlintButton { text: "Закрыть"; Layout.fillWidth: true; onClicked: diagnosticsPopup.close() }
        }
    }
    function openSettings() { settingsPopup.open() }
    function startInitialization() {
        FlintController.initializeServers()
        PageController.showNotificationMessage("Автонастройка серверов запущена. Результаты появятся в списке серверов.")
    }
    Timer {
        interval: 45000 + Math.floor(Math.random() * 15000); repeat: true
        running: ConnectionController.isConnected && FlintController.selectedCountry === "AUTO" && !FlintController.selectedSavedServerId && Qt.application.state === Qt.ApplicationActive
        onTriggered: if (!root.connectionPending) FlintController.refreshServerHealth()
    }
    Connections {
        target: FlintController
        function onAutomaticReconnectRequested() {
            if (ConnectionController.isConnected && !root.connectionPending && FlintController.selectedCountry === "AUTO" && !FlintController.selectedSavedServerId)
                root.chooseLocation({kind:"country",code:"AUTO",name:"Автоматически"})
        }
        function onInitializationFinished() { PageController.showNotificationMessage("Автонастройка завершена. Откройте серверы для просмотра результатов.") }
    }

    function accountTitle() {
        if (FlintController.telegramUsername.length > 0)
            return "@" + FlintController.telegramUsername
        if (FlintController.email.length > 0)
            return FlintController.email
        return "Аккаунт Flint"
    }

    function countryTitle() {
        var savedId = FlintController.selectedSavedServerId
        var saved = FlintController.savedServers
        for (var j = 0; j < saved.length; ++j)
            if (saved[j].id === savedId) return saved[j].name || "Добавленный сервер"
        var code = FlintController.selectedCountry
        if (!code || code === "AUTO") return "Автоматически"
        var list = FlintController.countries
        for (var i = 0; i < list.length; ++i)
            if (list[i].code === code) return list[i].name
        return code
    }

    function beginConnect() {
        if (FlintUpdateController.state.required && !root.connectionPending) { updatesPopup.open(); return }
        if (root.connectionPending) { cancelConnection(); return }
        if (ServersUiController.getServersCount() === 0 && !FlintController.subscriptionActive) {
            accountPopup.open()
            return
        }
        connectionFailed = false; cancellingConnection = false; lastConnectionError = -1
        resetConnectionDiagnostics()
        connectRequested = true
        autoConnection = FlintController.subscriptionActive && FlintController.selectedCountry === "AUTO" && !FlintController.selectedSavedServerId
        if (FlintController.subscriptionActive && !FlintController.selectedSavedServerId) {
            awaitingProfile = true
            traceConnection("PROFILE_PREPARING")
            FlintController.importSubscription()
        } else startTunnel()
    }

    function startTunnel() {
        if (!connectRequested) return
        routingStartupPending = false; routingDebounce.stop()
        lastNativeAttempt = ""
        traceConnection("CORE_START_REQUESTED")
        sawConnectionProgress = false
        connectionDeadline.restart()
        ConnectionController.connectButtonClicked()
    }

    function cancelConnection() {
        routingStartupPending = false; routingDebounce.stop()
        connectionFailed = false
        observedConnection = false
        cancellingConnection = ConnectionController.isConnected || ConnectionController.isConnectionInProgress
        queuedLocation = null; locationSwitch.stop()
        connectRequested = false; awaitingProfile = false; retryPending = false
        connectionDeadline.stop(); retryWait.stop()
        FlintController.cancelProfileImport()
        if (ConnectionController.isConnected || ConnectionController.isConnectionInProgress) ConnectionController.closeConnection()
    }

    function failConnection() {
        traceConnection("FAILED")
        failureMessage = FlintController.vpnFailureMessage()
        failureDetails = connectionReport()
        cancelConnection()
        cancellingConnection = false
        connectionFailed = true
    }

    function showConnectionFailure(fallbackMessage) {
        if (!failureMessage) failureMessage = fallbackMessage
        if (lastConnectionError > 0) PageController.showErrorMessage(lastConnectionError)
        else PageController.showNotificationMessage(fallbackMessage)
        diagnosticsPopup.open()
    }

    function retryConnection() {
        if (!connectRequested || retryPending) return
        lastNativeAttempt = FlintController.vpnDiagnostics()
        connectionDeadline.stop()
        retryPending = true; retryWaitTicks = 0
        ConnectionController.closeConnection()
        retryWait.start()
    }

    Timer {
        id: connectionDeadline; interval: 30000
        onTriggered: {
            var phase = FlintController.vpnDiagnosticStage()
            if ((phase === "VPN_PERMISSION" || phase === "NOTIFICATION_PERMISSION") && Date.now() - root.connectionStartedAt < 120000) {
                restart(); return
            }
            root.traceConnection("START_DEADLINE"); root.retryConnection()
        }
    }
    Timer {
        id: retryWait; interval: 100; repeat: true
        onTriggered: {
            if (ConnectionController.isConnectionInProgress || ConnectionController.isConnected) {
                if (++root.retryWaitTicks < 100) return
                root.failConnection()
                root.showConnectionFailure("Не удалось завершить подключение. Повторите попытку.")
                return
            }
            stop(); root.retryPending = false; root.awaitingProfile = true
            if (root.autoConnection && FlintController.tryNextAutomaticProfile()) return
            root.failConnection()
            if (root.autoConnection) FlintController.initializeServers()
            root.showConnectionFailure("VPN не завершил запуск. Скопируйте диагностику ниже — она поможет определить причину.")
        }
    }
    Connections {
        target: ConnectionController
        function onConnectionStateChanged() {
            root.traceConnection(ConnectionController.isConnected ? "CORE_CONNECTED" : ConnectionController.isConnectionInProgress ? "CORE_PROGRESS" : "CORE_STOPPED")
            if (root.routingStartupPending && ConnectionController.isConnected && !root.connectRequested && root.queuedLocation === null) {
                root.routingStartupPending = false
                root.requestRoutingApply()
            }
            if (ConnectionController.isConnected && !root.cancellingConnection && root.queuedLocation === null) {
                root.connectionFailed = false; root.lastConnectionError = -1
                root.connectRequested = false; root.awaitingProfile = false; root.retryPending = false
                connectionDeadline.stop(); retryWait.stop()
            } else if (root.cancellingConnection) {
                if (!ConnectionController.isConnected && !ConnectionController.isConnectionInProgress)
                    root.cancellingConnection = false
            } else if (root.connectRequested && !root.awaitingProfile) {
                if (ConnectionController.isConnectionInProgress) root.sawConnectionProgress = true
                else if (root.sawConnectionProgress) root.retryConnection()
            }
            root.observedConnection = !root.cancellingConnection && root.queuedLocation === null &&
                (ConnectionController.isConnected || ConnectionController.isConnectionInProgress)
        }
        function onConnectionErrorOccurred(error) {
            root.traceConnection("CORE_ERROR_" + error)
            // Teardown errors belong to the old tunnel, not to a queued target.
            if (root.cancellingConnection || root.queuedLocation !== null) return
            if (root.connectRequested) {
                if (error > 0) root.lastConnectionError = error
                root.retryConnection()
            } else if (root.observedConnection) {
                // An observed native failure changes presentation only. Native
                // teardown owns the connection; this must not cancel imports.
                root.connectionFailed = true
            }
        }
    }
    Connections {
        target: FlintController
        function onVpnPermissionDenied() {
            root.failConnection()
            PageController.showNotificationMessage("Разрешение VPN не выдано. Подключение отменено.")
        }
        function onProfilePreparationFinished(success) {
            if (!root.awaitingProfile || !root.connectRequested) return
            root.traceConnection(success ? "PROFILE_READY" : "PROFILE_FAILED")
            root.awaitingProfile = false
            if (success) root.startTunnel()
            else root.failConnection()
        }
    }

    Connections {
        target: FlintController
        function onTelegramChanged() {
            if (root.telegramRequested && FlintController.telegramPending && FlintController.telegramBotUrl.length > 0 && FlintController.telegramBotUrl !== root.previousTelegramUrl)
                root.openTelegram()
        }
    }

    Component.onCompleted: {
        // Qt may restore focus to the window after a Popup closes. Restore the
        // TV home explicitly once all overlays have closed; touch is unchanged.
        homePopups().forEach(function(popup) {
            popup.closed.connect(function() { Qt.callLater(root.restoreHomeFocus) })
        })
        routingStartupPending = FlintController.initializeRussianRouting()
        IpSplitTunnelingController.setRouteMode(2)
        IpSplitTunnelingController.toggleSplitTunneling(FlintController.ruDirectEnabled)
        routingBootstrapping = false
        if (routingStartupPending && ConnectionController.isConnected) requestRoutingApply()
        FlintController.refresh()
        Qt.callLater(root.restoreHomeFocus)
    }

    Image {
        anchors.fill: parent
        source: "qrc:/ui/qml/Assets/flint-background.jpg"
        fillMode: Image.PreserveAspectCrop
        cache: true
    }

    Rectangle {
        anchors.fill: parent
        color: "#44000811"
    }


    component Glyph: Image {
        property string pathData
        property color tint: root.ink
        width: 24 * root.u
        height: width
        sourceSize.width: 96
        sourceSize.height: 96
        source: "data:image/svg+xml;utf8," + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="' + tint + '" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="' + pathData + '"/></svg>')
    }

    component Tile: Rectangle {
        Layout.fillWidth: true
        Layout.preferredHeight: root.tileHeight
        Layout.minimumHeight: Layout.preferredHeight
        Layout.maximumHeight: Layout.preferredHeight
        radius: 17 * root.u
        color: root.card
        border.width: root.isTv && activeFocus ? 3 : 1
        border.color: root.isTv && activeFocus ? root.mint : root.line
        activeFocusOnTab: true
        property var activate: function() {}
        Keys.onReturnPressed: if (root.isTv) activate()
        Keys.onEnterPressed: if (root.isTv) activate()
        Keys.onPressed: function(event) { if(root.isTv && event.key === Qt.Key_Select) { activate(); event.accepted = true } }
        onActiveFocusChanged: if(activeFocus) root.keepHomeFocusVisible(this)
    }

    Flickable {
        id: viewport
        objectName: "viewport"
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.topMargin: PageController.safeAreaTopMargin + 8
        anchors.bottomMargin: PageController.safeAreaBottomMargin + 8
        width: Math.min(parent.width - 28, root.wideHome ? 1120 : 480)
        contentHeight: height
        boundsBehavior: Flickable.StopAtBounds
        interactive: false
        clip: true

        GridLayout {
            id: main
            objectName: "mainLayout"
            width: viewport.width
            height: viewport.height
            columns: root.wideHome ? 2 : 1
            columnSpacing: 16
            rowSpacing: root.homeGap
            uniformCellWidths: true

            RowLayout {
                Layout.columnSpan: main.columns
                Layout.fillWidth: true
                Layout.preferredHeight: root.denseHome ? 44 : 48
                spacing: 10 * root.u
                Image {
                    source: "qrc:/ui/qml/Assets/flint-logo.png"
                    Layout.preferredWidth: 40 * root.u
                    Layout.preferredHeight: 40 * root.u
                    fillMode: Image.PreserveAspectFit
                }
                ColumnLayout {
                    Layout.fillWidth: true
                    Layout.minimumWidth: 0
                    spacing: 0
                    Text {
                        text: "FLINT"
                        color: root.ink
                        font.pixelSize: 22 * root.u
                        font.bold: true
                        font.letterSpacing: 1.4
                    }
                    Text {
                        Layout.fillWidth: true
                        Layout.minimumWidth: 0
                        text: "Больше свободы в интернете"
                        color: root.muted
                        font.pixelSize: 11 * root.u
                        elide: Text.ElideRight
                    }
                }
                FlintButton {
                    id: homeSettingsButton
                    objectName: "settingsButton"
                    Layout.preferredWidth: 44
                    Layout.preferredHeight: 44
                    Accessible.name: "Настройки"
                    onClicked: settingsPopup.open()
                    background: Rectangle {
                        radius: 14
                        color: "#C50B2134"
                        border.width: 1
                        border.color: root.line
                    }
                    contentItem: Item {
                        Image {
                            anchors.centerIn: parent
                            width: 23; height: 23
                            source: "qrc:/images/controls/settings.svg"
                            sourceSize.width: 69; sourceSize.height: 69
                        }
                    }
                }
            }

            ColumnLayout {
                id: connectionSection
                objectName: "connectionSection"
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.minimumHeight: 0
                spacing: root.homeGap

            Item {
                id: emblemStage
                objectName: "emblemStage"
                Layout.fillWidth: true
                Layout.fillHeight: true
                Layout.minimumHeight: 0
                Layout.preferredHeight: 180
                FlintMascot {
                    id: mainEmblem
                    objectName: "mainEmblem"
                    anchors.centerIn: parent
                    width: Math.min(parent.height, parent.width * 0.72)
                    height: width
                    status: root.connectionVisualState
                    animate: Qt.application.state === Qt.ApplicationActive
                }
                FlintButton {
                    id: importQrButton
                    objectName: "importQrButton"
                    anchors.left: parent.left
                    y: mainEmblem.y
                    width: Math.min(110 * root.u, parent.width * 0.28)
                    height: 44
                    leftPadding: 8; rightPadding: 8; font.pixelSize: 12 * root.u
                    text: "QR-код"
                    enabled: !root.importBusy && !root.qrImageReading
                    onClicked: root.chooseQrSource()
                }
                FlintButton {
                    id: importClipboardButton
                    objectName: "importClipboardButton"
                    anchors.right: parent.right
                    y: mainEmblem.y
                    width: importQrButton.width; height: importQrButton.height
                    leftPadding: 8; rightPadding: 8; font.pixelSize: 12 * root.u
                    text: "Из буфера"
                    enabled: !root.importBusy && !root.qrImageReading
                    onClicked: root.openClipboardImport()
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                Layout.preferredHeight: root.denseHome ? 26 : 50
                spacing: 3
                Text {
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    text: ConnectionController.isConnected ? "Вы защищены" : "Вы не защищены"
                    color: ConnectionController.isConnected ? root.mint : root.ink
                    font.pixelSize: root.denseHome ? 21 : 26
                    font.bold: true
                }
                Text {
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    visible: !root.denseHome
                    text: ConnectionController.isConnected ? "Flint Guard контролирует соединение" : "Подключитесь, чтобы защитить свои данные"
                    color: root.muted
                    font.pixelSize: 12 * root.u
                    wrapMode: Text.Wrap
                }
            }

            FlintButton {
                id: connectBtn
                objectName: "connectButton"
                Layout.fillWidth: true
                Layout.preferredHeight: root.denseHome ? 48 : 56
                enabled: true
                text: root.connectionPending ? "ОТМЕНИТЬ" : (ConnectionController.isConnected ? "ОТКЛЮЧИТЬ" : "ПОДКЛЮЧИТЬСЯ")
                opacity: enabled ? 1 : 0.7
                onClicked: {
                    if (root.connectionPending) root.cancelConnection()
                    else if (ConnectionController.isConnected)
                        root.cancelConnection()
                    else
                        root.beginConnect()
                }
                background: Rectangle {
                    objectName: "connectionButtonBackground"
                    radius: height / 2
                    border.width: 1
                    border.color: Qt.lighter(root.connectionStatusColor, 1.22)
                    color: connectBtn.pressed ? Qt.darker(root.connectionStatusColor, 1.15) : root.connectionStatusColor
                }
                contentItem: Item {
                    Row {
                        objectName: "connectContent"
                        anchors.centerIn: parent
                        spacing: 12 * root.u
                        Glyph {
                            anchors.verticalCenter: parent.verticalCenter
                            tint: "#081827"
                            pathData: "M12 3v9M6 5.6a8.5 8.5 0 1 0 12 0"
                        }
                        Text {
                            text: connectBtn.text
                            color: "#081827"
                            font.pixelSize: 18 * root.u
                            font.bold: true
                        }
                    }
                }
            }

            Rectangle {
                id: guardPanel
                objectName: "guardPanel"
                Layout.fillWidth: true
                Layout.preferredHeight: root.denseHome ? 28 : 36
                radius: 14 * root.u
                color: "#E20A1A2A"
                border.width: 1
                border.color: root.line
                Row {
                    anchors.centerIn: parent
                    spacing: 8 * root.u
                    Glyph {
                        width: 19 * root.u
                        anchors.verticalCenter: parent.verticalCenter
                        tint: ConnectionController.isConnected ? root.mint : root.muted
                        pathData: "M12 2l8 3v6c0 5-4 9-8 11-4-2-8-6-8-11V5zM8 12l3 3 5-6"
                    }
                    Text {
                        text: "Flint Guard"
                        color: root.ink
                        font.bold: true
                        font.pixelSize: 13 * root.u
                    }
                    Rectangle {
                        anchors.verticalCenter: parent.verticalCenter
                        width: 6; height: 6; radius: 3
                        color: ConnectionController.isConnected ? root.mint : "#8297AA"
                    }
                    Text {
                        text: FlintController.profilePreparing ? "Подготовка профиля…" : root.retryPending ? "Проверка другого сервера…" : root.connectRequested || ConnectionController.isConnectionInProgress ? "Подключение…" : (ConnectionController.isConnected ? "Защищено" : "Отключён")
                        color: ConnectionController.isConnected ? root.mint : root.muted
                        font.pixelSize: 12 * root.u
                    }
                }
            }

            FlintButton {
                id: homePurchaseButton
                objectName: "homePurchaseButton"
                Layout.fillWidth: true
                Layout.preferredHeight: 44
                text: "Купить / продлить подписку"
                onClicked: root.openPurchase()
            }

            }

            ColumnLayout {
                id: accountSection
                objectName: "accountSection"
                Layout.fillWidth: true
                Layout.fillHeight: root.wideHome
                Layout.alignment: Qt.AlignTop
                spacing: root.homeGap

            FlintButton {
                id: tvPairButton
                objectName: "tvPairButton"
                visible: root.isTv
                Layout.fillWidth: true
                Layout.preferredHeight: 44
                text: "Добавить с помощью QR"
                onClicked: root.startTvPairing()
            }

            FlintButton {
                id: homeSubscriptionButton
                objectName: "homeSubscriptionButton"
                visible: FlintController.loggedIn
                Layout.fillWidth: true
                Layout.preferredHeight: root.denseHome ? 70 : 84
                topPadding: 6; bottomPadding: 6
                leftPadding: 12; rightPadding: 12
                Accessible.name: "Выбрать подписку"
                contentItem: ColumnLayout {
                    id: subscriptionLabels; spacing: 2
                    RowLayout {
                        Layout.fillWidth: true
                        Text { text: Usage.title(FlintController.selectedSubscription); font.pixelSize: root.denseHome ? 12 : 14; color: root.ink; font.bold: true; elide: Text.ElideRight; Layout.fillWidth: true }
                        Text { text: "Сменить ›"; color: root.mint; font.pixelSize: 12 }
                    }
                    FlintTrafficBar { compact: root.denseHome; Layout.fillWidth: true; Layout.preferredHeight: implicitHeight; subscription: FlintController.selectedSubscription; textColor: root.muted }
                }
                onClicked: subscriptionsPopup.open()
            }

            GridLayout {
                id: featureGrid
                objectName: "featureGrid"
                Layout.fillWidth: true
                columns: 2
                columnSpacing: root.homeGap
                rowSpacing: root.homeGap
                uniformCellWidths: true

                Tile {
                    id: locationTile
                    activate: function() { root.openLocations() }
                    Column {
                        anchors.fill: parent
                        anchors.margins: root.denseHome ? 8 : 12
                        spacing: root.denseHome ? 2 : 8
                        Row {
                            width: parent.width
                            spacing: 8 * root.u
                            Glyph { width: root.denseHome ? 20 : 24; pathData: "M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 0 1 16 0zM15 10a3 3 0 1 1-6 0 3 3 0 0 1 6 0" }
                            Text {
                                width: parent.width - 32 * root.u
                                anchors.verticalCenter: parent.verticalCenter
                                text: root.countryTitle()
                                color: root.ink
                                font.bold: true
                                font.pixelSize: 13 * root.u
                                elide: Text.ElideRight
                            }
                        }
                        Text { text: FlintController.selectedSavedServerId ? "Добавленный сервер" : "Сервер подписки"; color: root.muted; font.pixelSize: root.denseHome ? 11 : 12 }
                    }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: root.openLocations()
                    }
                }

                Tile {
                    id: directTile
                    activate: function() { sitesPopup.open() }
                    Item {
                        anchors.fill: parent
                        anchors.leftMargin: 8; anchors.rightMargin: 8
                        Column {
                            id: directLabels
                            anchors.left: parent.left; anchors.right: ruSwitch.left
                            anchors.rightMargin: 4; anchors.verticalCenter: parent.verticalCenter
                            spacing: 2
                            Text { width: parent.width; text: "Сайты РФ"; color: root.ink; font.bold: true; font.pixelSize: 13 * root.u; elide: Text.ElideRight }
                            Text { width: parent.width; text: "Правила →"; color: FlintController.ruDirectEnabled ? root.mint : root.muted; font.pixelSize: 11; elide: Text.ElideRight }
                        }
                        MouseArea { anchors.fill: directLabels; onClicked: sitesPopup.open() }
                        Switch {
                            id: ruSwitch
                        activeFocusOnTab: true
                        Keys.onPressed: function(event) { FlintFocus.toggleKey(this,event) }
                            objectName: "russianSwitch"
                            anchors.right: parent.right; anchors.verticalCenter: parent.verticalCenter
                            width: 44; height: 44; padding: 0
                            checked: FlintController.ruDirectEnabled
                            Accessible.name: "Российские сайты напрямую"
                            contentItem: Item {}
                            indicator: Rectangle {
                                width: 44; height: 24; y: (ruSwitch.height - height) / 2
                                radius: 12; color: ruSwitch.checked ? root.mint : "#3D5364"
                                Rectangle { x: ruSwitch.checked ? 23 : 3; y: 3; width: 18; height: 18; radius: 9; color: "#F8FBFF" }
                            }
                            onToggled: {
                                FlintController.ruDirectEnabled = checked
                                IpSplitTunnelingController.setRouteMode(2)
                                IpSplitTunnelingController.toggleSplitTunneling(checked)
                            }
                        }
                    }
                }

                Tile {
                    id: familyTile
                    activate: function() { FlintController.loggedIn ? devicesPopup.open() : accountPopup.open() }
                    Column {
                        anchors.fill: parent
                        anchors.margins: root.denseHome ? 8 : 12
                        spacing: root.denseHome ? 2 : 8
                        Row {
                            spacing: 8 * root.u
                            Glyph { width: root.denseHome ? 20 : 24; pathData: "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0M22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8" }
                            Text { anchors.verticalCenter: parent.verticalCenter; text: "Устройства"; color: root.ink; font.bold: true; font.pixelSize: 13 * root.u }
                        }
                        Text {
                            width: parent.width
                            text: FlintController.loggedIn ? "Устройства и доступ" : "Войти в аккаунт"
                            color: FlintController.subscriptionActive ? root.mint : root.muted
                            font.pixelSize: root.denseHome ? 11 : 12
                            elide: Text.ElideRight
                        }
                    }
                    MouseArea { anchors.fill: parent; onClicked: { if (FlintController.loggedIn) devicesPopup.open(); else accountPopup.open() } }
                }

                Tile {
                    id: supportTile
                    activate: function() { FlintController.loggedIn ? (servicePopup.section = 3, servicePopup.open()) : accountPopup.open() }
                    Column {
                        anchors.fill: parent
                        anchors.margins: root.denseHome ? 8 : 12
                        spacing: root.denseHome ? 2 : 8
                        Row {
                            spacing: 8 * root.u
                            Image { width: root.denseHome ? 20 : 24; height: width; source: "qrc:/ui/qml/Assets/flint-logo.png" }
                            Text { anchors.verticalCenter: parent.verticalCenter; text: "Поддержка"; color: root.ink; font.bold: true; font.pixelSize: 13 * root.u }
                        }
                        Text {
                            width: parent.width
                            text: FlintController.apiOnline ? "Flint готов помочь" : "Работаем офлайн"
                            color: FlintController.apiOnline ? root.mint : root.warning
                            font.pixelSize: root.denseHome ? 11 : 12
                            elide: Text.ElideRight
                        }
                    }
                    MouseArea { anchors.fill: parent; onClicked: { if (FlintController.loggedIn) { servicePopup.section = 3; servicePopup.open() } else accountPopup.open() } }
                }
            }


            Text {
                visible: FlintController.lastError.length > 0
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignHCenter
                text: root.displayBackendError
                color: "#FF9A9A"
                font.pixelSize: 11 * root.u
                elide: Text.ElideRight
                maximumLineCount: 1
                MouseArea { anchors.fill: parent; onClicked: PageController.showNotificationMessage(root.displayBackendError) }
            }
            }
        }
    }

    Popup {
        id: accountPopup
        Shortcut { sequence: "Back"; enabled: accountPopup.activeFocus; onActivated: accountPopup.close() }
        onOpened: Qt.callLater(function() { FlintFocus.firstButton(accountPopup.contentItem) })
        objectName: "accountPopup"
        x: Math.round((root.width - width) / 2)
        y: Math.round((root.height - height) / 2)
        width: Math.min(root.width - 28, 470)
        height: Math.min(root.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - 30, 680)
        modal: true
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

        background: Rectangle {
            radius: 23
            color: "#FC081827"
            border.width: 1
            border.color: root.line
        }

        contentItem: ScrollView {
            property bool flintFocusScope: true
            id: accountScroll
            clip: true
            contentWidth: availableWidth
            ColumnLayout {
            width: accountScroll.availableWidth
            spacing: 10

            RowLayout {
                Layout.fillWidth: true
                Image {
                    source: "qrc:/ui/qml/Assets/flint-logo.png"
                    Layout.preferredWidth: 45
                    Layout.preferredHeight: 45
                }
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 0
                    Text {
                        text: FlintController.loggedIn ? "Аккаунт Flint" : "Вход во Flint"
                        color: root.ink
                        font.pixelSize: 22
                        font.bold: true
                    }
                    Text { text: "Windows • Android • TV"; color: root.muted; font.pixelSize: 11 }
                }
            }

            FlintField {
                id: emailField
                visible: !FlintController.loggedIn
                Layout.fillWidth: true
                placeholderText: "Email"
                inputMethodHints: Qt.ImhEmailCharactersOnly
            }

            FlintField {
                id: passwordField
                visible: !FlintController.loggedIn
                Layout.fillWidth: true
                placeholderText: "Пароль"
                echoMode: TextInput.Password
            }

            FlintButton {
                visible: !FlintController.loggedIn
                Layout.fillWidth: true
                primary: true
                text: "Войти"
                enabled: !FlintController.busy
                onClicked: FlintController.login(emailField.text, passwordField.text)
            }

            FlintButton {
                visible: !FlintController.loggedIn
                Layout.fillWidth: true
                text: root.telegramNeedsRestart ? "Начать вход заново" : (FlintController.telegramPending ? "Открыть Telegram" : "Войти через Telegram")
                enabled: !FlintController.busy
                objectName: "telegramLoginButton"
                onClicked: root.beginTelegram(false)
            }

            FlintButton {
                visible: !FlintController.loggedIn
                Layout.fillWidth: true
                text: "Создать аккаунт"
                enabled: !FlintController.busy
                onClicked: FlintController.registerAccount(emailField.text, passwordField.text)
            }

            ColumnLayout {
                visible: FlintController.loggedIn
                Layout.fillWidth: true
                spacing: 8

                Text {
                    Layout.fillWidth: true
                    text: root.accountTitle()
                    color: root.ink
                    font.pixelSize: 16
                    font.bold: true
                    elide: Text.ElideRight
                }

                Text {
                    text: FlintController.subscriptionActive ? "Подписка активна" : "Нет активной подписки"
                    color: FlintController.subscriptionActive ? root.mint : root.warning
                }

                Text {
                    text: "Сеансов входа: " + FlintController.sessionsCount
                    color: root.muted
                }

                FlintButton {
                    Layout.fillWidth: true
                    text: "Обновить"
                    onClicked: FlintController.refresh()
                }

                FlintButton {
                    Layout.fillWidth: true
                    text: "Подготовить профиль"
                    enabled: FlintController.subscriptionActive
                    onClicked: FlintController.importSubscription(true, true)
                }

                FlintButton {
                    Layout.fillWidth: true
                    text: "Выйти"
                    onClicked: FlintController.logout()
                }
            }

            Text {
                Layout.fillWidth: true
                visible: root.telegramError.length > 0 || FlintController.lastError.length > 0
                text: root.telegramNeedsRestart ? root.displayBackendError : (root.telegramError || root.displayBackendError)
                color: "#FF9A9A"
                wrapMode: Text.Wrap
            }

            Text {
                visible: !FlintController.loggedIn && !root.telegramNeedsRestart && FlintController.telegramPending
                Layout.fillWidth: true
                text: "Подтвердите вход в Telegram-боте, затем вернитесь во Flint."
                color: root.mint
                wrapMode: Text.Wrap
            }

            FlintButton {
                Layout.fillWidth: true
                visible: !FlintController.loggedIn && !root.telegramNeedsRestart && FlintController.telegramPending
                objectName: "restartTelegramButton"
                text: "Начать вход заново"
                enabled: !FlintController.busy
                onClicked: root.beginTelegram(true)
            }

            RowLayout {
                Layout.fillWidth: true
                visible: !FlintController.loggedIn && !root.telegramNeedsRestart && FlintController.telegramPending && FlintController.telegramBotUrl.length > 0
                FlintButton {
                    Layout.fillWidth: true
                    text: "В браузере"
                    onClicked: {
                        if (!Qt.openUrlExternally(FlintController.telegramBotUrl))
                            root.telegramError = "Браузер не открылся. Скопируйте ссылку."
                    }
                }
                FlintButton {
                    Layout.fillWidth: true
                    text: "Копировать ссылку"
                    onClicked: {
                        linkClipboard.text = FlintController.telegramBotUrl
                        linkClipboard.selectAll()
                        linkClipboard.copy()
                        linkClipboard.text = ""
                        root.telegramError = ""
                        PageController.showNotificationMessage("Ссылка скопирована")
                    }
                }
            }

            Item { Layout.fillHeight: true }

            FlintButton {
                text: "Закрыть"
                Layout.alignment: Qt.AlignRight
                onClicked: accountPopup.close()
            }
        }
        }
    }

    Popup {
        id: countryPopup
        Shortcut { sequence: "Back"; enabled: countryPopup.activeFocus; onActivated: countryPopup.close() }
        onOpened: Qt.callLater(function() { FlintFocus.firstButton(countryPopup.contentItem) })
        x: Math.round((root.width - width) / 2)
        y: Math.round((root.height - height) / 2)
        width: Math.min(root.width - 28, 430)
        height: Math.min(root.height * 0.66, 430)
        modal: true
        focus: true

        background: Rectangle {
            radius: 23
            color: "#FC081827"
            border.width: 1
            border.color: root.line
        }

        contentItem: ColumnLayout {
        property bool flintFocusScope: true
            spacing: 9

            Text {
                text: "Выбор сервера"
                color: root.ink
                font.pixelSize: 21
                font.bold: true
            }

            ListView {
                id: locationList
                objectName: "locationList"
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                spacing: 10
                model: root.locationChoices
                ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

                delegate: FlintButton {
                    id: locationButton
                    Keys.onPressed: function(event) {
                        if (!root.isTv) return
                        if (event.key === Qt.Key_Down || event.key === Qt.Key_Up) {
                            locationList.currentIndex = Math.max(0, Math.min(locationList.count-1, index + (event.key === Qt.Key_Down ? 1 : -1)))
                            locationList.positionViewAtIndex(locationList.currentIndex, ListView.Contain)
                            Qt.callLater(function() { if(locationList.currentItem) locationList.currentItem.forceActiveFocus() })
                            event.accepted = true
                        } else if ([Qt.Key_Select, Qt.Key_Return, Qt.Key_Enter].indexOf(event.key) >= 0) { locationButton.clicked(); event.accepted = true }
                        else if (event.key === Qt.Key_Right || event.key === Qt.Key_Left) event.accepted = popupFocus(event.key === Qt.Key_Right)
                    }
                    required property int index
                    required property var modelData
                    width: ListView.view.width
                    height: 78
                    enabled: !root.connectionPending
                    text: modelData.name
                    highlighted: modelData.kind === "saved"
                        ? FlintController.selectedSavedServerId === modelData.id
                        : !FlintController.selectedSavedServerId && FlintController.selectedCountry === modelData.code
                    background: Rectangle {
                        radius: 13
                        color: (FlintController.healthRevision >= 0 && FlintController.serverUnavailable(modelData.code || modelData.id)) ? "#35404A" : (locationButton.highlighted ? "#57E4B0" : "#142E40")
                        border.color: locationButton.tvFocusVisible ? root.mint : "#46637A"
                    }
                    contentItem: ColumnLayout {
                        spacing: 3
                        Text {
                            Layout.fillWidth: true
                            text: modelData.name
                            textFormat: Text.PlainText
                            font.pixelSize: 15
                            font.weight: Font.DemiBold
                            color: locationButton.highlighted && !(FlintController.healthRevision >= 0 && FlintController.serverUnavailable(modelData.code || modelData.id)) ? "#052A20" : root.ink
                            elide: Text.ElideRight
                        }
                        Text {
                            text: { var revision = FlintController.healthRevision; return modelData.code === "AUTO" ? "По доступности, задержке и загрузке" : FlintController.serverHealthText(modelData.code || modelData.id) }
                            font.pixelSize: 11
                            color: locationButton.highlighted && !(FlintController.healthRevision >= 0 && FlintController.serverUnavailable(modelData.code || modelData.id)) ? "#164C3C" : root.muted
                        }
                    }
                    onClicked: root.chooseLocation(modelData)
                }
            }

            RowLayout {
                Layout.fillWidth: true
                FlintButton { text: "Проверить"; enabled: !FlintController.healthBusy; onClicked: FlintController.refreshServerHealth() }
                FlintButton { text: "Автонастройка"; enabled: !root.connectionPending; onClicked: { countryPopup.close(); root.startInitialization() } }
            }
            FlintButton {
                text: "Закрыть"
                Layout.alignment: Qt.AlignRight
                onClicked: countryPopup.close()
            }
        }
    }

    Popup {
        id: assistPopup
        Shortcut { sequence: "Back"; enabled: assistPopup.activeFocus; onActivated: assistPopup.close() }
        x: Math.round((root.width - width) / 2)
        y: Math.round((root.height - height) / 2)
        width: Math.min(root.width - 28, 460)
        height: Math.min(root.height * 0.56, 380)
        modal: true
        focus: true

        background: Rectangle {
            radius: 23
            color: "#FC081827"
            border.width: 1
            border.color: root.line
        }

        contentItem: ColumnLayout {
        property bool flintFocusScope: true
            spacing: 10

            RowLayout {
                Layout.fillWidth: true
                Image {
                    source: "qrc:/ui/qml/Assets/flint-logo.png"
                    Layout.preferredWidth: 44
                    Layout.preferredHeight: 44
                }
                Text { text: "Flint Assist"; color: root.ink; font.pixelSize: 21; font.bold: true }
            }

            Text {
                Layout.fillWidth: true
                text: FlintController.assistTitle
                color: root.mint
                font.bold: true
                wrapMode: Text.Wrap
            }

            Text {
                Layout.fillWidth: true
                Layout.fillHeight: true
                text: FlintController.assistReply.length > 0
                      ? FlintController.assistReply
                      : "Если соединение даст сбой — подскажу, что делать."
                color: root.ink
                wrapMode: Text.Wrap
            }

            FlintButton {
                Layout.fillWidth: true
                text: "Написать оператору"
                onClicked: { assistPopup.close(); servicePopup.section = 3; servicePopup.open() }
            }
            RowLayout {
                Layout.fillWidth: true
                FlintButton {
                    Layout.fillWidth: true
                    text: "Подключение"
                    onClicked: FlintController.askAssist("не подключается")
                }
                FlintButton {
                    Layout.fillWidth: true
                    text: "Госзакупки"
                    onClicked: FlintController.askAssist("zakupki российские сервисы")
                }
            }

            FlintButton {
                text: "Закрыть"
                Layout.alignment: Qt.AlignRight
                onClicked: assistPopup.close()
            }
        }
    }

    Popup {
        id: familyQrPopup
        Shortcut { sequence: "Back"; enabled: familyQrPopup.activeFocus; onActivated: familyQrPopup.close() }
        onOpened: Qt.callLater(function() { FlintFocus.firstButton(familyQrPopup.contentItem) })
        x: Math.round((root.width - width) / 2)
        y: Math.round((root.height - height) / 2)
        width: Math.min(root.width - 28, 430)
        height: Math.min(root.height * 0.72, 560)
        modal: true
        focus: true

        background: Rectangle {
            radius: 23
            color: "#FC081827"
            border.width: 1
            border.color: root.line
        }

        contentItem: ColumnLayout {
        property bool flintFocusScope: true
            spacing: 9

            Text {
                Layout.fillWidth: true
                text: "Добавить устройство"
                color: root.ink
                font.pixelSize: 21
                font.bold: true
                horizontalAlignment: Text.AlignHCenter
            }

            Text {
                Layout.fillWidth: true
                text: "Отсканируйте QR на своём телефоне, планшете или телевизоре."
                color: root.muted
                font.pixelSize: 11
                wrapMode: Text.Wrap
                horizontalAlignment: Text.AlignHCenter
            }

            Rectangle {
                Layout.preferredWidth: Math.min(familyQrPopup.width - 70, 310)
                Layout.preferredHeight: Layout.preferredWidth
                Layout.alignment: Qt.AlignHCenter
                radius: 18
                color: "white"
                visible: FlintController.subscriptionUrl.length > 0

                Image {
                    anchors.fill: parent
                    anchors.margins: 12
                    source: FlintController.subscriptionUrl.length > 0
                          ? MtProxyConfigModel.generateQrCode(FlintController.subscriptionUrl)
                          : ""
                    fillMode: Image.PreserveAspectFit
                    cache: false
                }
            }

            Text {
                Layout.fillWidth: true
                text: "QR содержит секретную ссылку подписки Flint. Не отправляйте его посторонним."
                color: root.warning
                font.pixelSize: 11
                wrapMode: Text.Wrap
                horizontalAlignment: Text.AlignHCenter
            }

            Text {
                Layout.fillWidth: true
                text: "Это общий ключ подписки. Устройства с этим ключом нельзя отключить по отдельности."
                color: root.muted
                font.pixelSize: 11
                wrapMode: Text.Wrap
                horizontalAlignment: Text.AlignHCenter
            }

            Item { Layout.fillHeight: true }

            FlintButton {
                text: "Закрыть"
                Layout.alignment: Qt.AlignHCenter
                onClicked: familyQrPopup.close()
            }
        }
    }

    Popup {
        id: settingsPopup
        Shortcut { sequence: "Back"; enabled: settingsPopup.activeFocus; onActivated: settingsPopup.close() }
        onOpened: Qt.callLater(function() { FlintFocus.firstButton(settingsPopup.contentItem) })
        x: Math.round((root.width - width) / 2)
        y: Math.round((root.height - height) / 2)
        width: Math.min(root.width - 28, 440)
        height: Math.min(root.height - 32, 470)
        modal: true
        focus: true

        background: Rectangle {
            radius: 23
            color: "#FC081827"
            border.width: 1
            border.color: root.line
        }

        contentItem: ScrollView {
            property bool flintFocusScope: true
            id: settingsScroll
            clip: true
            contentWidth: availableWidth
            ColumnLayout {
            width: settingsScroll.availableWidth
            spacing: 10
            FlintButton {
                Layout.fillWidth: true
                text: "Подписки, покупки и поддержка"
                onClicked: { settingsPopup.close(); if (FlintController.loggedIn) { servicePopup.section = 0; servicePopup.open() } else accountPopup.open() }
            }
            FlintButton {
                Layout.fillWidth: true
                text: "Адрес API сервиса"
                onClicked: { settingsPopup.close(); apiBaseField.text = FlintController.apiBase; apiSetupPopup.open() }
            }

            FlintButton {
                objectName: "checkFlintUpdates"
                Layout.fillWidth: true
                text: FlintUpdateController.state.available ? "Доступно обновление" : "Обновление приложения"
                onClicked: { settingsPopup.close(); updatesPopup.open(); FlintUpdateController.check(true) }
            }
            Text { text: "Настройки Flint"; color: root.ink; font.pixelSize: 21; font.bold: true }
            Text { text: "Flint Android 8.10.19"; color: root.muted }
            FlintButton {
                Layout.fillWidth: true
                text: "Диагностика подключения"
                onClicked: { settingsPopup.close(); root.openConnectionDiagnostics() }
            }

            FlintButton {
                Layout.fillWidth: true
                text: "Добавить виджет на экран"
                onClicked: { settingsPopup.close(); FlintController.requestHomeWidget() }
            }

            Text {
                Layout.fillWidth: true
                text: FlintController.apiOnline
                      ? "Flint API доступен"
                      : "API отвечает медленно. Сохранённый профиль продолжает работать."
                color: FlintController.apiOnline ? root.mint : root.warning
                wrapMode: Text.Wrap
            }

            FlintButton {
                Layout.fillWidth: true
                text: FlintController.loggedIn ? root.accountTitle() : "Войти во Flint"
                onClicked: {
                    settingsPopup.close()
                    accountPopup.open()
                }
            }

            FlintButton {
                Layout.fillWidth: true
                text: "Обновить данные"
                onClicked: FlintController.refresh()
            }


            FlintButton {
                text: "Закрыть"
                Layout.alignment: Qt.AlignRight
                onClicked: settingsPopup.close()
            }
        }
    }
    }

    Popup {
        id: importPopup
        Shortcut { sequence: "Back"; enabled: importPopup.activeFocus; onActivated: importPopup.close() }
        onOpened: Qt.callLater(function() { FlintFocus.firstButton(importPopup.contentItem) })
        objectName: "importPopup"
        x: (root.width - width) / 2
        y: PageController.safeAreaTopMargin + 12
        width: Math.min(root.width - 28, 470)
        height: Math.min(root.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - 24, 550)
        modal: true
        focus: true
        closePolicy: root.importBusy ? Popup.NoAutoClose : Popup.CloseOnEscape | Popup.CloseOnPressOutside
        onClosed: { if (!root.importBusy) { importText.text = ""; root.qrImageRequest = ""; root.qrImageReading = false } }
        background: Rectangle { radius: 23; color: "#FC081827"; border.width: 1; border.color: root.line }
        contentItem: ScrollView {
            property bool flintFocusScope: true
            id: importScroll
            clip: true
            contentWidth: availableWidth
            ColumnLayout {
                width: importScroll.availableWidth
                spacing: 12
                Text { Layout.fillWidth: true; text: "Добавить подключение"; color: root.ink; font.pixelSize: 21; font.bold: true; wrapMode: Text.Wrap }
                Text {
                    Layout.fillWidth: true
                    text: "Вставьте ключ VPN или отсканируйте QR-код профиля."
                    color: root.muted
                    wrapMode: Text.Wrap
                }
                TextArea {
                    id: importText
                    objectName: "importText"
                    Layout.fillWidth: true
                    Layout.preferredHeight: 120
                    enabled: !root.importBusy && !root.qrImageReading
                    visible: !root.importReady
                    placeholderText: "vpn://, vless:// или текст конфигурации"
                    color: root.ink
                    placeholderTextColor: root.muted
                    selectionColor: root.mint
                    selectedTextColor: "#081827"
                    background: Rectangle { radius: 10; color: "#0A1C2D"; border.color: root.line }
                    wrapMode: TextEdit.WrapAnywhere
                    selectByMouse: true
                    inputMethodHints: Qt.ImhNoPredictiveText | Qt.ImhNoAutoUppercase
                    onTextChanged: { root.importReady = false; root.importError = "" }
                }
                RowLayout {
                    Layout.fillWidth: true
                    visible: !root.importReady
                    FlintButton {
                        Layout.fillWidth: true
                        text: "Вставить"
                        enabled: !root.importBusy && !root.qrImageReading
                        onClicked: { importText.text = ""; importText.paste() }
                    }
                    FlintButton {
                        Layout.fillWidth: true
                        text: "QR-код"
                        enabled: !root.importBusy && !root.qrImageReading
                        onClicked: root.chooseQrSource()
                    }
                }
                Text {
                    Layout.fillWidth: true
                    visible: root.importError.length > 0
                    text: root.importError
                    color: "#FF9A9A"
                    wrapMode: Text.Wrap
                }
                ColumnLayout {
                    Layout.fillWidth: true
                    visible: root.importReady
                    spacing: 10
                    Text { text: "Профиль распознан"; color: root.mint; font.bold: true; font.pixelSize: 17 }
                    Text {
                        Layout.fillWidth: true
                        text: root.externalImportCount > 0 ? "Серверов в подписке: " + root.externalImportCount : (ImportController.configFileName || "Новое VPN-подключение")
                        color: root.ink
                        wrapMode: Text.Wrap
                    }
                    Text {
                        Layout.fillWidth: true
                        visible: ImportController.maliciousWarningText.length > 0
                        text: ImportController.maliciousWarningText
                        color: root.warning
                        wrapMode: Text.Wrap
                    }
                    CheckBox {
                        id: importCloaking
                        activeFocusOnTab: true
                        Keys.onPressed: function(event) { FlintFocus.toggleKey(this,event) }
                        Layout.fillWidth: true
                        visible: ImportController.isNativeWireGuardConfig
                        text: "Включить обфускацию WireGuard"
                    }
                    FlintButton {
                        Layout.fillWidth: true
                        text: "Другой ключ"
                        enabled: !root.importBusy && !root.qrImageReading
                        onClicked: root.importReady = false
                    }
                }
                FlintButton {
                    objectName: "confirmImportButton"
                    Layout.fillWidth: true
                    enabled: !root.importBusy && !root.qrImageReading
                    text: root.qrImageReading ? "Читаю QR-код…" : root.importBusy ? "Добавление…" : (root.importReady ? "Добавить профиль" : "Проверить ключ")
                    onClicked: root.importReady ? root.saveImport() : root.parseImport()
                }
                FlintButton {
                    Layout.fillWidth: true
                    text: "Закрыть"
                    enabled: !root.importBusy && !root.qrImageReading
                    onClicked: importPopup.close()
                }
            }
        }
    }

    Popup {
        id: apiSetupPopup
        Shortcut { sequence: "Back"; enabled: apiSetupPopup.activeFocus; onActivated: apiSetupPopup.close() }
        onOpened: Qt.callLater(function() { FlintFocus.firstButton(apiSetupPopup.contentItem) })
        x: (root.width - width) / 2
        y: PageController.safeAreaTopMargin + 16
        width: Math.min(root.width - 24, 470)
        height: Math.min(root.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - 32, 410)
        modal: true; focus: true
        background: Rectangle { radius: 20; color: "#FD081827"; border.color: root.line }
        contentItem: ScrollView {
            property bool flintFocusScope: true
            id: apiSetupScroll; clip: true; contentWidth: availableWidth
            ColumnLayout {
                width: apiSetupScroll.availableWidth; spacing: 14
                Text { Layout.fillWidth: true; text: "Подключение к сервису"; color: root.ink; font.pixelSize: 21; wrapMode: Text.Wrap }
                Text { Layout.fillWidth: true; text: "Адрес HTTPS API, полученный от администратора. После смены сервера потребуется войти повторно."; color: root.muted; wrapMode: Text.Wrap }
                FlintField { id: apiBaseField; Layout.fillWidth: true; placeholderText: "https://example.com/api/v1"; inputMethodHints: Qt.ImhUrlCharactersOnly }
                Text { Layout.fillWidth: true; visible: FlintController.lastError.length > 0; text: FlintController.lastError; color: "#FFAAAA"; wrapMode: Text.Wrap }
                FlintButton { primary: true; text: "Сохранить адрес"; onClicked: { if (FlintController.setApiBase(apiBaseField.text)) { apiSetupPopup.close(); accountPopup.open() } } }
                FlintButton { text: "Отмена"; onClicked: apiSetupPopup.close() }
            }
        }
    }
}
