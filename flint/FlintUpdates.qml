import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "FlintFocus.js" as FlintFocus

Popup {
    id: panel
    readonly property var update: FlintUpdateController.state
    width: Math.min(parent.width - 24, 520)
    height: Math.min(parent.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - 24, 530)
    x: (parent.width - width) / 2
    y: PageController.safeAreaTopMargin + (parent.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - height) / 2
    modal: true; focus: true; padding: 20
    closePolicy: update.required ? Popup.NoAutoClose : Popup.CloseOnEscape
    background: Rectangle { color: "#FA0A1C2D"; radius: 22; border.color: "#46637A" }
    onOpened: Qt.callLater(function() { FlintFocus.firstButton(panel.contentItem) })
    Shortcut { sequence: "Back"; enabled: panel.activeFocus && !panel.update.required; onActivated: panel.close() }
    contentItem: ColumnLayout {
        spacing: 14
        Text { text: "Обновление Flint"; color: "#F8FBFF"; font.bold: true; font.pixelSize: 24; textFormat: Text.PlainText }
        Text { text: "Установлена версия " + panel.update.version; color: "#B7C9DA"; font.pixelSize: 14; textFormat: Text.PlainText }
        Text { visible: !!panel.update.available; text: "Новая версия " + (panel.update.latestVersion || ""); color: "#57E4B0"; font.pixelSize: 19; font.bold: true; textFormat: Text.PlainText }
        Text { visible: !!panel.update.required; text: "Для продолжения работы необходимо обновление."; color: "#FFC56D"; wrapMode: Text.WordWrap; Layout.fillWidth: true }
        ScrollView {
            Layout.fillWidth: true; Layout.fillHeight: true; clip: true
            Text { width: parent.width; text: panel.update.notes || ""; color: "#E7F3F8"; font.pixelSize: 15; wrapMode: Text.WordWrap; textFormat: Text.PlainText }
        }
        ProgressBar { visible: panel.update.phase === "downloading" || panel.update.phase === "checking"; Layout.fillWidth: true; indeterminate: panel.update.phase === "checking"; from: 0; to: 100; value: panel.update.progress || 0 }
        Text { text: panel.update.message || ""; Layout.fillWidth: true; color: panel.update.phase === "error" ? "#FFA6AE" : "#B7C9DA"; wrapMode: Text.WordWrap; textFormat: Text.PlainText }
        RowLayout {
            Layout.fillWidth: true; spacing: 12
            FlintButton {
                Layout.fillWidth: true
                visible: !panel.update.required || panel.update.phase === "downloading"
                text: panel.update.phase === "downloading" ? "Отменить" : "Позже"
                onClicked: { if (panel.update.phase === "downloading") FlintUpdateController.cancel(); else panel.close() }
            }
            FlintButton {
                Layout.fillWidth: true; primary: true
                enabled: panel.update.phase !== "checking" && panel.update.phase !== "downloading"
                text: panel.update.phase === "ready" ? "Установить" : (panel.update.available ? (Qt.platform.os === "ios" ? "Открыть обновление" : "Скачать") : "Проверить")
                onClicked: { if (panel.update.phase === "ready") FlintUpdateController.install(); else if (panel.update.available) FlintUpdateController.download(); else FlintUpdateController.check(true) }
            }
        }
    }
}
