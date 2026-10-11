"use strict";
// The key stays in the fragment/browser. Do not add analytics or third-party JS.
(() => {
  const id = location.pathname.match(/^\/connect\/([A-Za-z0-9_-]{43})\/?$/)?.[1];
  const key = location.hash.match(/^#key=([A-Za-z0-9_-]{43})$/)?.[1];
  const status = document.getElementById("status");
  if (!id || !key) { status.textContent = "Ссылка подключения неполная. Снова отсканируйте QR во Flint владельца подписки."; return; }
  const open = document.getElementById("open");
  open.href = `flint://connect/${id}#key=${key}`;
  open.hidden = false;
  const hint = document.getElementById("hint");
  hint.textContent = "Flint уже установлен? Нажмите «Открыть Flint и подключиться». Если появится системный запрос, разрешите открытие.";
  fetch("/api/v1/devices/pairing/distribution", {credentials:"omit",cache:"no-store",referrerPolicy:"no-referrer"})
    .then(r => { if (!r.ok) throw new Error("unavailable"); return r.json(); })
    .then(config => {
      const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
      const value = isIOS ? config.iosUrl : config.androidUrl;
      if (!value) { if(isIOS) hint.textContent = "Версия для iPhone требует подписи Apple и публикации владельцем Flint. Если она уже установлена, откройте подключение кнопкой выше."; return; }
      const url = new URL(value, location.origin);
      if (url.protocol !== "https:" || url.username || url.password) return;
      const download = document.getElementById("download");
      download.href = url.href;download.textContent = isIOS ? "Установить Flint для iPhone" : "Скачать Flint для Android";download.hidden=false;
    }).catch(() => { hint.textContent += " Загрузка пока не настроена владельцем сервиса."; });
})();
