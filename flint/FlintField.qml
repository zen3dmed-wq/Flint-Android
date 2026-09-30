import QtQuick
import QtQuick.Controls

TextField {
    id: control
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
