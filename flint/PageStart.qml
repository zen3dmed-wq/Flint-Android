import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "./"
import "../Controls2"

PageType {
    id: root
    property bool isControlsDisabled: false

    Rectangle {
        anchors.fill: parent
        color: "#06111D"
    }

    Image {
        anchors.fill: parent
        source: "qrc:/ui/qml/Assets/flint-background.jpg"
        fillMode: Image.PreserveAspectCrop
        opacity: 0.72
    }

    Loader {
        id: flintLoader
        anchors.fill: parent
        asynchronous: false
        source: "PageHome.qml"
        enabled: !root.isControlsDisabled

        onStatusChanged: {
            if (status === Loader.Error)
                console.error("FLINT_STARTUP_UI_ERROR:", source)
        }
    }

    Column {
        anchors.centerIn: parent
        width: Math.min(parent.width - 48, 330)
        spacing: 14
        visible: flintLoader.status === Loader.Error

        Image {
            anchors.horizontalCenter: parent.horizontalCenter
            width: 84
            height: 84
            source: "qrc:/ui/qml/Assets/flint-logo.png"
            fillMode: Image.PreserveAspectFit
        }

        Text {
            width: parent.width
            text: "FLINT"
            color: "white"
            font.pixelSize: 28
            font.bold: true
            horizontalAlignment: Text.AlignHCenter
        }

        Text {
            width: parent.width
            text: "Не удалось загрузить интерфейс Flint. Приложение не закрыто — можно отправить диагностику разработчику."
            color: "#B7C9DA"
            font.pixelSize: 13
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
        }

        FlintButton {
            anchors.horizontalCenter: parent.horizontalCenter
            text: "Повторить"
            onClicked: {
                flintLoader.source = ""
                Qt.callLater(function() { flintLoader.source = "PageHome.qml" })
            }
        }
    }

    BusyIndicator {
        anchors.centerIn: parent
        visible: flintLoader.status === Loader.Loading
        running: visible
    }

    Connections {
        target: PageController
        function onDisableControls(disabled) { root.isControlsDisabled = disabled }
        function onGoToPageHome() {
            if (flintLoader.item && flintLoader.item.openSettings)
                return
        }
        function onGoToPageSettings() {
            if (flintLoader.item && flintLoader.item.openSettings)
                flintLoader.item.openSettings()
        }
        function onGoToStartPage() {
            if (flintLoader.status === Loader.Error) {
                flintLoader.source = ""
                Qt.callLater(function() { flintLoader.source = "PageHome.qml" })
            }
        }
        function onEscapePressed() { PageController.hideWindow() }
    }
    Connections {
        target: ImportController
        function onImportErrorOccurred(error, goToPageHome) {
            if (flintLoader.item) flintLoader.item.handleImportError(error)
        }
    }
}
