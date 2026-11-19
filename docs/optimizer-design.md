Statistics
The planner knows row count and NDV.

Where they live and how they merge:
NDV is the number of distinct values of column of the whole table.
NDV should live in catalog for each partition in a table.
Using Max NDV approach is the easiest to implement compared to HyperLogLog (I think we should do this @Thorbjørn, @Philip), but it can also be very wrong and is not accurate.

Roll forward:
A missing statistic means that the catalog was created before the addition of the statistic was made. 
You can roll forward by running through the partition files referenced in the catalog, collecting the data needed for the statistic, and adding the statistic to the catalog. 
You could roll backward by deleting the statistic for each partition in the entire catalog file, although not sure if this is necessary for the code to work.
Depends on how we go through the file to find information.

The estimator:
For Equality filter:
col = x |R| / ndv(col)
Reason for using formula: decent estimate, though skewed by the assumption of uniformity: that every value is equally frequent.

For Range filter col<x, col>c :
|R| / 3 or interpolate from min/max
Reason for using formula: Some sql people decided this was the way to do it ages ago, and it is apperently still used, so who are we to argue with the pros? Chosen for a lack of known better.

Estimate Result Rows of Equality Joins:
|R| · |S| / max(ndv(R.a), ndv(S.b))
Reason for using formula: Containment: Every key on the smaller key domain also appears and thus find partners on the larger one. Uniformity, assuming that every key has the same number of rows on each side. 
Not a true assumption, but an attempt at an estimation.

Heuristic:
We decided to use the greedy operator ordering, as it is quite effective, uses the important metadata/statistics (NDV and rowcount) to choose the ordering, and because it is simpler to implement than the dynamic programming solution.