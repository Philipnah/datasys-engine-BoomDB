# Considerations on Exercise 3 Code

Consider moving the validation of columns to the binder instead of the engine. So we only validate the columns in the Binder and not in the Engine. Doing this change, it would make sense to also move the definition of the validateColumns function to the binder.

TO DO exercise 3

Combine some of the AST files.

Check whether this is checked in the binder: SELECT : the table exists; if a WHERE is present, the column exists and the constant's Java type matches the column type

Check whether the demo is updated to the exercise 3 version.

Check logging for success and error.

Check all the tests mentioned in exercise 3.7

Review the rest of the code

Check the DOD

Do exercise 3.8: Cut release v0.3