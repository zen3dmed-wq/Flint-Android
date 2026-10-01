import QtQuick
import QtQuick.Layouts
import "FlintUsage.js" as Usage

Item {
    id: control
    property var subscription: ({})
    property color textColor: "#F8FBFF"
    readonly property bool stacked: width < 600
    implicitHeight: stacked ? 62 : 34
    Rectangle {
        id: bar
        width: control.stacked ? control.width : control.width * 0.48
        height: 32; radius: 10; color: "#77818E"; clip: true
        Rectangle {
            width: parent.width * Usage.progress(control.subscription)
            height: parent.height; radius: 10; color: "#008CFF"
        }
        Text {
            anchors.fill: parent; anchors.margins: 4
            text: Usage.traffic(control.subscription)
            color: "#FFFFFF"; font.pixelSize: 17; font.weight: Font.Medium
            horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter
            elide: Text.ElideRight; textFormat: Text.PlainText
        }
    }
    Text {
        x: control.stacked ? 0 : bar.width + 16
        y: control.stacked ? 39 : 0
        width: control.stacked ? control.width : control.width - x
        height: control.stacked ? 22 : 32
        text: Usage.expiry(control.subscription)
        color: control.textColor; font.pixelSize: 12
        verticalAlignment: Text.AlignVCenter
        horizontalAlignment: control.stacked ? Text.AlignHCenter : Text.AlignLeft
        elide: Text.ElideRight; textFormat: Text.PlainText
    }
}
