import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Qt5Compat.GraphicalEffects

import Style 1.0
import "./"
import "../Controls2"

PageType {
    id: root

    property color ink: "#F7FBFF"
    property color muted: "#B9CCE0"
    property color mint: "#49E8A7"
    property color mint2: "#7CFFD4"
    property color card: "#E10A1C2D"
    property color card2: "#E6122A40"
    property color line: "#3C6682"
    property color warning: "#FFC56D"

    function openSettings() { settingsPopup.open() }

    function accountTitle() {
        if (FlintController.telegramUsername.length > 0)
            return "@" + FlintController.telegramUsername
        if (FlintController.email.length > 0)
            return FlintController.email
        return "аккаунт Flint"
    }

    function ensureReady() {
        if (!FlintController.loggedIn || !FlintController.subscriptionActive) {
            accountPopup.open()
            return false
        }
        if (ServersUiController.getServersCount() === 0) {
            FlintController.importSubscription()
            delayedConnect.restart()
            return false
        }
        return true
    }

    Timer {
        id: delayedConnect
        interval: 900
        repeat: false
        onTriggered: {
            if (ServersUiController.getServersCount() > 0) {
                ConnectionController.connectButtonClicked()
            } else {
                PageController.showNotificationMessage("Профиль Flint загружается. Нажмите «Подключиться» ещё раз через секунду.")
                FlintController.refresh()
            }
        }
    }

    Component.onCompleted: {
        FlintController.refresh()
        if (FlintController.subscriptionActive)
            FlintController.importSubscription()
    }

    Image {
        anchors.fill: parent
        source: "qrc:/ui/qml/Assets/flint-background.svg"
        fillMode: Image.PreserveAspectCrop
        cache: true
    }

    Rectangle {
        anchors.fill: parent
        color: "#29000B14"
    }

    Flickable {
        id: scroller
        anchors.fill: parent
        contentWidth: width
        contentHeight: body.implicitHeight + 58 + PageController.safeAreaTopMargin + PageController.safeAreaBottomMargin
        clip: true
        boundsBehavior: Flickable.StopAtBounds

        ColumnLayout {
            id: body
            width: Math.min(scroller.width - 26, 590)
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top
            anchors.topMargin: 12 + PageController.safeAreaTopMargin
            spacing: 12

            RowLayout {
                Layout.fillWidth: true
                spacing: 10

                Image {
                    source: "qrc:/ui/qml/Assets/flint-logo.svg"
                    Layout.preferredWidth: 52
                    Layout.preferredHeight: 52
                    fillMode: Image.PreserveAspectFit
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: -2
                    Text {
                        text: "FLINT"
                        color: root.ink
                        font.pixelSize: 27
                        font.bold: true
                        font.letterSpacing: 1.6
                    }
                    Text {
                        text: "Больше свободы в интернете"
                        color: root.muted
                        font.pixelSize: 12
                    }
                }

                Rectangle {
                    width: 44
                    height: 44
                    radius: 15
                    color: "#B70C2134"
                    border.width: 1
                    border.color: root.line
                    Text {
                        anchors.centerIn: parent
                        text: "⚙"
                        color: root.ink
                        font.pixelSize: 22
                    }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: settingsPopup.open()
                    }
                }
            }

            Item {
                Layout.fillWidth: true
                Layout.preferredHeight: Math.min(scroller.width * 0.68, 330)

                Image {
                    id: dog
                    anchors.centerIn: parent
                    width: Math.min(parent.width * 0.70, 300)
                    height: width
                    source: "qrc:/ui/qml/Assets/flint-dog.svg"
                    fillMode: Image.PreserveAspectFit
                    cache: true
                    layer.enabled: true
                    layer.effect: DropShadow {
                        radius: 24
                        samples: 33
                        color: ConnectionController.isConnected ? "#A449E8A7" : "#6634B9D5"
                        verticalOffset: 3
                    }
                }
            }

            Text {
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignHCenter
                text: ConnectionController.isConnected ? "Вы защищены" : "Вы не защищены"
                color: ConnectionController.isConnected ? root.mint : root.ink
                font.pixelSize: 30
                font.bold: true
            }

            Text {
                Layout.fillWidth: true
                Layout.leftMargin: 16
                Layout.rightMargin: 16
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                text: ConnectionController.isConnected
                      ? "Flint Guard контролирует соединение и защищает ваши данные."
                      : "Подключитесь, чтобы защитить данные и открыть нужные сервисы."
                color: root.muted
                font.pixelSize: 13
                lineHeight: 1.15
            }

            Button {
                id: connectButton
                Layout.preferredWidth: Math.min(body.width * 0.90, 455)
                Layout.preferredHeight: 70
                Layout.alignment: Qt.AlignHCenter
                enabled: !FlintController.busy
                text: ConnectionController.isConnected ? "ОТКЛЮЧИТЬ" : "ПОДКЛЮЧИТЬСЯ"

                onClicked: {
                    if (ConnectionController.isConnected) {
                        ConnectionController.connectButtonClicked()
                        return
                    }
                    if (root.ensureReady())
                        ConnectionController.connectButtonClicked()
                }

                background: Rectangle {
                    radius: 35
                    border.width: 1
                    border.color: "#AEFFE3"
                    gradient: Gradient {
                        GradientStop { position: 0; color: connectButton.pressed ? "#31C980" : "#3EDC91" }
                        GradientStop { position: 1; color: connectButton.pressed ? "#64EAB0" : "#7AF2C4" }
                    }
                    layer.enabled: true
                    layer.effect: DropShadow {
                        radius: 20
                        samples: 33
                        color: "#6B49E8A7"
                    }
                }

                contentItem: Row {
                    anchors.centerIn: parent
                    spacing: 14
                    Text {
                        text: "⏻"
                        color: "#05251B"
                        font.pixelSize: 34
                    }
                    Rectangle {
                        anchors.verticalCenter: parent.verticalCenter
                        width: 1
                        height: 36
                        color: "#6605251B"
                    }
                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        text: connectButton.text
                        color: "#05251B"
                        font.pixelSize: 20
                        font.bold: true
                    }
                }
            }

            Rectangle {
                Layout.preferredWidth: Math.min(body.width * 0.76, 385)
                Layout.preferredHeight: 54
                Layout.alignment: Qt.AlignHCenter
                radius: 18
                color: "#E30A1B2B"
                border.width: 1
                border.color: root.line

                Row {
                    anchors.centerIn: parent
                    spacing: 9
                    Text { text: "◇"; color: root.ink; font.pixelSize: 22 }
                    Text { text: "Flint Guard"; color: root.ink; font.bold: true; font.pixelSize: 14 }
                    Rectangle {
                        anchors.verticalCenter: parent.verticalCenter
                        width: 9
                        height: 9
                        radius: 5
                        color: ConnectionController.isConnected ? root.mint : "#7693AC"
                    }
                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        text: ConnectionController.isConnected ? "Защищено" : "Flint отключён"
                        color: root.muted
                        font.pixelSize: 12
                    }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 84
                radius: 21
                color: root.card
                border.width: 1
                border.color: root.line

                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 15
                    spacing: 12

                    Rectangle {
                        width: 49; height: 49; radius: 25
                        color: "#214764"
                        Text { anchors.centerIn: parent; text: "⌖"; color: root.ink; font.pixelSize: 24 }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Text { text: "Автоматически"; color: root.ink; font.bold: true; font.pixelSize: 17 }
                        Text {
                            Layout.fillWidth: true
                            text: "Flint выберет лучший доступный сервер"
                            color: root.muted
                            font.pixelSize: 12
                            elide: Text.ElideRight
                        }
                    }

                    Text { text: "›"; color: root.muted; font.pixelSize: 29 }
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: PageController.showNotificationMessage("Автоматический выбор сервера включён")
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 88
                radius: 21
                color: root.card
                border.width: 1
                border.color: root.line

                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 15
                    spacing: 12

                    Rectangle {
                        width: 49; height: 49; radius: 25
                        color: "#214764"
                        Text { anchors.centerIn: parent; text: "РФ"; color: root.ink; font.bold: true; font.pixelSize: 14 }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Text { text: "Российские сервисы"; color: root.ink; font.bold: true; font.pixelSize: 17 }
                        Text {
                            Layout.fillWidth: true
                            text: "zakupki.gov.ru и выбранные сайты — напрямую"
                            color: root.muted
                            font.pixelSize: 12
                            elide: Text.ElideRight
                        }
                    }

                    Switch {
                        id: ruSwitch
                        checked: FlintController.ruDirectEnabled
                        onToggled: {
                            FlintController.ruDirectEnabled = checked
                            if (checked)
                                IpSplitTunnelingController.setRouteMode(2)
                            IpSplitTunnelingController.toggleSplitTunneling(checked)
                        }
                    }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 86
                radius: 21
                color: root.card
                border.width: 1
                border.color: root.line

                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 15
                    spacing: 12

                    Rectangle {
                        width: 49; height: 49; radius: 25
                        color: "#214764"
                        Text { anchors.centerIn: parent; text: "♙"; color: root.ink; font.pixelSize: 23 }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Text { text: "Семейная подписка"; color: root.ink; font.bold: true; font.pixelSize: 17 }
                        Text {
                            Layout.fillWidth: true
                            text: FlintController.loggedIn
                                  ? FlintController.sessionsCount + " устройств • " + root.accountTitle()
                                  : "Войдите во Flint"
                            color: FlintController.subscriptionActive ? root.mint : root.muted
                            font.pixelSize: 12
                            elide: Text.ElideRight
                        }
                    }

                    Text { text: "›"; color: root.muted; font.pixelSize: 29 }
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: accountPopup.open()
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 86
                radius: 21
                color: root.card
                border.width: 1
                border.color: root.line

                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 15
                    spacing: 12

                    Rectangle {
                        width: 49; height: 49; radius: 25
                        color: "#214764"
                        Image {
                            anchors.centerIn: parent
                            width: 38; height: 38
                            source: "qrc:/ui/qml/Assets/flint-logo.svg"
                            fillMode: Image.PreserveAspectFit
                        }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Text { text: "Онлайн поддержка"; color: root.ink; font.bold: true; font.pixelSize: 17 }
                        Text {
                            Layout.fillWidth: true
                            text: FlintController.apiOnline ? "Flint готов помочь" : "API недоступен — подключение не блокируется"
                            color: FlintController.apiOnline ? root.mint : root.warning
                            font.pixelSize: 12
                            elide: Text.ElideRight
                        }
                    }

                    Text { text: "›"; color: root.muted; font.pixelSize: 29 }
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: assistPopup.open()
                }
            }

            Text {
                visible: FlintController.lastError.length > 0
                Layout.fillWidth: true
                Layout.leftMargin: 12
                Layout.rightMargin: 12
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                text: FlintController.lastError
                color: "#FF9A9A"
                font.pixelSize: 12
            }

            Item { Layout.fillWidth: true; height: 14 }
        }
    }

    Popup {
        id: accountPopup
        x: Math.max(13, (root.width - width) / 2)
        y: Math.max(PageController.safeAreaTopMargin + 12, (root.height - height) / 2)
        width: Math.min(root.width - 26, 520)
        height: Math.min(570, root.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - 24)
        modal: true
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

        background: Rectangle {
            radius: 24
            color: "#FB081928"
            border.width: 1
            border.color: root.line
        }

        contentItem: Flickable {
            contentWidth: width
            contentHeight: accountCol.implicitHeight
            clip: true

            ColumnLayout {
                id: accountCol
                width: parent.width
                spacing: 12

                RowLayout {
                    Layout.fillWidth: true
                    Image {
                        source: "qrc:/ui/qml/Assets/flint-logo.svg"
                        Layout.preferredWidth: 48
                        Layout.preferredHeight: 48
                    }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 0
                        Text {
                            text: FlintController.loggedIn ? "Аккаунт Flint" : "Вход во Flint"
                            color: root.ink
                            font.pixelSize: 23
                            font.bold: true
                        }
                        Text {
                            text: "Один аккаунт для Windows, Android и TV"
                            color: root.muted
                            font.pixelSize: 12
                        }
                    }
                }

                TextField {
                    id: emailField
                    visible: !FlintController.loggedIn
                    Layout.fillWidth: true
                    placeholderText: "Email"
                    inputMethodHints: Qt.ImhEmailCharactersOnly
                }

                TextField {
                    id: passwordField
                    visible: !FlintController.loggedIn
                    Layout.fillWidth: true
                    placeholderText: "Пароль"
                    echoMode: TextInput.Password
                }

                RowLayout {
                    visible: !FlintController.loggedIn
                    Layout.fillWidth: true
                    Button {
                        Layout.fillWidth: true
                        text: "Войти"
                        enabled: !FlintController.busy
                        onClicked: FlintController.login(emailField.text, passwordField.text)
                    }
                    Button {
                        Layout.fillWidth: true
                        text: "Регистрация"
                        enabled: !FlintController.busy
                        onClicked: FlintController.registerAccount(emailField.text, passwordField.text)
                    }
                }

                Button {
                    visible: !FlintController.loggedIn
                    Layout.fillWidth: true
                    enabled: !FlintController.busy
                    text: FlintController.telegramPending ? "Открыть Telegram" : "Войти через Telegram"
                    onClicked: {
                        if (!FlintController.telegramPending) {
                            FlintController.startTelegramLogin()
                        } else if (FlintController.telegramBotUrl.length > 0) {
                            Qt.openUrlExternally(FlintController.telegramBotUrl)
                        }
                    }
                }

                Text {
                    visible: FlintController.telegramPending
                    Layout.fillWidth: true
                    text: "Подтвердите вход в Telegram-боте. Flint завершит авторизацию автоматически."
                    color: root.mint
                    wrapMode: Text.Wrap
                    font.pixelSize: 12
                }

                ColumnLayout {
                    visible: FlintController.loggedIn
                    Layout.fillWidth: true
                    spacing: 8

                    Rectangle {
                        Layout.fillWidth: true
                        implicitHeight: 80
                        radius: 18
                        color: root.card2
                        border.width: 1
                        border.color: root.line
                        Column {
                            anchors.fill: parent
                            anchors.margins: 13
                            spacing: 4
                            Text { text: root.accountTitle(); color: root.ink; font.bold: true; font.pixelSize: 15 }
                            Text {
                                text: FlintController.subscriptionActive ? "Подписка активна" : "Подписка пока не получена"
                                color: FlintController.subscriptionActive ? root.mint : root.warning
                            }
                            Text { text: "Подключённых устройств: " + FlintController.sessionsCount; color: root.muted; font.pixelSize: 12 }
                        }
                    }

                    Button {
                        Layout.fillWidth: true
                        text: "Обновить аккаунт"
                        onClicked: FlintController.refresh()
                    }

                    Button {
                        Layout.fillWidth: true
                        text: "Загрузить профиль Flint"
                        enabled: FlintController.subscriptionActive
                        onClicked: FlintController.importSubscription()
                    }

                    Button {
                        Layout.fillWidth: true
                        text: "Выйти"
                        onClicked: FlintController.logout()
                    }
                }

                Text {
                    visible: FlintController.lastError.length > 0
                    Layout.fillWidth: true
                    text: FlintController.lastError
                    color: "#FF9A9A"
                    wrapMode: Text.Wrap
                    font.pixelSize: 12
                }

                Button {
                    text: "Закрыть"
                    Layout.alignment: Qt.AlignRight
                    onClicked: accountPopup.close()
                }
            }
        }
    }

    Popup {
        id: assistPopup
        x: Math.max(13, (root.width - width) / 2)
        y: Math.max(PageController.safeAreaTopMargin + 12, (root.height - height) / 2)
        width: Math.min(root.width - 26, 520)
        height: Math.min(455, root.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - 24)
        modal: true
        focus: true

        background: Rectangle {
            radius: 24
            color: "#FB081928"
            border.width: 1
            border.color: root.line
        }

        contentItem: ColumnLayout {
            spacing: 11

            RowLayout {
                Layout.fillWidth: true
                Image {
                    source: "qrc:/ui/qml/Assets/flint-dog.svg"
                    Layout.preferredWidth: 66
                    Layout.preferredHeight: 66
                }
                ColumnLayout {
                    Layout.fillWidth: true
                    Text { text: "Flint Assist"; color: root.ink; font.bold: true; font.pixelSize: 22 }
                    Text { text: FlintController.assistTitle; color: root.mint; font.pixelSize: 13 }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.fillHeight: true
                radius: 18
                color: root.card2
                border.width: 1
                border.color: root.line
                Text {
                    anchors.fill: parent
                    anchors.margins: 14
                    text: FlintController.assistReply.length > 0
                          ? FlintController.assistReply
                          : "Я Flint. Если соединение даст сбой — подскажу, что делать."
                    color: root.ink
                    wrapMode: Text.Wrap
                    verticalAlignment: Text.AlignTop
                }
            }

            RowLayout {
                Layout.fillWidth: true
                Button {
                    Layout.fillWidth: true
                    text: "Не подключается"
                    onClicked: FlintController.askAssist("не подключается")
                }
                Button {
                    Layout.fillWidth: true
                    text: "Госзакупки"
                    onClicked: FlintController.askAssist("zakupki российские сервисы")
                }
            }

            Button {
                text: "Закрыть"
                Layout.alignment: Qt.AlignRight
                onClicked: assistPopup.close()
            }
        }
    }

    Popup {
        id: settingsPopup
        x: Math.max(13, (root.width - width) / 2)
        y: Math.max(PageController.safeAreaTopMargin + 12, (root.height - height) / 2)
        width: Math.min(root.width - 26, 500)
        height: Math.min(390, root.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - 24)
        modal: true
        focus: true

        background: Rectangle {
            radius: 24
            color: "#FB081928"
            border.width: 1
            border.color: root.line
        }

        contentItem: ColumnLayout {
            spacing: 12

            Text { text: "Настройки Flint"; color: root.ink; font.pixelSize: 23; font.bold: true }
            Text { text: "Android • Flint 8.9.6"; color: root.muted }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 74
                radius: 18
                color: root.card2
                border.width: 1
                border.color: root.line
                Column {
                    anchors.fill: parent
                    anchors.margins: 12
                    spacing: 4
                    Text {
                        text: FlintController.apiOnline ? "Flint API доступен" : "Flint API отвечает медленно"
                        color: FlintController.apiOnline ? root.mint : root.warning
                        font.bold: true
                    }
                    Text {
                        width: parent.width
                        text: "VPN не блокируется ожиданием API: используется сохранённый рабочий профиль."
                        color: root.muted
                        font.pixelSize: 11
                        wrapMode: Text.Wrap
                    }
                }
            }

            Button {
                Layout.fillWidth: true
                text: FlintController.loggedIn ? "Аккаунт: " + root.accountTitle() : "Войти во Flint"
                onClicked: {
                    settingsPopup.close()
                    accountPopup.open()
                }
            }

            Button {
                Layout.fillWidth: true
                text: "Обновить данные"
                onClicked: FlintController.refresh()
            }

            Button {
                text: "Закрыть"
                Layout.alignment: Qt.AlignRight
                onClicked: settingsPopup.close()
            }
        }
    }
}
