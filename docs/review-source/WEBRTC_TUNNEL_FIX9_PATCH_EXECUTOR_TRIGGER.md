# FIX9 Patch Executor Trigger

The simplified, already-registered `Publish CI Status Issues` workflow now owns the one-time FIX9 patch. Completion of this metadata-only commit must extract `SetupPersistenceRequest` construction from `SetupSaveController.commitSetup` and remove this marker plus every temporary FIX9 patch workflow.
