import QtQuick
import QtQuick.Controls
import "FlintFocus.js" as FlintFocus

Button {
    id: control
    property bool primary: false
    property bool subtle: false
    property bool retainHighlight: false
    activeFocusOnTab: true
    Keys.onReleased: function(event) {
        if ([Qt.Key_Select,Qt.Key_Enter,Qt.Key_Return].indexOf(event.key)>=0) event.accepted=true
    }
    function popupFocus(forward) {
        return FlintFocus.move(control, forward)
    }
    Keys.onPressed: function(event) {
        if (event.key === Qt.Key_Select || event.key === Qt.Key_Return || event.key === Qt.Key_Enter) { if (!event.isAutoRepeat) control.clicked(); event.accepted = true }
        else if ([Qt.Key_Down, Qt.Key_Right, Qt.Key_Up, Qt.Key_Left].indexOf(event.key) >= 0)
            event.accepted = popupFocus(event.key === Qt.Key_Down || event.key === Qt.Key_Right)
    }
    Rectangle {
        anchors.fill: parent; anchors.margins: -3; radius: 16
        color: "transparent"; border.color: "#FFFFFF"; border.width: 3
        visible: control.activeFocus; z: 2
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
