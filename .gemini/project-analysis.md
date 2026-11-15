# Анализ Flux — Telegram WS Proxy: Найденные проблемы

## Критические проблемы в TelegramProxyService.kt

### Проблема 1 (ГЛАВНАЯ): WebSocket-эндпоинт kws.web.telegram.org — это НЕ relay

Ваш код делает прямое WS-подключение к `wss://kws{dc}.web.telegram.org/apiws` и пытается отправить туда сырые MTProto байты. Но это **WebSocket API для web.telegram.org**, а не публичный TCP-relay. Он не принимает произвольные MTProto-пакеты от стороннего клиента.

**tg-ws-proxy работает с СОБСТВЕННЫМ серверным компонентом или CF Worker**, который принимает WS и делает TCP-relay к Telegram DC.

### Проблема 2: firstBytes не пересылаются при SOCKS5 + WS

В `handleSocks5` после SOCKS5 handshake вызывается:
```kotlin
runWsTunnel(client, null, endpoint, true)  // firstBytes = null!
```
Первый MTProto-пакет просто теряется.

### Проблема 3: DC IP адреса неточны

DC2 = `149.154.167.51` (должно быть .50), DC5 = `91.108.56.130` (должно быть .100).

---

## Рекомендуемое решение: прямой TCP relay через SOCKS5

Если VPN уже активен, достаточно простого SOCKS5 → TCP relay без WS.
