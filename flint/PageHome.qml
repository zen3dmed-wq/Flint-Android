import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

import Style 1.0
import "./"
import "../Controls2"

PageType {
    id: root

    property real u: Math.max(0.78, Math.min(1.0,
                         (height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin) / 790))
    property color ink: "#F8FBFF"
    property color muted: "#B7C9DA"
    property color mint: "#4AE6A3"
    property color card: "#DE0A1C2D"
    property color line: "#46637A"
    property color warning: "#FFC56D"
    property int connectWaitTicks: 0

    function openSettings() { settingsPopup.open() }

    function accountTitle() {
        if (FlintController.telegramUsername.length > 0)
            return "@" + FlintController.telegramUsername
        if (FlintController.email.length > 0)
            return FlintController.email
        return "Аккаунт Flint"
    }

    function countryTitle() {
        var code = FlintController.selectedCountry
        if (!code || code === "AUTO") return "Автоматически"
        var list = FlintController.countries
        for (var i = 0; i < list.length; ++i)
            if (list[i].code === code) return list[i].name
        return code
    }

    function beginConnect() {
        if (!FlintController.loggedIn || !FlintController.subscriptionActive) {
            accountPopup.open()
            return
        }

        if (ServersUiController.getServersCount() > 0) {
            ConnectionController.connectButtonClicked()
            return
        }

        connectWaitTicks = 0
        FlintController.importSubscription()
        connectWait.start()
    }

    Timer {
        id: connectWait
        interval: 300
        repeat: true
        onTriggered: {
            root.connectWaitTicks++
            if (ServersUiController.getServersCount() > 0) {
                stop()
                ConnectionController.connectButtonClicked()
                return
            }
            if (root.connectWaitTicks >= 30) {
                stop()
                PageController.showNotificationMessage("Не удалось подготовить профиль Flint. Откройте аккаунт и нажмите «Обновить».")
            }
        }
    }

    Component.onCompleted: {
        IpSplitTunnelingController.setRouteMode(2)
        IpSplitTunnelingController.toggleSplitTunneling(FlintController.ruDirectEnabled)
        FlintController.refresh()
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

    ColumnLayout {
        id: main
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        anchors.bottom: parent.bottom
        anchors.leftMargin: 14
        anchors.rightMargin: 14
        anchors.topMargin: PageController.safeAreaTopMargin + 8
        anchors.bottomMargin: PageController.safeAreaBottomMargin + 8
        spacing: 7 * root.u

        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 46 * root.u
            spacing: 9

            Image {
                source: "qrc:/ui/qml/Assets/flint-logo.svg"
                Layout.preferredWidth: 43 * root.u
                Layout.preferredHeight: 43 * root.u
                fillMode: Image.PreserveAspectFit
            }

            ColumnLayout {
                Layout.fillWidth: true
                spacing: -2
                Text {
                    text: "FLINT"
                    color: root.ink
                    font.pixelSize: 22 * root.u
                    font.bold: true
                    font.letterSpacing: 1.4
                }
                Text {
                    text: "Больше свободы в интернете"
                    color: root.muted
                    font.pixelSize: 10.5 * root.u
                }
            }

            Rectangle {
                Layout.preferredWidth: 39 * root.u
                Layout.preferredHeight: 39 * root.u
                radius: 13 * root.u
                color: "#C50B2134"
                border.width: 1
                border.color: root.line
                Text {
                    anchors.centerIn: parent
                    text: "⚙"
                    color: root.ink
                    font.pixelSize: 19 * root.u
                }
                MouseArea { anchors.fill: parent; onClicked: settingsPopup.open() }
            }
        }

        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(175 * root.u, main.width * 0.48)

            Image {
                anchors.centerIn: parent
                width: Math.min(parent.height, parent.width * 0.58)
                height: width
                source: "qrc:/ui/qml/Assets/flint-main.png"
                fillMode: Image.PreserveAspectFit
                cache: true
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 54 * root.u
            spacing: 1

            Text {
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignHCenter
                text: ConnectionController.isConnected ? "Вы защищены" : "Вы не защищены"
                color: ConnectionController.isConnected ? root.mint : root.ink
                font.pixelSize: 26 * root.u
                font.bold: true
            }

            Text {
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignHCenter
                text: ConnectionController.isConnected
                      ? "Flint Guard контролирует соединение"
                      : "Подключитесь, чтобы защитить свои данные"
                color: root.muted
                font.pixelSize: 11.5 * root.u
            }
        }

        Button {
            id: connectBtn
            Layout.fillWidth: true
            Layout.maximumWidth: 440
            Layout.preferredHeight: 61 * root.u
            Layout.alignment: Qt.AlignHCenter
            enabled: !FlintController.busy && !ConnectionController.isConnectionInProgress
            text: ConnectionController.isConnected ? "ОТКЛЮЧИТЬ" : "ПОДКЛЮЧИТЬСЯ"

            onClicked: {
                if (ConnectionController.isConnected)
                    ConnectionController.connectButtonClicked()
                else
                    root.beginConnect()
            }

            background: Rectangle {
                radius: height / 2
                border.width: 1
                border.color: "#A8FFE0"
                gradient: Gradient {
                    GradientStop { position: 0; color: connectBtn.pressed ? "#31C47D" : "#3EDB91" }
                    GradientStop { position: 1; color: connectBtn.pressed ? "#62E6AD" : "#72EFC0" }
                }
            }

            contentItem: Row {
                anchors.centerIn: parent
                spacing: 12 * root.u
                Text {
                    text: "⏻"
                    color: "#052219"
                    font.pixelSize: 29 * root.u
                }
                Rectangle {
                    anchors.verticalCenter: parent.verticalCenter
                    width: 1
                    height: 31 * root.u
                    color: "#65052219"
                }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: connectBtn.text
                    color: "#052219"
                    font.pixelSize: 18 * root.u
                    font.bold: true
                }
            }
        }

        Rectangle {
            Layout.preferredWidth: Math.min(main.width * 0.78, 365)
            Layout.preferredHeight: 45 * root.u
            Layout.alignment: Qt.AlignHCenter
            radius: 15 * root.u
            color: "#E20A1A2A"
            border.width: 1
            border.color: root.line

            RowLayout {
                anchors.centerIn: parent
                spacing: 8 * root.u

                Text { text: "◇"; color: root.ink; font.pixelSize: 18 * root.u }
                Text { text: "Flint Guard"; color: root.ink; font.bold: true; font.pixelSize: 12.5 * root.u }
                Rectangle {
                    width: 8 * root.u
                    height: 8 * root.u
                    radius: width / 2
                    color: ConnectionController.isConnected ? root.mint : "#8297AA"
                }
                Text {
                    text: ConnectionController.isConnectionInProgress
                          ? "Подключение…"
                          : (ConnectionController.isConnected ? "Защищено" : "Отключён")
                    color: ConnectionController.isConnected ? root.mint : root.muted
                    font.pixelSize: 11 * root.u
                }
            }
        }

        GridLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            columns: 2
            rows: 2
            columnSpacing: 8
            rowSpacing: 8

            Rectangle {
                Layout.fillWidth: true
                Layout.fillHeight: true
                radius: 17 * root.u
                color: root.card
                border.width: 1
                border.color: root.line

                Column {
                    anchors.fill: parent
                    anchors.margins: 11 * root.u
                    spacing: 4 * root.u

                    Row {
                        spacing: 7 * root.u
                        Rectangle {
                            width: 29 * root.u; height: 29 * root.u; radius: width/2
                            color: "#214764"
                            Text { anchors.centerIn: parent; text: "⌖"; color: root.ink; font.pixelSize: 16 * root.u }
                        }
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            width: parent.parent.width - 50 * root.u
                            text: root.countryTitle()
                            color: root.ink
                            font.bold: true
                            font.pixelSize: 13 * root.u
                            elide: Text.ElideRight
                        }
                    }

                    Text {
                        width: parent.width
                        text: "Лучший сервер"
                        color: root.muted
                        font.pixelSize: 10.5 * root.u
                    }
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: {
                        if (ConnectionController.isConnected || ConnectionController.isConnectionInProgress) {
                            PageController.showNotificationMessage("Отключите Flint перед сменой локации.")
                            return
                        }
                        if (FlintController.subscriptionActive) {
                            FlintController.importSubscription()
                            countryPopup.open()
                        } else {
                            accountPopup.open()
                        }
                    }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.fillHeight: true
                radius: 17 * root.u
                color: root.card
                border.width: 1
                border.color: root.line

                Column {
                    anchors.fill: parent
                    anchors.margins: 11 * root.u
                    spacing: 4 * root.u

                    RowLayout {
                        width: parent.width
                        spacing: 6 * root.u

                        Rectangle {
                            Layout.preferredWidth: 29 * root.u
                            Layout.preferredHeight: 29 * root.u
                            radius: width/2
                            color: "#214764"
                            Text { anchors.centerIn: parent; text: "РФ"; color: root.ink; font.bold: true; font.pixelSize: 10.5 * root.u }
                        }

                        Text {
                            Layout.fillWidth: true
                            text: "Российские"
                            color: root.ink
                            font.bold: true
                            font.pixelSize: 13 * root.u
                            elide: Text.ElideRight
                        }

                        Switch {
                            id: ruSwitch
                            Layout.preferredWidth: 42 * root.u
                            Layout.preferredHeight: 25 * root.u
                            checked: FlintController.ruDirectEnabled
                            onToggled: {
                                FlintController.ruDirectEnabled = checked
                                IpSplitTunnelingController.setRouteMode(2)
                                IpSplitTunnelingController.toggleSplitTunneling(checked)
                            }
                        }
                    }

                    Text {
                        width: parent.width
                        text: "zakupki.gov.ru напрямую"
                        color: FlintController.ruDirectEnabled ? root.mint : root.muted
                        font.pixelSize: 9.8 * root.u
                        elide: Text.ElideRight
                    }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.fillHeight: true
                radius: 17 * root.u
                color: root.card
                border.width: 1
                border.color: root.line

                Column {
                    anchors.fill: parent
                    anchors.margins: 11 * root.u
                    spacing: 4 * root.u

                    Row {
                        spacing: 7 * root.u
                        Rectangle {
                            width: 29 * root.u; height: 29 * root.u; radius: width/2
                            color: "#214764"
                            Text { anchors.centerIn: parent; text: "♙"; color: root.ink; font.pixelSize: 16 * root.u }
                        }
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: "Семья"
                            color: root.ink
                            font.bold: true
                            font.pixelSize: 13 * root.u
                        }
                    }

                    Text {
                        width: parent.width
                        text: FlintController.loggedIn
                              ? FlintController.sessionsCount + " устройств"
                              : "Войти в аккаунт"
                        color: FlintController.subscriptionActive ? root.mint : root.muted
                        font.pixelSize: 10.5 * root.u
                        elide: Text.ElideRight
                    }
                }

                MouseArea { anchors.fill: parent; onClicked: accountPopup.open() }
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.fillHeight: true
                radius: 17 * root.u
                color: root.card
                border.width: 1
                border.color: root.line

                Column {
                    anchors.fill: parent
                    anchors.margins: 11 * root.u
                    spacing: 4 * root.u

                    Row {
                        spacing: 7 * root.u
                        Image {
                            width: 29 * root.u
                            height: 29 * root.u
                            source: "qrc:/ui/qml/Assets/flint-logo.svg"
                            fillMode: Image.PreserveAspectFit
                        }
                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: "Поддержка"
                            color: root.ink
                            font.bold: true
                            font.pixelSize: 13 * root.u
                        }
                    }

                    Text {
                        width: parent.width
                        text: FlintController.apiOnline ? "Flint готов помочь" : "Работаем офлайн"
                        color: FlintController.apiOnline ? root.mint : root.warning
                        font.pixelSize: 10.5 * root.u
                        elide: Text.ElideRight
                    }
                }

                MouseArea { anchors.fill: parent; onClicked: assistPopup.open() }
            }
        }

        Text {
            visible: FlintController.lastError.length > 0
            Layout.fillWidth: true
            Layout.preferredHeight: visible ? 17 * root.u : 0
            horizontalAlignment: Text.AlignHCenter
            text: FlintController.lastError
            color: "#FF9A9A"
            font.pixelSize: 9.5 * root.u
            elide: Text.ElideRight
        }
    }

    Popup {
        id: accountPopup
        anchors.centerIn: Overlay.overlay
        width: Math.min(root.width - 28, 470)
        height: Math.min(root.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - 30, 510)
        modal: true
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

        background: Rectangle {
            radius: 23
            color: "#FC081827"
            border.width: 1
            border.color: root.line
        }

        contentItem: ColumnLayout {
            spacing: 10

            RowLayout {
                Layout.fillWidth: true
                Image {
                    source: "qrc:/ui/qml/Assets/flint-logo.svg"
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

            Button {
                visible: !FlintController.loggedIn
                Layout.fillWidth: true
                text: "Войти"
                enabled: !FlintController.busy
                onClicked: FlintController.login(emailField.text, passwordField.text)
            }

            Button {
                visible: !FlintController.loggedIn
                Layout.fillWidth: true
                text: FlintController.telegramPending ? "Открыть Telegram" : "Войти через Telegram"
                enabled: !FlintController.busy
                onClicked: {
                    if (FlintController.telegramPending && FlintController.telegramBotUrl.length > 0)
                        Qt.openUrlExternally(FlintController.telegramBotUrl)
                    else
                        FlintController.startTelegramLogin()
                }
            }

            Button {
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
                    text: "Устройств: " + FlintController.sessionsCount
                    color: root.muted
                }

                Button {
                    Layout.fillWidth: true
                    text: "Обновить"
                    onClicked: FlintController.refresh()
                }

                Button {
                    Layout.fillWidth: true
                    text: "Подготовить профиль"
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
                visible: FlintController.telegramPending
                Layout.fillWidth: true
                text: "Подтвердите вход в Telegram-боте"
                color: root.mint
                wrapMode: Text.Wrap
            }

            Item { Layout.fillHeight: true }

            Button {
                text: "Закрыть"
                Layout.alignment: Qt.AlignRight
                onClicked: accountPopup.close()
            }
        }
    }

    Popup {
        id: countryPopup
        anchors.centerIn: Overlay.overlay
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
            spacing: 9

            Text {
                text: "Локация"
                color: root.ink
                font.pixelSize: 21
                font.bold: true
            }

            ListView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                clip: true
                model: FlintController.countries

                delegate: Button {
                    required property var modelData
                    width: ListView.view.width
                    height: 48
                    text: modelData.name
                    checkable: true
                    checked: FlintController.selectedCountry === modelData.code
                    onClicked: {
                        if (ConnectionController.isConnected || ConnectionController.isConnectionInProgress) {
                            PageController.showNotificationMessage("Отключите Flint перед сменой локации.")
                            countryPopup.close()
                            return
                        }
                        FlintController.selectedCountry = modelData.code
                        FlintController.importSubscription()
                        countryPopup.close()
                    }
                }
            }

            Button {
                text: "Закрыть"
                Layout.alignment: Qt.AlignRight
                onClicked: countryPopup.close()
            }
        }
    }

    Popup {
        id: assistPopup
        anchors.centerIn: Overlay.overlay
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
            spacing: 10

            RowLayout {
                Layout.fillWidth: true
                Image {
                    source: "qrc:/ui/qml/Assets/flint-logo.svg"
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

            RowLayout {
                Layout.fillWidth: true
                Button {
                    Layout.fillWidth: true
                    text: "Подключение"
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
        anchors.centerIn: Overlay.overlay
        width: Math.min(root.width - 28, 440)
        height: Math.min(root.height * 0.50, 340)
        modal: true
        focus: true

        background: Rectangle {
            radius: 23
            color: "#FC081827"
            border.width: 1
            border.color: root.line
        }

        contentItem: ColumnLayout {
            spacing: 10

            Text { text: "Настройки Flint"; color: root.ink; font.pixelSize: 21; font.bold: true }
            Text { text: "Flint Android 8.9.7"; color: root.muted }

            Text {
                Layout.fillWidth: true
                text: FlintController.apiOnline
                      ? "Flint API доступен"
                      : "API отвечает медленно. Сохранённый профиль продолжает работать."
                color: FlintController.apiOnline ? root.mint : root.warning
                wrapMode: Text.Wrap
            }

            Button {
                Layout.fillWidth: true
                text: FlintController.loggedIn ? root.accountTitle() : "Войти во Flint"
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

            Item { Layout.fillHeight: true }

            Button {
                text: "Закрыть"
                Layout.alignment: Qt.AlignRight
                onClicked: settingsPopup.close()
            }
        }
    }
}
