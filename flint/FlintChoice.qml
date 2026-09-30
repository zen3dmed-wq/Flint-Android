import QtQuick
import QtQuick.Controls

ComboBox {
    id: control
    implicitHeight: 54
    font.pixelSize: 15
    leftPadding: 16; rightPadding: 40
    contentItem: Text {
        text: control.displayText; font: control.font; color: control.enabled ? "#F0F7FB" : "#819DAC"
        verticalAlignment: Text.AlignVCenter; elide: Text.ElideRight
    }
    indicator: Canvas {
        x: control.width - width - 18; y: (control.height - height)/2
        width: 12; height: 7
        onPaint: { var c=getContext("2d"); c.clearRect(0,0,width,height); c.strokeStyle="#8FADBD";c.lineWidth=1.6;c.beginPath();c.moveTo(1,1);c.lineTo(6,6);c.lineTo(11,1);c.stroke() }
    }
    background: Rectangle { radius: 13; color: "#102635"; border.color: control.activeFocus || control.popup.visible ? "#57E4B0" : "#2B4A5E" }
    delegate: ItemDelegate {
        id: option
        required property var modelData
        required property int index
        width: control.width - 8; implicitHeight: 48
        highlighted: control.highlightedIndex === index
        contentItem: Text { text: option.modelData[control.textRole] || ""; color: "#F0F7FB"; font.pixelSize: 14; verticalAlignment: Text.AlignVCenter; elide: Text.ElideRight }
        background: Rectangle { radius: 9; color: option.highlighted ? "#245343" : "transparent" }
    }
    popup: Popup {
        y: control.height + 6; width: control.width
        implicitHeight: Math.min(contentItem.implicitHeight + 8, 240)
        padding: 4
        contentItem: ListView { clip: true; implicitHeight: contentHeight; model: control.popup.visible ? control.delegateModel : null; currentIndex: control.highlightedIndex; ScrollIndicator.vertical: ScrollIndicator {} }
        background: Rectangle { radius: 13; color: "#142E40"; border.color: "#3A6173" }
    }
}
