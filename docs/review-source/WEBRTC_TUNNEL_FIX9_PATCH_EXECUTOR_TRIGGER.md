# FIX9 Patch Executor Trigger

The registered CI-status publisher now preserves completed-event runs instead of cancelling them when newer pushes start. Completion of this metadata-only commit must apply the one-time `SetupSaveController.commitSetup` extraction and remove this marker plus all temporary FIX9 patch workflows.
