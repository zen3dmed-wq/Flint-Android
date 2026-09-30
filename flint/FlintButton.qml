import QtQuick
import QtQuick.Controls

Button {
    id: control
    property bool primary: false
    property bool subtle: false
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
        color: !control.enabled ? "#142B3A" : ((control.primary || control.highlighted) ? (control.down ? "#36C997" : "#57E4B0") : (control.down ? "#21495B" : (control.subtle ? "transparent" : "#142E40")))
        border.color: control.activeFocus ? "#57E4B0" : ((control.primary || control.highlighted) ? color : "#2B4A5E")
        border.width: control.subtle && !control.activeFocus ? 0 : 1
        Behavior on color { ColorAnimation { duration: 100 } }
    }
}
