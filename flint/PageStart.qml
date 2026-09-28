import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import PageEnum 1.0
import "./"
import "../Controls2"

PageType {
    id: root
    property bool isControlsDisabled: false

    // Flint owns the whole visible Android navigation. The upstream setup wizard
    // stays under the hood only as part of the VPN engine and is never shown.
    PageHome {
        id: flintHome
        anchors.fill: parent
        enabled: !root.isControlsDisabled
    }

    Connections {
        target: PageController
        function onDisableControls(disabled) { root.isControlsDisabled = disabled }
        function onGoToPageHome() { FlintController.refresh() }
        function onGoToStartPage() { FlintController.refresh() }
        function onGoToPageSettings() { flintHome.openSettings() }
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
