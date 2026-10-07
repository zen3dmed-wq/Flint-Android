import QtQuick
import QtQuick.Layouts
import "FlintUsage.js" as Usage

Item {
    id: control
    property bool compact: false
    property var subscription: ({})
    property color textColor: "#F8FBFF"
    readonly property bool stacked: width < 600
    implicitHeight: stacked ? (compact ? 41 : 51) : 26
    Rectangle {
        id: bar
        width: control.stacked ? control.width : control.width * 0.48
        height: control.compact ? 20 : 24; radius: 8; color: "#77818E"; clip: true
        Rectangle {
            width: parent.width * Usage.progress(control.subscription)
            height: parent.height; radius: 10; color: "#008CFF"
        }
        Text {
            anchors.fill: parent; anchors.margins: 2
            text: Usage.traffic(control.subscription)
            color: "#FFFFFF"; font.pixelSize: control.compact ? 12 : 14; font.weight: Font.Medium
            horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight; textFormat: Text.PlainText
        }
    }
    Text {
        x: control.stacked ? 0 : bar.width + 16
        y: control.stacked ? (control.compact ? 25 : 29) : 0
        width: control.stacked ? control.width : control.width - x
        height: control.stacked ? (control.compact ? 16 : 22) : 32
        text: Usage.expiry(control.subscription)
        color: control.textColor; font.pixelSize: control.compact ? 11 : 12
        verticalAlignment: Text.AlignVCenter
        horizontalAlignment: control.stacked ? Text.AlignHCenter : Text.AlignLeft
        elide: Text.ElideRight; textFormat: Text.PlainText
    }
}
