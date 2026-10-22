1. Versioning: One catalog file per version.
2. Commit: We keep an integer of catalog version in memory of the newest version of the catalog. On db start we check which catalog file has the highest integer appended to it and use that for the in-memory integer.
3. Snapshot: We pin the version of the catalog to the newest catalog version when the statement starts.
4. Retention: We want to keep old version files. We keep them based on reference counting, a time limit and maximum amount of versions (5). Reference counting to make sure that we don't remove a currently used version. Time limit is to allow for time travel feature. 
5. The rewrite set: The planner's pruning helper. And what happens to a partition that may contain a match but turns out to contain none: carry over the original.
6. New version: DELETE that matches nothing creates no new version of the partition.
7. Partition placement: Write new partition into a new file, as we store partitions in separate files.
