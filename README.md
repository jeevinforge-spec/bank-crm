# Banking CRM: Multi-threaded Bulk Loader

A Spring Boot 3 (Java 17) MVC web application that showcases a banking CRM with:

- **A customer master table** (19 business columns plus audit columns), with full CRUD
- **An audit trail table**. Every create, update, delete and purge is recorded, along with every bulk-load event. Updates store field-level `from → to` diffs.
- **Multi-threaded bulk loading** of customers from CSV, or from generated synthetic data. It loads 10,000+ rows in a couple of seconds.
- **A plain JavaScript front end** (no framework): dashboard, customer grid, bulk-load console with live per-thread progress, and an audit viewer.

## Run it

Needs only a JDK 17+. Maven comes with the Maven Wrapper, and the default database is an embedded H2 file DB.

```bash
./mvnw spring-boot:run          # macOS/Linux/Git Bash
mvnw.cmd spring-boot:run        # Windows cmd/PowerShell
```

Open **http://localhost:8080**. Then:

1. Go to **Bulk load**, keep *Rows = 10000*, and click **Start load**. Watch the progress bar and the per-worker-thread bars.
2. Run it again with *Worker threads = 1* and compare the throughput.
3. Set *Invalid rows % = 5* to see rows rejected one by one while the rest still load.
4. Open **Customers** to search, sort, filter or edit, then check **Audit trail** for the diff.

The H2 console is at `/h2-console`, using JDBC URL `jdbc:h2:file:./data/bankcrm` and user `sa` with no password.

### Use MySQL instead

```sql
CREATE DATABASE bankcrm;
```
```bash
DB_USER=root DB_PASSWORD=secret ./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql
```

### Tests

```bash
./mvnw test
```

The integration tests load 10,000 rows with 8 threads, check that invalid rows are isolated and that duplicate files are rejected, and verify the CRUD audit diffs.

## Architecture (MVC)

| Layer | Package / location | Contents |
|---|---|---|
| **Model** | `model`, `repository` | Hibernate/JPA entities `Customer`, `AuditLog`, `BulkLoadJob`, `BulkLoadError`; Spring Data repositories |
| **Controller** | `controller` | REST controllers: `/api/customers`, `/api/bulk`, `/api/audit`, `/api/dashboard` |
| **Service** | `service`, `service.bulk` | Business logic, auditing, the bulk-load engine |
| **View** | `src/main/resources/static` | `index.html`, `js/app.js`, `css/styles.css` |

The schema is defined in explicit SQL (`schema-h2.sql`, `schema-mysql.sql`), and Hibernate uses it with `ddl-auto=none`.

### Tables

- `customer`: customer_number, first_name, last_name, email, phone, date_of_birth, address_line, city, state, postal_code, country, account_type, account_balance, annual_income, credit_score, kyc_status, risk_category, customer_status, branch_code, bulk_job_id, and created/updated at/by, version. Customer number and email are unique; credit score has a CHECK constraint; `version` gives optimistic locking.
- `audit_log`: event_time, action, entity_type, entity_id, performed_by, client_ip, job_id, outcome, details, changes (JSON).
- `bulk_load_job` and `bulk_load_error`: job metadata and results, plus the rejected rows with line numbers and reasons.

## How the multi-threaded loader works

```
HTTP thread ──► saves file, inserts job row (QUEUED), returns 202 + jobId immediately
                   │
                   ▼
bulk-job coordinator thread  (pool size = crm.bulk.max-concurrent-jobs; extra jobs wait QUEUED)
   streams the CSV row by row (never loads the whole file into memory)
   cuts it into chunks of N rows
   Semaphore(2 × threads) ── backpressure: reading pauses when workers fall behind
                   │
                   ▼
per-job worker pool  (fixed ThreadPoolExecutor, size chosen per job, named bulk-<job>-worker-NN)
   ChunkProcessor, per chunk:
     1. parse + Bean-Validate each row                    (CPU work runs in parallel)
     2. reject in-file duplicates                         (ConcurrentHashMap key set shared by threads)
     3. reject existing DB duplicates                     (one IN-query per chunk, not one per row)
     4. INSERT survivors in ONE transaction               (Hibernate JDBC batching, flush/clear every 500)
     5. if the batch fails → retry row by row              (one bad row can't sink 499 good ones)
     6. write one audit row for the chunk
                   │
                   ▼
CompletableFuture.allOf(...) ──► persist error report, final status + stats, BULK_LOAD_COMPLETED audit
```

Design points:

- **Why SEQUENCE ids:** `IDENTITY` ids silently turn off Hibernate's JDBC insert batching. The pooled sequence (`allocationSize = 500`) hands out ids 500 at a time, so id generation costs one round trip per 500 rows. On MySQL, Hibernate emulates the sequence with a table.
- **Separate transaction per chunk:** each chunk commits on its own, so a bad chunk never rolls back good ones. The DB connection pool (24) is sized above `crm.bulk.max-threads` (16).
- **Lock-free progress:** counters are `AtomicInteger`s, and the per-thread stats and duplicate sets are concurrent collections. The UI polls `/api/bulk/jobs/{id}` every 400 ms.
- **Bounded memory:** file streaming, the semaphore, and per-batch `EntityManager.clear()` keep memory flat. Only the first `crm.bulk.max-stored-errors` rejected rows are kept.
- **Restart safety:** jobs left QUEUED or RUNNING by a crash are marked FAILED at startup.

Measured on the embedded H2 DB, after warm-up, loading 10,000 rows:

| Worker threads | Time | Rows/s |
|---|---|---|
| 1 | 5.6 s | ~1,800 |
| 4 | 2.2 s | ~4,600 |
| 8 | 1.8 s | ~5,700 |

H2 is an embedded DB with limited write concurrency. A server database such as MySQL with `rewriteBatchedStatements=true` scales further with more threads.

## Tuning (`application.properties`)

| Property | Default | Meaning |
|---|---|---|
| `crm.bulk.default-threads` / `max-threads` | 8 / 16 | worker threads per job |
| `crm.bulk.default-chunk-size` / `max-chunk-size` | 500 / 5000 | rows per transaction |
| `crm.bulk.max-concurrent-jobs` | 1 | jobs loading at the same time |
| `crm.bulk.max-stored-errors` | 1000 | rejected rows kept per job |
| `spring.jpa.properties.hibernate.jdbc.batch_size` | 500 | rows per JDBC batch |

## REST API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/customers?q=&accountType=&kycStatus=&riskCategory=&page=&size=&sort=&dir=` | search/page |
| GET/PUT/DELETE | `/api/customers/{id}` | read / update (send `version`) / delete |
| POST | `/api/customers` | create |
| DELETE | `/api/customers?confirm=true` | purge all (demo reset) |
| POST | `/api/bulk/upload` (multipart `file`, `threads`, `chunkSize`) | load a CSV |
| POST | `/api/bulk/generate?rows=10000&invalidPercent=0&threads=8&chunkSize=500` | generate + load |
| GET | `/api/bulk/sample?rows=10000` | download a sample CSV |
| GET | `/api/bulk/jobs`, `/api/bulk/jobs/{id}`, `/api/bulk/jobs/{id}/errors` | job status and errors |
| GET | `/api/audit?action=&user=&jobId=&page=&size=` | audit trail (read-only) |
| GET | `/api/dashboard`, `/api/meta` | stats, enum values |

The operator's name is sent in the `X-User` header and stored in `created_by`, `updated_by` and `audit_log.performed_by`. This showcase has no authentication; in production that value would come from Spring Security.
