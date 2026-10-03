import QtQuick
import QtQuick.Controls

Button {
    id: control
    property bool primary: false
    property bool subtle: false
    property bool retainHighlight: false
    activeFocusOnTab: true
    function popupFocus(forward) {
        var scope = parent
        while (scope && !(scope.hasOwnProperty("flintFocusScope") && scope.flintFocusScope)) scope = scope.parent
        if (!scope) return false
        var item = control
        for (var i = 0; i < 256; i++) {
            item = item.nextItemInFocusChain(forward)
            if (!item || item === control) break
            if (!item.visible || !item.enabled || !item.activeFocusOnTab) continue
            var ancestor = item.parent
            while (ancestor && ancestor !== scope) ancestor = ancestor.parent
            if (ancestor === scope) { item.forceActiveFocus(Qt.TabFocusReason); return true }
        }
        return false
    }
    Keys.onPressed: function(event) {
        if (!SettingsController.isOnTv()) return
        if (event.key === Qt.Key_Select || event.key === Qt.Key_Return || event.key === Qt.Key_Enter) { control.clicked(); event.accepted = true }
        else if ([Qt.Key_Down, Qt.Key_Right, Qt.Key_Up, Qt.Key_Left].indexOf(event.key) >= 0)
            event.accepted = popupFocus(event.key === Qt.Key_Down || event.key === Qt.Key_Right)
    }
    implicitHeight: 48
    implicitWidth: Math.max(80, contentItem.implicitWidth + leftPadding + rightPadding)
    padding: 14
    leftPadding: 16
    rightPadding: 16
    font.pixelSize: 14
    font.weight: Font.DemiBold
    contentItem: Text {
        text: control.text; font: control.font
        color: !control.enabled ? "#667F91" : ((control.primary || control.highlighted) ? "#052A20" : "#E7F3F8")
        horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight; textFormat: Text.PlainText
    }
    background: Rectangle {
        radius: 13
        color: !control.enabled && !(control.highlighted && control.retainHighlight) ? "#142B3A" : ((control.primary || control.highlighted) ? (control.down ? "#36C997" : "#57E4B0") : (control.down ? "#21495B" : (control.subtle ? "transparent" : "#142E40")))
        border.color: control.activeFocus ? "#57E4B0" : ((control.primary || control.highlighted) ? color : "#2B4A5E")
        border.width: control.subtle && !control.activeFocus ? 0 : 1
        Behavior on color { ColorAnimation { duration: 100 } }
    }
}
