# ntfy: уведомления и команды

ntfy работает на российском VPS: до него телефон достаёт из любой сети, а до домашнего бота — нет.

- Бот публикует уведомления в топик `vpn-alerts`.
- Бот раз в 15 с забирает команды из топика `vpn-commands`.
- Команды: `статус` / `status`, `сменить` / `rotate`, `пауза` / `pause`, `продолжить` / `resume`.

Пользователи ntfy:

| Пользователь | `vpn-alerts` | `vpn-commands` | Чей токен |
|--------------|--------------|----------------|-----------|
| `bot`        | запись       | чтение         | `NTFY_TOKEN` бота |
| `phone`      | чтение       | запись         | «Быстрые команды» на iPhone и `NTFY_COMMAND_TOKEN` бота (для кнопок в уведомлениях) |

## На VPS (под root)

Возьмите свежий `ntfy_<версия>_linux_amd64.rpm` со страницы https://github.com/binwiederhier/ntfy/releases.

```sh
dnf install -y https://github.com/binwiederhier/ntfy/releases/download/v<версия>/ntfy_<версия>_linux_amd64.rpm
install -d -o ntfy -g ntfy /var/lib/ntfy /var/cache/ntfy
cp server.yml /etc/ntfy/server.yml
systemctl enable --now ntfy

dnf install -y 'dnf-command(copr)'
dnf copr enable -y @caddy/caddy
dnf install -y caddy
cp Caddyfile /etc/caddy/Caddyfile
systemctl enable --now caddy

firewall-cmd --add-service=http --add-service=https
firewall-cmd --permanent --add-service=http --add-service=https
```

Порт 80 нужен Caddy, чтобы получить и продлевать сертификат Let's Encrypt.

Пользователи, права и токены. `ntfy user add` спросит пароль: у `bot` он не нужен (бот входит по токену),
а пароль `phone` понадобится в iOS-приложении.

```sh
sudo -u ntfy ntfy user add bot
sudo -u ntfy ntfy user add phone
sudo -u ntfy ntfy access bot vpn-alerts write-only
sudo -u ntfy ntfy access bot vpn-commands read-only
sudo -u ntfy ntfy access phone vpn-alerts read-only
sudo -u ntfy ntfy access phone vpn-commands write-only
sudo -u ntfy ntfy token add bot
sudo -u ntfy ntfy token add phone
```

Проверка из дома — уведомление должно прийти на телефон, когда он будет настроен:

```sh
curl -H "Authorization: Bearer <токен bot>" -d "проверка" https://my-vpn-hetzner.duckdns.org/vpn-alerts
```

## iPhone

1. Установите ntfy из App Store.
2. В настройках приложения задайте сервер по умолчанию `https://my-vpn-hetzner.duckdns.org` —
   точно как `base-url` в `server.yml`, без `/` в конце.
3. Добавьте в настройках пользователя `phone` с его паролем для этого сервера.
4. Подпишитесь на топик `vpn-alerts`.

Отправлять сообщения iOS-приложение не умеет, поэтому команды шлют «Быстрые команды».
Для каждой команды:

1. Новая быстрая команда → действие «Получить содержимое URL».
2. URL: `https://my-vpn-hetzner.duckdns.org/`, метод `POST`.
3. Заголовки: `Authorization` = `Bearer <токен phone>`.
4. Тело запроса: JSON, поля `topic` = `vpn-commands` и `message` = `status` (или `rotate`, `pause`, `resume`).
5. Поделиться → «На экран „Домой“».

Ответ бота придёт уведомлением. В уведомлениях бот добавляет кнопки («Сменить IP», «Пауза» и др.) —
если iOS-приложение их покажет, ими тоже можно пользоваться.

## Настройки бота

```
NTFY_URL=https://my-vpn-hetzner.duckdns.org
NTFY_TOKEN=<токен bot>
NTFY_COMMAND_TOKEN=<токен phone>
TZ=Europe/Moscow
```

`TZ` нужен, чтобы время в уведомлениях было московским, а не UTC контейнера.
