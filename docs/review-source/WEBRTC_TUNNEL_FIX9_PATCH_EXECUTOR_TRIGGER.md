# FIX9 Patch Executor Trigger

The established `Publish CI Status Issues` workflow now contains the one-time FIX9 patch job. Completion of this metadata-only commit's `CI` workflow must invoke that registered publisher, extract `SetupPersistenceRequest` construction from `SetupSaveController.commitSetup`, and delete this marker plus every temporary FIX9 patch workflow.
