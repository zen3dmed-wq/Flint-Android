import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "FlintFocus.js" as FlintFocus

Popup {
    id: panel
        Shortcut { sequence: "Back"; enabled: panel.activeFocus; onActivated: panel.close() }
    objectName: "flintSitesPanel"
    property string feedback: ""
    property bool submitting: false
    signal routingEdited()
    width: Math.min(parent.width - 24, 500)
    height: Math.max(220, Math.min(parent.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - PageController.imeHeight - 24, 680))
    x: (parent.width - width) / 2
    y: PageController.safeAreaTopMargin + 12
    padding: 18; modal: true; focus: true
    background: Rectangle { radius: 23; color: "#FD081827"; border.color: "#46637A" }
    function add(value) {
        var host = FlintController.normalizeDirectSite(value)
        if (!host) { feedback = "Введите домен или ссылку на сайт, например example.ru"; return }
        submitting = true
        IpSplitTunnelingController.addSite(host)
        submitting = false
    }
    function changed() {
        feedback = "Сохранено. " + (ConnectionController.isConnected || ConnectionController.isConnectionInProgress ? "VPN переподключается с новыми правилами." : "Изменения применятся при подключении.")
        routingEdited()
    }
    onOpened: { Qt.callLater(function() { FlintFocus.firstButton(panel.contentItem) }); feedback = ""; IpSplitTunnelingController.setRouteMode(2); IpSplitTunnelingController.updateModel() }
    Connections {
        target: IpSplitTunnelingController
        function onFinished(message) { if (panel.opened) { if (panel.submitting) siteInput.clear(); panel.changed() } }
        function onErrorOccurred(message) { if (panel.opened) panel.feedback = message }
    }
    contentItem: ColumnLayout {
        property bool flintFocusScope: true
        spacing: 12
        Text { text: "Раздельное проксирование"; color: "#F8FBFF"; font.pixelSize: 23; font.bold: true; Layout.fillWidth: true; wrapMode: Text.WordWrap }
        ScrollView {
            id: bodyScroll; Layout.fillWidth: true; Layout.fillHeight: true; contentWidth: availableWidth; clip: true
            ColumnLayout { width: bodyScroll.availableWidth; spacing: 12
        Text { text: FlintController.routingSummary; color: "#64ECC0"; font.pixelSize: 12; Layout.fillWidth: true; wrapMode: Text.Wrap }
        Text { text: "Автоматически: российские сервисы из базы правил и локальная сеть — напрямую, остальные сайты — через VPN. Правила обновляются из конфигурации, если сервер их предоставляет. Для VLESS/Xray маршрут выбирается по домену; для других протоколов используется список отдельных сайтов."; color: "#B7C9DA"; font.pixelSize: 13; Layout.fillWidth: true; wrapMode: Text.WordWrap }
        RowLayout {
            Layout.fillWidth: true
            Text { text: "Разделять трафик"; color: "#F8FBFF"; Layout.fillWidth: true; wrapMode: Text.WordWrap }
            Switch {
                activeFocusOnTab: true
                Keys.onPressed: function(event) { FlintFocus.toggleKey(this,event) }
                checked: FlintController.ruDirectEnabled
                Accessible.name: "Российские сайты напрямую"
                onToggled: {
                    FlintController.ruDirectEnabled = checked
                    IpSplitTunnelingController.toggleSplitTunneling(checked)
                    panel.changed()
                }
            }
        }
        RowLayout {
            Layout.fillWidth: true
            Text { text: "Определять маршрут автоматически"; color: "#F8FBFF"; Layout.fillWidth: true; wrapMode: Text.WordWrap }
            Switch {
                activeFocusOnTab: true
                Keys.onPressed: function(event) { FlintFocus.toggleKey(this,event) }
                objectName: "automaticRoutingSwitch"
                checked: FlintController.automaticRoutingEnabled
                enabled: FlintController.ruDirectEnabled
                Accessible.name: "Автоматическое раздельное проксирование"
                onToggled: { FlintController.automaticRoutingEnabled = checked; panel.changed() }
            }
        }
        Text { text: !FlintController.ruDirectEnabled ? "Выключено: весь интернет-трафик идёт через VPN." : FlintController.automaticRoutingEnabled ? "Ваши сайты ниже дополняют автоматические правила." : "Вручную: только сайты ниже идут напрямую, остальные — через VPN."; color: "#B7C9DA"; Layout.fillWidth: true; wrapMode: Text.WordWrap }
        FlintField { id: siteInput; objectName: "directSiteInput"; Layout.fillWidth: true; placeholderText: "example.ru или https://…"; maximumLength: 2048; inputMethodHints: Qt.ImhUrlCharactersOnly | Qt.ImhNoAutoUppercase; onAccepted: panel.add(text) }
        FlintButton { text: "Добавить сайт"; objectName: "addDirectSite"; Layout.fillWidth: true; primary: true; onClicked: panel.add(siteInput.text) }
        Text { text: panel.feedback; visible: text.length > 0; color: "#64ECC0"; font.pixelSize: 12; Layout.fillWidth: true; wrapMode: Text.WordWrap }
        Repeater {
            id: sites; objectName: "directSitesList"
            model: IpSplitTunnelingModel
            delegate: Rectangle {
                required property string url
                required property int index
                Layout.fillWidth: true; implicitHeight: Math.max(72, siteName.implicitHeight + 24); radius: 14
                color: "#142D3E"; border.color: "#345468"
                RowLayout {
                    anchors.fill: parent; anchors.margins: 10
                    Text { id: siteName; text: url; color: "#F8FBFF"; font.pixelSize: 13; wrapMode: Text.WrapAnywhere; Layout.fillWidth: true }
                    FlintButton { text: "Удалить"; Layout.preferredWidth: 90; Accessible.name: "Удалить " + url; onClicked: IpSplitTunnelingController.removeSite(index) }
                }
            }
        }
        Text { visible: sites.count === 0; text: "Добавьте первый сайт"; color: "#B7C9DA" }
            }
        }
        FlintButton { text: "Закрыть"; Layout.alignment: Qt.AlignRight; onClicked: panel.close() }
    }
}
