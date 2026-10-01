function firstButton(item) {
    if (!item || !item.visible || !item.enabled) return false
    if (item.activeFocusOnTab && typeof item.clicked === "function") {
        item.forceActiveFocus(Qt.TabFocusReason)
        return true
    }
    var children = item.children || []
    for (var i = 0; i < children.length; ++i)
        if (firstButton(children[i])) return true
    return false
}
