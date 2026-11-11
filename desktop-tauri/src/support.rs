//! The support cards, the same rules as the phone's `SupportAsk`: Ko-fi first, then a GitHub star.
//! A card is due after 14 days of use or 10 good connections. At most one card shows a day, only while the buds
//! are connected. Showing a card sets its next date at once, so a dismissed dialog counts as "Later" (30 days).
use serde_json::{json, Value};
use std::sync::Mutex;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

const DAY: u64 = 86_400;
const MIN_DAYS: u64 = 14;
const MIN_CONNECTIONS: u64 = 10;
const LATER_DAYS: u64 = 30;
/// A connection counts as good when it lived this long.
const GOOD_SECS: u64 = 60;
const CARDS: [&str; 2] = ["kofi", "github"];

/// The card to show now, or None. Times are in seconds.
fn pick(first_run: u64, connections: u64, now: u64, last_day: Option<u64>, next: impl Fn(&str) -> u64, off: impl Fn(&str) -> bool) -> Option<&'static str> {
    let used = now.saturating_sub(first_run) >= MIN_DAYS * DAY || connections >= MIN_CONNECTIONS;
    if !used || last_day == Some(now / DAY) { return None; }
    CARDS.into_iter().find(|c| !off(c) && now >= next(c))
}

fn now() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0) }

static LINK_UP: Mutex<Option<Instant>> = Mutex::new(None);

/// The link thread calls this on every state. A link that ends after a good time counts as one good connection.
pub fn note_link(up: bool) {
    let mut at = LINK_UP.lock().unwrap();
    match (up, *at) {
        (true, None) => *at = Some(Instant::now()),
        (false, Some(t)) => {
            *at = None;
            if t.elapsed().as_secs() >= GOOD_SECS {
                let n = crate::load_settings()["support_connections"].as_u64().unwrap_or(0);
                crate::save_setting("support_connections", (n + 1).into());
            }
        }
        _ => {}
    }
}

/// The due card for the page to show, or null. Answer: { card, title, body, action, later, never, url }.
#[tauri::command]
pub fn support_due() -> Value {
    let st = crate::load_settings();
    let t = now();
    let Some(first) = st["support_first_run"].as_u64() else {
        crate::save_setting("support_first_run", t.into());
        return Value::Null;
    };
    let Some(card) = pick(first, st["support_connections"].as_u64().unwrap_or(0), t, st["support_last_day"].as_u64(),
        |c| st[format!("support_next_{c}")].as_u64().unwrap_or(0), |c| st[format!("support_off_{c}")].as_bool() == Some(true)) else { return Value::Null };
    crate::save_setting("support_last_day", (t / DAY).into());
    crate::save_setting(&format!("support_next_{card}"), (t + LATER_DAYS * DAY).into());
    let (title, body, action, url) = match card {
        "kofi" => ("support_kofi_title", "support_kofi_body", "support_kofi_action", "https://ko-fi.com/spizganed"),
        _ => ("support_github_title", "support_github_body", "support_github_action", "https://github.com/spizganed/QuickBuds"),
    };
    json!({ "card": card, "title": crate::tr(title), "body": crate::tr(body), "action": crate::tr(action),
        "later": crate::tr("support_later"), "never": crate::tr("support_never"), "url": url })
}

/// "Support" and "Do not ask again" both end the card for good. "Later" needs no call.
#[tauri::command]
pub fn support_off(card: String) {
    if CARDS.contains(&card.as_str()) { crate::save_setting(&format!("support_off_{card}"), true.into()); }
}

#[cfg(test)]
mod tests {
    use super::*;

    const D: u64 = DAY;
    const NOW: u64 = 100 * D;
    fn p(first: u64, conns: u64, last: Option<u64>, next: u64, off: &[&str]) -> Option<&'static str> {
        pick(first, conns, NOW, last, |_| next, |c| off.contains(&c))
    }

    #[test] fn nothing_before_14_days_or_10_connections() { assert_eq!(p(NOW - 5 * D, 9, None, 0, &[]), None); }
    #[test] fn fourteen_days_make_kofi_due() { assert_eq!(p(NOW - 20 * D, 0, None, 0, &[]), Some("kofi")); }
    #[test] fn ten_connections_make_it_due_on_a_new_install() { assert_eq!(p(NOW - D, 10, None, 0, &[]), Some("kofi")); }
    #[test] fn one_card_a_day() { assert_eq!(p(NOW - 20 * D, 0, Some(NOW / D), 0, &[]), None); }
    #[test] fn later_holds_a_card_back() { assert_eq!(p(NOW - 20 * D, 0, None, NOW + D, &[]), None); }
    #[test] fn never_skips_that_card() { assert_eq!(p(NOW - 20 * D, 0, None, 0, &["kofi"]), Some("github")); }
    #[test] fn nothing_when_both_are_off() { assert_eq!(p(NOW - 20 * D, 0, None, 0, &["kofi", "github"]), None); }
}
