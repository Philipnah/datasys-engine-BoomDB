# Considerations on Exercise 3 Code

Consider moving the validation of columns to the binder instead of the engine. So we only validate the columns in the Binder and not in the Engine. Doing this change, it would make sense to also move the definition of the validateColumns function to the binder.

