.pragma library
function duration(plan) { return Number(plan.durationDays === undefined ? plan.days : plan.durationDays) }
function available(plan, now) {
    if (!plan || !plan.id || !plan.price) return false
    if (!plan.availableUntil) return true
    var deadline = Date.parse(plan.availableUntil)
    return !isFinite(deadline) || deadline > (now === undefined ? Date.now() : now)
}
function firstAvailable(plans) {
    for (var i = 0; i < plans.length; ++i) if (available(plans[i])) return i
    return -1
}
function benefit(plan, plans) {
    if (!plan || !plan.price) return ""
    var days = duration(plan), amount = Number(plan.price.amount), parts = []
    // Respect account-specific backend discounts, including an explicit zero.
    var percent = plan.savingsPercent, saving = plan.savings
    var hasPercent = percent !== undefined && percent !== null && isFinite(Number(percent)) && Number(percent) >= 0 && Number(percent) <= 100
    var hasMoney = saving && saving.currency === plan.price.currency && saving.amount !== null && saving.amount !== undefined && isFinite(Number(saving.amount)) && Number(saving.amount) >= 0
    if (hasPercent) {
        if (Number(percent) > 0) parts.push("Выгода " + Number(percent) + "%")
    } else if (hasMoney) {
        if (Number(saving.amount) > 0) parts.push("Выгода " + saving.amount + " " + saving.currency)
    } else if (days > 0 && isFinite(amount) && amount > 0) {
        var base = null
        for (var i = 0; i < plans.length; ++i) {
            var p = plans[i], d = duration(p), a = p.price ? Number(p.price.amount) : NaN
            if (p.price && p.price.currency === plan.price.currency && d > 0 && a > 0 && isFinite(a) && (!base || d < duration(base))) base = p
        }
        if (base && duration(base) < days) {
            var estimate = Math.floor(100 * (1 - (amount / days) / (Number(base.price.amount) / duration(base))))
            if (estimate > 0) parts.push("Выгода " + estimate + "%")
        }
    }
    if (days > 0 && isFinite(amount) && amount >= 0) parts.push((amount * 30 / days).toFixed(0) + " " + plan.price.currency + " / 30 дней")
    return parts.join(" · ")
}
function details(plan) {
    var parts = []
    if (plan.isPersonal) parts.push("Для вас")
    if (plan.traffic && plan.traffic.limitBytes !== undefined) {
        var limit = plan.traffic.limitBytes
        if (limit === null) parts.push("Безлимит")
        else if (isFinite(Number(limit)) && Number(limit) >= 0) parts.push((Number(limit) / 1000000000).toLocaleString(Qt.locale("ru_RU"), 'f', Number(limit) % 1000000000 ? 1 : 0) + " GB")
    }
    if (plan.availableUntil && isFinite(Date.parse(plan.availableUntil)))
        parts.push(available(plan) ? "До " + new Date(plan.availableUntil).toLocaleDateString(Qt.locale("ru_RU")) : "Предложение завершено")
    return parts.join(" · ")
}
