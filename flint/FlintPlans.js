.pragma library
function benefit(plan, plans) {
    if (!plan || !plan.price) return ""
    var days = Number(plan.durationDays), amount = Number(plan.price.amount)
    if (!(days > 0) || !isFinite(amount) || amount <= 0) return ""
    var base = null
    for (var i = 0; i < plans.length; ++i) {
        var p = plans[i], d = Number(p.durationDays), a = p.price ? Number(p.price.amount) : NaN
        if (p.price && p.price.currency === plan.price.currency && d > 0 && a > 0 && isFinite(a) && (!base || d < Number(base.durationDays))) base = p
    }
    var monthly = (amount * 30 / days).toFixed(0) + " " + plan.price.currency + " / 30 дней"
    if (!base || Number(base.durationDays) >= days) return monthly
    var saving = Math.floor(100 * (1 - (amount / days) / (Number(base.price.amount) / Number(base.durationDays))))
    return saving > 0 ? "Выгода " + saving + "% · " + monthly : monthly
}
