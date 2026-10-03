import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "FlintFocus.js" as FlintFocus
import "FlintPlans.js" as Plans

Popup {
    id: panel
    property var profile: ({})
    FlintIdentity {
        id: identityPanel; parent: panel.parent
        onProfileUpdated: function(value) { panel.profile = value; if (panel.opened) panel.reload() }
    }
    signal addDeviceRequested()
    FlintDevices { id: devicesPanel; parent: panel.parent; onAddDeviceRequested: panel.addDeviceRequested() }
    FlintSubscriptions { id: subscriptionsPanel; parent: panel.parent }
    objectName: "flintAccountPanel"
    property int section: 0
    property var config: ({})
    property var subscriptions: []
    property var plans: []
    property int selectedPlanIndex: -1
    onPlansChanged: { if (selectedPlanIndex < 0 || selectedPlanIndex >= plans.length) selectedPlanIndex = plans.length ? 0 : -1 }
    property var methods: []
    property var referrals: ({})
    property var tickets: []
    property var purchase: ({})
    property var pending: ({})
    property string error: ""
    property string message: ""
    readonly property color ink: "#F8FBFF"
    readonly property color muted: "#B7C9DA"
    readonly property color mint: "#4AE6A3"
    width: Math.min(parent.width - 20, 560)
    height: Math.max(180, Math.min(parent.height - PageController.safeAreaTopMargin - PageController.safeAreaBottomMargin - 24, 760))
    x: (parent.width - width) / 2
    y: PageController.safeAreaTopMargin + 12
    padding: 18
    modal: true
    focus: true
    background: Rectangle { radius: 20; color: "#FD081827"; border.color: "#46637A" }

    function request(id, method, path, body, key) {
        if (pending[id]) return
        var next = Object.assign({}, pending); next[id] = true; pending = next
        FlintController.accountRequest(id, method, path, body || {}, key || "")
    }
    function reload() {
        FlintController.refresh()
        error = ""
        request("config", "GET", "/config")
        if (!FlintController.loggedIn) return
        request("profile", "GET", "/me")
        request("subscriptions", "GET", "/subscriptions")
        purchase = FlintController.clientDraft("purchase")
        if (purchase.order && purchase.order.id) request("order", "GET", "/orders/" + encodeURIComponent(purchase.order.id))
    }
    function loadSection() {
        if (!FlintController.loggedIn) return
        if (section === 1 && config.purchasesEnabled) {
            request("plans", "GET", "/plans"); request("methods", "GET", "/payment-methods")
        }
        if (section === 2 && config.referralsEnabled) request("referrals", "GET", "/referrals")
        if (section === 3 && config.flintIntegration && config.flintIntegration.supportEnabled) request("tickets", "GET", "/support/tickets")
    }
    function pay() {
        error = ""; message = ""
        if (!purchase.key) {
            if (panel.selectedPlanIndex < 0 || methodChoice.currentIndex < 0) { error = "Выберите тариф и способ оплаты"; return }
            purchase = { key: FlintController.newRequestKey(), body: { planId: plans[panel.selectedPlanIndex].id, provider: methods[methodChoice.currentIndex].id }, startedAt: Date.now() }
            FlintController.saveClientDraft("purchase", purchase)
        }
        request("purchase", "POST", "/orders", purchase.body, purchase.key)
    }
    function openPayment() {
        var url = purchase.order && purchase.order.payment ? purchase.order.payment.url : ""
        if (!/^https:\/\//i.test(url)) { error = "Ссылка на оплату отсутствует. Обновите её."; return }
        if (!Qt.openUrlExternally(url)) error = "Не удалось открыть форму оплаты"
    }
    function sendTicket() {
        error = ""; message = ""
        var text = supportText.text.trim()
        if (!text) { error = "Опишите проблему"; return }
        var draft = FlintController.clientDraft("support")
        if (draft.text && draft.text !== text) { error = "Сначала повторите отправку сохранённого сообщения, чтобы проверить его доставку."; supportText.text = draft.text; return }
        if (!draft.key) draft = { key: FlintController.newRequestKey(), text: text }
        FlintController.saveClientDraft("support", draft)
        request("sendTicket", "POST", "/support/tickets", { text: draft.text, platform: "android" }, draft.key)
    }
    function copyText(value) { clipboard.text = value; clipboard.selectAll(); clipboard.copy(); clipboard.text = ""; message = "Скопировано" }
    function referralText() {
        var template = config.flintIntegration ? config.flintIntegration.referralUrlTemplate : ""
        return template && template.indexOf("{code}") >= 0 ? template.replace("{code}", encodeURIComponent(referrals.code || "")) : (referrals.code || "")
    }
    onOpened: { if(SettingsController.isOnTv()) Qt.callLater(function() { FlintFocus.firstButton(panel.contentItem) }); purchase = FlintController.clientDraft("purchase"); supportText.text = FlintController.clientDraft("support").text || ""; reload() }
    onSectionChanged: { error = ""; message = ""; loadSection() }
    TextEdit { id: clipboard; visible: false }
    Connections {
        target: FlintController
        function onAuthChanged() { if (!FlintController.loggedIn) { panel.profile = {}; panel.subscriptions = []; panel.tickets = []; panel.referrals = {}; panel.purchase = {}; panel.pending = {} } }
        function onApiBaseChanged() { panel.profile = {}; panel.config = {}; panel.pending = {}; panel.close() }
        function onAccountResponse(id, status, data, failure) {
            if (!panel.pending[id]) return
            var next = Object.assign({}, panel.pending); delete next[id]; panel.pending = next
            if (failure) {
                panel.error = failure
                if (id === "purchase" && [400,403,404,409,422].indexOf(status) >= 0) { panel.purchase = {}; FlintController.saveClientDraft("purchase", {}) }
                return
            }
            if (id === "config") { panel.config = data; panel.loadSection() }
            if (id === "profile") panel.profile = data
            if (id === "subscriptions") panel.subscriptions = data.items || []
            if (id === "plans") panel.plans = data.items || []
            if (id === "methods") panel.methods = data.items || []
            if (id === "referrals" || id === "applyReferral") { panel.referrals = data; if (id === "applyReferral") panel.message = "Код сохранён" }
            if (id === "tickets") panel.tickets = data.items || []
            if (id === "purchase" || id === "order" || id === "paymentLink" || id === "cancelOrder") {
                var order = Object.assign({}, panel.purchase.order || {}, data)
                var draft = Object.assign({}, panel.purchase, {order: order})
                panel.purchase = draft; FlintController.saveClientDraft("purchase", draft)
                if (order.status === "completed") {
                    panel.message = "Оплата подтверждена сервером. Подписка закреплена за аккаунтом."
                    FlintController.saveClientDraft("purchase", {}); panel.purchase = {}
                    panel.request("subscriptions", "GET", "/subscriptions"); FlintController.refresh()
                } else if (order.status === "cancelled") { panel.message = "Заказ отменён"; panel.purchase = {}; FlintController.saveClientDraft("purchase", {}) }
                else if (id === "purchase" || id === "paymentLink") panel.openPayment()
            }
            if (id === "sendTicket") {
                FlintController.saveClientDraft("support", {}); supportText.text = ""
                panel.message = "Обращение сохранено. Ответ появится здесь."
                panel.request("tickets", "GET", "/support/tickets")
            }
        }
    }
    Timer {
        interval: 4000; repeat: true
        running: panel.opened && Qt.application.state === Qt.ApplicationActive && panel.purchase.order !== undefined && panel.purchase.order.status === "pending"
        onTriggered: { if (Date.now() - panel.purchase.startedAt < 900000) panel.request("order", "GET", "/orders/" + encodeURIComponent(panel.purchase.order.id)) }
    }
    contentItem: ColumnLayout {
        property bool flintFocusScope: true
        spacing: 10
        RowLayout {
            Layout.fillWidth: true
            Text { Layout.fillWidth: true; text: "Мой Flint"; color: panel.ink; font.pixelSize: 26; font.bold: true }
            FlintButton { text: "×"; implicitWidth: 40; implicitHeight: 40; font.pixelSize: 24; subtle: true; onClicked: panel.close() }
        }
        Text { text: "Один аккаунт на всех устройствах"; color: panel.muted; font.pixelSize: 12; Layout.bottomMargin: 10 }
        FlintButton { Layout.fillWidth: true; visible: FlintController.loggedIn; text: "Способы входа · почта и Telegram"; onClicked: identityPanel.open() }
        RowLayout {
            Layout.fillWidth: true; spacing: 5
            Repeater { model: ["Подписки", "Купить", "Друзья", "Поддержка"]
                FlintButton { required property int index; required property string modelData; Layout.fillWidth: true; text: modelData; font.pixelSize: 11; leftPadding: 6; rightPadding: 6; highlighted: panel.section === index; onClicked: panel.section = index }
            }
        }
        Text { Layout.fillWidth: true; visible: panel.error.length > 0; text: panel.error; color: "#FFAAAA"; wrapMode: Text.Wrap }
        Text { Layout.fillWidth: true; visible: panel.message.length > 0; text: panel.message; color: panel.mint; wrapMode: Text.Wrap }
        ScrollView {
            id: scroll
            Layout.fillWidth: true; Layout.fillHeight: true; clip: true; contentWidth: availableWidth
            ColumnLayout {
                width: scroll.availableWidth; spacing: 14
                Text { Layout.fillWidth: true; visible: !FlintController.loggedIn; text: "Войдите в аккаунт Flint, чтобы видеть подписки, покупать и обращаться в поддержку."; color: panel.muted; wrapMode: Text.Wrap }
                ColumnLayout {
                    visible: FlintController.loggedIn && panel.section === 0; Layout.fillWidth: true; spacing: 12
                    Text { visible: panel.subscriptions.length === 0; text: "Подписок пока нет"; color: panel.muted }
                    Repeater { model: panel.subscriptions
                        ColumnLayout {
                            required property var modelData
                            Layout.fillWidth: true; spacing: 5
                            Text { Layout.fillWidth: true; text: modelData.plan.name; color: panel.ink; font.pixelSize: 18; font.bold: true; wrapMode: Text.Wrap }
                            Text { Layout.fillWidth: true; text: (modelData.status === "active" ? "Активна до " : "Истекла ") + new Date(modelData.expiresAt).toLocaleDateString(Qt.locale("ru_RU")); color: panel.muted; wrapMode: Text.Wrap }
                            Text { Layout.fillWidth: true; visible: !!modelData.traffic && modelData.traffic.limitReached; text: "Лимит трафика исчерпан. Он обновится 1-го числа."; color: "#FFC56D"; wrapMode: Text.Wrap }
                            Rectangle { Layout.fillWidth: true; height: 1; color: "#46637A" }
                        }
                    }
                    FlintButton { text: "Обновить подписки"; enabled: !panel.pending.subscriptions; onClicked: { panel.request("subscriptions", "GET", "/subscriptions"); FlintController.refresh() } }
                    FlintButton { text: "Устройства подписки"; onClicked: { panel.close(); devicesPanel.open() } }
                    FlintButton { visible: panel.config.purchasesEnabled === true; primary: true; text: "Купить подписку"; onClicked: panel.section = 1 }
                }
                ColumnLayout {
                    visible: FlintController.loggedIn && panel.section === 1; Layout.fillWidth: true
                    Text { Layout.fillWidth: true; visible: !panel.config.purchasesEnabled; text: "Покупки пока недоступны на подключённом API."; color: panel.muted; wrapMode: Text.Wrap }
                    Text { Layout.fillWidth: true; textFormat: Text.PlainText; text: "Покупка для: " + (panel.profile.email || (panel.profile.telegram && panel.profile.telegram.username ? "@" + panel.profile.telegram.username : "текущего аккаунта Flint")); color: panel.mint; wrapMode: Text.WrapAnywhere }
                    Text { Layout.fillWidth: true; text: "Все тарифы перед вами. Выгода рассчитана относительно самого короткого тарифа в той же валюте."; color: panel.muted; wrapMode: Text.Wrap }
                    Text { text: "ТАРИФ"; font.pixelSize: 11; font.letterSpacing: 1.2; color: panel.muted; Layout.topMargin: 16 }
                    GridLayout {
                        Layout.fillWidth: true; columns: 2; columnSpacing: 10; rowSpacing: 10
                        Repeater { model: panel.plans
                            FlintButton {
                                objectName: "purchasePlanCard"
                                required property var modelData
                                required property int index
                                Layout.fillWidth: true; Layout.preferredWidth: 1
                                implicitHeight: Math.max(116, planLabels.implicitHeight + 24)
                                highlighted: panel.selectedPlanIndex === index; retainHighlight: true
                                enabled: panel.config.purchasesEnabled === true && !panel.purchase.key
                                onClicked: panel.selectedPlanIndex = index
                                contentItem: ColumnLayout {
                                    id: planLabels; spacing: 5
                                    Text { Layout.fillWidth: true; text: modelData.name; textFormat: Text.PlainText; wrapMode: Text.Wrap; color: parent.parent.highlighted ? "#052A20" : panel.ink; font.bold: true; font.pixelSize: 14 }
                                    Text { text: modelData.price.amount + " " + modelData.price.currency; color: parent.parent.highlighted ? "#052A20" : panel.ink; font.bold: true; font.pixelSize: 20 }
                                    Text { Layout.fillWidth: true; text: Plans.benefit(modelData, panel.plans); wrapMode: Text.Wrap; color: parent.parent.highlighted ? "#164C3C" : panel.mint; font.pixelSize: 11 }
                                }
                            }
                        }
                    }
                    Text { text: "СПОСОБ ОПЛАТЫ"; font.pixelSize: 11; font.letterSpacing: 1.2; color: panel.muted; Layout.topMargin: 12 }
                    FlintChoice { id: methodChoice; Layout.fillWidth: true; model: panel.methods; textRole: "title"; enabled: panel.config.purchasesEnabled === true && !panel.purchase.key }
                    FlintButton { Layout.fillWidth: true; visible: !panel.purchase.order; enabled: panel.config.purchasesEnabled === true && !panel.pending.purchase; primary: true; text: panel.purchase.key ? "Повторить запрос заказа" : "Перейти к оплате"; onClicked: panel.pay() }
                    Text { Layout.fillWidth: true; visible: panel.purchase.order !== undefined; text: "Ожидаем подтверждения оплаты от сервера. После оплаты вернитесь в приложение."; color: panel.muted; wrapMode: Text.Wrap }
                    FlintButton { visible: panel.purchase.order !== undefined; text: "Открыть оплату"; onClicked: panel.openPayment() }
                    FlintButton { visible: panel.purchase.order !== undefined; text: "Проверить оплату"; enabled: !panel.pending.order; onClicked: panel.request("order", "GET", "/orders/" + encodeURIComponent(panel.purchase.order.id)) }
                    FlintButton { visible: panel.purchase.order !== undefined && panel.config.purchasesEnabled === true; text: "Получить новую ссылку"; enabled: !panel.pending.paymentLink; onClicked: panel.request("paymentLink", "POST", "/orders/" + encodeURIComponent(panel.purchase.order.id) + "/payment-link") }
                    FlintButton { visible: panel.purchase.order !== undefined; text: "Отменить заказ"; enabled: !panel.pending.cancelOrder; onClicked: panel.request("cancelOrder", "POST", "/orders/" + encodeURIComponent(panel.purchase.order.id) + "/cancel") }
                }
                ColumnLayout {
                    visible: FlintController.loggedIn && panel.section === 2; Layout.fillWidth: true
                    Text { Layout.fillWidth: true; text: panel.config.referralsEnabled ? "Приглашайте друзей" : "Реферальная программа пока недоступна"; font.pixelSize: 19; color: panel.ink; wrapMode: Text.Wrap }
                    Text { Layout.fillWidth: true; text: panel.referralText(); color: panel.mint; wrapMode: Text.WrapAnywhere }
                    Text { text: "Приглашено: " + (panel.referrals.invitedCount || 0) + " · Бонусных дней: " + (panel.referrals.bonusDays || 0); color: panel.muted; Layout.fillWidth: true; wrapMode: Text.Wrap }
                    FlintButton { text: "Скопировать приглашение"; enabled: !!panel.referrals.code; onClicked: panel.copyText(panel.referralText()) }
                    FlintField { id: referralCode; Layout.fillWidth: true; placeholderText: "Код пригласившего друга"; visible: panel.config.referralsEnabled === true }
                    FlintButton { visible: panel.config.referralsEnabled === true; text: "Применить код"; enabled: referralCode.text.trim().length > 0 && !panel.pending.applyReferral; onClicked: panel.request("applyReferral", "POST", "/referrals/apply", {code: referralCode.text.trim()}) }
                }
                ColumnLayout {
                    visible: FlintController.loggedIn && panel.section === 3; Layout.fillWidth: true
                    Text { Layout.fillWidth: true; visible: !(panel.config.flintIntegration && panel.config.flintIntegration.supportEnabled); text: "Доставка обращений ещё не подключена. Администратор сможет включить её через API и админку."; color: panel.muted; wrapMode: Text.Wrap }
                    TextArea { id: supportText; padding: 16; font.pixelSize: 14; Layout.topMargin: 10; Layout.fillWidth: true; Layout.preferredHeight: 140; color: panel.ink; placeholderTextColor: panel.muted; placeholderText: "Опишите проблему"; wrapMode: TextEdit.Wrap; enabled: !panel.pending.sendTicket; background: Rectangle { radius: 14; color: "#102635"; border.color: supportText.activeFocus ? panel.mint : "#2B4A5E" } }
                    FlintButton { primary: true; text: "Отправить в поддержку"; enabled: panel.config.flintIntegration !== undefined && panel.config.flintIntegration.supportEnabled === true && !panel.pending.sendTicket; onClicked: panel.sendTicket() }
                    FlintButton { text: "Обновить ответы"; enabled: panel.config.flintIntegration !== undefined && panel.config.flintIntegration.supportEnabled === true && !panel.pending.tickets; onClicked: panel.request("tickets", "GET", "/support/tickets") }
                    Repeater { model: panel.tickets
                        ColumnLayout { required property var modelData; Layout.fillWidth: true
                            Text { text: "Обращение " + modelData.id.slice(0,10); color: panel.mint }
                            Repeater { model: modelData.messages
                                Text { required property var modelData; Layout.fillWidth: true; text: (modelData.author === "support" ? "Поддержка: " : "Вы: ") + modelData.text; color: panel.ink; textFormat: Text.PlainText; wrapMode: Text.Wrap }
                            }
                        }
                    }
                }
            }
        }
    }
}
