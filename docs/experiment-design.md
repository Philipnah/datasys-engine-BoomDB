# Experiment Design
### X axis
Varying "maximum amount of rows per partition" from 2^1=2 to 2^15=32.768.
It will be a log scale, doubling for every run.

### Y axis
We will measure the time it takes to retrieve a single row based on a unique id on the row.
A measurement of the `durationMs` value.

### Procedure 
We will use the exact same data for all runs. It will be 1M rows. The columns of the data will be (id long, name string, random-number double). We will double the partition size for run/data point. 

The query used will be of the following form: `SELECT * FROM tableName WHERE 'unique id of row';`

##### Example Data
id, name, random_double
1, Slava, 2.2
2, Vasya, 3.2
3, Anton, 3.3
4, Taras, 3.6

##### Example Query
`SELECT * FROM table WHERE id = 2`


### Hypothesis
"It looks like a parabola. There will be a valley where no larger partition size and no smaller partition size will decrease the time".