# Experiment Design
### X axis
Varying max rows per partition from 2^1 to 2^14 = 16.384. 
It will be a log scale, doubling for every run.

### Y axis
durationMs 

### Procedure 
TBD
Same amount of data.

### Hypothesis
TBD


NOTE: 
Which query/queries to use? (If we use multiple, then each Q becomes another line in the graph)

select * from tableName;
select "one column" from tableName;
select * from tableName where "unique id of row";
