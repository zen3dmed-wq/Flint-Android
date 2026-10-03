function ensureVisible(item) {
    var parent = item.parent
    while (parent) {
        if (parent.contentItem && parent.contentY !== undefined && parent.contentHeight !== undefined) {
            var point = item.mapToItem(parent.contentItem, 0, 0)
            if (point.y < parent.contentY) parent.contentY = Math.max(0, point.y - 8)
            else if (point.y + item.height > parent.contentY + parent.height)
                parent.contentY = Math.max(0, Math.min(parent.contentHeight - parent.height, point.y + item.height - parent.height + 8))
        }
        parent = parent.parent
    }
}
function move(item, forward) {
    var scope = item.parent
    while (scope && !(scope.hasOwnProperty("flintFocusScope") && scope.flintFocusScope)) scope = scope.parent
    if (!scope) return false
    var next = item
    for (var i = 0; i < 512; ++i) {
        next = next.nextItemInFocusChain(forward)
        if (!next || next === item) break
        if (!next.visible || !next.enabled || !next.activeFocusOnTab) continue
        var ancestor = next.parent
        while (ancestor && ancestor !== scope) ancestor = ancestor.parent
        if (ancestor === scope) {
            next.forceActiveFocus(Qt.TabFocusReason)
            ensureVisible(next)
            return true
        }
    }
    return false
}
function firstButton(item) {
    if (!item || !item.visible || !item.enabled) return false
    if (item.activeFocusOnTab && typeof item.clicked === "function") {
        item.forceActiveFocus(Qt.TabFocusReason)
        ensureVisible(item)
        return true
    }
    var children = item.children || []
    for (var i = 0; i < children.length; ++i)
        if (firstButton(children[i])) return true
    return false
}

function toggleKey(control, event) {
    if ([Qt.Key_Select, Qt.Key_Return, Qt.Key_Enter].indexOf(event.key) >= 0) {
        if (!event.isAutoRepeat) { control.toggle(); control.toggled() }
        event.accepted = true
    } else if ([Qt.Key_Down, Qt.Key_Right, Qt.Key_Up, Qt.Key_Left].indexOf(event.key) >= 0)
        event.accepted = move(control, event.key === Qt.Key_Down || event.key === Qt.Key_Right)
}
