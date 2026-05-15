# v0.3 hardening clarification questions

These are the open implementation questions from reading `docs/V03_CODE_REVIEW.md` and `docs/V03_FIX_TODO.md`.

## 1. Status fields

For the v0.3 hardening pass, should status be simplified honestly by removing `active_stream_count` and renaming `open_forward_ids` to something like `configured_forward_ids` or `available_forward_ids`, or should this pass implement real tunnel runtime stream status now?

## 2. Daemon-level state

Should the hardening pass add a new daemon state such as `DaemonState::Serving` for answer-daemon service with zero or more possible sessions, or should it avoid enum churn and instead adjust `current_state` / `p2pctl status` wording using existing states?

## 3. Authenticated routing design

Is Option A preferred: centrally decode/authenticate/decrypt each incoming signaling payload once in the answer daemon loop, then pass a typed authenticated signal to the owning session task?

This seems cleaner than an authenticated peek plus replay-safe handoff, but it will require careful replay ownership changes so duplicate/retransmitted ACK-required messages still behave correctly.

## 4. Stale event protection

Is exact `session_id` matching sufficient for stale status/end event protection, or should this hardening pass add a per-session generation token and require events to match both `session_id` and generation?

## 5. TODO status handling

Should `docs/V03_TODO.md` be partially unchecked until the hardening pass completes, or should it remain as historical record of the first v0.3 implementation while `docs/V03_FIX_TODO.md` becomes the active corrective checklist?
