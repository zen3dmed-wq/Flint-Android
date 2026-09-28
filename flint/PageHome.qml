import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Style 1.0
import "./"
import "../Controls2"
import "../Components"

PageType {
    id: root

    property color ink: "#F5FAFF"
    property color muted: "#B7CCE0"
    property color mint: "#42E59A"
    property color card: "#DE0A1929"
    property color line: "#3C627F"

    function accountName() {
        if (FlintController.telegramUsername.length > 0)
            return "@" + FlintController.telegramUsername
        if (FlintController.email.length > 0)
            return FlintController.email
        return "аккаунт Flint"
    }

    function needAccount() {
        if (!FlintController.loggedIn || !FlintController.subscriptionActive) {
            accountPopup.open()
            return true
        }
        return false
    }

    Component.onCompleted: {
        FlintController.refresh()
        if (FlintController.subscriptionActive)
            FlintController.importSubscription()
    }

    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#06121F" }
            GradientStop { position: 0.48; color: "#092B42" }
            GradientStop { position: 1.0; color: "#06111D" }
        }
    }

    Rectangle {
        width: parent.width * 1.25
        height: width
        radius: width / 2
        x: -parent.width * 0.55
        y: -height * 0.70
        color: "#163A56"
        opacity: 0.32
    }

    Rectangle {
        width: parent.width
        height: width
        radius: width / 2
        x: parent.width * 0.44
        y: -height * 0.58
        color: "#00E7A6"
        opacity: 0.10
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: content.implicitHeight + 58 + PageController.safeAreaTopMargin
        clip: true
        boundsBehavior: Flickable.StopAtBounds

        ColumnLayout {
            id: content
            width: Math.min(parent.width - 28, 620)
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top
            anchors.topMargin: 12 + PageController.safeAreaTopMargin
            spacing: 12

            RowLayout {
                Layout.fillWidth: true
                spacing: 10

                Rectangle {
                    width: 50
                    height: 50
                    radius: 25
                    color: "#102B40"
                    border.width: 2
                    border.color: root.mint

                    Text {
                        anchors.centerIn: parent
                        text: "F"
                        color: root.mint
                        font.bold: true
                        font.pixelSize: 27
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: -2
                    Text {
                        text: "FLINT"
                        color: root.ink
                        font.bold: true
                        font.pixelSize: 27
                    }
                    Text {
                        text: "Больше свободы в интернете"
                        color: root.muted
                        font.pixelSize: 12
                    }
                }

                ToolButton {
                    text: "⚙"
                    font.pixelSize: 23
                    onClicked: settingsPopup.open()
                }
            }

            Item {
                Layout.fillWidth: true
                Layout.preferredHeight: Math.min(content.width * 0.55, 290)

                Rectangle {
                    anchors.centerIn: parent
                    width: Math.min(parent.width * 0.56, 255)
                    height: width
                    radius: width / 2
                    color: "#071827"
                    border.width: 5
                    border.color: ConnectionController.isConnected ? root.mint : "#2D6F8D"

                    Rectangle {
                        anchors.centerIn: parent
                        width: parent.width * 0.77
                        height: width
                        radius: width / 2
                        color: "#0D2C42"
                        border.width: 1
                        border.color: "#365F78"

                        Text {
                            anchors.centerIn: parent
                            text: "F"
                            color: ConnectionController.isConnected ? root.mint : root.ink
                            font.bold: true
                            font.pixelSize: parent.width * 0.48
                        }

                        Text {
                            anchors.horizontalCenter: parent.horizontalCenter
                            anchors.bottom: parent.bottom
                            anchors.bottomMargin: 25
                            text: "FLINT"
                            color: root.muted
                            font.bold: true
                            font.pixelSize: 15
                        }
                    }
                }
            }

            Text {
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignHCenter
                text: ConnectionController.isConnected ? "Вы защищены" : "Вы не защищены"
                color: ConnectionController.isConnected ? root.mint : root.ink
                font.pixelSize: 29
                font.bold: true
            }

            Text {
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                text: ConnectionController.isConnected
                      ? "Flint Guard контролирует защищённое соединение."
                      : "Подключитесь, чтобы защитить данные и открыть нужные сервисы."
                color: root.muted
                font.pixelSize: 13
            }

            Button {
                id: connectButton
                Layout.preferredWidth: Math.min(content.width * 0.90, 450)
                Layout.preferredHeight: 70
                Layout.alignment: Qt.AlignHCenter
                enabled: !FlintController.busy
                text: ConnectionController.isConnected ? "ОТКЛЮЧИТЬ" : "ПОДКЛЮЧИТЬСЯ"

                onClicked: {
                    if (!ConnectionController.isConnected && root.needAccount())
                        return
                    ConnectionController.connectButtonClicked()
                }

                background: Rectangle {
                    radius: 35
                    border.width: 1
                    border.color: "#8DFFD2"
                    gradient: Gradient {
                        GradientStop {
                            position: 0.0
                            color: connectButton.pressed ? "#31C37C" : "#3EDB91"
                        }
                        GradientStop {
                            position: 1.0
                            color: connectButton.pressed ? "#62E7AD" : "#72EFC0"
                        }
                    }
                }

                contentItem: Row {
                    anchors.centerIn: parent
                    spacing: 13
                    Text {
                        text: "⏻"
                        color: "#052117"
                        font.pixelSize: 33
                    }
                    Rectangle {
                        width: 1
                        height: 35
                        color: "#60052117"
                    }
                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        text: connectButton.text
                        color: "#052117"
                        font.pixelSize: 20
                        font.bold: true
                    }
                }
            }

            Rectangle {
                Layout.preferredWidth: Math.min(content.width * 0.76, 390)
                Layout.preferredHeight: 52
                Layout.alignment: Qt.AlignHCenter
                radius: 18
                color: root.card
                border.width: 1
                border.color: root.line

                Row {
                    anchors.centerIn: parent
                    spacing: 9

                    Text {
                        text: "◇"
                        color: root.ink
                        font.pixelSize: 22
                    }
                    Text {
                        text: "Flint Guard"
                        color: root.ink
                        font.bold: true
                        font.pixelSize: 14
                    }
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
                implicitHeight: 82
                radius: 20
                color: root.card
                border.width: 1
                border.color: root.line

                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 15
                    spacing: 12

                    Rectangle {
                        width: 48
                        height: 48
                        radius: 24
                        color: "#1B4363"
                        Text {
                            anchors.centerIn: parent
                            text: "⌖"
                            color: root.ink
                            font.pixelSize: 25
                        }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Text {
                            text: "Автоматически"
                            color: root.ink
                            font.bold: true
                            font.pixelSize: 17
                        }
                        Text {
                            text: "Лучший доступный сервер из подписки"
                            color: root.muted
                            font.pixelSize: 12
                        }
                    }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 86
                radius: 20
                color: root.card
                border.width: 1
                border.color: root.line

                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 15
                    spacing: 12

                    Rectangle {
                        width: 48
                        height: 48
                        radius: 24
                        color: "#1B4363"
                        Text {
                            anchors.centerIn: parent
                            text: "РФ"
                            color: root.ink
                            font.bold: true
                        }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Text {
                            text: "Российские сервисы"
                            color: root.ink
                            font.bold: true
                            font.pixelSize: 17
                        }
                        Text {
                            Layout.fillWidth: true
                            text: "zakupki.gov.ru и выбранные сайты — напрямую"
                            color: root.muted
                            font.pixelSize: 12
                            elide: Text.ElideRight
                        }
                    }

                    Switch {
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
                implicitHeight: 84
                radius: 20
                color: root.card
                border.width: 1
                border.color: root.line

                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 15
                    spacing: 12

                    Rectangle {
                        width: 48
                        height: 48
                        radius: 24
                        color: "#1B4363"
                        Text {
                            anchors.centerIn: parent
                            text: "♙"
                            color: root.ink
                            font.pixelSize: 24
                        }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Text {
                            text: "Семейная подписка"
                            color: root.ink
                            font.bold: true
                            font.pixelSize: 17
                        }
                        Text {
                            Layout.fillWidth: true
                            text: FlintController.loggedIn
                                  ? FlintController.sessionsCount + " устройств • " + root.accountName()
                                  : "Войдите во Flint"
                            color: FlintController.subscriptionActive ? root.mint : root.muted
                            font.pixelSize: 12
                            elide: Text.ElideRight
                        }
                    }

                    Text {
                        text: "›"
                        color: root.muted
                        font.pixelSize: 29
                    }
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: accountPopup.open()
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 84
                radius: 20
                color: root.card
                border.width: 1
                border.color: root.line

                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 15
                    spacing: 12

                    Rectangle {
                        width: 48
                        height: 48
                        radius: 24
                        color: "#1B4363"
                        Text {
                            anchors.centerIn: parent
                            text: "?"
                            color: root.mint
                            font.bold: true
                            font.pixelSize: 23
                        }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Text {
                            text: "Онлайн поддержка"
                            color: root.ink
                            font.bold: true
                            font.pixelSize: 17
                        }
                        Text {
                            Layout.fillWidth: true
                            text: FlintController.assistTitle.length
                                  ? FlintController.assistTitle
                                  : "Flint готов"
                            color: root.mint
                            font.pixelSize: 12
                            elide: Text.ElideRight
                        }
                    }

                    Text {
                        text: "›"
                        color: root.muted
                        font.pixelSize: 29
                    }
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: assistPopup.open()
                }
            }

            Text {
                visible: FlintController.lastError.length > 0
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                text: FlintController.lastError
                color: "#FF9696"
                font.pixelSize: 12
            }

            Item {
                Layout.fillWidth: true
                height: 20
            }
        }
    }

    Popup {
        id: accountPopup
        x: Math.max(14, (root.width - width) / 2)
        y: Math.max(PageController.safeAreaTopMargin + 16, (root.height - height) / 2)
        width: Math.min(root.width - 28, 520)
        height: Math.min(560, root.height - PageController.safeAreaTopMargin - 40)
        modal: true
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

        background: Rectangle {
            radius: 22
            color: "#FA0A1929"
            border.width: 1
            border.color: root.line
        }

        contentItem: Flickable {
            contentWidth: width
            contentHeight: accountColumn.implicitHeight
            clip: true

            ColumnLayout {
                id: accountColumn
                width: parent.width
                spacing: 11

                Text {
                    text: FlintController.loggedIn ? "Аккаунт Flint" : "Вход во Flint"
                    color: root.ink
                    font.pixelSize: 24
                    font.bold: true
                }

                Text {
                    Layout.fillWidth: true
                    text: "Один аккаунт для Windows, Android и TV."
                    color: root.muted
                    wrapMode: Text.Wrap
                }

                TextField {
                    id: emailInput
                    visible: !FlintController.loggedIn
                    Layout.fillWidth: true
                    placeholderText: "Email"
                    inputMethodHints: Qt.ImhEmailCharactersOnly
                }

                TextField {
                    id: passInput
                    visible: !FlintController.loggedIn
                    Layout.fillWidth: true
                    placeholderText: "Пароль"
                    echoMode: TextInput.Password
                }

                RowLayout {
                    visible: !FlintController.loggedIn
                    Layout.fillWidth: true

                    Button {
                        text: "Войти"
                        enabled: !FlintController.busy
                        onClicked: FlintController.login(emailInput.text, passInput.text)
                    }

                    Button {
                        text: "Регистрация"
                        enabled: !FlintController.busy
                        onClicked: FlintController.registerAccount(emailInput.text, passInput.text)
                    }
                }

                Button {
                    visible: !FlintController.loggedIn
                    Layout.fillWidth: true
                    enabled: !FlintController.busy
                    text: FlintController.telegramPending
                          ? "Открыть Telegram"
                          : "Войти через Telegram"

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
                    text: "Подтвердите вход в боте. Flint завершит авторизацию автоматически."
                    color: root.mint
                    wrapMode: Text.Wrap
                }

                ColumnLayout {
                    visible: FlintController.loggedIn
                    Layout.fillWidth: true
                    spacing: 7

                    Text {
                        Layout.fillWidth: true
                        text: "Аккаунт: " + root.accountName()
                        color: root.ink
                        font.bold: true
                        wrapMode: Text.Wrap
                    }

                    Text {
                        text: FlintController.subscriptionActive
                              ? "Подписка активна"
                              : "Активная подписка пока не получена"
                        color: FlintController.subscriptionActive ? root.mint : root.muted
                    }

                    Text {
                        text: "Устройств в аккаунте: " + FlintController.sessionsCount
                        color: root.muted
                    }

                    Button {
                        text: "Обновить аккаунт"
                        onClicked: FlintController.refresh()
                    }

                    Button {
                        text: "Импортировать подписку"
                        enabled: FlintController.subscriptionActive
                        onClicked: FlintController.importSubscription()
                    }

                    Button {
                        text: "Выйти"
                        onClicked: FlintController.logout()
                    }
                }

                Text {
                    visible: FlintController.lastError.length > 0
                    Layout.fillWidth: true
                    text: FlintController.lastError
                    color: "#FF9696"
                    wrapMode: Text.Wrap
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
        x: Math.max(14, (root.width - width) / 2)
        y: Math.max(PageController.safeAreaTopMargin + 16, (root.height - height) / 2)
        width: Math.min(root.width - 28, 520)
        height: Math.min(430, root.height - PageController.safeAreaTopMargin - 40)
        modal: true
        focus: true

        background: Rectangle {
            radius: 22
            color: "#FA0A1929"
            border.width: 1
            border.color: root.line
        }

        contentItem: ColumnLayout {
            spacing: 10

            Text {
                text: "Flint Assist"
                color: root.ink
                font.bold: true
                font.pixelSize: 23
            }

            Text {
                text: FlintController.assistTitle
                color: root.mint
                font.bold: true
                wrapMode: Text.Wrap
                Layout.fillWidth: true
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.fillHeight: true
                radius: 16
                color: "#A60D2032"
                border.width: 1
                border.color: root.line

                Text {
                    anchors.fill: parent
                    anchors.margins: 14
                    text: FlintController.assistReply.length
                          ? FlintController.assistReply
                          : "Опишите проблему."
                    color: root.ink
                    wrapMode: Text.Wrap
                    verticalAlignment: Text.AlignTop
                }
            }

            RowLayout {
                Layout.fillWidth: true

                Button {
                    text: "Не подключается"
                    onClicked: FlintController.askAssist("не подключается")
                }

                Button {
                    text: "Российские сервисы"
                    onClicked: FlintController.askAssist("российские сервисы закупки")
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
        x: Math.max(14, (root.width - width) / 2)
        y: Math.max(PageController.safeAreaTopMargin + 16, (root.height - height) / 2)
        width: Math.min(root.width - 28, 470)
        height: 330
        modal: true
        focus: true

        background: Rectangle {
            radius: 22
            color: "#FA0A1929"
            border.width: 1
            border.color: root.line
        }

        contentItem: ColumnLayout {
            spacing: 10

            Text {
                text: "Настройки Flint"
                color: root.ink
                font.pixelSize: 22
                font.bold: true
            }

            Text {
                text: "Android 8.9.5"
                color: root.muted
            }

            Text {
                Layout.fillWidth: true
                text: FlintController.apiOnline
                      ? "Flint API доступен"
                      : "API отвечает медленно или недоступен. Сохранённая подписка не блокируется."
                color: FlintController.apiOnline ? root.mint : "#FFC56D"
                wrapMode: Text.Wrap
            }

            Button {
                text: FlintController.loggedIn
                      ? "Аккаунт: " + root.accountName()
                      : "Войти во Flint"
                onClicked: {
                    settingsPopup.close()
                    accountPopup.open()
                }
            }

            Button {
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
