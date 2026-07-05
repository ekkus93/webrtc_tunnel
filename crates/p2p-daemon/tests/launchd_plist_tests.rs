//! Platform-independent structural validation of the `launchd` `LaunchDaemon`
//! plists under `packaging/launchd/`. Runs on every host (not just macOS) so Linux
//! CI does not leave the macOS service definitions completely unchecked; native
//! `plutil -lint` is additional, macOS-only validation (see P1-004), not a
//! replacement for these assertions.

use std::path::{Path, PathBuf};

use plist::Value;

fn workspace_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(Path::parent)
        .expect("p2p-daemon should be two levels under the workspace root")
        .to_path_buf()
}

fn load_plist(name: &str) -> Value {
    let path = workspace_root().join("packaging").join("launchd").join(name);
    Value::from_file(&path)
        .unwrap_or_else(|error| panic!("{name} should parse as a plist: {error}"))
}

fn dict<'a>(value: &'a Value, key: &str) -> &'a Value {
    value
        .as_dictionary()
        .and_then(|dict| dict.get(key))
        .unwrap_or_else(|| panic!("plist should have a `{key}` key"))
}

fn string_array(value: &Value) -> Vec<&str> {
    value
        .as_array()
        .expect("value should be an array")
        .iter()
        .map(|item| item.as_string().expect("array item should be a string"))
        .collect()
}

struct ExpectedPlist {
    file: &'static str,
    label: &'static str,
    executable: &'static str,
    config_path: &'static str,
    stdout_path: &'static str,
    stderr_path: &'static str,
}

const OFFER: ExpectedPlist = ExpectedPlist {
    file: "com.p2ptunnel.offer.plist",
    label: "com.p2ptunnel.offer",
    executable: "/usr/local/bin/p2p-offer",
    config_path: "/Library/Application Support/P2PTunnel/offer/config.toml",
    stdout_path: "/Library/Logs/P2PTunnel/offer.stdout.log",
    stderr_path: "/Library/Logs/P2PTunnel/offer.stderr.log",
};

const ANSWER: ExpectedPlist = ExpectedPlist {
    file: "com.p2ptunnel.answer.plist",
    label: "com.p2ptunnel.answer",
    executable: "/usr/local/bin/p2p-answer",
    config_path: "/Library/Application Support/P2PTunnel/answer/config.toml",
    stdout_path: "/Library/Logs/P2PTunnel/answer.stdout.log",
    stderr_path: "/Library/Logs/P2PTunnel/answer.stderr.log",
};

fn assert_plist_matches(expected: &ExpectedPlist) {
    let plist = load_plist(expected.file);

    assert_eq!(
        dict(&plist, "Label").as_string(),
        Some(expected.label),
        "{}: Label should be unique and correct",
        expected.file
    );

    let program_arguments = string_array(dict(&plist, "ProgramArguments"));
    assert_eq!(
        program_arguments,
        vec![expected.executable, "run", "--config", expected.config_path],
        "{}: run/--config/<absolute config path> must be tokenized as separate arguments",
        expected.file
    );

    assert_eq!(
        dict(&plist, "RunAtLoad").as_boolean(),
        Some(true),
        "{}: RunAtLoad must be true",
        expected.file
    );

    let keep_alive = dict(&plist, "KeepAlive");
    assert_eq!(
        dict(keep_alive, "SuccessfulExit").as_boolean(),
        Some(false),
        "{}: KeepAlive.SuccessfulExit must be false so a clean shutdown is not relaunched",
        expected.file
    );

    assert_eq!(
        dict(&plist, "UserName").as_string(),
        Some("_p2ptunnel"),
        "{}: must specify the unprivileged service account",
        expected.file
    );
    assert_eq!(
        dict(&plist, "GroupName").as_string(),
        Some("_p2ptunnel"),
        "{}: must specify the unprivileged service group",
        expected.file
    );

    assert_eq!(
        dict(&plist, "StandardOutPath").as_string(),
        Some(expected.stdout_path),
        "{}: stdout path should be role-specific",
        expected.file
    );
    assert_eq!(
        dict(&plist, "StandardErrorPath").as_string(),
        Some(expected.stderr_path),
        "{}: stderr path should be role-specific",
        expected.file
    );
}

#[test]
fn offer_plist_invokes_the_foreground_binary_directly() {
    let plist = load_plist(OFFER.file);
    let program_arguments = string_array(dict(&plist, "ProgramArguments"));
    assert_eq!(
        program_arguments.first(),
        Some(&OFFER.executable),
        "ProgramArguments[0] must be the direct executable path, not a shell wrapper"
    );
    assert!(
        !program_arguments.iter().any(|arg| arg.contains("/bin/sh") || *arg == "-c"),
        "plist must not wrap the executable in a shell"
    );
}

#[test]
fn answer_plist_invokes_the_foreground_binary_directly() {
    let plist = load_plist(ANSWER.file);
    let program_arguments = string_array(dict(&plist, "ProgramArguments"));
    assert_eq!(
        program_arguments.first(),
        Some(&ANSWER.executable),
        "ProgramArguments[0] must be the direct executable path, not a shell wrapper"
    );
    assert!(
        !program_arguments.iter().any(|arg| arg.contains("/bin/sh") || *arg == "-c"),
        "plist must not wrap the executable in a shell"
    );
}

#[test]
fn offer_plist_matches_expected_lifecycle_structure() {
    assert_plist_matches(&OFFER);
}

#[test]
fn answer_plist_matches_expected_lifecycle_structure() {
    assert_plist_matches(&ANSWER);
}

#[test]
fn offer_and_answer_plists_do_not_cross_labels_or_paths() {
    let offer = load_plist(OFFER.file);
    let answer = load_plist(ANSWER.file);

    assert_ne!(dict(&offer, "Label"), dict(&answer, "Label"));
    assert_ne!(
        dict(&offer, "ProgramArguments"),
        dict(&answer, "ProgramArguments"),
        "offer and answer must not share ProgramArguments (executable or config path)"
    );
    assert_ne!(dict(&offer, "StandardOutPath"), dict(&answer, "StandardOutPath"));
    assert_ne!(dict(&offer, "StandardErrorPath"), dict(&answer, "StandardErrorPath"));
}
