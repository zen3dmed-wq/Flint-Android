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
    readonly property bool artworkReady: original.artworkReady && sadFace.artworkReady
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

    Canvas {
        id: original
        objectName: "mascotStateRing"
        anchors.centerIn: parent
        // Paint at the source image's size, once per state. Resizing the home
        // screen only scales this cached texture and does not recolour pixels.
        width: 360; height: 360
        scale: Math.min(parent.width, parent.height) / 360
        property url artwork: "qrc:/ui/qml/Assets/flint-main.png"
        property bool artworkReady: false
        property string connectionState: mascot.status
        property int paintCount: 0
        property int cacheBuilds: 0
        property var cachedRings: ({})
        renderTarget: Canvas.Image
        Component.onCompleted: loadImage(artwork)
        onImageLoaded: { artworkReady = true; requestPaint() }
        onConnectionStateChanged: requestPaint()
        onPaint: {
            if (!artworkReady) return
            var ctx = getContext("2d")
            ctx.reset()
            ctx.clearRect(0, 0, 360, 360)
            ctx.drawImage(artwork, 0, 0, 360, 360)
            if (connectionState !== "connected") {
                var cached = cachedRings[connectionState]
                if (cached) {
                    ctx.clearRect(0, 0, 360, 360)
                    ctx.putImageData(cached, 0, 0, 0, 0, 360, 360)
                    paintCount++
                    return
                }
                var tint = connectionState === "connecting" ? [241, 199, 91]
                         : connectionState === "error" ? [239, 98, 107] : [130, 144, 158]
                var pixels = ctx.getImageData(0, 0, 360, 360)
                var data = pixels.data
                for (var y = 0; y < 360; ++y) {
                    var dy = y / 360 - 0.49
                    for (var x = 0; x < 360; ++x) {
                        var dx = x / 360 - 0.50
                        var radius2 = dx * dx + dy * dy
                        if (radius2 < 0.375 * 0.375 || radius2 > 0.49 * 0.49) continue
                        var i = (y * 360 + x) * 4
                        var red = data[i], green = data[i+1], blue = data[i+2]
                        // Restrict the tint to the coloured ring and its halo;
                        // neutral fur/edges and inner wifi/shield icons stay intact.
                        if (!data[i+3] || green <= red + 6 || green < blue * 0.65) continue
                        var brightness = Math.max(red, green, blue) / 255
                        data[i] = Math.round(tint[0] * brightness)
                        data[i+1] = Math.round(tint[1] * brightness)
                        data[i+2] = Math.round(tint[2] * brightness)
                    }
                }
                ctx.clearRect(0, 0, 360, 360)
                ctx.putImageData(pixels, 0, 0, 0, 0, 360, 360)
                cachedRings[connectionState] = pixels
                cacheBuilds++
            }
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
