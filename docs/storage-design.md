1. **Catalog storage:** one catalog file or one per table? Which format: JSON, Java properties, or your own binary? Where on disk relative to the data directory?
Choice: 
    - One catalog file per table, because 
        (1) updating statistics for a table will not block updating the statistics for another, 
        (2) One catalog file is not as scalable as one catalog per table. 
        Disadvantage are that 
        (1) it is a more complex implementation, 
        (2) you might need to do more I/O operations when join multiple tables.
    - The catalog files should be in JSON, because it's easy to read and easy to develop (for educational purposes). A disadvantage is that it is slower than binary.
    - We have two directories: /catalogs & /data. catalog/table-name.json and data/table-name.bin.


2. **Catalog contents:** per table, at least the schema and the list of data files and partitions that belong to it.
Choice:
    - The schema and the list of data files and partitions that belong to it.
    - Stats are stored in the catalog, because 
        (1) we don't want to open the data file to read stats, 
        (2) the implementation is simpler, as there are fewer files when we don't have separate files for stats. 
        Disadvantages are
        (1) Having the stats directly in the catalog files makes the catalog files larger. This is a disadvantage when you need to read the catalog files and you don't need the stats.

3. **Where the min/max summaries live.** The requirement is only that they exist per column per partition and that `select` can consult them without reading the column data they describe. Three designs are defensible. A **footer** after the data is Parquet's choice and is natural for a single-pass writer. A **header** at the front is convenient for the reader, but the writer must buffer the partition or seek back to fill it in. **In the catalog only** means that pruning needs no data-file I/O at all, as in Snowflake and Iceberg, but a data file is then no longer self-describing. Pick one and justify it.
Choice:
    - 

4. **Restart:** what does a fresh `StorageEngine` on the same directory have to read before it can answer a `select`?
Choice:
    - 

5. **Layout inside a partition:** choose either row-wise or columnar format.
Choice:
    - 

6. **Partition size:** maximum rows per partition, as a configurable parameter (your tests will use tiny values like 2; pick a sensible default).
Choice:
    - 


7. **Value encodings and framing:** e.g. `LONG` as 8-byte two's-complement, `DOUBLE` as 8-byte IEEE 754, `STRING` as length-prefixed ASCII bytes; magic bytes and a format version number at the start of each file; how a reader finds a given partition's column chunk.
Choice:
    - 


8. **Byte order:** `ByteBuffer` defaults to big-endian, while the machines you run on are little-endian. Pick one and document the choice.
Choice:
    - 
