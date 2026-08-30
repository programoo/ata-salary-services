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
dataset (`src/main/resources/data/salary_survey-3.json`, 3,777 records).
Later restarts detect the existing data and skip re-importing it.

## Data model

Every field is stored as raw free text, exactly as it appears in the source
survey (e.g. `salary` may be `"80000"`, `"$24/hr"`, or `"135k"`). Nothing is
parsed or normalized at import time; numeric interpretation happens at query
time, on a best-effort basis (see [Number parsing](#number-parsing)).

Record fields: `id`, `timestamp`, `employer`, `location`, `jobTitle`,
`yearsAtEmployer`, `yearsOfExperience`, `salary`, `signingBonus`,
`annualBonus`, `annualStockValueBonus`, `gender`, `additionalComments`.

## API

### `GET /`

Health check.

```bash
curl http://localhost:8080/
# {"status":"OK"}
```

### `GET /atadev/job_data`

Paginated list of records, with operator-based filtering, sorting, and
sparse fieldsets.

```bash
curl "http://localhost:8080/atadev/job_data"
```

#### Filtering

Filters are relational comparisons passed as `field[op]=value`; omitting
`[op]` defaults to `eq`.

| Operator | Meaning               |
|----------|-----------------------|
| `eq`     | equal (default)       |
| `ne`     | not equal             |
| `gt`     | greater than          |
| `gte`    | greater than or equal |
| `lt`     | less than             |
| `lte`    | less than or equal    |

Only three fields are filterable, and their names must be spelled exactly as
below (unlike `fields` and `sort`, filter names are **not** case- or
underscore-insensitive - `jobTitle[eq]=...` is silently ignored):

| Filter param | Compared as                          |
|--------------|--------------------------------------|
| `job_title`  | full string, case-insensitive        |
| `gender`     | full string, case-insensitive        |
| `salary`     | number, parsed from the raw text     |

- String comparisons are whole-string, not substring: `job_title[eq]=engineer`
  matches `"Engineer"` but not `"Software Engineer"`. Use `gt`/`lt` and friends
  for alphabetical ranges.
- Records with a blank or missing value never match a string filter.
- Records whose salary can't be parsed as a number (e.g. `"$24/hr"`) are
  excluded from salary filters.
- An unparseable filter value for `salary` returns `400 Bad Request`, as does
  an unrecognized operator.
- Multiple filters combine with AND, including two on the same field.

```bash
curl "http://localhost:8080/atadev/job_data?salary[gte]=120000"
curl "http://localhost:8080/atadev/job_data?job_title[eq]=Software%20Engineer&gender[ne]=Male"
curl "http://localhost:8080/atadev/job_data?salary[gte]=100000&salary[lt]=150000"
```

#### Sorting

`sort=field[,asc|desc]`, repeatable for multi-level sorts. Every record field
is sortable, including `id`.

```bash
curl "http://localhost:8080/atadev/job_data?sort=salary,desc"
curl "http://localhost:8080/atadev/job_data?sort=annual_bonus,desc&sort=job_title"
```

- Sort field names are matched case- and underscore-insensitively, so
  `job_title`, `jobTitle`, and `JOBTITLE` are equivalent.
- Numeric-looking fields (`salary`, `signingBonus`, `annualBonus`,
  `annualStockValueBonus`, `yearsAtEmployer`, `yearsOfExperience`) sort by
  their parsed number, with unparseable or missing values treated as `0`.
- All other fields sort case-insensitively as text, with null treated as `""`.
- Unrecognized sort fields are ignored rather than rejected.

#### Pagination

Standard Spring `Pageable` params:

- `page` - 0-indexed page number (default `0`)
- `size` - page size (default `100`)

```bash
curl "http://localhost:8080/atadev/job_data?page=0&size=20"
curl "http://localhost:8080/atadev/job_data?page=1&size=20"
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

`totalElements` reflects the filtered result set. To fetch the next page,
increment `page` (next page = `number + 1`) as long as `last` is `false`.

#### Sparse fieldsets

Use `fields` to return only specific columns per record, instead of the full
record:

```bash
curl "http://localhost:8080/atadev/job_data?fields=job_title,gender,salary"
```

```json
{
  "content": [
    { "jobTitle": "Software Engineer", "gender": "Male", "salary": "135000" }
  ],
  ...
}
```

- Any of the record's fields can be requested (see [Data model](#data-model)).
- Field names are matched case- and underscore-insensitively, so
  `job_title`, `jobTitle`, and `JOBTITLE` are all equivalent.
- Duplicates are collapsed; keys come back in the order requested, using the
  canonical camelCase name.
- An unknown field name returns `400 Bad Request`.
- Omitting `fields` returns the full record.
- Combines with filtering, sorting, and pagination:

```bash
curl "http://localhost:8080/atadev/job_data?fields=job_title,salary&salary[gte]=120000&sort=salary,desc"
```

## Number parsing

`SalaryParser` extracts a leading number from raw free text: a `$` prefix is
ignored, commas are stripped, and a `k` suffix is expanded (`135k` ->
`135000`). Text with no recognizable leading number yields no value - such
records are excluded from salary filters and treated as `0` when sorting on a
numeric field.

## Implementation notes

- Filtering and sorting run in memory: the service loads all records via
  `repository.findAll()` and then filters, sorts, and pages the list. That is
  fine at this dataset's size (3,777 rows) but is not a database-side query.
- `server.tomcat.relaxed-query-chars=[,]` is set in `application.properties`
  so Tomcat accepts the unencoded `[` and `]` in `salary[gte]=...`. Clients
  that percent-encode them (`salary%5Bgte%5D=...`) work either way.
