SalaryDataLoader# clear.
	SalaryRecordImportDto.java Map JSON raw data to java field.
	and then it map the dto to SalaryRecord.java
	
JpaRepository<SalaryRecord, Long> — the standard CRUD repository. The two generic params are <EntityType, IdType>, so this gives you findById(Long), findAll(), save(...), deleteById(...), count(), pagination (findAll(Pageable)), etc. — all for SalaryRecord, keyed by its Long id. This is what backs repository.findById(id) in getById(...) (SalaryRecordController.java:50) and repository.count() in SalaryDataLoader (SalaryDataLoader.java:42).

JpaSpecificationExecutor<SalaryRecord> — adds the ability to query using the Specification<SalaryRecord> objects you saw earlier (the dynamic WHERE-clause builder from SalaryRecordSpecifications). Specifically it adds overloads like findAll(Specification<SalaryRecord> spec, Pageable pageable) — which is exactly what's called at SalaryRecordController.java:45: repository.findAll(spec, pageable).

Why extend both instead of one

JpaRepository alone gives you basic CRUD but no way to pass in a dynamically-built filter — you'd be stuck writing a fixed set of query methods (findByEmployer, findByEmployerAndLocation, etc.) for every filter combination. JpaSpecificationExecutor is what lets the controller build up an arbitrary combination of optional filters at request time (via Specification.and(...) chaining in SalaryRecordSpecifications.build(...)) and pass the whole thing in as one object. Combining both interfaces means you get plain CRUD and flexible dynamic querying from a single, zero-implementation interface.


Method References: SalaryRecord::getId
This is Java's method reference syntax. It's equivalent to writing:
java
(salaryRecord) -> salaryRecord.getId()

canonicalize
Input:  "Salary_Record_ID"
After toLowerCase(Locale.ROOT): "salary_record_id"
After replace("_", ""): "salaryrecordid"
Output: "salaryrecordid"

projection.put(field, FIELD_ACCESSORS.get(field).apply(record));

What each part does:

projection.put(...) — Adds an entry to the projection Map (or updates an existing key)
field — The key being added to the projection (a field name)
FIELD_ACCESSORS.get(field) — Retrieves a function/accessor from the FIELD_ACCESSORS map using the field name as a key. This accessor is likely a Function object or similar functional interface
.apply(record) — Calls that accessor function, passing the record object as input. The accessor extracts a value from the record based on its logic
The result — The extracted value is stored in projection with the field name as the key

In simple terms: It's looking up a function that knows how to extract a particular field from a record, applying that function to get the value, then storing that value in a map.
