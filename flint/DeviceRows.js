.pragma library

// Never merge different phones merely because their model names match.
function uniqueSessions(items) {
    var result = [], indexes = {}
    ;(items || []).forEach(function(item, i) {
        var key = item.deviceId ? "device:" + item.deviceId : item.id ? "session:" + item.id : "row:" + i
        if (indexes[key] === undefined) { indexes[key] = result.length; result.push(item); return }
        var previous = result[indexes[key]]
        if (item.isCurrent || (!previous.isCurrent && String(item.lastActiveAt || "") > String(previous.lastActiveAt || "")))
            result[indexes[key]] = item
    })
    return result
}
