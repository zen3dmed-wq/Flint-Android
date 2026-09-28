import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Qt5Compat.GraphicalEffects

import Style 1.0
import "./"
import "../Controls2"
import "../Controls2/TextTypes"
import "../Components"

PageType {
    id: root

    property color ink: "#F6FAFF"
    property color muted: "#A9BDD2"
    property color mint: "#42E59A"
    property color card: "#E60A1929"
    property color cardBorder: "#365976"
    property color navy: "#06101D"

    function ensureReady() {
        if (!FlintController.authenticated) {
            loginPopup.open()
            return false
        }
        if (!FlintController.subscriptionActive) {
            FlintController.refreshAll()
            return false
        }
        return true
    }

    Component.onCompleted: {
        if (FlintController.authenticated) FlintController.refreshAll()
    }

    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: "#06101D" }
            GradientStop { position: 0.55; color: "#0A2137" }
            GradientStop { position: 1.0; color: "#0B3440" }
        }
    }

    // subtle aurora
    Rectangle {
        width: parent.width * 1.2
        height: parent.height * 0.34
        anchors.horizontalCenter: parent.horizontalCenter
        y: -height * 0.25
        radius: height / 2
        color: "#183CE59A"
        rotation: -8
    }

    Flickable {
        anchors.fill: parent
        contentWidth: width
        contentHeight: content.implicitHeight + 56 + PageController.safeAreaTopMargin
        clip: true
        boundsBehavior: Flickable.StopAtBounds

        ColumnLayout {
            id: content
            width: Math.min(parent.width - 28, 620)
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.top: parent.top
            anchors.topMargin: 16 + PageController.safeAreaTopMargin
            spacing: 12

            RowLayout {
                Layout.fillWidth: true
                spacing: 12

                Rectangle {
                    Layout.preferredWidth: 52
                    Layout.preferredHeight: 52
                    radius: 26
                    color: "#15384F"
                    border.color: root.mint
                    border.width: 2
                    Text {
                        anchors.centerIn: parent
                        text: "🐕"
                        font.pixelSize: 28
                    }
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 0
                    Label { text: "FLINT"; color: root.ink; font.pixelSize: 28; font.bold: true }
                    Label { text: "Ваша приватность в надёжных лапах"; color: root.muted; font.pixelSize: 11 }
                }

                ToolButton {
                    text: "⚙"
                    font.pixelSize: 23
                    onClicked: settingsPopup.open()
                }
            }

            Item {
                Layout.fillWidth: true
                Layout.preferredHeight: Math.min(width * 0.48, 260)

                Rectangle {
                    anchors.centerIn: parent
                    width: Math.min(parent.width * 0.55, 230)
                    height: width
                    radius: width / 2
                    color: ConnectionController.isConnected ? "#182FE29A" : "#1A18344C"
                    border.color: ConnectionController.isConnected ? root.mint : "#416580"
                    border.width: 2
                    layer.enabled: true
                    layer.effect: DropShadow {
                        radius: 26
                        samples: 33
                        color: ConnectionController.isConnected ? "#7042E59A" : "#50000000"
                    }

                    Text {
                        anchors.centerIn: parent
                        text: ConnectionController.isConnected ? "🛡" : "🐕"
                        font.pixelSize: 72
                    }
                }
            }

            Label {
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignHCenter
                text: ConnectionController.isConnected ? "Вы защищены" : "Вы не защищены"
                color: ConnectionController.isConnected ? root.mint : root.ink
                font.pixelSize: 29
                font.bold: true
            }

            Label {
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                text: ConnectionController.isConnected
                      ? "Flint Guard контролирует защищённое соединение."
                      : (FlintController.authenticated
                         ? (FlintController.subscriptionActive ? "Нажмите кнопку, чтобы подключиться." : "Активная подписка не найдена.")
                         : "Войдите в Flint — подписка и устройства загрузятся автоматически.")
                color: root.muted
                font.pixelSize: 13
            }

            Button {
                id: connectBtn
                Layout.preferredWidth: Math.min(content.width * 0.88, 440)
                Layout.preferredHeight: 68
                Layout.alignment: Qt.AlignHCenter
                text: ConnectionController.isConnected ? "ОТКЛЮЧИТЬ" : "ПОДКЛЮЧИТЬСЯ"
                enabled: !FlintController.busy
                onClicked: {
                    if (!ConnectionController.isConnected && !root.ensureReady()) return
                    ConnectionController.connectButtonClicked()
                }
                background: Rectangle {
                    radius: 34
                    border.width: 1
                    border.color: "#86F9CB"
                    gradient: Gradient {
                        GradientStop { position: 0.0; color: connectBtn.pressed ? "#35C980" : "#39DA8D" }
                        GradientStop { position: 1.0; color: connectBtn.pressed ? "#65EFB6" : "#6CF2BC" }
                    }
                }
                contentItem: Row {
                    spacing: 12
                    anchors.centerIn: parent
                    Text { text: "⏻"; color: "#041A12"; font.pixelSize: 30 }
                    Text { text: connectBtn.text; color: "#041A12"; font.pixelSize: 18; font.bold: true; anchors.verticalCenter: parent.verticalCenter }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 76
                radius: 18
                color: root.card
                border.color: root.cardBorder
                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 14
                    spacing: 12
                    Rectangle {
                        width: 44; height: 44; radius: 22; color: "#163B58"
                        Text { anchors.centerIn: parent; text: "🛡"; font.pixelSize: 22 }
                    }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Label { text: "Flint Guard"; color: root.ink; font.bold: true; font.pixelSize: 16 }
                        Label { text: ConnectionController.isConnected ? "Защищено" : "Не подключено"; color: ConnectionController.isConnected ? root.mint : root.muted; font.pixelSize: 12 }
                    }
                    Rectangle { width: 10; height: 10; radius: 5; color: ConnectionController.isConnected ? root.mint : "#6E879D" }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 82
                radius: 20
                color: root.card
                border.color: root.cardBorder
                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 14
                    spacing: 12
                    Rectangle { width: 46; height: 46; radius: 23; color: "#163B58"; Text { anchors.centerIn: parent; text: "👨‍👩‍👧"; font.pixelSize: 21 } }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Label { text: "Семейная подписка"; color: root.ink; font.bold: true; font.pixelSize: 16 }
                        Label {
                            text: FlintController.authenticated
                                  ? (FlintController.devicesUsed + " из " + FlintController.maxDevices + " устройств")
                                  : "Войдите, чтобы увидеть устройства"
                            color: FlintController.subscriptionActive ? root.mint : root.muted
                            font.pixelSize: 12
                        }
                    }
                    Label { text: "›"; color: root.muted; font.pixelSize: 28 }
                }
                MouseArea { anchors.fill: parent; onClicked: familyPopup.open() }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 82
                radius: 20
                color: root.card
                border.color: root.cardBorder
                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 14
                    spacing: 12
                    Rectangle { width: 46; height: 46; radius: 23; color: "#163B58"; Label { anchors.centerIn: parent; text: "РФ"; color: root.ink; font.bold: true } }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Label { text: "Российские сервисы"; color: root.ink; font.bold: true; font.pixelSize: 16 }
                        Label { text: "Открывать выбранные российские сайты напрямую"; color: root.muted; font.pixelSize: 11; wrapMode: Text.Wrap; Layout.fillWidth: true }
                    }
                    Switch { checked: FlintController.ruDirectEnabled; onToggled: FlintController.ruDirectEnabled = checked }
                }
            }

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 82
                radius: 20
                color: root.card
                border.color: root.cardBorder
                RowLayout {
                    anchors.fill: parent
                    anchors.margins: 14
                    spacing: 12
                    Rectangle { width: 46; height: 46; radius: 23; color: "#163B58"; Text { anchors.centerIn: parent; text: "💬"; font.pixelSize: 21 } }
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Label { text: "Онлайн поддержка"; color: root.ink; font.bold: true; font.pixelSize: 16 }
                        Label { text: "Помощь и ответы"; color: root.muted; font.pixelSize: 12 }
                    }
                }
            }

            Label {
                Layout.fillWidth: true
                visible: FlintController.lastError.length > 0
                text: FlintController.lastError
                color: "#FF9C9C"
                wrapMode: Text.Wrap
                horizontalAlignment: Text.AlignHCenter
            }
        }
    }

    Popup {
        id: loginPopup
        anchors.centerIn: Overlay.overlay
        width: Math.min(root.width - 28, 520)
        modal: true
        focus: true
        closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside
        background: Rectangle { radius: 22; color: "#F40A1929"; border.color: root.cardBorder }
        contentItem: ColumnLayout {
            spacing: 10
            Label { text: "Вход во Flint"; color: root.ink; font.pixelSize: 22; font.bold: true }
            Label { text: "Один аккаунт для Windows, Android и Android TV."; color: root.muted; wrapMode: Text.Wrap; Layout.fillWidth: true }
            TextField { id: emailField; Layout.fillWidth: true; placeholderText: "Email"; inputMethodHints: Qt.ImhEmailCharactersOnly }
            TextField { id: passField; Layout.fillWidth: true; placeholderText: "Пароль"; echoMode: TextInput.Password }
            RowLayout {
                Layout.fillWidth: true
                Button { text: "Регистрация"; onClicked: FlintController.registerAccount(emailField.text, passField.text) }
                Item { Layout.fillWidth: true }
                Button { text: "Войти"; onClicked: FlintController.login(emailField.text, passField.text) }
            }
            Button {
                Layout.fillWidth: true
                text: "Войти через Telegram"
                onClicked: FlintController.startTelegramLogin()
            }
            Label {
                visible: FlintController.telegramBotUrl.length > 0 && !FlintController.authenticated
                text: "Подтвердите вход в Telegram. Flint ждёт подтверждение…"
                color: root.mint
                wrapMode: Text.Wrap
                Layout.fillWidth: true
            }
            Button { text: "Закрыть"; Layout.alignment: Qt.AlignRight; onClicked: loginPopup.close() }
        }
    }

    Popup {
        id: familyPopup
        anchors.centerIn: Overlay.overlay
        width: Math.min(root.width - 28, 520)
        modal: true
        focus: true
        background: Rectangle { radius: 22; color: "#F40A1929"; border.color: root.cardBorder }
        contentItem: ColumnLayout {
            spacing: 10
            Label { text: "Семья и устройства"; color: root.ink; font.pixelSize: 22; font.bold: true }
            Label {
                text: FlintController.authenticated
                      ? ("Сейчас активно " + FlintController.devicesUsed + " из " + FlintController.maxDevices + " устройств.")
                      : "Сначала войдите в Flint."
                color: root.muted
                wrapMode: Text.Wrap
                Layout.fillWidth: true
            }
            Label {
                text: "Чтобы добавить этот телефон в семью, достаточно войти в тот же аккаунт Flint. Подписка загрузится автоматически."
                color: root.muted
                wrapMode: Text.Wrap
                Layout.fillWidth: true
            }
            Button {
                text: "Обновить устройства"
                enabled: FlintController.authenticated
                onClicked: FlintController.refreshAll()
            }
            Button {
                text: "Импортировать подписку ещё раз"
                enabled: FlintController.subscriptionActive
                onClicked: FlintController.importSubscription()
            }
            Button { text: "Закрыть"; Layout.alignment: Qt.AlignRight; onClicked: familyPopup.close() }
        }
    }

    Popup {
        id: settingsPopup
        anchors.centerIn: Overlay.overlay
        width: Math.min(root.width - 28, 480)
        modal: true
        focus: true
        background: Rectangle { radius: 22; color: "#F40A1929"; border.color: root.cardBorder }
        contentItem: ColumnLayout {
            spacing: 10
            Label { text: "Настройки Flint"; color: root.ink; font.pixelSize: 22; font.bold: true }
            Label {
                text: FlintController.authenticated
                      ? ("Аккаунт: " + (FlintController.email.length ? FlintController.email : "Telegram"))
                      : "Вы не вошли"
                color: root.muted
                wrapMode: Text.Wrap
                Layout.fillWidth: true
            }
            Label {
                text: FlintController.subscriptionActive
                      ? ("Подписка: " + FlintController.planName)
                      : "Активная подписка не найдена"
                color: FlintController.subscriptionActive ? root.mint : root.muted
            }
            Button { text: "Обновить данные"; enabled: FlintController.authenticated; onClicked: FlintController.refreshAll() }
            Button { text: "Войти"; visible: !FlintController.authenticated; onClicked: { settingsPopup.close(); loginPopup.open() } }
            Button { text: "Выйти из аккаунта"; visible: FlintController.authenticated; onClicked: { FlintController.logout(); settingsPopup.close() } }
            Button { text: "Закрыть"; Layout.alignment: Qt.AlignRight; onClicked: settingsPopup.close() }
        }
    }
}
