# Доступ бота к VPS каскада

Бот заходит на российский VPS по SSH и вызывает `cascade-set-target <IPv4>`. Скрипт переводит оба проброса
(36346/udp и 2222/tcp) на новый IP и сбрасывает conntrack. Ключ бота привязан к скрипту: ничего другого с ним
сделать нельзя.

## Дома: ключ и known_hosts

```sh
ssh-keygen -t ed25519 -N "" -C hetzner-ip-renewal -f cascade_bot
ssh-keyscan -p 22 146.19.142.135 > known_hosts
ssh-keygen -lf known_hosts
```

Отпечатки из `ssh-keygen -lf known_hosts` сверьте с выводом на VPS:
`for k in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf "$k"; done`.
В known_hosts нужны все типы ключей, которые выдал `ssh-keyscan`, а не только ed25519.

## На VPS (под root)

```sh
dnf install -y conntrack-tools
useradd -m -s /bin/bash cascade-bot

install -o root -g root -m 0755 cascade-set-target /usr/local/sbin/cascade-set-target

cat > /etc/sudoers.d/cascade-bot <<'EOF'
Defaults:cascade-bot !requiretty
cascade-bot ALL=(root) NOPASSWD: /usr/local/sbin/cascade-set-target
EOF
chmod 0440 /etc/sudoers.d/cascade-bot
visudo -cf /etc/sudoers.d/cascade-bot

install -d -o cascade-bot -g cascade-bot -m 0700 /home/cascade-bot/.ssh
```

В `/home/cascade-bot/.ssh/authorized_keys` одна строка, вместо `ssh-ed25519 AAAA…` — содержимое `cascade_bot.pub`:

```
restrict,command="sudo -n /usr/local/sbin/cascade-set-target \"$SSH_ORIGINAL_COMMAND\"" ssh-ed25519 AAAA… hetzner-ip-renewal
```

```sh
chown cascade-bot:cascade-bot /home/cascade-bot/.ssh/authorized_keys
chmod 0600 /home/cascade-bot/.ssh/authorized_keys
restorecon -R /home/cascade-bot/.ssh
```

Скрипт правит зону по умолчанию. Проверьте, что оба правила в ней: `firewall-cmd --list-forward-ports`.
Если правила в другой зоне, поменяйте `zone=` в скрипте.

## Проверка из дома

Передайте текущий IP сервера — скрипт ничего не изменит и ответит «Каскад уже указывает на …»:

```sh
ssh -i cascade_bot -o UserKnownHostsFile=known_hosts cascade-bot@146.19.142.135 46.225.178.76
```

## Настройки бота

```
CASCADE_HOST=146.19.142.135
CASCADE_PRIVATE_KEY=<путь к cascade_bot>
CASCADE_KNOWN_HOSTS=<путь к known_hosts>
```
