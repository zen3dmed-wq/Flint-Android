import QtQuick

// Preserve the original artwork; only the inner face and ring indicate VPN state.
Item {
    id: mascot
    property string status: "idle"
    readonly property bool connected: status === "connected"
    property bool animate: true
    property bool ready: false
    readonly property string expression: connected ? "happy" : "sad"
    readonly property bool transitioning: expressionFade.running
    readonly property real sadOpacity: sadFace.opacity
    readonly property bool artworkReady: nativeOriginal.status === Image.Ready && sadFace.artworkReady && (connected || original.artworkReady)
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

    // The native Image displays the connected emblem and remains the fallback
    // until the state-specific layer has drawn a complete source image.
    Image {
        id: nativeOriginal
        objectName: "mascotOriginalImage"
        anchors.centerIn: parent
        width: Math.min(parent.width, parent.height)
        height: width
        source: "qrc:/ui/qml/Assets/flint-main.png"
        fillMode: Image.PreserveAspectFit
        smooth: true
        visible: mascot.connected || !original.hasDrawnFrame
    }

    Canvas {
        id: original
        objectName: "mascotStateRing"
        anchors.centerIn: parent
        width: 360; height: 360
        scale: Math.min(parent.width, parent.height) / 360
        visible: !mascot.connected
        property url artwork: nativeOriginal.source
        property bool artworkReady: false
        property bool hasDrawnFrame: false
        property string connectionState: mascot.status
        property int paintCount: 0
        property int cacheBuilds: 0
        property var cachedRings: ({})
        renderTarget: Canvas.Image
        Component.onCompleted: loadImage(artwork)
        onImageLoaded: { artworkReady = true; requestPaint() }
        onConnectionStateChanged: requestPaint()
        onVisibleChanged: if (visible) requestPaint()
        onPaint: {
            if (!artworkReady || connectionState === "connected") return
            var ctx = getContext("2d")
            ctx.reset()
            ctx.clearRect(0, 0, width, height)
            var cached = cachedRings[connectionState]
            if (!cached) {
                // createImageData(url) reads the original PNG in source pixels.
                // getImageData()/putImageData() mix framebuffer device pixels
                // with logical coordinates on DPR > 1 and crop the emblem.
                var pixels = ctx.createImageData(String(artwork))
                var data = pixels.data
                var imageWidth = pixels.width, imageHeight = pixels.height
                if (!imageWidth || !imageHeight) return
                var tint = connectionState === "connecting" ? [241, 199, 91]
                         : connectionState === "error" ? [239, 98, 107] : [130, 144, 158]
                for (var y = 0; y < imageHeight; ++y) {
                    var dy = y / imageHeight - 0.49
                    for (var x = 0; x < imageWidth; ++x) {
                        var dx = x / imageWidth - 0.50
                        var radius2 = dx * dx + dy * dy
                        if (radius2 < 0.375 * 0.375 || radius2 > 0.49 * 0.49) continue
                        var i = (y * imageWidth + x) * 4
                        var red = data[i], green = data[i+1], blue = data[i+2]
                        if (!data[i+3] || green <= red + 6 || green < blue * 0.65) continue
                        var brightness = Math.max(red, green, blue) / 255
                        data[i] = Math.round(tint[0] * brightness)
                        data[i+1] = Math.round(tint[1] * brightness)
                        data[i+2] = Math.round(tint[2] * brightness)
                    }
                }
                cached = pixels
                cachedRings[connectionState] = cached
                cacheBuilds++
            }
            // drawImage honours logical sizing and Qt's device-pixel ratio.
            ctx.drawImage(cached, 0, 0, width, height)
            hasDrawnFrame = true
            paintCount++
        }
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
