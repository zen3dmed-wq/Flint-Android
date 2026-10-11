import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
Popup {
    id: panel
    objectName: "flintPairingPanel"
    property string mode: ""
    property string qr: ""
    property string notice: ""
    property bool busy: false
    property bool completed: false
    property int generation: 0
    property string request: ""
    property string operation: ""
    property string subscriptionId: ""
    property var subscriptions: []
    property var state: ({})
    property var payload: ({})
    property real deadline: 0
    signal received()
    width: Math.min(parent.width-24,480)
    height: Math.min(parent.height-32,680)
    anchors.centerIn: parent
    padding: 20;modal:true;focus:true
    background: Rectangle {color:"#081827";radius:24;border.color:"#46637A"}
    function send(op,body) {
        if(busy)return
        busy=true;operation=op;request="pairing:"+generation+":"+FlintController.newRequestKey()
        FlintController.accountRequest(request,"POST","/devices/pairing/"+op,body,"")
    }
    function owner(selected) {
        reset("share");subscriptionId=selected||FlintController.selectedSubscriptionId;open();loadSubscriptions()
    }
    function receiver() {
        reset("receive");state=FlintController.newPairingState();open()
        if(!state.key){notice="Не удалось подготовить QR";return}
        send("start",{codeChallenge:state.challenge,device:FlintController.pairingDevice()})
    }
    function invite(link) {
        var target=FlintController.parsePairingLink(link);if(!target.id)return false
        reset(target.shared?"accept":"approve");state=target;open()
        if(target.shared){notice="Добавить подключение из QR? Вход в аккаунт владельца не передаётся."}
        else if(!FlintController.loggedIn){notice="Для передачи своей подписки войдите в аккаунт и отсканируйте QR ещё раз."}
        else send("inspect",{pairingId:state.id})
        return true
    }
    function reset(value){generation++;request="";busy=false;completed=false;qr="";notice="Подготавливаем…";mode=value;state=({});payload=({});subscriptions=[];poll.stop()}
    function loadSubscriptions(){
        if(!FlintController.loggedIn){notice="Войдите в аккаунт с активной подпиской.";return}
        busy=true;operation="subscriptions";request="pairing:"+generation+":"+FlintController.newRequestKey()
        FlintController.accountRequest(request,"GET","/subscriptions",{},"")
    }
    function action(){
        if(busy)return
        if(mode==="share"&&qr){send("share/cancel",{pairingId:state.id});return}
        if(mode==="share"||mode==="approve") {
            if(!subscriptionId)return
            if(mode==="share")state=FlintController.newPairingState()
            state.requestId=FlintController.newRequestKey()
            busy=true;request="prepare:"+generation+":"+FlintController.newRequestKey();operation="prepare"
            FlintController.preparePairing(request,subscriptionId)
        }else if(mode==="accept"){
            var saved=FlintController.clientDraft("pairing")
            if(saved.id!==state.id||saved.key!==state.key||saved.expires<Date.now()){
                saved=FlintController.newPairingState();saved.id=state.id;saved.key=state.key;saved.claimSecret=state.claimSecret;saved.expires=Date.now()+900000
                FlintController.saveClientDraft("pairing",saved)
            }
            state=saved
            send("share/claim",{pairingId:state.id,claimSecret:state.claimSecret,codeChallenge:state.challenge,device:FlintController.pairingDevice()})
        }
    }
    function consume(data){
        var value=FlintController.openPairing(state.id,state.key,data.encryptedSettings||{})
        if(!value.version||!FlintController.acceptPairing(value)){notice="Не удалось проверить настройки. Попросите владельца обновить Flint и создать новый QR.";return}
        completed=true;poll.stop();FlintController.saveClientDraft("pairing",{})
        FlintController.accountRequest("pairing-ack:"+generation,"POST","/devices/pairing/ack",{pairingId:state.id,codeVerifier:state.verifier},"")
        close();received()
    }
    onClosed:{
        poll.stop();generation++;request="";busy=false
        if(mode==="receive"&&!completed&&state.id)FlintController.accountRequest("pairing-cancel:"+generation,"POST","/devices/pairing/cancel",{pairingId:state.id,codeVerifier:state.verifier},"")
    }
    Timer {id:poll;interval:2000;repeat:true;onTriggered:{if(Date.now()>=panel.deadline){stop();panel.qr="";panel.notice="QR истёк. Создайте новый.";return}panel.send("complete",{pairingId:panel.state.id,codeVerifier:panel.state.verifier})}}
    Connections {
        target:FlintController
        function onPairingPrepared(id,value,error){
            if(id!==panel.request||!panel.opened)return
            panel.busy=false;if(error){panel.notice=error;return}panel.payload=value
            if(panel.mode==="share")panel.send("share/start",{subscriptionId:panel.subscriptionId,requestId:panel.state.requestId,claimChallenge:panel.state.claimChallenge})
            else panel.publish()
        }
        function onAccountResponse(id,status,data,error){
            if(id!==panel.request||!panel.opened)return
            panel.busy=false
            if(error||status<200||status>=300){panel.notice=status===410?"QR истёк или уже использован.":status===404||status===501?"Передача по QR ещё не включена на сервере Flint.":error||"Не удалось передать настройки.";if(status===410){poll.stop();panel.qr=""}return}
            switch(panel.operation){
            case "subscriptions":
                panel.subscriptions=(data.items||[]).filter(function(s){return s.status==="active"&&s.subscriptionUrl&&s.subscriptionUrl.indexOf("https://")===0})
                if(!panel.subscriptions.some(function(s){return s.id===panel.subscriptionId}))panel.subscriptionId=panel.subscriptions.length?panel.subscriptions[0].id:""
                panel.notice=panel.subscriptions.length?"Выберите подписку для нового устройства.":"Нет активных подписок.";break
            case "inspect": panel.loadSubscriptions();break
            case "share/start":panel.state.id=data.pairingId;panel.publish();break
            case "approve":
                if(panel.mode==="share") {panel.qr="https://flintmain.ru/connect/"+panel.state.id+"#key="+panel.state.key;panel.notice="Покажите QR другому пользователю. Откроется Flint или страница скачивания. Ссылка действует 15 минут и принимается одним устройством. Передаётся доступ к VPN."}
                else{panel.completed=true;panel.notice="Настройки переданы. Другое устройство может подключаться."}
                break
            case "start":panel.state.id=data.pairingId;panel.qr="flint://pair?v=1&id="+panel.state.id+"#key="+panel.state.key;panel.deadline=Date.now()+300000;panel.notice="Владелец сканирует этот QR во Flint и подтверждает передачу. Оставьте окно открытым.";poll.start();break
            case "complete":if(status!==202)panel.consume(data);break
            case "share/claim":panel.consume(data);break
            case "share/cancel":panel.completed=true;panel.close();break
            }
        }
    }
    function publish(){
        var envelope=FlintController.sealPairing(state.id,state.key,payload)
        if(!envelope.version){notice="Настройки слишком большие или повреждены.";return}
        send("approve",{pairingId:state.id,subscriptionId:subscriptionId,requestId:state.requestId,encryptedSettings:envelope})
    }
    contentItem:ColumnLayout {
        spacing:14
        Text {text:panel.mode==="share"?"Поделиться подключением":panel.mode==="approve"?"Передать настройки":"Получить подключение";color:"#F8FBFF";font.pixelSize:24;font.bold:true;Layout.fillWidth:true;wrapMode:Text.Wrap}
        FlintChoice {Layout.fillWidth:true;visible:panel.subscriptions.length>0&&!panel.qr;textRole:"label";model:panel.subscriptions.map(function(s){return {id:s.id,label:s.plan?s.plan.name:"Подписка"}});currentIndex:panel.subscriptions.findIndex(function(s){return s.id===panel.subscriptionId});enabled:!panel.busy;onActivated:panel.subscriptionId=panel.subscriptions[currentIndex].id}
        Rectangle {Layout.fillWidth:true;Layout.fillHeight:true;Layout.minimumHeight:80;radius:18;color:"white";visible:!!panel.qr
            Image {anchors.fill:parent;anchors.margins:16;fillMode:Image.PreserveAspectFit;source:panel.qr?MtProxyConfigModel.generateQrCode(panel.qr):""}
        }
        Item {Layout.fillHeight:true;visible:!panel.qr}
        Text {text:panel.notice;color:"#B7C9DA";Layout.fillWidth:true;wrapMode:Text.Wrap}
        Text {visible:panel.mode==="share"&&!!panel.qr;text:"После установки вернитесь на страницу и нажмите «Открыть Flint» или отсканируйте QR ещё раз.";color:"#B7C9DA";Layout.fillWidth:true;wrapMode:Text.Wrap;font.pixelSize:12}
        FlintButton {Layout.fillWidth:true;visible:panel.mode!=="receive"&&!panel.completed;text:panel.busy?"Подготавливаем…":panel.mode==="share"?(panel.qr?"Отменить передачу":"Создать QR"):panel.mode==="accept"?"Получить и подключиться":"Передать настройки";enabled:!panel.busy&&(panel.mode==="accept"||!!panel.subscriptionId);onClicked:panel.action()}
        FlintButton {Layout.fillWidth:true;text:"Закрыть";subtle:true;onClicked:panel.close()}
    }
}
