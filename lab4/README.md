# Лабораторная работа №4 — использование контейнеров

Предметная область: **биржа валют**.  
Сетевой интерфейс из предыдущей работы: **UDP-сокет**.

## 1. Что требуется

Четвёртая лабораторная продолжает `lab3`.

Нужно:

- упаковать процессы программного комплекса в Docker-контейнеры;
- настроить взаимодействие между контейнерами;
- сделать так, чтобы встроенное хранилище переживало перезапуск контейнера;
- сохранить возможность запуска тестов из IDE;
- добавить запуск тестов внутри Docker в отдельном контейнере.

В этой версии бизнес-логика, H2-хранилище и UDP-протокол из `lab3` не переписываются. Добавляется контейнерный слой.

## 2. Архитектура

```text
                    Docker network
+---------------------+       UDP       +-----------------------+
| tests container     | --------------> | exchange-server       |
| Maven + JUnit       |                 | Java UDP server       |
+---------------------+                 +-----------+-----------+
                                                    |
                                                    | JDBC
                                                    v
                                        /app/data/exchange.mv.db
                                                    |
                                                    v
                                         named Docker volume
                                           exchange-data
```

Снаружи Docker UDP-сервер доступен на:

```text
localhost:9000/udp
```

Внутри compose-сети тестовый контейнер обращается к серверу по DNS-имени сервиса:

```text
exchange-server:9000
```

## 3. Файлы Docker

```text
lab4/
├── Dockerfile
├── docker-compose.yml
├── .dockerignore
├── verify-persistence.cmd
├── verify-persistence.sh
├── pom.xml
├── README.md
└── src/
```

### Dockerfile

Используется multi-stage build:

- `build` — Maven собирает приложение и runtime-зависимости;
- `server` — запускает `org.example.Main`;
- `tests` — отдельный образ, из которого запускается JUnit.

Приложение по-прежнему компилируется с:

```xml
<maven.compiler.release>23</maven.compiler.release>
```

### docker-compose.yml

Определены два сервиса:

```text
exchange-server
tests
```

`exchange-server`:

- слушает UDP-порт 9000;
- публикует `9000:9000/udp`;
- хранит H2 в `/app/data`;
- подключает именованный volume `exchange-data`.

`tests`:

- запускается отдельным контейнером;
- получает `EXCHANGE_HOST=exchange-server`;
- получает `EXCHANGE_PORT=9000`;
- обращается к серверу по Docker network через UDP.

## 4. Почему данные переживают restart

Внутри серверного контейнера H2 открывается по пути:

```text
/app/data/exchange
```

Физический файл H2 находится примерно здесь:

```text
/app/data/exchange.mv.db
```

Каталог `/app/data` смонтирован в именованный Docker volume:

```text
exchange-data
```

Поэтому команда:

```bash
docker compose restart exchange-server
```

перезапускает контейнер, но не удаляет volume.

То же относится к обычному:

```bash
docker compose down
```

Volume остаётся.

Удалить данные явно можно командой:

```bash
docker compose down -v
```

Она используется в начале автоматической persistence-проверки, чтобы тест стартовал с чистой БД.

## 5. Запуск тестов из IDE

Как и раньше, можно открыть `lab4` как Maven-проект и запустить все обычные JUnit-тесты из IntelliJ IDEA.

Либо из терминала:

```bash
cd lab4
mvn test
```

Без переменной `EXCHANGE_HOST` Docker-специфичный `DockerUdpIntegrationTest` автоматически пропускается.

Без переменной `PERSISTENCE_PHASE` автоматически пропускается `DockerPersistenceTest`.

Все остальные тесты работают без Docker и проверяют ту же функциональность, что в предыдущих лабораторных.

## 6. Запуск UDP-сервера в Docker

Из папки `lab4`:

```bash
docker compose up -d --build exchange-server
```

Проверить состояние:

```bash
docker compose ps
```

