# DataSystems-2026
The "How to Build Data Systems" course running at ITU autumn 2026.

### Join planning

Join order is optimized by default. To force joins in the order listed in `FROM`,
use syntax mode: `./engine.sh -j syntax -f script.sql`. The `-j optimized` or
`-j syntax` option can appear before or after the `-c` or `-f` pair.
