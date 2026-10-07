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

// Legacy APIs omit deviceId. These are explicitly labelled groups of logins,
// not an assertion that identically named phones are one physical device.
function groupSessions(items) {
    var groups = [], indexes = {}, seen = {}
    ;(items || []).forEach(function(item, i) {
        if (!item || !item.id || seen["id:" + item.id]) return
        seen["id:" + item.id] = true
        var known = !!item.deviceId
        var platform = String(item.platform || "unknown").toLowerCase()
        var labels = {android:"Android", windows:"Windows", ios:"iPhone и iPad", macos:"macOS", linux:"Linux"}
        var key = known ? "device:" + item.deviceId : "platform:" + platform
        if (indexes[key] === undefined) {
            indexes[key] = groups.length
            groups.push({key:key, model:known ? (item.model || item.platform || "Устройство") : (labels[platform] || "Другие устройства"), verifiedIdentity:known, isCurrent:false, items:[]})
        }
        var group = groups[indexes[key]]
        group.items.push(item)
        group.isCurrent = group.isCurrent || item.isCurrent === true
    })
    groups.forEach(function(group) {
        group.items.sort(function(a,b) {
            if (!!a.isCurrent !== !!b.isCurrent) return a.isCurrent ? -1 : 1
            return String(b.lastActiveAt || "").localeCompare(String(a.lastActiveAt || ""))
        })
    })
    groups.sort(function(a,b) { return a.isCurrent === b.isCurrent ? 0 : a.isCurrent ? -1 : 1 })
    return groups
}
