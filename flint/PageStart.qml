import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import PageEnum 1.0
import "./"
import "../Controls2"

PageType {
    id: root
    property bool isControlsDisabled: false

    // Keep a tiny, known-good shell alive even if the custom Home QML has an
    // error. A broken Home must never terminate the Android process.
    Rectangle {
        anchors.fill: parent
        color: "#061522"

        Column {
            anchors.centerIn: parent
            width: Math.min(parent.width - 48, 340)
            spacing: 12
            visible: flintLoader.status !== Loader.Ready

            Text {
                width: parent.width
                text: "FLINT"
                horizontalAlignment: Text.AlignHCenter
                color: "#F8FBFF"
                font.pixelSize: 34
                font.bold: true
                font.letterSpacing: 2
            }

            Text {
                width: parent.width
                text: flintLoader.status === Loader.Error
                      ? "Не удалось загрузить интерфейс Flint"
                      : "Запуск Flint…"
                horizontalAlignment: Text.AlignHCenter
                color: flintLoader.status === Loader.Error ? "#FF9A9A" : "#B7C9DA"
                wrapMode: Text.Wrap
            }

            Button {
                anchors.horizontalCenter: parent.horizontalCenter
                visible: flintLoader.status === Loader.Error
                text: "Повторить"
                onClicked: {
                    flintLoader.source = ""
                    loadTimer.restart()
                }
            }
        }
    }

    Loader {
        id: flintLoader
        anchors.fill: parent
        asynchronous: true
        enabled: !root.isControlsDisabled
        source: ""

        onStatusChanged: {
            if (status === Loader.Error)
                console.error("Flint Home loader error:", source)
        }
    }

    Timer {
        id: loadTimer
        interval: 100
        repeat: false
        running: true
        onTriggered: flintLoader.source = "PageHome.qml"
    }

    Connections {
        target: PageController
        function onDisableControls(disabled) { root.isControlsDisabled = disabled }
        function onGoToPageHome() {
            if (flintLoader.item)
                FlintController.refresh()
        }
        function onGoToStartPage() {
            if (flintLoader.item)
                FlintController.refresh()
        }
        function onGoToPageSettings() {
            if (flintLoader.item && flintLoader.item.openSettings)
                flintLoader.item.openSettings()
        }
        function onEscapePressed() { PageController.hideWindow() }
    }

    Connections {
        target: ImportController
        function onImportErrorOccurred(error, goToPageHome) {
            PageController.showErrorMessage(error)
        }
    }

    Connections {
        target: ConnectionController
        function onNoInstalledContainers() {
            PageController.showNotificationMessage("Профиль Flint ещё не готов. Обновите аккаунт и повторите подключение.")
            FlintController.refresh()
        }
    }
}
