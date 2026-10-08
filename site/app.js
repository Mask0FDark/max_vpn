// This page never reads MAX passwords, QR tokens or session cookies.
// It only controls a localhost companion that opens the official MAX site.
(() => {
  const button = document.getElementById("maxLoginButton");
  const details = document.getElementById("maxLoginStatus");
  let csrf = "";

  const messages = {
    not_started: "Нажми кнопку, чтобы открыть официальный MAX Web в отдельном окне.",
    opening: "Запускается окно MAX Web...",
    awaiting_sign_in: "Открой окно Edge и войди в свой MAX. Авторизация идёт на официальном сайте MAX.",
    checking: "Проверка состояния MAX Web...",
    signed_in: "Вход в MAX Web обнаружен. Сессия хранится на этом компьютере.",
    browser_closed: "Окно MAX закрыто. Профиль браузера сохранён локально; статус входа можно перепроверить.",
    manual_browser_opened: "Официальный MAX Web открыт в Edge. Войди вручную; автоматическая проверка входа недоступна из-за сбоя браузерного драйвера, но профиль браузера сохраняется.",
    error: "Не удалось открыть или проверить MAX Web. Проверь установленный Microsoft Edge и Playwright."
  };

  const refresh = async () => {
    try {
      const response = await fetch("/api/auth/status", { cache: "no-store" });
      if (!response.ok) throw new Error("not_local");
      const data = await response.json();
      csrf = data.csrf;
      details.textContent = messages[data.state] || "Статус авторизации неизвестен.";
      if (data.state === "browser_closed" && data.previously_signed_in) {
        details.textContent += " В предыдущем сеансе вход подтверждался.";
      }
      button.disabled = !!data.running;
      button.textContent = data.running ? "Окно MAX Web открыто" : "Войти через MAX Web";
    } catch {
      csrf = "";
      button.disabled = true;
      button.textContent = "Требуется приложение на ПК";
      details.textContent = "Запусти локальный MAX VPN Companion. На обычном публичном сайте нельзя безопасно открыть браузерную сессию твоего ПК.";
    }
  };

  button?.addEventListener("click", async () => {
    if (!csrf) return;
    button.disabled = true;
    try {
      const response = await fetch("/api/auth/start", {
        method: "POST",
        headers: { "X-MAXVPN-CSRF": csrf }
      });
      if (!response.ok && response.status !== 409) throw new Error("launch_failed");
    } catch {
      details.textContent = "Не удалось запустить окно входа. Проверь локальный Companion.";
    }
    await refresh();
  });

  void refresh();
  window.setInterval(refresh, 3000);
})();
