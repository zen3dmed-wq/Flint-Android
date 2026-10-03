.pragma library

function bytes(value) {
    var n = Number(value)
    if (!isFinite(n) || n < 0) return "—"
    var units = ["Б", "КиБ", "МиБ", "ГиБ", "ТиБ"], i = 0
    while (n >= 1024 && i < units.length - 1) { n /= 1024; ++i }
    return n.toLocaleString(Qt.locale("ru_RU"), "f", i === 0 ? 0 : 1) + " " + units[i]
}
function title(sub) {
    return sub && sub.plan && sub.plan.name ? sub.plan.name : "Подписка Flint"
}
function traffic(sub) {
    var t = sub && sub.traffic
    if (!t) return "Нет данных"
    var used = t.updatedAt && t.usedBytes !== null && t.usedBytes !== undefined ? gb(t.usedBytes) : "—"
    return used + " / " + (t.limitBytes === null || t.limitBytes === undefined ? "∞" : gb(t.limitBytes))
}
function gb(value) {
    return (Number(value) / 1000000000).toLocaleString(Qt.locale("ru_RU"), "f", 1) + " GB"
}
function progress(sub) {
    var t = sub && sub.traffic
    return t && t.updatedAt && Number(t.limitBytes) > 0 ? Math.max(0, Math.min(1, Number(t.usedBytes) / Number(t.limitBytes))) : 0
}
function expiry(sub) {
    if (!sub || !sub.expiresAt) return ""
    var d = new Date(sub.expiresAt)
    return isNaN(d.getTime()) ? "" : (sub.status === "expired" ? "Истекла: " : "Активна до: ") + d.toLocaleString(Qt.locale("ru_RU"), "dd MMMM yyyy HH:mm")
}
