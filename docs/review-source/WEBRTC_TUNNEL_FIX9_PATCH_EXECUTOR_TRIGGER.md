# FIX9 Patch Executor Trigger

This temporary marker was added after `.github/workflows/fix9-patch-after-ci.yml` was already present on the default branch, so completion of this commit's `CI` workflow can invoke the registered one-time `workflow_run` patch executor.

The executor must delete this marker together with all temporary FIX9 patch workflows when it commits the `SetupSaveController.commitSetup` long-method refactor.