Посмотреть вывод сервера:

```bash
docker compose logs -f exchange-server
```

Остановить:

```bash
docker compose down
```

Данные H2 при этом сохраняются в named volume.

## 7. Запуск тестов в отдельном Docker-контейнере

Сначала можно запустить сервер:

```bash
docker compose up -d --build exchange-server
```

Затем тесты:

```bash
docker compose run --rm tests
```

В контейнере выполняется:

```bash
mvn -q test
```

При таком запуске:

- обычные тесты выполняются внутри контейнера;
- `DockerUdpIntegrationTest` дополнительно проверяет реальное UDP-взаимодействие между контейнерами `tests` и `exchange-server`;
- `DockerPersistenceTest` не запускается без специальной фазы, потому что для его полного сценария между двумя фазами требуется перезапустить серверный контейнер.

## 8. DockerUdpIntegrationTest

Этот тест включается только при наличии:

```text
EXCHANGE_HOST
```

В Compose значение:

```text
EXCHANGE_HOST=exchange-server
EXCHANGE_PORT=9000
```

Тест:

1. ждёт готовности UDP-сервера;
2. создаёт двух UDP-клиентов в тестовом контейнере;
3. подключает buyer и seller;
4. выставляет встречные BUY/SELL ордера;
5. проверяет отдельные UDP-уведомления обоим клиентам;
6. проверяет сделку и отсутствие открытого остатка.

Для каждой проверки используется новая валютная пара, поэтому сохранённые данные от предыдущих запусков не мешают тесту.

## 9. Автоматическая проверка сохранения H2 после restart

Для Windows CMD:

```bat
verify-persistence.cmd
```

Для Linux/macOS/Git Bash:

```bash
sh verify-persistence.sh
```

Сценарий делает следующее:

```text
1. docker compose down -v
2. docker compose build
3. docker compose up -d exchange-server
4. test container -> записывает открытый ордер через UDP
5. docker compose restart exchange-server
6. новый test container -> читает тот же ордер через UDP
```

Первая фаза:

```text
PERSISTENCE_PHASE=seed
```

создаёт открытый BUY-ордер.

После `restart` вторая фаза:

```text
PERSISTENCE_PHASE=verify
```

проверяет, что тот же ордер восстановился из H2.

Если volume был бы обычной файловой системой удаляемого контейнера, эта проверка бы не прошла.

## 10. Сеть Docker

Compose автоматически создаёт bridge-сеть проекта.

Сервис `tests` не использует `localhost` для обращения к серверу, потому что `localhost` внутри тестового контейнера означает сам тестовый контейнер.

Вместо этого используется имя compose-сервиса:

```text
exchange-server
```

Docker DNS разрешает его во внутренний IP серверного контейнера.

Публикация:

```yaml
ports:
  - "9000:9000/udp"
```

нужна для доступа к UDP-серверу с хостовой машины, например из IDE или внешнего клиента.

## 11. Что осталось от lab3

Без изменения сохранены:

- `PersistentExchange`;
- H2-схема;
- matching ордеров;
- частичное исполнение;
- offline-уведомления;
- восстановление после штатного и нештатного завершения;
- UDP-команды;
- `clientId -> SocketAddress`;
- отдельные UDP-датаграммы для ответов и уведомлений.

То есть `lab4` добавляет контейнеризацию, а не переписывает предыдущую архитектуру.

## 12. Команды для защиты

Обычные тесты:

```bash
mvn test
```

Сборка контейнеров:

```bash
docker compose build
```

Запуск сервера:

```bash
docker compose up -d exchange-server
```

Тесты в отдельном контейнере:

```bash
docker compose run --rm tests
```

Проверка сохранения состояния после перезапуска:

```bat
verify-persistence.cmd
```

Очистка контейнеров без удаления БД:

```bash
docker compose down
```

Полная очистка вместе с H2 volume:

```bash
docker compose down -v
```
