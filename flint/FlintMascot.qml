import QtQuick

// Keep the original emblem intact. Only the inner facial expression is blended.
Item {
    id: mascot
    property bool connected: false
    property bool animate: true
    property bool ready: false
    readonly property string expression: connected ? "happy" : "sad"
    readonly property bool transitioning: expressionFade.running
    readonly property real sadOpacity: sadFace.opacity
    readonly property bool artworkReady: original.status === Image.Ready && sadFace.artworkReady
    function updateExpression(withAnimation) {
        if (!ready) return
        expressionFade.stop()
        var targetOpacity = connected ? 0 : 1
        if (withAnimation && animate && visible && Math.abs(sadFace.opacity - targetOpacity) > 0.001) {
            expressionFade.from = sadFace.opacity
            expressionFade.to = targetOpacity
            expressionFade.start()
        } else sadFace.opacity = targetOpacity
    }
    Component.onCompleted: { ready = true; updateExpression(false) }
    onConnectedChanged: updateExpression(true)
    onVisibleChanged: if (!visible) updateExpression(false)
    onAnimateChanged: if (!animate) updateExpression(false)

    NumberAnimation {
        id: expressionFade
        target: sadFace
        property: "opacity"
        duration: 300
        easing.type: Easing.InOutQuad
    }

    Image {
        id: original
        anchors.fill: parent
        source: "qrc:/ui/qml/Assets/flint-main.png"
        fillMode: Image.PreserveAspectFit
        smooth: true
    }

    Canvas {
        id: sadFace
        objectName: "mascotSadFace"
        anchors.centerIn: parent
        width: Math.min(parent.width, parent.height)
        height: width
        property url artwork: "qrc:/ui/qml/Assets/flint-main-sad.png"
        property bool artworkReady: false
        opacity: 1
        renderTarget: Canvas.Image
        Component.onCompleted: loadImage(artwork)
        onImageLoaded: { artworkReady = true; requestPaint() }
        onWidthChanged: requestPaint()
        onHeightChanged: requestPaint()
        onPaint: {
            if (!artworkReady || width <= 0 || height <= 0) return
            var ctx = getContext("2d")
            ctx.reset()
            ctx.clearRect(0, 0, width, height)
            ctx.drawImage(artwork, 0, 0, width, height)
            // Soft, opaque-centred face mask. Ring, icons, fur outline and paws
            // always come from the unchanged original, including during fades.
            ctx.globalCompositeOperation = "destination-in"
            ctx.save()
            ctx.translate(width * 0.50, height * 0.455)
            ctx.scale(width * 0.19, height * 0.185)
            var mask = ctx.createRadialGradient(0, 0, 0, 0, 0, 1)
            mask.addColorStop(0, "#ffffff")
            mask.addColorStop(0.80, "#ffffff")
            mask.addColorStop(1, "#00ffffff")
            ctx.fillStyle = mask
            ctx.fillRect(-4, -4, 8, 8)
            ctx.restore()
        }
    }
}
