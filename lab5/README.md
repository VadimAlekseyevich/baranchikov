# Лабораторная №5 — распределённая слоистая архитектура (полное задание)

Продолжение `lab4`. Внешний протокол прежний: **текстовые UDP датаграммы**, например `PLACE_ORDER|1|buyer|EUR|USD|BUY|10|1.1000`. Между контейнерами используется **Google Protocol Buffers** по TCP, с length-delimited framing. Контракт в [`src/main/proto/exchange.proto`](src/main/proto/exchange.proto); Java классы генерируются Maven-плагином.

## Схема

```text
UDP client (lab3) --> facade:9000/udp       [валидация, адаптация протокола, CONNECT/EVENT]
                           |
                           | TCP + Protobuf Packet
                           v
                      business:9101          [правила выставления и сопоставления ордеров]
                           |
                           | TCP + Protobuf Packet, BEGIN/COMMIT/ROLLBACK
                           v
                       storage:9102          [SQL, отдельный сервис и контракт]
                           |
                           | JDBC over TCP
                           v
                       database:9092         [H2 TCP, named volume exchange-data]
```

* `facade` — отдельный входной контейнер (шаблон Facade). Разбирает, проверяет и преобразует пользовательские сообщения в Protobuf. Сохраняет UDP-адреса подключившихся клиентов и доставляет события `EVENT|TRADE`.
* `business` — отдельный контейнер с правилами лимитных ордеров (частичное исполнение, цена/время по ID, сделки и отложенные уведомления). Не открывает SQL/JDBC и не знает UDP.
* `storage` — отдельный контейнер с реализацией команд к БД. Для `BEGIN` берёт блокировку одной служебной строки H2, обеспечивая согласованность транзакций при нескольких репликах бизнес-слоя и хранения.
* `database` — единственный H2 TCP-server с файлом `/app/data/exchange.mv.db` на Docker named volume. **Несколько процессов не открывают этот файл напрямую**.
* `tests` — контейнер, выполняющий JUnit (включая UDP-клиентов, работающих через фасад).

Масштабируются **facade, business и storage**. H2 преднамеренно один (он ограничивает максимальное ускорение). Docker Compose предоставляет адреса реплик через сервисные DNS-имена; разные вызовы выбирают адреса по кругу. Для фасада тестовый контейнер и бенчмарк распределяют запросы между IP реплик явно. Внешний UDP-порт фасада публикуется динамически, чтобы можно было запускать несколько реплик без конфликта порта 9000 хоста.

## Запуск из IDEA

Открой `lab5/pom.xml` как Maven-проект; требуется **JDK 23+**. В папке `lab5` запусти:

```powershell
mvn test
```

Обычный `mvn test` запускает локальные тесты. `DockerPersistenceTest` и `DockerUdpIntegrationTest` без специальных переменных окружения пропускаются. Не отключай JUnit conditions в IDE (иначе Docker-only тест попытается подключиться к несуществующему серверу).

## Docker — Windows (Docker Desktop должен быть запущен)

В PowerShell перейди в **свою** папку `lab5`, затем:

```powershell
docker compose build
docker compose up -d facade
docker compose ps
docker compose run --rm tests
.\verify-persistence.cmd
```

`docker compose up -d facade` запускает также `business`, `storage`, `database`. Если нужен доступ из **Windows** к фасаду, узнай динамический опубликованный порт: `docker compose port facade 9000` (UDP). Внутри Docker по-прежнему `facade:9000`.

Тест сохранения **сначала удаляет volume** только текущего Compose-проекта, затем выставляет ордер по UDP, перезапускает `database` и `storage` и проверяет наличие ордера. Успешный вывод: `Docker persistence test PASSED.`. После проверки скрипт вызывает `docker compose down` (без `-v`).

## Масштабирование и измерения

```powershell
.\benchmark.cmd
```

или `powershell -ExecutionPolicy Bypass -File .\benchmark.ps1` (Linux/macOS: `sh ./benchmark.sh`). Скрипт последовательно масштабирует **только один компонент** до 1, 2 и 3 реплик, остальные возвращает к одной реплике, запускает одинаковый read-only UDP workload через контейнер `benchmark` и сохраняет **реально измеренные** данные в `benchmark-results.csv`:

```text
layer,replicas,threads,requests,completed,errors,rps,p50_ms,p95_ms
```

На Windows скрипт записывает промежуточный вывод в `benchmark-last-result.txt` через bind mount, чтобы не перехватывать вывод Docker в PowerShell (на некоторых версиях Docker Desktop это вызывает `The handle is invalid`). После успешного прогона промежуточный файл удаляется.

`rps` — успешные запросы за секунду; `p50_ms` и `p95_ms` — медиана и 95-й процентиль задержки. Сравни строки 1/2/3 реплики каждого слоя. Значения заранее не указываются, результаты зависят от твоего ПК, Docker и выделенных ресурсов. Для иной нагрузки можно задать `BENCH_REQUESTS`, `BENCH_THREADS` в окружении. При тестировании слоя хранения увеличение реплик может почти не ускорять систему из-за единственного H2. **Это результат архитектурного ограничения, а не ошибка**.

При многократном масштабировании фасада CONNECT/EVENT имеют ограничение: UDP-адрес клиента находится в памяти конкретной реплики. Для сохранения событий подключённому клиенту нужна привязка клиента к одной реплике (sticky routing); benchmark использует чтение без CONNECT. UDP сам по себе не подтверждает доставку уведомлений, как и в лабораторной №3.

## Тестирование слоёв

* `DistributedLayersTest` проверяет отдельно валидацию фасада, правила бизнес-слоя, SQL операции хранилища на in-memory H2 и сериализацию Protobuf;
* тесты `PersistentExchange*`, `UdpExchangeIntegrationTest` — проверяют обратную совместимость кода из предыдущей работы;
* `DockerUdpIntegrationTest` — настоящий пользовательский UDP-обмен через цепочку распределённых сервисов;
* `DockerPersistenceTest` — сохранение ордера между перезапусками контейнеров H2 и storage.

Вызовы между контейнерами являются TCP + Protobuf; внешнее UDP API не меняется. Ответы `requestId` по-прежнему сопоставляют запросы с ответами, но не гарантируют надёжность UDP.

## Команды обслуживания

```powershell
docker compose logs -f facade
docker compose ps
docker compose down          # остановка с сохранением volume
docker compose down -v       # удаляет также ВСЕ ДАННЫЕ H2 в volume этого Compose-проекта
```
