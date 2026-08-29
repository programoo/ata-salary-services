SalaryDataLoader# clear.
	SalaryRecordImportDto.java Map JSON raw data to java field.
	and then it map the dto to SalaryRecord.java
	
JpaRepository<SalaryRecord, Long> — the standard CRUD repository. The two generic params are <EntityType, IdType>, so this gives you findById(Long), findAll(), save(...), deleteById(...), count(), pagination (findAll(Pageable)), etc. — all for SalaryRecord, keyed by its Long id. This is what backs repository.findById(id) in getById(...) (SalaryRecordController.java:50) and repository.count() in SalaryDataLoader (SalaryDataLoader.java:42).

JpaSpecificationExecutor<SalaryRecord> — adds the ability to query using the Specification<SalaryRecord> objects you saw earlier (the dynamic WHERE-clause builder from SalaryRecordSpecifications). Specifically it adds overloads like findAll(Specification<SalaryRecord> spec, Pageable pageable) — which is exactly what's called at SalaryRecordController.java:45: repository.findAll(spec, pageable).

Why extend both instead of one

JpaRepository alone gives you basic CRUD but no way to pass in a dynamically-built filter — you'd be stuck writing a fixed set of query methods (findByEmployer, findByEmployerAndLocation, etc.) for every filter combination. JpaSpecificationExecutor is what lets the controller build up an arbitrary combination of optional filters at request time (via Specification.and(...) chaining in SalaryRecordSpecifications.build(...)) and pass the whole thing in as one object. Combining both interfaces means you get plain CRUD and flexible dynamic querying from a single, zero-implementation interface.
