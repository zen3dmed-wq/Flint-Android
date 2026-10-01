import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "FlintUsage.js" as Usage
import "FlintFocus.js" as FlintFocus

Popup {
    id: panel
    objectName: "flintSubscriptionsPanel"
    property string feedback: ""
    width: Math.min(parent.width - 24, 560)
    height: Math.min(parent.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - 24, 660)
    x: (parent.width - width) / 2; y: PageController.safeAreaTopMargin + 12
    padding: 18; modal: true; focus: true
    background: Rectangle { radius: 23; color: "#FD081827"; border.color: "#46637A" }
    onOpened: { feedback = ""; FlintController.refresh(); if (SettingsController.isOnTv()) Qt.callLater(function() { FlintFocus.firstButton(panel.contentItem) }) }
    contentItem: ColumnLayout {
        property bool flintFocusScope: true
        spacing: 12
        Text { text: "Мои подписки"; color: "#F8FBFF"; font.pixelSize: 23; font.bold: true }
        Text { text: ConnectionController.isConnected || ConnectionController.isConnectionInProgress ? "Отключите VPN, чтобы сменить подписку." : "Выберите подписку для подключения. У каждой свой список серверов и трафик."; color: "#B7C9DA"; wrapMode: Text.Wrap; Layout.fillWidth: true }
        ListView {
            id: list; Layout.fillWidth: true; Layout.fillHeight: true; spacing: 12; clip: true
            model: FlintController.subscriptions
            ScrollBar.vertical: ScrollBar {}
            delegate: FlintButton {
                id: subscriptionButton
                required property var modelData
                required property int index
                width: list.width; height: Math.max(136, labels.implicitHeight + 24)
                highlighted: FlintController.selectedSubscriptionId === modelData.id
                enabled: modelData.status === "active" && !ConnectionController.isConnected && !ConnectionController.isConnectionInProgress && !FlintController.profilePreparing
                Accessible.name: Usage.title(modelData) + (highlighted ? ", выбрана" : "")
                Keys.onPressed: function(event) {
                    if (!SettingsController.isOnTv()) return
                    if ([Qt.Key_Select, Qt.Key_Return, Qt.Key_Enter].indexOf(event.key) >= 0) { subscriptionButton.clicked(); event.accepted = true }
                    else if (event.key === Qt.Key_Left || event.key === Qt.Key_Right) event.accepted = popupFocus(event.key === Qt.Key_Right)
                    else if (event.key === Qt.Key_Down || event.key === Qt.Key_Up) {
                        list.currentIndex = Math.max(0, Math.min(list.count - 1, index + (event.key === Qt.Key_Down ? 1 : -1)))
                        list.positionViewAtIndex(list.currentIndex, ListView.Contain)
                        Qt.callLater(function() { if (list.currentItem) list.currentItem.forceActiveFocus() }); event.accepted = true
                    }
                }
                contentItem: ColumnLayout {
                    id: labels; spacing: 4
                    Text { text: Usage.title(modelData) + (FlintController.selectedSubscriptionId === modelData.id ? " · выбрана" : ""); color: subscriptionButton.highlighted && subscriptionButton.enabled ? "#052A20" : "#F8FBFF"; font.bold: true; wrapMode: Text.Wrap; Layout.fillWidth: true }
                    FlintTrafficBar { Layout.fillWidth: true; Layout.preferredHeight: implicitHeight; subscription: modelData; textColor: subscriptionButton.highlighted && subscriptionButton.enabled ? "#164C3C" : "#B7C9DA" }
                }
                onClicked: { if (FlintController.selectSubscription(modelData.id)) { FlintController.importSubscription(); panel.close() } else panel.feedback = FlintController.lastError }
            }
        }
        Text { text: panel.feedback || (FlintController.subscriptions.length === 0 ? "Подписок пока нет. После покупки нажмите «Обновить»." : ""); visible: text.length > 0; color: "#FFC56D"; wrapMode: Text.Wrap; Layout.fillWidth: true }
        RowLayout {
            Layout.fillWidth: true
            FlintButton { text: "Обновить"; Layout.fillWidth: true; enabled: !FlintController.busy; onClicked: FlintController.refresh() }
            FlintButton { text: "Закрыть"; Layout.fillWidth: true; onClicked: panel.close() }
        }
    }
}
