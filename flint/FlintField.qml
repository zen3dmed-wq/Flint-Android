import QtQuick
import QtQuick.Controls
import "FlintFocus.js" as FlintFocus

TextField {
    id: control
    activeFocusOnTab: true
    Keys.onPressed: function(event) {
        if (!FlintFocus.isTv()) return
        if (event.key === Qt.Key_Up || event.key === Qt.Key_Down)
            event.accepted = FlintFocus.move(control, event.key === Qt.Key_Down)
        else if (event.key === Qt.Key_Select) { Qt.inputMethod.show(); event.accepted = true }
    }
    implicitHeight: 52
    padding: 15
    font.pixelSize: 14
    color: "#F0F7FB"
    placeholderTextColor: "#819DAC"
    selectionColor: "#2A6C5E"
    selectedTextColor: "#FFFFFF"
    background: Rectangle {
        radius: 13; color: "#102635"
        border.color: control.activeFocus ? "#57E4B0" : "#2B4A5E"
        border.width: 1
    }
}
