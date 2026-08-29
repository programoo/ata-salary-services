# ata-salary-services

Read-only REST service for browsing and filtering a salary survey dataset.
No create/update/delete endpoints - get and filter only.

## Stack

- Java 21, Spring Boot 3.5.16, Maven
- Spring Data JPA + Hibernate community dialect
- SQLite (file-based, `data/salary.db`)

## Running

```bash
mvn spring-boot:run
```

On first run, the app creates the SQLite database and imports the seed
dataset (`src/main/resources/data/salary_survey-3.json`, ~3,777 records).
Later restarts detect the existing data and skip re-importing it.

## Data model

Every field is stored as raw free text, exactly as it appears in the source
survey (e.g. `salary` may be `"80000"`, `"$24/hr"`, or `"135k"`). Nothing is
parsed or normalized, so filtering on numeric-looking fields (`salary`,
`yearsOfExperience`, `yearsAtEmployer`) is substring based, not range based.

## API

### `GET /`

Health check.

```bash
curl http://localhost:8080/
# {"status":"OK"}
```

### `GET /api/salary-records`

Paginated list of records, with optional filters. All filters are
case-insensitive substring matches, and can be combined.

| Param              | Matches against          |
|--------------------|---------------------------|
| `employer`         | Employer                  |
| `location`         | Location                  |
| `jobTitle`          | Job Title                 |
| `gender`           | Gender                     |
| `salary`           | Salary (raw text)          |
| `yearsOfExperience`| Years of Experience (raw text) |
| `yearsAtEmployer`  | Years at Employer (raw text)   |

```bash
curl "http://localhost:8080/api/salary-records?jobTitle=engineer&location=seattle"
```

#### Pagination

Standard Spring `Pageable` params:

- `page` - 0-indexed page number (default `0`)
- `size` - page size (default `20`)
- `sort` - e.g. `sort=salary,desc` (sorts lexicographically, since salary is raw text)

```bash
curl "http://localhost:8080/api/salary-records?page=0&size=20"
curl "http://localhost:8080/api/salary-records?page=1&size=20"
```

The response tells you where you are and whether there's more:

```json
{
  "content": [ ... ],
  "number": 0,
  "size": 20,
  "totalElements": 3777,
  "totalPages": 189,
  "first": true,
  "last": false
}
```

To fetch the next page, increment `page` (next page = `number + 1`) as long
as `last` is `false`.

### `GET /api/salary-records/{id}`

Single record by id. Returns `404` if it doesn't exist.

```bash
curl http://localhost:8080/api/salary-records/1
```

### `GET /job_data`

Paginated list of records filtered by relational operators, rather than the
substring matching used by `/api/salary-records`. Supports `jobTitle`,
`salary`, and `gender`.

Each filter is passed as `field[op]=value`; omitting `[op]` defaults to `eq`.

| Operator | Meaning              |
|----------|----------------------|
| `eq`     | equal (default)      |
| `ne`     | not equal            |
| `gt`     | greater than         |
| `gte`    | greater than or equal|
| `lt`     | less than            |
| `lte`    | less than or equal   |

`jobTitle` and `gender` compare case-insensitively as full strings (not
substrings). `salary` comparisons parse the raw salary text into a number on
a best-effort basis - `"$"` is ignored, commas are stripped, and a `k` suffix
is expanded (`135k` -> `135000`). Records whose salary can't be parsed as a
number (e.g. `"$24/hr"`) are excluded from salary filters and from salary
sorting.


```bash
curl "http://localhost:8080/job_data?salary[gte]=120000"
curl "http://localhost:8080/job_data?jobTitle[eq]=Software%20Engineer&gender[ne]=Male"
curl "http://localhost:8080/job_data?salary[gte]=100000&salary[lt]=150000&sort=salary,desc"
```

Supports the same pagination params as `/api/salary-records` (`page`,
`size`, `sort`), with `sort` limited to `jobTitle`, `salary`, and `gender`.
