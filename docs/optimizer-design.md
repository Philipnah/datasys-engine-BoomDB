# Statistics:
The planner knows row count per partition in the catalog for any given table.
The number of distinct values per column, NDV, is also stored in the catalog per partition alongside the min/max statistics.

# Where they live and how they merge:
NDV is an estimated number of distinct values in a column across the whole table.
NDV should live in catalog for each partition in a table.
Using Max NDV approach is the easiest to implement compared to HyperLogLog, but it can also be very wrong and is not necessarily accurate. 
It is simply the lower bound of the possible true NDV value, so depending on the data it might be pretty accurate or completely off the mark.

# Roll forward:
You can roll forward by running through the partition files referenced in the catalog, collecting the data needed for the statistic, and adding the statistic to the catalog. 
You could roll backward by deleting the statistic for each partition in the catalog file for every table.

# The estimator:
### For Equality filter:
col = x |R| / ndv(col)
Reason for using this formula: decent estimate, though skewed by the assumption of uniformity: that every value is equally frequent.

### For Range filter col<x, col>c :
|R| / 3 or interpolate from min/max
Reason for using formula: Some SQL people decided this was the way to do it ages ago, and it is apparently still used in some database systems, so who are we to argue with the pros? Chosen for a lack of known better.

### Estimate Result Rows of Equality Joins:
|R| · |S| / max(ndv(R.a), ndv(S.b))
Reason for using formula: Containment: Every key on the smaller key domain also appears and thus find partners on the larger one. Uniformity, assuming that every key has the same number of rows on each side. 
Not a true assumption, but an attempt at an estimation.

# The heuristic:
We decided to use the greedy operator ordering, as it is quite effective, uses the important metadata/statistics (NDV and rowcount) to choose the ordering, and because it is simpler to implement than the dynamic programming solution.